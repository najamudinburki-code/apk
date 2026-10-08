package com.example.systemhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosticsTest {
    private val minute = 60_000L

    @Test fun theDeliveryWindowFollowsThePhonesOwnSampleCadence() {
        assertEquals(15 * minute, ConnectionDiagnostics.deliveryWindowMs(1))
        assertEquals(15 * minute, ConnectionDiagnostics.deliveryWindowMs(5))
        assertEquals(15 * minute, ConnectionDiagnostics.deliveryWindowMs(0))
        assertEquals(30 * minute, ConnectionDiagnostics.deliveryWindowMs(10))
        assertEquals(12 * 60 * minute, ConnectionDiagnostics.deliveryWindowMs(240))
    }

    @Test fun aDeliveryInsideTheWindowProvesTheServerAndOneOutsideDoesNot() {
        val now = 1_700_000_000_000L
        val window = ConnectionDiagnostics.deliveryWindowMs(5)
        assertTrue(ConnectionDiagnostics.deliveryProvesConnection(now - minute, now, window))
        assertTrue(ConnectionDiagnostics.deliveryProvesConnection(now - window, now, window))
        assertFalse(ConnectionDiagnostics.deliveryProvesConnection(now - window - 1, now, window))
        assertFalse("never delivered", ConnectionDiagnostics.deliveryProvesConnection(0L, now, window))
        assertFalse("clock moved back",
            ConnectionDiagnostics.deliveryProvesConnection(now + minute, now, window))
    }
}
