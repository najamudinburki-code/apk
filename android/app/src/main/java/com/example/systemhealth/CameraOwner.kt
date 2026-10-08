package com.example.systemhealth

/** The camera serves one job at a time.
 *
 * Android hands the hardware to a single client, so a one-off photo and a live view cannot both hold
 * it. This token turns that into a readable refusal on the phone instead of an `ERROR_CAMERA_IN_USE`
 * from the system a moment later. It does not own the hardware: each user still opens and closes its
 * own camera. */
internal object CameraOwner {
    const val PHOTO = "photo"
    const val LIVE_VIEW = "live view"

    private val lock = Any()
    private var holder: String? = null

    /** True when `who` owns the camera now; false when somebody else still does. */
    fun acquire(who: String): Boolean = synchronized(lock) {
        if (holder != null) false else { holder = who; true }
    }

    /** Frees the camera only for the holder, so a late teardown cannot cut another job off mid-capture. */
    fun release(who: String): Boolean = synchronized(lock) {
        if (holder != who) false else { holder = null; true }
    }

    fun holder(): String? = synchronized(lock) { holder }
}
