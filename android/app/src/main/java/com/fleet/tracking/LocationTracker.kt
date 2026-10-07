/*
 * LocationTracker.kt — platform location sharing + local proximity boundaries
 *
 * AndroidManifest.xml (already present):
 *
 *   <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 *   <application ...>
 *     <service
 *         android:name="com.fleet.tracking.LocationTrackingService"
 *         android:exported="false"
 *         android:foregroundServiceType="location" />
 *   </application>
 *
 * Gradle: no play-services-location. core-ktx and coroutines only.
 *
 * Usage (Application.onCreate):
 *   LocationTracker.initialize(this, object : TrackingSink {
 *       override suspend fun onLocation(payload: LocationPayload) = queue(payload)
 *       override suspend fun onGeofenceEvent(event: GeofenceEvent) = queue(event)
 *   })
 */

package com.fleet.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.systemhealth.AppForeground
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
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
        fun from(location: android.location.Location): LocationPayload = LocationPayload(
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

/** A boundary the owner named. Kept in phone preferences, watched by [ProximityWatch]. */
data class FleetGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = GeoPoint.PROXIMITY_METERS.toFloat()
) {
    init {
        require(id.isNotBlank()) { "Geofence id must not be blank" }
        require(latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        require(radiusMeters > 0f) { "Radius must be > 0" }
    }

    fun toGeoPoint(): GeoPoint = GeoPoint(id, latitude, longitude, radiusMeters.toDouble())

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("latitude", latitude)
        put("longitude", longitude)
        put("radiusMeters", radiusMeters.toDouble())
    }

    internal companion object {
        /** Old saved boundaries carry loitering and expiration fields; they are ignored now. */
        fun fromJson(json: JSONObject): FleetGeofence = FleetGeofence(
            id = json.getString("id"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            radiusMeters = json.optDouble("radiusMeters", GeoPoint.PROXIMITY_METERS).toFloat()
        )
    }
}

enum class GeofenceTransition { ENTER, EXIT }

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

/**
 * Why nothing asks for ACCESS_BACKGROUND_LOCATION any more: Android grants a foreground service of
 * type `location` while-in-use access for as long as that service runs, which is all a polling
 * proximity watch needs. Only the old Google geofence daemon, which wakes up with no service of its
 * own, required "allow all the time".
 */
data class TrackingConfig(
    val intervalMillis: Long = 5_000L,
    val backgroundIntervalMillis: Long = 60_000L,
    val minUpdateDistanceMeters: Float = 0f,
    val maxAcceptedAccuracyMeters: Float = 50f
) {
    internal fun toEngineConfig() = EngineConfig(
        intervalMillis = intervalMillis,
        backgroundIntervalMillis = backgroundIntervalMillis,
        minDistanceMeters = minUpdateDistanceMeters,
        maxAcceptedAccuracyMeters = maxAcceptedAccuracyMeters
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("intervalMillis", intervalMillis)
        put("backgroundIntervalMillis", backgroundIntervalMillis)
        put("minUpdateDistanceMeters", minUpdateDistanceMeters.toDouble())
        put("maxAcceptedAccuracyMeters", maxAcceptedAccuracyMeters.toDouble())
    }

    internal companion object {
        fun fromJson(json: JSONObject): TrackingConfig = TrackingConfig(
            intervalMillis = json.optLong("intervalMillis", 5_000L),
            backgroundIntervalMillis = json.optLong("backgroundIntervalMillis", 60_000L),
            minUpdateDistanceMeters = json.optDouble("minUpdateDistanceMeters", 0.0).toFloat(),
            maxAcceptedAccuracyMeters = json.optDouble("maxAcceptedAccuracyMeters", 50.0).toFloat()
        )
    }
}

/** Raised when this phone cannot produce fixes yet, with the owner-facing reason to show. */
class LocationSettingsUnavailable(val reason: String) : Exception(reason)

/** Implement to forward data to your backend (called off the main thread). */
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

    private val watch: ProximityWatch by lazy { ProximityWatch(ProximityStateStore(context)) }

    private val context: Context
        get() = appContext
            ?: error("LocationTracker.initialize() must be called first (e.g. in Application.onCreate)")

    private val geofenceStore: GeofenceStore by lazy { GeofenceStore(context) }

    fun initialize(context: Context, sink: TrackingSink? = null) {
        appContext = context.applicationContext
        if (sink != null) this.sink = sink
    }

    internal fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    fun hasFineLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Kept for the tools screen, which still offers "all the time" for owners who want it. */
    fun hasBackgroundLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            hasFineLocationPermission()
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Readiness ────────────────────────────────────────────────────────────

    /** Replaces the Play Services settings check: does this phone have a provider and permission? */
    suspend fun checkLocationSettings(config: TrackingConfig = TrackingConfig()): Result<Unit> =
        suspendRunCatching {
            check(hasFineLocationPermission() || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                "Location permission not granted"
            }
            val managers = context.getSystemService(LocationManager::class.java)
            val usable = managers.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
            if (usable.isEmpty()) throw LocationSettingsUnavailable(
                "Turn on location in Android settings, then start sharing again."
            )
            Unit
        }

    /** The Android page that turns location on; no longer a Play Services resolution dialog. */
    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    // ── Tracking ─────────────────────────────────────────────────────────────

    fun startTracking(config: TrackingConfig = TrackingConfig()) {
        check(hasFineLocationPermission() || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            "Location permission not granted"
        }
        TrackingConfigStore.save(context, config)
        val intent = Intent(context, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopTracking() {
        watch.reset()
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }

    // ── Boundaries ───────────────────────────────────────────────────────────

    /** Watching happens in the running service, so saving a boundary is only a preference write. */
    suspend fun addGeofences(fences: List<FleetGeofence>): Result<Unit> = suspendRunCatching {
        require(fences.isNotEmpty()) { "No geofences supplied" }
        check(hasFineLocationPermission() || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            "Location permission not granted"
        }
        val merged = geofenceStore.load().associateBy { it.id } + fences.associateBy { it.id }
        require(merged.size <= MAX_GEOFENCES) { "Geofence limit exceeded (${merged.size}/$MAX_GEOFENCES)" }
        geofenceStore.save(merged.values.toList())
        // A phone already standing inside a new boundary should report arrival at once, the way the
        // old initial-trigger geofence did, rather than waiting for the next crossing.
        _locations.replayCache.firstOrNull()?.let { fix -> reportCrossings(fix) }
    }

    suspend fun addGeofence(fence: FleetGeofence): Result<Unit> = addGeofences(listOf(fence))

    suspend fun removeGeofences(ids: List<String>): Result<Unit> = suspendRunCatching {
        if (ids.isEmpty()) return@suspendRunCatching
        geofenceStore.save(geofenceStore.load().filterNot { it.id in ids })
    }

    suspend fun removeAllGeofences(): Result<Unit> = suspendRunCatching {
        geofenceStore.clear()
        watch.reset()
    }

    fun registeredGeofences(): List<FleetGeofence> = geofenceStore.load()

    internal fun points(): List<GeoPoint> = geofenceStore.load().map { it.toGeoPoint() }

    /** Feeds one fix through the watch and publishes every boundary it crossed. */
    internal suspend fun reportCrossings(fix: LocationPayload) {
        val crossings = watch.crossings(fix.latitude, fix.longitude, points())
        for (point in crossings.entered) publishGeofenceEvent(GeofenceEvent(point.id, GeofenceTransition.ENTER, fix, fix.timestamp))
        for (point in crossings.exited) publishGeofenceEvent(GeofenceEvent(point.id, GeofenceTransition.EXIT, fix, fix.timestamp))
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    internal fun setTracking(active: Boolean) { _isTracking.value = active }

    internal suspend fun publishLocation(payload: LocationPayload) {
        _locations.emit(payload)
        sink?.let { s ->
            try {
                s.onLocation(payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Location upload failed: ${error.javaClass.simpleName}")
            }
        }
    }

    internal suspend fun publishGeofenceEvent(event: GeofenceEvent) {
        _geofenceEvents.emit(event)
        sink?.let { s ->
            try {
                s.onGeofenceEvent(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Geofence upload failed: ${error.javaClass.simpleName}")
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

    private var engine: LocationEngine? = null
    private lateinit var notificationManager: NotificationManager

    /** Nothing announces the owner opening or closing a screen, so the cadence is re-checked here on
     * every foreground change. Both ends of this run on the main thread. */
    private val foregroundChanged: (Boolean) -> Unit = { engine?.resyncInterval() }

    override fun onCreate() {
        super.onCreate()
        LocationTracker.attach(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        serviceScope.launch {
            for (payload in outbox) {
                LocationTracker.publishLocation(payload)
                LocationTracker.reportCrossings(payload)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!LocationTracker.hasFineLocationPermission()) {
            // Revoked permission is a stop, never a silent fallback to coarser tracking.
            Log.i(TAG, "Location permission revoked; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        val config = TrackingConfigStore.load(this)
        stopEngine()
        val replacement = LocationEngine(
            context = this,
            config = config.toEngineConfig(),
            isForeground = { AppForeground.isForeground },
            onLocation = { fix ->
                val payload = LocationPayload.from(fix)
                outbox.trySend(payload)
                // Coordinates stay off the lockscreen: accuracy proves the fix is real without
                // publishing where the owner happens to be standing.
                notificationManager.notify(NOTIFICATION_ID, buildNotification("Sharing ±${payload.accuracy.roundToInt()} m fix"))
            },
            onProblem = { detail -> notificationManager.notify(NOTIFICATION_ID, buildNotification(detail)) }
        )
        engine = replacement
        val refusal = replacement.start(Looper.getMainLooper())
        if (refusal != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(refusal))
            stopEngine()
            stopSelf()
            return
        }
        AppForeground.addWatcher(foregroundChanged)
        LocationTracker.setTracking(true)
    }

    private fun stopEngine() {
        AppForeground.removeWatcher(foregroundChanged)
        engine?.stop()
        engine = null
        LocationTracker.setTracking(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEngine()
        outbox.close()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting location sharing…"), type)
        true
    } catch (error: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException (API 34+ missing permission)
        Log.e(TAG, "Unable to start foreground service", error)
        false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shown while this phone's location is being shared with your dashboard"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
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

private suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
