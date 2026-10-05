package com.example.utility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.MessageDigest
import com.example.systemhealth.ParentalCapture
import com.example.systemmanagement.ServiceManager

open class ScreenMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenMonitorService"
        private const val DEBOUNCE_MS = 500L
        const val PREFS_NAME = "screen_monitor"
        const val KEY_ENABLED = "monitoring_enabled"
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        const val KEY_ALL_APPS = "all_apps"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var pendingPackage: String? = null
    private var lastDeliveredHash: String? = null
    private var captureSession = -1L
    private var eventText = ""

    private val captureRunnable = Runnable {
        val targetPackage = pendingPackage
        pendingPackage = null
        if (targetPackage != null && isCollectionAllowed(targetPackage)) {
            captureCurrentWindow(targetPackage)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        clearState()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0L
            // Ensure we can retrieve all interactive windows
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        ServiceManager.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.isPassword) return
        if (captureSession != ParentalCapture.session) {
            clearState()
            captureSession = ParentalCapture.session
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handler.removeCallbacks(captureRunnable)
                pendingPackage = null
                val sourcePackage = event.packageName?.toString() ?: return
                if (!isCollectionAllowed(sourcePackage)) {
                    lastDeliveredHash = null
                    return
                }
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    val source = event.source
                    try {
                        // Only explicitly approved, ordinary fields; never password events.
                        if (source != null && !source.isPassword) {
                            eventText = event.text.joinToString(" ").take(1600)
                        }
                    } finally { releaseNode(source) }
                } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    eventText = ""
                }
                pendingPackage = sourcePackage
                handler.postDelayed(captureRunnable, DEBOUNCE_MS)
            }
            else -> Unit
        }
    }

    private fun captureCurrentWindow(expectedPackage: String) {
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return
            val actualPackage = root.packageName?.toString() ?: return
            if (actualPackage != expectedPackage || !isCollectionAllowed(actualPackage)) return

            // 1. Extract Data
            val fields = mutableListOf<JSONObject>()
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try { if (focused != null) traverseForFields(focused, fields) }
            finally { releaseNode(focused) }
            traverseForFields(root, fields)

            // 2. Simple Hash for Duplicate Detection
            // We hash the package + number of fields + sum of text lengths
            // This is lighter than hashing the full JSON
            val hash = simpleHash(actualPackage + eventText, fields)
            if (hash == lastDeliveredHash) return

            // 3. Build JSON
            val json = JSONObject().apply {
                put("type", "screen_fields")
                put("package", actualPackage)
                put("timestamp", utcTimestamp())
                put("fields", JSONArray(fields))
                if (eventText.isNotBlank()) put("event_text", eventText)
                if (fields.none { it.optString("text").isNotBlank() } && eventText.isBlank()) {
                    put("capture_status", "The app did not expose readable text on this screen.")
                }
            }

            // UTF-8 can use four bytes per character. Keep the socket payload below its 48 KiB cap.
            while (json.toString().toByteArray(Charsets.UTF_8).size > 40 * 1024 && fields.isNotEmpty()) {
                fields.removeAt(fields.lastIndex)
                json.put("fields", JSONArray(fields))
            }
            if (!isCollectionAllowed(actualPackage)) return
            onTextExtracted(json)
            lastDeliveredHash = hash
        } catch (_: NullPointerException) {
            Log.w(TAG, "Accessibility tree changed during capture.")
        } catch (_: IllegalStateException) {
            Log.w(TAG, "Accessibility tree is no longer available.")
        } catch (_: SecurityException) {
            Log.w(TAG, "Accessibility window access was denied.")
        } finally {
            releaseNode(root)
        }
    }

    /**
     * Extracts visible text and field metadata. Password values are redacted.
     */
    private fun traverseForFields(node: AccessibilityNodeInfo?, fields: MutableList<JSONObject>) {
        var visited = 0
        var characters = 0
        fun bounded(value: CharSequence?): String {
            val remaining = (6_000 - characters).coerceAtLeast(0)
            val text = value?.toString().orEmpty().take(minOf(1_000, remaining))
            characters += text.length
            return text
        }
        fun visit(current: AccessibilityNodeInfo?, depth: Int) {
            if (current == null || depth > 32 || visited >= 500 ||
                fields.size >= 64 || characters >= 6_000) return
            visited++
            if (!current.isVisibleToUser) return
            val className = current.className?.toString().orEmpty()
            val isEditable = className.contains("EditText") || current.isEditable
            val isTextView = className.contains("TextView")
            if (isEditable || isTextView || !current.text.isNullOrBlank() ||
                (current.childCount == 0 && !current.contentDescription.isNullOrBlank())) {
                val password = current.isPassword
                fields.add(JSONObject().apply {
                    put("type", if (isEditable) "input" else "text")
                    put("isPassword", password)
                    put("resourceId", bounded(current.viewIdResourceName))
                    put("text", if (password) "[REDACTED]" else bounded(current.text
                        ?: if (current.childCount == 0) current.contentDescription else null))
                    if (password) {
                        put("redacted", true)
                    } else {
                        val hint = bounded(current.hintText)
                        if (hint.isNotEmpty()) put("hint", hint)
                        val description = bounded(current.contentDescription)
                        if (description.isNotEmpty()) put("contentDescription", description)
                    }
                })
            }
            if (current.isPassword) return
            for (index in 0 until current.childCount) {
                if (visited >= 500 || fields.size >= 64 || characters >= 6_000) break
                val child = current.getChild(index) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    releaseNode(child)
                }
            }
        }
        visit(node, 0)
    }

    /**
     * Lightweight hash for duplicate detection.
     */
    private fun simpleHash(packageName: String, fields: List<JSONObject>): String {
        val content = packageName + JSONArray(fields).toString()
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isCollectionAllowed(sourcePackage: String): Boolean {
        if (!preferences.getBoolean(KEY_ENABLED, false)) return false
        val allowedPackages = preferences.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()
        return (preferences.getBoolean(KEY_ALL_APPS, false) || sourcePackage in allowedPackages) &&
            ParentalCapture.permits(this, sourcePackage)
    }

    protected open fun onTextExtracted(json: JSONObject) {
        if (isCollectionAllowed(json.optString("package"))) ParentalCapture.deliver(json)
    }

    private fun utcTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }

    @Suppress("DEPRECATION")
    private fun releaseNode(node: AccessibilityNodeInfo?) {
        node?.recycle()
    }

    private fun clearState() {
        handler.removeCallbacks(captureRunnable)
        pendingPackage = null
        lastDeliveredHash = null
        eventText = ""
    }

    override fun onInterrupt() {
        clearState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearState()
        ServiceManager.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearState()
        ServiceManager.onServiceDisconnected(this)
        super.onDestroy()
    }
}
