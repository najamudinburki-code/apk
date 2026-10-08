package com.fleet.tracking

import android.content.Context
import android.content.SharedPreferences

/**
 * Which boundaries the phone was inside the last time anything knew.
 *
 * [ProximityWatch] is edge-triggered, so it needs this memory to outlive the process. Without it, a
 * service Android killed and restarted finds the phone still standing inside a boundary and reports
 * an arrival all over again — the owner gets a second "arrived at Home" for one journey, and the
 * dashboard counts a trip that never happened.
 */
interface ProximityState {
    fun load(): Set<String>
    fun save(inside: Set<String>)
}

/** Correct within one process, forgotten after it. The default so the watch stays unit-testable:
 * Android's [SharedPreferences] is a stub that throws in a JVM test. */
class TransientProximityState : ProximityState {
    private var inside: Set<String> = emptySet()

    override fun load(): Set<String> = inside

    override fun save(inside: Set<String>) {
        this.inside = inside
    }
}

/**
 * One string set under one key. Nothing here is sensitive enough for the encrypted store: it holds
 * the owner's own names for places ("home"), never the coordinates, and uninstalling the app removes
 * it along with everything else.
 *
 * [SharedPreferences] itself is safe to use from several threads, and each call here makes its own
 * [SharedPreferences.Editor], so no state is shared between them. What is not atomic is the
 * read-modify-write of the boundary set, and that belongs to [ProximityWatch], which holds it under
 * one lock. The store is stateless by design: two instances reading the same file see the same thing.
 */
class ProximityStateStore(context: Context) : ProximityState {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Copied out, because Android returns the live set and lets callers corrupt it by mutating it. */
    override fun load(): Set<String> = prefs.getStringSet(KEY_INSIDE, emptySet()).orEmpty().toSet()

    /** `apply()` rather than `commit()`: the caller is the location listener on the main looper, and a
     * synchronous disk write there is an ANR waiting to happen under storage pressure. The cost is that
     * a process killed within milliseconds of a crossing can lose it — which re-reports one arrival on
     * the next start. It cannot swallow one, so the failure mode leans towards telling the owner more,
     * not less. */
    override fun save(inside: Set<String>) {
        prefs.edit().putStringSet(KEY_INSIDE, inside.toSet()).apply()
    }

    companion object {
        private const val PREFERENCES = "fleet_proximity_state"
        private const val KEY_INSIDE = "inside"
    }
}
