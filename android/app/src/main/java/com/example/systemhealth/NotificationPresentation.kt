package com.example.systemhealth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Groups visible notices. Each foreground service retains its own status and controls. */
object NotificationPresentation {
    const val GROUP = "system_health_visible_status"
    const val REQUEST_CHANNEL = "phone_requests_quiet_v1"
    private const val SUMMARY_CHANNEL = "monitoring_summary"
    private const val SUMMARY_ID = 3090
    private val handler = Handler(Looper.getMainLooper())

    fun quietChannel(context: Context, id: String, title: String, description: String) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, title, NotificationManager.IMPORTANCE_LOW).apply {
                this.description = description
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        // Notification posts/removals are asynchronous in Android's notification service.
        handler.postDelayed({ update(app) }, 200)
    }

    @Synchronized private fun update(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val children = manager.activeNotifications.filter { it.id != SUMMARY_ID && it.notification.group == GROUP }.sortedBy { it.id }
        if (children.isEmpty()) { manager.cancel(SUMMARY_ID); return }
        quietChannel(context, SUMMARY_CHANNEL, "Active monitoring summary", "Visible overview of active sharing and phone requests")
        val titles = children.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "System Health status" }
        val open = PendingIntent.getActivity(context, SUMMARY_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, SUMMARY_ID, Intent(context, CoreService::class.java).setAction(CoreService.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ongoing = children.any { (it.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 }
        val style = Notification.InboxStyle().setSummaryText("Open System Health to manage sharing")
        titles.forEach { style.addLine(it) }
        val builder = Notification.Builder(context, SUMMARY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(if (ongoing) "System Health — monitoring active" else "System Health — requests to review")
            .setContentText(titles.joinToString(" · ")).setStyle(style)
            .setContentIntent(open).setGroup(GROUP).setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setShowWhen(false)
        if (ongoing) builder.addAction(Notification.Action.Builder(null, "Stop monitoring", stop).build())
        manager.notify(SUMMARY_ID, builder.build())
    }
}
