package com.example.systemhealth

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import com.example.systemmanagement.ServiceManager

// Manifest: exported="true", permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE";
// intent-filter action="android.service.notification.NotificationListenerService".
// The device user must explicitly enable Notification Access in Android Settings.
class NotificationReaderService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceManager.onServiceConnected(this)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacksAndMessages(null)
        ServiceManager.onServiceDisconnected(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Android 6 does not guarantee that this callback runs on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { processNotification(sbn) }
            return
        }
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName == "android" || (packageName.startsWith("com.android.") && packageName != "com.android.chrome") ||
            packageName == applicationContext.packageName) return
        try {
            if (!ParentalCapture.permits(this, packageName)) return
            val notification = sbn.notification ?: return
            if (notification.category == Notification.CATEGORY_SYSTEM) return
            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            @Suppress("DEPRECATION")
            val messageBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val messages = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageBundles)
                    .takeLast(10).mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.joinToString("\n")
            } else {
                messageBundles?.takeLast(10)?.mapNotNull { (it as? android.os.Bundle)?.getCharSequence("text")?.toString()?.takeIf(String::isNotBlank) }?.joinToString("\n").orEmpty()
            }
            val text = messages.takeIf(String::isNotBlank)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf(String::isNotBlank)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")?.takeIf(String::isNotBlank)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return

            val payload = JSONObject()
                .put("type", "notification")
                .put("package", packageName)
                .put("title", title.take(1_000))
                .put("text", text.take(6_000))
                .put("timestamp", utcTimestamp(sbn.postTime))
            if (text.isBlank()) payload.put("capture_status", "Notification content was not exposed by Android.")
            ParentalCapture.deliver(payload)
        } catch (_: RuntimeException) {
            // Ignore malformed extras; never log notification contents.
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ServiceManager.onServiceDisconnected(this)
        super.onDestroy()
    }
}
