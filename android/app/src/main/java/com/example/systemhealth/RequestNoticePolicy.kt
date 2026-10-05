package com.example.systemhealth

/** Server ordering or metadata changes do not create another request alert. */
internal object RequestNoticePolicy {
    fun shouldUpdate(previous: Collection<String>, current: Collection<String>): Boolean =
        previous.toSet() != current.toSet()
}
