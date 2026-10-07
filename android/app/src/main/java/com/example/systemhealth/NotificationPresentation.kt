package com.example.systemhealth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/** One place decides what the owner is told about what this app is doing.
 *
 * Android requires an ongoing notification while a foreground service runs, so that notice is never
 * hidden or downgraded to nothing. What the owner can choose is the extra alert for a dashboard
 * camera or microphone capture, and whether the ongoing line carries live detail. */
object NotificationPresentation {
    const val MONITORING_CHANNEL = "system_health_monitor"
    const val CAPTURE_CHANNEL = "capture_alerts"
    const val SCREEN_CHANNEL = "screen_capture"

    private const val PREFS = "notification_state"
    private const val KEY_ACTIVITY = "last_activity"
    private const val KEY_LOG = "activity_log"
    private const val KEY_CAPTURE_ALERTS = "capture_alerts"
    private const val KEY_DETAIL = "monitoring_detail"
    private const val ALERT_ID = 3040
    private const val LOG_LIMIT = 50

    fun captureAlerts(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAPTURE_ALERTS, true)

    /** The state line inside the ongoing monitoring notice: still a disclosure, just more useful. */
    fun detailInOngoing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DETAIL, true)

    fun setCaptureAlerts(context: Context, enabled: Boolean) = preference(context, KEY_CAPTURE_ALERTS, enabled)
    fun setDetailInOngoing(context: Context, enabled: Boolean) = preference(context, KEY_DETAIL, enabled)

    private fun preference(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
        CoreService.refreshNotification()
    }

    /** Channels are created once per start and re-created when a preference changes. Importance is
     * never set to NONE: that would make Android tear down the monitoring service. */
    fun applyChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MONITORING_CHANNEL, "Monitoring status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows that System Health is monitoring and sharing with your dashboard"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CAPTURE_CHANNEL, "Dashboard capture alerts", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One notice each time a dashboard request used the camera or microphone"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SCREEN_CHANNEL, "Approved screenshot", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows while Android is sharing one screenshot of your screen"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
    }

    /** Records that an upload reached the server, for the ongoing line and the phone-side log. */
    fun recordDelivery(context: Context, label: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = LocalTime.now().withNano(0).toString()
        val entry = JSONObject().put("label", label).put("time", stamp)
        val log = JSONArray().apply {
            JSONArray(prefs.getString(KEY_LOG, "[]").orEmpty()).let { previous ->
                put(entry)
                for (i in 0 until minOf(previous.length(), LOG_LIMIT - 1)) put(previous.getJSONObject(i))
            }
        }
        prefs.edit().putString(KEY_ACTIVITY, "$label at $stamp").putString(KEY_LOG, log.toString()).apply()
        CoreService.refreshNotification()
    }

    /** The last deliveries this phone made to the dashboard, newest first. */
    fun activityLog(context: Context): List<JSONObject> {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun ongoingText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activity = prefs.getString(KEY_ACTIVITY, null)
        if (!detailInOngoing(context)) return "Sharing with your dashboard"
        val pending = FeatureBridge.pendingCount(context)
        return when {
            pending > 0 -> "$pending upload(s) waiting" + (activity?.let { " · last sent $it" } ?: "")
            activity != null -> "Last sent $activity"
            else -> "Sharing with your dashboard. No uploads yet."
        }
    }

    /** A one-shot, silent heads-up for a sensor capture the owner cannot see happening. */
    fun captureAlert(context: Context, title: String, text: String) {
        if (!captureAlerts(context)) return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CAPTURE_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setOnlyAlertOnce(true).build()
        context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
    }
}
