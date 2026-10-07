package com.fleet.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cadence decision is pure arithmetic, so the tier the owner will actually get is checked here.
 * Whether Android honors that cadence under Doze is a handset question, answered in
 * docs/SILENT_VERIFICATION.md. */
class IntervalPolicyTest {
    private val fast = 5_000L
    private val slow = 60_000L

    private fun choose(charging: Boolean, foreground: Boolean) =
        IntervalPolicy.choose(charging, foreground, fast, slow)

    @Test fun chargingIsAlwaysQuick() {
        assertEquals(fast, choose(charging = true, foreground = true))
        assertEquals(fast, choose(charging = true, foreground = false))
    }

    @Test fun anOpenAppIsAlwaysQuick() {
        assertEquals(fast, choose(charging = false, foreground = true))
    }

    @Test fun batteryInBackgroundIsTheOnlySlowCase() {
        assertEquals(slow, choose(charging = false, foreground = false))
    }

    @Test fun defaultsMatchTheOwnersRequest() {
        val config = EngineConfig()
        assertEquals(5_000L, config.intervalMillis)
        assertEquals(60_000L, config.backgroundIntervalMillis)
    }
}
