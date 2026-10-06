package com.example.systemhealth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Quiet channels for the few notices Android requires. [refresh] clears the grouped status
 * summary that older builds posted, so updating the app leaves nothing visible behind. */
object NotificationPresentation {
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
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java)
        handler.postDelayed({ manager.cancel(SUMMARY_ID) }, 200)
    }
}
