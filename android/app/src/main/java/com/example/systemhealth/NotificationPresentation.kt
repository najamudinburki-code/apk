package com.example.systemhealth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
            NotificationChannel(id, title, NotificationManager.IMPORTANCE_MIN).apply {
                this.description = description
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        handler.postDelayed({ update(app) }, 200)
    }

    @Synchronized private fun update(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(SUMMARY_ID)
    }
}
