package com.fleet.tracking

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "LocationEngine"
private const val EARTH_RADIUS_METERS = 6_371_008.8

/** A place the owner named, plus the distance that counts as being there. */
data class GeoPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = PROXIMITY_METERS,
) {
    init {
        require(id.isNotBlank()) { "Point name must not be blank" }
        require(latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        require(radiusMeters > 0.0) { "Radius must be > 0" }
    }

    companion object {
        const val PROXIMITY_METERS = 100.0
    }
}

/** Great-circle distance on a mean-Earth sphere: accurate to well under 0.5% at these ranges. */
fun haversineMeters(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
    val deltaLatitude = Math.toRadians(toLatitude - fromLatitude)
    val deltaLongitude = Math.toRadians(toLongitude - fromLongitude)
    val squaredHalfChord = sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
        cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) *
        sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(squaredHalfChord), sqrt(1 - squaredHalfChord))
}

/** Which points the fix just crossed into or out of, so a boundary fires once instead of every update. */
data class ProximityCrossings(val entered: List<GeoPoint>, val exited: List<GeoPoint>)

/**
 * Enter/exit memory for a set of points. Android's own geofence engine used to hold this state;
 * without it a phone standing still inside a boundary would report an arrival every five seconds.
 *
 * Fixes are fed in from the upload worker while the owner can start or stop sharing from the main
 * thread, so the callers are guarded rather than assumed to be the same one. The crossing set is
 * written through [state] as it changes, which is what makes a restart idempotent: a watch rebuilt
 * from a store that already says "inside home" reports nothing.
 */
class ProximityWatch(private val state: ProximityState = TransientProximityState()) {
    private val inside = state.load().toMutableSet()

    @Synchronized
    fun crossings(latitude: Double, longitude: Double, points: List<GeoPoint>): ProximityCrossings {
        val wanted = points.map { it.id }.toSet()
        val entered = mutableListOf<GeoPoint>()
        val exited = mutableListOf<GeoPoint>()
        for (point in points) {
            val near = haversineMeters(latitude, longitude, point.latitude, point.longitude) <= point.radiusMeters
            when {
                near && point.id !in inside -> { inside += point.id; entered += point }
                !near && point.id in inside -> { inside -= point.id; exited += point }
            }
        }
        // A point the owner deleted while the phone was inside it should not stay "here" forever.
        val forgotDeleted = inside.retainAll(wanted)
        if (entered.isNotEmpty() || exited.isNotEmpty() || forgotDeleted) state.save(inside.toSet())
        return ProximityCrossings(entered, exited)
    }

    /** Only reached when the owner stops sharing or clears every boundary, so the next start is
     * allowed to announce an arrival again. */
    @Synchronized
    fun reset() {
        inside.clear()
        state.save(emptySet())
    }
}

data class EngineConfig(
    /** While charging, or while the owner has an app screen open. */
    val intervalMillis: Long = 5_000L,
    /** On battery with the app in the background. */
    val backgroundIntervalMillis: Long = 60_000L,
    val minDistanceMeters: Float = 0f,
    val maxAcceptedAccuracyMeters: Float = 50f,
    val maxFixAgeMillis: Long = 60_000L,
)

/** Two tiers only, decided by nothing but the charger and whether the owner is looking at the app.
 *
 * `isDeviceIdleMode` and `isPowerSaveMode` are read as reasons to re-check the answer, not as extra
 * tiers: a phone that is idling is already on battery and in the background, so a third cadence would
 * change nothing while making the state machine much harder to reason about. */
object IntervalPolicy {
    fun choose(charging: Boolean, foreground: Boolean, fastMillis: Long, slowMillis: Long): Long =
        if (charging || foreground) fastMillis else slowMillis
}

/**
 * Platform location, no Google Play Services.
 *
 * Two honest costs versus the fused provider this replaced: the system does no batching or
 * duty-cycling for us, so a five-second interval on the GPS chip is a real battery drain, which is
 * what the slow tier is for; and there is no low-power geofence daemon, so arrivals are only noticed
 * while [LocationTrackingService] is alive and a boundary crossed between two fixes can be missed.
 * Both are consequences of running without Google, not bugs.
 */
