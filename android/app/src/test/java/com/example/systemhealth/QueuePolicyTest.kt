package com.example.systemhealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePolicyTest {
    @Test
    fun `a payload the server rejects permanently is kept aside`() {
        assertTrue(QueuePolicy.retires(400, 1, isResult = false))
        assertTrue(QueuePolicy.retires(413, 1, isResult = false))
    }

    @Test
    fun `a result for a request nobody is waiting for is kept aside`() {
        assertTrue(QueuePolicy.retires(404, 1, isResult = true))
        assertTrue(QueuePolicy.retires(410, 1, isResult = true))
        // The same codes for a fresh event may be a routing or version problem worth retrying.
        assertFalse(QueuePolicy.retires(404, 1, isResult = false))
        assertFalse(QueuePolicy.retires(410, 1, isResult = false))
    }

    @Test
    fun `a rejected credential is healed, not discarded`() {
        assertFalse(QueuePolicy.retires(401, QueuePolicy.MAX_ATTEMPTS, isResult = false))
        assertFalse(QueuePolicy.retires(401, QueuePolicy.MAX_ATTEMPTS, isResult = true))
        // Exhausting retries must not silently discard an upload the owner needs to see.
        assertTrue(QueuePolicy.retires(404, QueuePolicy.MAX_ATTEMPTS, isResult = false))
        assertTrue(QueuePolicy.retires(410, QueuePolicy.MAX_ATTEMPTS, isResult = true))
    }

    @Test
    fun `temporary server trouble is retried until the patience runs out`() {
        assertFalse(QueuePolicy.retires(500, 1, isResult = false))
        assertFalse(QueuePolicy.retires(503, QueuePolicy.MAX_ATTEMPTS - 1, isResult = false))
        assertTrue(QueuePolicy.retires(500, QueuePolicy.MAX_ATTEMPTS, isResult = false))
    }

    @Test
    fun `only samples the next poll replaces may be shed`() {
        assertTrue(QueuePolicy.isSheddable("system_health"))
        assertTrue(QueuePolicy.isSheddable("device_status"))
        for (type in listOf("app_capture", "location", "photo", "audio", "screenshot", "notification")) {
            assertFalse("a $type cannot be remade by the next poll", QueuePolicy.isSheddable(type))
        }
    }

    @Test
    fun `a full queue still leaves room for one batch of deliveries`() {
        assertTrue(QueuePolicy.FLUSH_BATCH in 1..QueuePolicy.OUTBOX_LIMIT)
    }
}
