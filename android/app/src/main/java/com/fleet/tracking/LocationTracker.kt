/*
 * LocationTracker.kt — Fleet vehicle location tracking + geofencing
 *
 * AndroidManifest.xml:
 *
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 *   <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
 *   <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 *   <application ...>
 *     <service
 *         android:name="com.fleet.tracking.LocationTrackingService"
 *         android:exported="false"
 *         android:foregroundServiceType="location" />
 *
 *     <receiver
 *         android:name="com.fleet.tracking.GeofenceBroadcastReceiver"
 *         android:exported="false" />
 *
 *     <receiver
 *         android:name="com.fleet.tracking.GeofenceBootReceiver"
 *         android:exported="false">
 *       <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *         <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *       </intent-filter>
 *     </receiver>
 *   </application>
 *
 * Gradle:
 *   implementation("com.google.android.gms:play-services-location:21.3.0")
 *   implementation("androidx.core:core-ktx:1.13.1")
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 *   minSdk 26
 *
 * Usage (Application.onCreate):
 *   LocationTracker.initialize(this, object : TrackingSink {
 *       override suspend fun onLocation(payload: LocationPayload) = api.postLocation(payload.toJsonString())
 *       override suspend fun onGeofenceEvent(event: GeofenceEvent) = api.postGeofenceEvent(event.toJsonString())
 *   })
 */

package com.fleet.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Serialized as:
 * { "latitude": 40.7128, "longitude": -74.0060, "accuracy": 10, "timestamp": "2026-10-05T10:15:30.123Z" }
 */
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("latitude", latitude)
        put("longitude", longitude)
        put("accuracy", (accuracy * 10f).roundToInt() / 10.0)
        put("timestamp", timestamp)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun from(location: Location): LocationPayload = LocationPayload(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            timestamp = Instant.ofEpochMilli(location.time).toString()
        )

        fun fromJson(json: JSONObject): LocationPayload = LocationPayload(
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            accuracy = json.getDouble("accuracy").toFloat(),
            timestamp = json.getString("timestamp")
        )
    }
}

