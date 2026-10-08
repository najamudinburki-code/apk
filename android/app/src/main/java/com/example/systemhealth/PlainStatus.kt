package com.example.systemhealth

/**
 * Plain words for states and failures, so a screen never shows an owner a boolean, a raw permission
 * name or a stack-flavoured message. Nothing here changes a state: it only says the same thing in
 * language a person reading the phone can act on.
 */
internal object PlainStatus {

    /** The request states the ledger uses, in the words the phone's owner reads. */
    fun requestState(state: String): String = when (state) {
        "pending" -> "Waiting for the phone"
        "delivered" -> "Reached the phone"
        "running" -> "Working on it"
        "completed" -> "Done — the phone finished it"
        "reviewed" -> "You read it on the phone; nothing was shared"
        "declined" -> "Not done — turned off by a rule, or cancelled on the phone"
        "failed" -> "Did not finish"
        "expired" -> "Timed out before the phone could answer"
        else -> state
    }

    /** Android's permission names, as the thing the owner has to find in Settings. */
    fun permissionLabel(permission: String): String = when (permission) {
        "android.permission.POST_NOTIFICATIONS" -> "notifications for this app"
        "android.permission.CAMERA" -> "the camera"
        "android.permission.RECORD_AUDIO" -> "the microphone"
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION" -> "location"
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT" -> "nearby devices"
        else -> permission.substringAfterLast('.')
    }

    /** What to do after Android says no. A refusal is a normal answer, so it must read as one and
     * still say which tool is now unavailable and where to change the decision. */
    fun permissionDenied(permission: String): String =
        "Android did not allow ${permissionLabel(permission)}, so nothing was shared. " +
            "That tool stays off until you allow it: open Android Settings → Apps → " +
            "System Health → Permissions, choose ${permissionLabel(permission)}, then try again."

    /** Where a declined setup step leaves each tool, said before the owner is asked again. */
    fun whyPermissionIsNeeded(step: String): String = when (step) {
        "notifications" -> "Android will not let monitoring run without a notice you can see, " +
            "so this keeps the active-sharing message on screen. It is also how you reach Stop."
        "camera" -> "Needed only for the photo tool. Every photo still asks you first, and Android " +
            "lights its own camera indicator while it is taken."
        "microphone" -> "Needed only for recordings. Nothing is recorded until you start it on the " +
            "tools screen or approve a dashboard request, and the microphone indicator stays lit."
        "location" -> "Needed only for the location tools. Sharing stays off until you start it, " +
            "and an ongoing Android notice shows while it runs."
        "nearby" -> "Needed only for a nearby Wi-Fi and Bluetooth scan, and only when a scan runs."
        else -> "This app asks for it so one specific tool can work; nothing else is enabled by it."
    }

    fun monitoring(running: Boolean): String =
        if (running) "On — sharing with your dashboard" else "Off — nothing is being sent"

    fun microphone(recording: Boolean): String =
        if (recording) "Recording right now" else "Not recording"

    fun location(tracking: Boolean): String =
        if (tracking) "Sharing this phone's location" else "Not sharing location"

    fun liveView(streaming: Boolean, allowed: Boolean): String = when {
        streaming -> "Streaming to your dashboard now"
        allowed -> "Allowed, not streaming"
        else -> "Not allowed on this phone"
    }

    fun uploads(pending: Int): String = when (pending) {
        0 -> "Nothing waiting to upload"
        1 -> "1 item waiting to upload"
        else -> "$pending items waiting to upload"
    }

    fun unsent(count: Int): String = when (count) {
        0 -> "No unsent items kept aside"
        1 -> "1 item could not be sent and is kept aside"
        else -> "$count items could not be sent and are kept aside"
    }

    fun requests(count: Int): String = when (count) {
        0 -> "No dashboard requests waiting"
        1 -> "1 dashboard request waiting"
        else -> "$count dashboard requests waiting"
    }

    /** "1 item" / "4 items". An on-screen "(s)" reads like a form letter on the one screen whose job
     * is to be trusted. */
    fun count(number: Int, noun: String): String =
        if (number == 1) "1 $noun" else "$number ${noun}s"

    fun rules(summary: String?): String =
        summary ?: "None — this phone's own choices decide what runs"

    fun schedule(summary: String?): String = summary ?: "None set"

    /** The distinction the owner asked to keep: saved on the phone is not the same as received by the
     * server, so a local file must never be described as uploaded. */
    fun savedLocally(kind: String): String = "Saved on this phone as a $kind. It uploads once monitoring is on."
}
