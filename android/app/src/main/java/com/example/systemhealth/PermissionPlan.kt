package com.example.systemhealth

/** A setup plan only: granting access never starts capture or sharing. */
internal object PermissionPlan {
    data class Step(val id: String, val title: String, val detail: String, val permissions: List<String>)

    /** The parts of setup that exist only because Android makes them mandatory. */
    enum class StepType { ENROLLMENT, NOTIFICATION, LOCATION, ACCESSIBILITY }

    fun steps(sdk: Int): List<Step> = buildList {
        if (sdk >= 33) add(Step("notifications", "Status notifications",
            "Show active monitoring and requests on this phone.", listOf("android.permission.POST_NOTIFICATIONS")))
        add(Step("camera", "Camera", "Prepare the photo tool; taking or sharing a photo still needs your action.",
            listOf("android.permission.CAMERA")))
        add(Step("microphone", "Microphone", "Prepare the recording tool; recording still needs your action.",
            listOf("android.permission.RECORD_AUDIO")))
        add(Step("location", "Location", "Prepare location tools. Location sharing stays off until you start it.",
            listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION")))
        if (sdk >= 31) add(Step("nearby", "Nearby Bluetooth devices",
            "Prepare nearby scans; a scan runs only when requested on this phone.",
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")))
    }

    fun pending(sdk: Int, selected: Set<String>, granted: Set<String>): List<Step> =
        steps(sdk).filter { it.id in selected && !granted.containsAll(it.permissions) }

    /** A step that is already satisfied is never shown again.
     *
     * LOCATION is a preparation step, not a gate: monitoring itself runs on the special-use type and
     * asks for location only when the owner starts location sharing, so requiring it here would add a
     * prompt Android does not need. ACCESSIBILITY is never skipped and never blocks, matching the
     * owner's rule that an optional reader must not stand between them and Start. */
    fun shouldSkip(step: StepType, sdk: Int, granted: Set<String>, enrolled: Boolean): Boolean = when (step) {
        StepType.ENROLLMENT -> enrolled
        StepType.NOTIFICATION -> sdk < 33 || "android.permission.POST_NOTIFICATIONS" in granted
        StepType.LOCATION -> "android.permission.ACCESS_FINE_LOCATION" in granted
        StepType.ACCESSIBILITY -> false
    }

    /** Only these two actually stop monitoring: a phone that is not enrolled has no server to talk to,
     * and Android refuses a foreground service whose notification channel is switched off. */
    fun blocking(sdk: Int, granted: Set<String>, enrolled: Boolean): List<StepType> =
        listOf(StepType.ENROLLMENT, StepType.NOTIFICATION).filterNot { shouldSkip(it, sdk, granted, enrolled) }
}
