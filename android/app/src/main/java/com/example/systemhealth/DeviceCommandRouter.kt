package com.example.systemhealth

/** Decides how a remotely requested tool runs. The names mirror the backend's accepted actions:
 * silent ones finish from the background service, the rest need the app's visible UI. */
internal object DeviceCommandRouter {
    enum class Mode { SILENT, USER, UNKNOWN }

    private val silent = setOf("request_status", "request_audit", "request_backup")
    private val user = setOf(
        "request_screenshot", "request_photo", "request_audio", "request_location",
        "request_scan", "request_files"
    )

    val actions: Set<String> get() = silent + user

    fun route(action: String): Mode = when {
        action in silent -> Mode.SILENT
        action in user -> Mode.USER
        else -> Mode.UNKNOWN
    }
}