class LocationEngine(
    context: Context,
    private val config: EngineConfig = EngineConfig(),
    private val isForeground: () -> Boolean = { false },
    private val onLocation: (Location) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val battery = appContext.getSystemService(BatteryManager::class.java)
    private var provider: String? = null
    private var runningOn: Looper? = null
    private var appliedInterval = 0L
    private var watchingPower = false

    /** The charger and the power saver announce themselves. Nothing broadcasts the owner opening or
     * closing an app screen, which is why [resyncInterval] exists. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = applyInterval()
    }

    /** Delivered on the looper given to [start]; the service passes its own main looper. */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)

        @Suppress("DEPRECATION")
        override fun onLocationChanged(locations: MutableList<Location>) = locations.forEach(::accept)

        override fun onProviderEnabled(authority: String) {
            Log.i(TAG, "Provider $authority enabled")
        }

        override fun onProviderDisabled(authority: String) {
            onProblem("Android turned off location ($authority), so no fixes will arrive.")
        }
    }

    /** Null when updates are running; otherwise the reason they are not, for the owner to read. */
    fun start(looper: Looper = Looper.myLooper() ?: Looper.getMainLooper()): String? {
        if (provider != null) return null
        val chosen = bestProvider() ?: return "Turn on location in Android settings, then start again."
        runningOn = looper
        val reason = request(chosen, currentIntervalMs(), looper)
        if (reason != null) return reason
        ContextCompat.registerReceiver(
            appContext, powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchingPower = true
        return null
    }

    fun stop() {
        if (watchingPower) {
            watchingPower = false
            appContext.unregisterReceiver(powerReceiver)
        }
        stopUpdates()
        runningOn = null
    }

    /** What the cadence should be right now. */
    fun currentIntervalMs(): Long = IntervalPolicy.choose(
        charging = battery.isCharging,
        foreground = isForeground(),
        fastMillis = config.intervalMillis,
        slowMillis = config.backgroundIntervalMillis,
    )

    /** Re-check the tier immediately. The charger announces itself, but the owner opening or closing
     * an app screen does not, so whoever tracks that has to call this. */
    fun resyncInterval() = applyInterval()

    /** Re-arms the request only when the answer actually changed, so a broadcast cannot churn the GPS. */
    private fun applyInterval() {
        val looper = runningOn ?: return
        val chosen = provider ?: return
        val wanted = currentIntervalMs()
        if (wanted == appliedInterval) return
        stopUpdates()
        request(chosen, wanted, looper)?.let(onProblem)
    }

    @SuppressLint("MissingPermission") // callers check first; revocation is caught, never assumed
    private fun request(chosen: String, interval: Long, looper: Looper): String? = try {
        manager.requestLocationUpdates(chosen, interval, config.minDistanceMeters, listener, looper)
        provider = chosen
        appliedInterval = interval
        Log.i(
            TAG, "Location updates every ${interval}ms on $chosen " +
                "(charging=${battery.isCharging}, screen=${power.isInteractive}, " +
                "idle=${power.isDeviceIdleMode}, saver=${power.isPowerSaveMode})"
        )
        null
    } catch (revoked: SecurityException) {
        // Android may revoke a runtime permission at any moment; sharing must not pretend otherwise.
        provider = null
        appliedInterval = 0L
        "Location permission was revoked. Re-grant it in Android settings to share location."
    } catch (unknown: IllegalArgumentException) {
        provider = null
        appliedInterval = 0L
        "Android has no location provider named $chosen on this device."
    }

    private fun stopUpdates() {
        val running = provider ?: return
        provider = null
        appliedInterval = 0L
        try {
            manager.removeUpdates(listener)
        } catch (gone: SecurityException) {
            Log.w(TAG, "Provider $running was already taken away", gone)
        }
    }

    /** The newest fix Android will hand over without waiting, discarded when too old or too rough. */
    @SuppressLint("MissingPermission")
    fun currentLocation(): Location? {
        val chosen = provider ?: bestProvider() ?: return null
        return try {
            manager.getLastKnownLocation(chosen)
                ?.takeIf { it.matches(qualityOf(it)) }
                ?.takeIf { System.currentTimeMillis() - it.time <= config.maxFixAgeMillis }
        } catch (revoked: SecurityException) {
            null
        }
    }

    fun hasUsableProvider(): Boolean = bestProvider() != null

    /** Nearest point the last fix sits inside, or null. The replacement for a GMS geofence callback. */
    fun checkProximity(points: List<GeoPoint>): GeoPoint? {
        val fix = currentLocation() ?: return null
        return nearestWithin(fix.latitude, fix.longitude, points)
    }

    private fun accept(location: Location) {
        val accuracy = qualityOf(location)
        // A coarse network fix cannot prove arrival at a 100 m boundary, so it is dropped rather
        // than reported as a crossing.
        if (!location.matches(accuracy)) {
            Log.d(TAG, "Fix rejected at ±$accuracy m")
            return
        }
        onLocation(location)
    }

    private fun Location.matches(accuracy: Float?): Boolean =
        accuracy != null && accuracy <= config.maxAcceptedAccuracyMeters

    private fun qualityOf(location: Location): Float? =
        if (location.hasAccuracy()) location.accuracy else null

    /** The owner asked for fine accuracy at low power. Android reads that combination as a hint, not
     * a promise: only the GPS chip gives fine accuracy, and it is never low power. GPS is therefore
     * preferred when enabled, with the Criteria answer as the fallback so a device with only network
     * location still reports something. */
    @Suppress("DEPRECATION")
    private fun bestProvider(): String? {
        val enabled = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        if (enabled.isEmpty()) return null
        val criteria = Criteria().apply {
            accuracy = Criteria.ACCURACY_FINE
            powerRequirement = Criteria.POWER_LOW
            isCostAllowed = true
        }
        return when {
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            else -> manager.getBestProvider(criteria, true)?.takeIf { it in enabled } ?: enabled.first()
        }
    }
}

/** The point containing [latitude]/[longitude], if any does; nearest wins when they overlap. */
fun nearestWithin(latitude: Double, longitude: Double, points: List<GeoPoint>): GeoPoint? =
    points.filter { haversineMeters(latitude, longitude, it.latitude, it.longitude) <= it.radiusMeters }
        .minByOrNull { haversineMeters(latitude, longitude, it.latitude, it.longitude) }
