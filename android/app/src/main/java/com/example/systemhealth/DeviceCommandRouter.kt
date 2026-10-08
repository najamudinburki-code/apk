package com.example.systemhealth

/** Decides how a remotely requested tool runs. The names mirror the backend's accepted actions:
 * silent ones answer from the background loop, capture ones use the camera or microphone without
 * showing a window, the rest need the app open on screen so their owner can approve them. */
internal object DeviceCommandRouter {
    enum class Mode { SILENT, CAPTURE, USER, UNKNOWN }

    // Stopping a live view is silent on purpose: a command that ends the camera must never be held
    // in the server's queue behind the very capture that is using it.
    private val silent = setOf("request_status", "request_scan", "request_settings", "request_live_view_stop")
    private val capture = setOf("request_photo", "request_audio", "request_live_view")
    private val user = setOf("request_screenshot", "request_location", "request_geofence")

    val actions: Set<String> get() = silent + capture + user

    fun route(action: String): Mode = when {
        action in silent -> Mode.SILENT
        action in capture -> Mode.CAPTURE
        action in user -> Mode.USER
        else -> Mode.UNKNOWN
    }
}
