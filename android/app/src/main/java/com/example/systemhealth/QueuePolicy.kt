package com.example.systemhealth

/** The queue's keep-or-drop decisions, kept free of Android so they can be tested directly. */
internal object QueuePolicy {
    const val OUTBOX_LIMIT = 200
    const val FLUSH_BATCH = 20
    const val MAX_ATTEMPTS = 40

    /** Periodic samples are superseded by the next one; captures the phone cannot remake are not. */
    private val sheddableTypes = setOf("system_health", "device_status")
    fun isSheddable(type: String): Boolean = type in sheddableTypes

    /** True when retrying cannot help: the server rejects this payload outright, the dashboard
     *  stopped waiting for this result, or the item has used up its patience. A 401 is answered
     *  by healing the enrollment instead, so it never retires however often it repeats. */
    fun retires(responseCode: Int, attempts: Int, isResult: Boolean): Boolean =
        responseCode != 401 &&
            ((isResult && responseCode in listOf(404, 410)) ||
                responseCode in listOf(400, 413) ||
                attempts >= MAX_ATTEMPTS)
}
