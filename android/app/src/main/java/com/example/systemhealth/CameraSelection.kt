package com.example.systemhealth

internal object CameraSelection {
    const val FRONT = 0
    const val REAR = 1
    fun select(cameras: List<Pair<String, Int?>>, facing: Int = FRONT): String? =
        cameras.firstOrNull { it.second == facing }?.first
    fun label(facing: Int) = if (facing == FRONT) "front" else "rear"
}
