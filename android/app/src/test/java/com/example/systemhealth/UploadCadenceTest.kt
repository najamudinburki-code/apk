package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

class UploadCadenceTest {
    @Test fun connectedLoopPollsFarFasterThanTheOldThirtySeconds() {
        assertEquals(10_000L, FeatureBridge.cadenceMs(0, false))
        assertEquals(5_000L, FeatureBridge.cadenceMs(0, true))
    }

    @Test fun failuresBackOffWithoutEverGoingSilentForLong() {
        assertEquals(20_000L, FeatureBridge.cadenceMs(1, false))
        assertEquals(40_000L, FeatureBridge.cadenceMs(2, false))
        assertEquals(300_000L, FeatureBridge.cadenceMs(9, false))
    }
}
