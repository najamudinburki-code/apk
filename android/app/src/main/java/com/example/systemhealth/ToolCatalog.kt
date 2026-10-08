package com.example.systemhealth

/**
 * One place names what this phone can do, in the owner's words. The tools screen builds its headings
 * and its search filter from this list, so a row's label and the word that finds it can never drift
 * apart, and neither depends on Android to be checked.
 */
internal data class Tool(
    val id: String,
    val name: String,
    val blurb: String,
    val category: String,
    val keywords: List<String> = emptyList()
)

internal object ToolCatalog {
    const val REQUESTS = "Dashboard requests"
    const val CAPTURE = "Camera, microphone and screen"
    const val LOCATION = "Location and places to watch"
    const val NEARBY = "Nearby scan"
    const val REPORTS = "Reports that repeat"
    const val FILES = "Shared files"
    const val NOTICES = "Notices and privacy"

    /** Categories in the order the tools screen shows them. */
    val categories: List<String> = listOf(REQUESTS, CAPTURE, LOCATION, NEARBY, REPORTS, FILES, NOTICES)

    val tools: List<Tool> = listOf(
        Tool("run_requests", "Run a waiting request", "See what your dashboard has asked for and answer one now.",
            REQUESTS, listOf("pending", "queue")),
        Tool("cancel_request", "Cancel the active request", "Stop the request this phone is working on; the dashboard is told you cancelled it.",
            REQUESTS, listOf("abort", "decline")),
        Tool("camera_lens", "Which camera", "Front by default. If the chosen lens is missing you get an error, never a silent swap.",
            CAPTURE, listOf("front", "rear", "lens")),
        Tool("photo", "Take one photo", "One picture, then it goes to your dashboard Files.",
            CAPTURE, listOf("camera", "image", "picture")),
        Tool("recording_start", "Record microphone audio", "Records while this screen stays open, in 10-second pieces.",
            CAPTURE, listOf("sound", "voice", "audio")),
        Tool("recording_stop", "Stop the recording", "Ends the recording and keeps what was already saved.",
            CAPTURE, listOf("audio")),
        Tool("screenshot", "Take one screenshot", "Android asks which screen to share; one capture, then it stops.",
            CAPTURE, listOf("screen", "image")),
        Tool("live_view_allow", "Allow the dashboard to start a live camera view", "Lets your dashboard start a short, self-ending stream. Off means every request is refused.",
            CAPTURE, listOf("stream", "video", "live")),
        Tool("live_view_stop", "Stop the live camera view now", "Ends a running stream immediately and hands the camera back.",
            CAPTURE, listOf("stream", "end")),
        Tool("location_start", "Start sharing location", "Sends where this phone is while monitoring runs.",
            LOCATION, listOf("gps", "map")),
        Tool("location_stop", "Stop sharing location", "Ends location reporting; monitoring itself is unaffected.",
            LOCATION, listOf("gps")),
        Tool("geofence_add", "Watch an area", "Add a place and a radius so arrivals and departures are reported.",
            LOCATION, listOf("boundary", "place", "radius", "geofence")),
        Tool("geofence_list", "See or remove watched areas", "List every area this phone watches and remove any of them.",
            LOCATION, listOf("boundary", "delete", "geofence")),
        Tool("scan", "Scan nearby Wi-Fi and Bluetooth", "One discovery sweep, up to about 35 seconds, then the list is uploaded.",
            NEARBY, listOf("wifi", "bluetooth", "networks", "nearby")),
        Tool("report_cadence", "How often", "Pick the gap between repeats: 15, 30, 60 or 240 minutes.",
            REPORTS, listOf("interval", "every")),
        Tool("report_status", "Repeat the phone status report", "Sends phone status on its own while monitoring is on.",
            REPORTS, listOf("schedule", "repeat", "status")),
        Tool("report_scan", "Repeat the nearby scan", "Rescans nearby networks on its own while monitoring is on.",
            REPORTS, listOf("schedule", "repeat", "wifi", "bluetooth")),
        Tool("folder_pick", "Choose a folder to browse", "Pick a folder with Android's picker, then confirm the one file to upload.",
            FILES, listOf("documents", "browse", "picker")),
        Tool("local_files", "Files saved on this phone", "Open, export, play or delete what this phone has kept.",
            FILES, listOf("vault", "delete", "export", "download")),
        Tool("clear_queue", "Clear waiting uploads", "Delete unsent items from the phone queue. Saved and uploaded files stay.",
            FILES, listOf("pending", "empty", "queue")),
        Tool("capture_alert", "Alert me when a dashboard request used the camera or microphone",
            "One extra notice per dashboard capture. The monitoring notice and Android's own indicator stay either way.",
            NOTICES, listOf("notification", "sound")),
        Tool("notice_detail", "Show recent uploads in the monitoring notice",
            "Puts the last sent item in the ongoing notice instead of a single quiet word.",
            NOTICES, listOf("notification", "detail")),
        Tool("activity_log", "See what this phone has sent", "The deliveries this phone completed in the current session.",
            NOTICES, listOf("history", "log", "audit")),
        Tool("back_home", "Back to monitoring", "Return to the home screen.", NOTICES, listOf("home", "close"))
    )

    private val byId: Map<String, Tool> = tools.associateBy { it.id }

    /** The label for one request action, in the owner's words. Unknown actions keep the server's name
     * rather than being guessed at, so a newer phone build never mislabels a request. */
    fun plainAction(action: String): String = when (action) {
        "request_photo" -> "Take one photo"
        "request_audio" -> "Record microphone audio"
        "request_screenshot" -> "Take one screenshot"
        "request_location" -> "Share your location"
        "request_scan" -> "Scan nearby Wi-Fi and Bluetooth"
        "request_geofence" -> "Watch an area"
        "request_status" -> "Send phone status"
        "request_settings" -> "Apply dashboard rules"
        "request_live_view" -> "Start a live camera view"
        "request_live_view_stop" -> "Stop the live camera view"
        else -> action.removePrefix("request_").replace('_', ' ')
    }

    fun name(id: String): String = byId[id]?.name ?: id

    /** Ids a search should keep, or null when the box is empty and everything shows. Every word counts,
     * so "stop camera" narrows instead of widening. */
    fun matching(query: String): Set<String>? {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return null
        return tools.filter { tool ->
            val haystack = listOf(tool.name, tool.blurb, tool.category)
                .plus(tool.keywords).joinToString(" ").lowercase()
            words.all(haystack::contains)
        }.map { it.id }.toSet()
    }
}
