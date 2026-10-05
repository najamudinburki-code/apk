package com.example.systemhealth

/** A setup plan only: granting access never starts capture or sharing. */
internal object PermissionPlan {
    data class Step(val id: String, val title: String, val detail: String, val permissions: List<String>)

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
}