data class FleetGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val expirationMillis: Long = Geofence.NEVER_EXPIRE,
    val loiteringDelayMillis: Int = 0,
    val responsivenessMillis: Int = 0
) {
    init {
        require(id.isNotBlank()) { "Geofence id must not be blank" }
        require(latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        require(radiusMeters > 0f) { "Radius must be > 0" }
    }

    internal fun toGeofence(): Geofence {
        var transitions = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
        if (loiteringDelayMillis > 0) transitions = transitions or Geofence.GEOFENCE_TRANSITION_DWELL

        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(expirationMillis)
            .setTransitionTypes(transitions)
            .setNotificationResponsiveness(responsivenessMillis)
            .apply { if (loiteringDelayMillis > 0) setLoiteringDelay(loiteringDelayMillis) }
            .build()
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("latitude", latitude)
        put("longitude", longitude)
        put("radiusMeters", radiusMeters.toDouble())
        put("expirationMillis", expirationMillis)
        put("loiteringDelayMillis", loiteringDelayMillis)
        put("responsivenessMillis", responsivenessMillis)
    }

    internal companion object {
        fun fromJson(json: JSONObject): FleetGeofence = FleetGeofence(
            id = json.getString("id"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            radiusMeters = json.getDouble("radiusMeters").toFloat(),
            expirationMillis = json.optLong("expirationMillis", Geofence.NEVER_EXPIRE),
            loiteringDelayMillis = json.optInt("loiteringDelayMillis", 0),
            responsivenessMillis = json.optInt("responsivenessMillis", 0)
        )
    }
}

enum class GeofenceTransition(val code: Int) {
    ENTER(Geofence.GEOFENCE_TRANSITION_ENTER),
    EXIT(Geofence.GEOFENCE_TRANSITION_EXIT),
    DWELL(Geofence.GEOFENCE_TRANSITION_DWELL);

    companion object {
        fun fromCode(code: Int): GeofenceTransition? = values().firstOrNull { it.code == code }
    }
}

data class GeofenceEvent(
    val geofenceId: String,
    val transition: GeofenceTransition,
    val location: LocationPayload?,
    val timestamp: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("geofenceId", geofenceId)
        put("transition", transition.name)
        put("timestamp", timestamp)
        location?.let { put("location", it.toJson()) }
    }

    fun toJsonString(): String = toJson().toString()
}

data class TrackingConfig(
    val intervalMillis: Long = 10_000L,
    val minUpdateIntervalMillis: Long = 5_000L,
    val minUpdateDistanceMeters: Float = 10f,
    val maxUpdateDelayMillis: Long = 0L,
    val maxAcceptedAccuracyMeters: Float = 50f
) {
    fun toLocationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(minUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(minUpdateDistanceMeters)
            .setMaxUpdateDelayMillis(maxUpdateDelayMillis)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(true)
            .build()

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("intervalMillis", intervalMillis)
        put("minUpdateIntervalMillis", minUpdateIntervalMillis)
        put("minUpdateDistanceMeters", minUpdateDistanceMeters.toDouble())
        put("maxUpdateDelayMillis", maxUpdateDelayMillis)
        put("maxAcceptedAccuracyMeters", maxAcceptedAccuracyMeters.toDouble())
    }

    internal companion object {
        fun fromJson(json: JSONObject): TrackingConfig = TrackingConfig(
            intervalMillis = json.optLong("intervalMillis", 10_000L),
            minUpdateIntervalMillis = json.optLong("minUpdateIntervalMillis", 5_000L),
            minUpdateDistanceMeters = json.optDouble("minUpdateDistanceMeters", 10.0).toFloat(),
            maxUpdateDelayMillis = json.optLong("maxUpdateDelayMillis", 0L),
            maxAcceptedAccuracyMeters = json.optDouble("maxAcceptedAccuracyMeters", 50.0).toFloat()
        )
    }
}

/** Implement to forward data to your backend (called on Dispatchers.IO). */
interface TrackingSink {
    suspend fun onLocation(payload: LocationPayload)
    suspend fun onGeofenceEvent(event: GeofenceEvent)
}

// ─────────────────────────────────────────────────────────────────────────────
// Public facade
// ─────────────────────────────────────────────────────────────────────────────

object LocationTracker {

    private const val TAG = "LocationTracker"
    const val MAX_GEOFENCES = 100
    internal const val ACTION_GEOFENCE_EVENT = "com.fleet.tracking.action.GEOFENCE_EVENT"

    @Volatile private var appContext: Context? = null
    @Volatile private var sink: TrackingSink? = null

    private val _locations = MutableSharedFlow<LocationPayload>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val locations: SharedFlow<LocationPayload> = _locations.asSharedFlow()

    private val _geofenceEvents = MutableSharedFlow<GeofenceEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val geofenceEvents: SharedFlow<GeofenceEvent> = _geofenceEvents.asSharedFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val context: Context
        get() = appContext
            ?: error("LocationTracker.initialize() must be called first (e.g. in Application.onCreate)")

    private val geofencingClient: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }
    private val settingsClient: SettingsClient by lazy { LocationServices.getSettingsClient(context) }
    private val geofenceStore: GeofenceStore by lazy { GeofenceStore(context) }

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).setAction(ACTION_GEOFENCE_EVENT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun initialize(context: Context, sink: TrackingSink? = null) {
        appContext = context.applicationContext
        if (sink != null) this.sink = sink
    }

    internal fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    fun hasFineLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            hasFineLocationPermission()
        }

    /** Request these first, together. */
    fun foregroundPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Request separately, after foreground permissions are granted (Android 11+ requires this). */
    fun backgroundPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── Location settings ────────────────────────────────────────────────────

    /**
     * On failure, if the exception is a ResolvableApiException, call
     * exception.startResolutionForResult(activity, REQUEST_CODE) to prompt the user to enable GPS.
     */
    suspend fun checkLocationSettings(config: TrackingConfig = TrackingConfig()): Result<Unit> = suspendRunCatching {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(config.toLocationRequest())
            .setAlwaysShow(true)
            .build()
        settingsClient.checkLocationSettings(request).awaitTask()
        Unit
    }

    // ── Tracking ─────────────────────────────────────────────────────────────

    fun startTracking(config: TrackingConfig = TrackingConfig()) {
        check(hasFineLocationPermission()) { "ACCESS_FINE_LOCATION not granted" }
        TrackingConfigStore.save(context, config)
        val intent = Intent(context, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopTracking() {
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }

    // ── Geofencing ───────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun addGeofences(fences: List<FleetGeofence>): Result<Unit> = suspendRunCatching {
        require(fences.isNotEmpty()) { "No geofences supplied" }
        check(hasFineLocationPermission()) { "ACCESS_FINE_LOCATION not granted" }
        check(hasBackgroundLocationPermission()) { "ACCESS_BACKGROUND_LOCATION required for geofencing" }

        val merged = geofenceStore.load().associateBy { it.id } + fences.associateBy { it.id }
        require(merged.size <= MAX_GEOFENCES) { "Geofence limit exceeded (${merged.size}/$MAX_GEOFENCES)" }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences.map { it.toGeofence() })
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent).awaitTask()
        geofenceStore.save(merged.values.toList())
    }

    suspend fun addGeofence(fence: FleetGeofence): Result<Unit> = addGeofences(listOf(fence))

    suspend fun removeGeofences(ids: List<String>): Result<Unit> = suspendRunCatching {
        if (ids.isEmpty()) return@suspendRunCatching
        geofencingClient.removeGeofences(ids).awaitTask()
        geofenceStore.save(geofenceStore.load().filterNot { it.id in ids })
    }

    suspend fun removeAllGeofences(): Result<Unit> = suspendRunCatching {
        geofencingClient.removeGeofences(geofencePendingIntent).awaitTask()
        geofenceStore.clear()
    }

    fun registeredGeofences(): List<FleetGeofence> = geofenceStore.load()

    /** Geofences are cleared by the OS on reboot, app update, or Play services data reset. */
    suspend fun reRegisterGeofences(): Result<Unit> {
        val stored = geofenceStore.load()
        if (stored.isEmpty()) return Result.success(Unit)
        return addGeofences(stored)
    }

    // ── Internal dispatch ────────────────────────────────────────────────────

    internal fun setTracking(active: Boolean) {
        _isTracking.value = active
    }

    internal suspend fun publishLocation(payload: LocationPayload) {
        _locations.emit(payload)
        sink?.let { s ->
            try {
                s.onLocation(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Location upload failed: ${e.javaClass.simpleName}")
            }
        }
    }

    internal suspend fun publishGeofenceEvent(event: GeofenceEvent) {
        _geofenceEvents.emit(event)
        sink?.let { s ->
            try {
                s.onGeofenceEvent(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Geofence upload failed: ${e.javaClass.simpleName}")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Foreground service
// ─────────────────────────────────────────────────────────────────────────────

class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START = "com.fleet.tracking.action.START_TRACKING"
        private const val TAG = "LocationTrackingService"
        private const val CHANNEL_ID = "fleet_location_tracking"
        private const val NOTIFICATION_ID = 4201
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<LocationPayload>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private var currentLocationCts: CancellationTokenSource? = null
    private var config = TrackingConfig()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val accepted = result.locations.filter {
                it.hasAccuracy() && it.accuracy <= config.maxAcceptedAccuracyMeters
            }
            if (accepted.isEmpty()) return
            accepted.forEach { outbox.trySend(LocationPayload.from(it)) }
            updateNotification(LocationPayload.from(accepted.last()))
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                Log.w(TAG, "Location currently unavailable")
                notificationManager.notify(NOTIFICATION_ID, buildNotification("Waiting for GPS signal…"))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocationTracker.attach(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        serviceScope.launch {
            for (payload in outbox) LocationTracker.publishLocation(payload)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = TrackingConfigStore.load(this)

        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!LocationTracker.hasFineLocationPermission()) {
            Log.e(TAG, "Location permission revoked; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startLocationUpdates()
        LocationTracker.setTracking(true)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        currentLocationCts?.cancel()
        LocationTracker.setTracking(false)
        outbox.close()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting location tracking…"), type)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException (API 34+ missing permission)
        Log.e(TAG, "Unable to start foreground service", e)
        false
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        // Re-requesting with the same callback replaces any previous request.
        fusedClient.requestLocationUpdates(config.toLocationRequest(), locationCallback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                Log.e(TAG, "requestLocationUpdates failed", e)
                stopSelf()
            }

        currentLocationCts?.cancel()
        val cts = CancellationTokenSource().also { currentLocationCts = it }
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null && location.hasAccuracy() &&
                    location.accuracy <= config.maxAcceptedAccuracyMeters
                ) {
                    val payload = LocationPayload.from(location)
                    outbox.trySend(payload)
                    updateNotification(payload)
                }
            }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location sharing",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shown while this phone's location is being shared with your dashboard"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location sharing on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(payload: LocationPayload) {
        val text = String.format(
            Locale.US, "Last fix: %.5f, %.5f (±%dm)",
            payload.latitude, payload.longitude, payload.accuracy.roundToInt()
        )
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Broadcast receivers
// ─────────────────────────────────────────────────────────────────────────────

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationTracker.ACTION_GEOFENCE_EVENT) return
        LocationTracker.attach(context)

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            val message = GeofenceStatusCodes.getStatusCodeString(event.errorCode)
            Log.e(TAG, "Geofencing error: $message")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                Log.w(TAG, "Geofences were removed by the system (location disabled?). Re-register when available.")
            }
            return
        }

        val transition = GeofenceTransition.fromCode(event.geofenceTransition) ?: return
        val location = event.triggeringLocation?.let { LocationPayload.from(it) }
        val timestamp = location?.timestamp ?: Instant.now().toString()

        val events = event.triggeringGeofences.orEmpty().map { geofence ->
            GeofenceEvent(
                geofenceId = geofence.requestId,
                transition = transition,
                location = location,
                timestamp = timestamp
            )
        }
        if (events.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                events.forEach { LocationTracker.publishGeofenceEvent(it) }
            } finally {
                pending.finish()
            }
        }
    }
}

class GeofenceBootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "GeofenceBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        LocationTracker.attach(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                LocationTracker.reRegisterGeofences()
                    .onFailure { Log.e(TAG, "Failed to re-register geofences", it) }
            } finally {
                pending.finish()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Persistence
// ─────────────────────────────────────────────────────────────────────────────

private const val PREFS_NAME = "fleet_location_tracker"

private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

internal object TrackingConfigStore {
    private const val KEY = "tracking_config"

    fun save(context: Context, config: TrackingConfig) {
        prefs(context).edit().putString(KEY, config.toJson().toString()).apply()
    }

    fun load(context: Context): TrackingConfig =
        prefs(context).getString(KEY, null)
            ?.let { runCatching { TrackingConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: TrackingConfig()
}

internal class GeofenceStore(private val context: Context) {
    private val key = "geofences"

    @Synchronized
    fun load(): List<FleetGeofence> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { FleetGeofence.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(fences: List<FleetGeofence>) {
        val array = JSONArray().apply { fences.forEach { put(it.toJson()) } }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    @Synchronized
    fun clear() {
        prefs(context).edit().remove(key).apply()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Coroutine helpers
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}

private inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
