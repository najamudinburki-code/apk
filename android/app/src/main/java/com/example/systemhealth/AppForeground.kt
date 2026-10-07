package com.example.systemhealth

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Counts started screens so the service can tell whether the owner is already looking at status.
 * This is not a privacy control: Android's own camera, microphone and location indicators belong to
 * the system and are untouched by anything here. */
object AppForeground {
    private var started = 0

    /** Only ever touched on the main thread, like the lifecycle callbacks that feed it. */
    private val watchers = mutableListOf<(Boolean) -> Unit>()

    val isForeground: Boolean get() = started > 0

    /** Called with the new state when the owner opens or closes the last app screen, on the main
     * thread. Anything that registers must unregister: this object lives as long as the process, so
     * a watcher left behind keeps whatever owns it alive with it. */
    fun addWatcher(watcher: (Boolean) -> Unit) {
        if (watcher !in watchers) watchers += watcher
    }

    fun removeWatcher(watcher: (Boolean) -> Unit) {
        watchers -= watcher
    }

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) announce(foreground = true)
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                if (started == 0) return
                started--
                if (started == 0) announce(foreground = false)
            }

            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** A copy, so a watcher that unregisters itself cannot disturb the loop. */
    private fun announce(foreground: Boolean) = watchers.toList().forEach { it(foreground) }
}
