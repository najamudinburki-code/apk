package com.example.systemhealth

internal object CameraSelection {
    const val FRONT = 0
    const val REAR = 1

    /** `Surface.ROTATION_90`, `ROTATION_180` and `ROTATION_270` as degrees. Named here so the two
     * camera paths share one conversion and neither needs the view hierarchy: those constants are
     * simply 1, 2 and 3. */
    fun rotationDegrees(androidRotation: Int): Int = when (androidRotation) {
        1 -> 90
        2 -> 180
        3 -> 270
        else -> 0
    }

    /** How far a frame must turn to stand upright for whoever is looking at it. A front camera is
     * mounted against the display rotation, a rear camera with it. */
    fun orientation(sensorOrientation: Int, displayRotationDegrees: Int, front: Boolean): Int {
        val turned = if (front) displayRotationDegrees else -displayRotationDegrees
        return (sensorOrientation + turned + 360) % 360
    }

    fun select(cameras: List<Pair<String, Int?>>, facing: Int = FRONT): String? =
        cameras.firstOrNull { it.second == facing }?.first

    fun label(facing: Int) = if (facing == FRONT) "front" else "rear"
}
