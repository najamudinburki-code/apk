package com.example.systemhealth

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.systemmanagement.ServiceManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AccessibilityHelperService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceManager.onServiceConnected(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearPending()
        lastText.clear()
        ServiceManager.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    private val handler = Handler(Looper.getMainLooper())
    private data class PendingCapture(val text: String, val task: Runnable)
    private val pending = mutableMapOf<String, PendingCapture>()
    private val lastText = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 64
    }
    private var session = -1L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.isPassword) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        if (session != ParentalCapture.session) {
            clearPending()
            lastText.clear()
            session = ParentalCapture.session
        }
        val packageName = event.packageName?.toString() ?: return
        if (!ParentalCapture.permits(this, packageName)) {
            clearPending()
            return
        }

        try {
            val root = rootInActiveWindow
            if (root == null) {
                cancelPending(packageName)
                return
            }
            val text = try {
                if (root.packageName?.toString() != packageName) {
                    cancelPending(packageName)
                    return
                }
                traverseNode(root)
            } finally {
                recycleNode(root)
            }
            if (text.isBlank() || lastText[packageName] == text) {
                cancelPending(packageName)
                return
            }
            // Repeated events with the same pending text must not reset its timer.
            if (pending[packageName]?.text == text) return
            cancelPending(packageName)

            val capturedSession = session
            val timestamp = utcTimestamp()
            val task = Runnable {
                pending.remove(packageName)
                if (capturedSession != ParentalCapture.session ||
                    !ParentalCapture.permits(this, packageName) ||
                    lastText[packageName] == text) return@Runnable

                val payload = JSONObject()
                    .put("type", "screen_text")
                    .put("package", packageName)
                    .put("text", text)
                    .put("timestamp", timestamp)
                if (ParentalCapture.deliver(payload)) lastText[packageName] = text
            }
            pending[packageName] = PendingCapture(text, task)
            handler.postDelayed(task, 500L)
        } catch (_: NullPointerException) {
            // A transient UI tree may disappear while being inspected.
            clearPending()
        } catch (_: IllegalStateException) {
            clearPending()
        } catch (_: SecurityException) {
            clearPending()
        }
    }

    fun traverseNode(node: AccessibilityNodeInfo?): String {
        val result = StringBuilder()
        var visited = 0

        fun visit(current: AccessibilityNodeInfo?, depth: Int) {
            if (current == null || depth > 32 || visited >= 500 || result.length >= 6_000) return
            visited++
            if (current.isPassword || !current.isVisibleToUser) return
            val className = current.className?.toString().orEmpty()
            if (className.endsWith("TextView") || className.endsWith("EditText") ||
                !current.text.isNullOrBlank()) {
                val value = current.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    if (result.isNotEmpty()) result.append('\n')
                    val remaining = 6_000 - result.length
                    result.append(value.take(remaining.coerceAtLeast(0)))
                }
            }
            for (index in 0 until current.childCount) {
                if (visited >= 500 || result.length >= 6_000) break
                val child = current.getChild(index) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    recycleNode(child)
                }
            }
        }

        try {
            visit(node, 0)
        } catch (_: NullPointerException) {
            return ""
        } catch (_: IllegalStateException) {
            return ""
        }
        return result.toString()
    }

    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun cancelPending(packageName: String) {
        pending.remove(packageName)?.let { handler.removeCallbacks(it.task) }
    }

    private fun clearPending() {
        pending.values.forEach { handler.removeCallbacks(it.task) }
        pending.clear()
    }

    override fun onInterrupt() {
        clearPending()
    }

    override fun onDestroy() {
        clearPending()
        lastText.clear()
        ServiceManager.onServiceDisconnected(this)
        super.onDestroy()
    }
}

// Call enable() or enableAllApps() from the device's visible consent UI.
// Register an in-process consumer first; nothing is logged, uploaded, or persisted here.
// Call disable() from the app's Stop control. Consent expires on process death.
object ParentalCapture {
    private const val CHANNEL = "parental_capture_status"
    private const val NOTICE_ID = 2101
    private var approvedPackages = emptySet<String>()
    private var captureAllApps = false
    private var enabled = false
    var session: Long = 0
        private set
    var onJson: ((JSONObject) -> Unit)? = null

    fun enable(context: Context, packages: Set<String>): Boolean {
        return enableCapture(context, packages, false)
    }

    fun enableAllApps(context: Context): Boolean = enableCapture(context, emptySet(), true)

    private fun enableCapture(context: Context, packages: Set<String>, allApps: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        disable(context)
        if ((!allApps && packages.isEmpty()) || onJson == null) return false
        if (packages.any { !AppCapturePolicy.isEligible(context.packageName, it) }) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationPresentation.quietChannel(context, CHANNEL, "Parental monitoring", "Visible status while app text and notification contents are shared")
            if (manager.getNotificationChannel(CHANNEL)?.importance ==
                NotificationManager.IMPORTANCE_NONE) return false
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return false
        val openApp = PendingIntent.getActivity(
            context, NOTICE_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(NOTICE_ID, builder
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Parental monitoring is active")
            .setContentText(if (allApps) "Text and notifications from all supported apps are being shared. Open app to stop."
                else "Approved app text and notifications are being shared. Open app to stop.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NotificationPresentation.GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .build())
        NotificationPresentation.refresh(context)
        approvedPackages = packages.toSet()
        captureAllApps = allApps
        enabled = true
        return true
    }

    fun disable(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        enabled = false
        approvedPackages = emptySet()
        captureAllApps = false
        session++
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID)
        NotificationPresentation.refresh(context)
    }

    internal fun permits(context: Context, packageName: String): Boolean {
        if (!enabled || onJson == null ||
            !AppCapturePolicy.includes(context.packageName, packageName, captureAllApps, approvedPackages)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL)?.importance ==
            NotificationManager.IMPORTANCE_NONE) return false
        return manager.activeNotifications.any { it.id == NOTICE_ID && it.tag == null }
    }

    internal fun deliver(json: JSONObject): Boolean {
        val consumer = onJson ?: return false
        return try {
            consumer(json)
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}

internal fun utcTimestamp(timeMillis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timeMillis))
