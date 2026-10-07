package com.example.systemhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePolicyTest {
    @Test
    fun `a rule list the phone has never seen leaves the phone in charge`() {
        RemotePolicy.tools.forEach { assertTrue(RemotePolicy.allows(it, null)) }
    }

    @Test
    fun `a tool the dashboard left out stays off`() {
        val allowed = setOf("photo", "audio")
        assertTrue(RemotePolicy.allows("photo", allowed))
        assertFalse(RemotePolicy.allows("screenshot", allowed))
        assertFalse(RemotePolicy.allows("geofence", allowed))
    }

    @Test
    fun `an empty rule list stops every dashboard tool`() {
        RemotePolicy.tools.forEach { assertFalse(RemotePolicy.allows(it, emptySet())) }
    }

    @Test
    fun `a tool this build does not know is governed elsewhere`() {
        // "status" has no rule of its own, and an older server may name something new.
        assertTrue(RemotePolicy.allows("status", emptySet()))
        assertTrue(RemotePolicy.allows("request_photo", setOf("photo")))
    }

    @Test
    fun `a cadence outside the allowed range changes nothing`() {
        assertNull(RemotePolicy.rules(null, null).intervalMinutes)
        assertEquals(2, RemotePolicy.rules(2, null).intervalMinutes)
        assertEquals(1, RemotePolicy.rules(1, null).intervalMinutes)
        assertEquals(1440, RemotePolicy.rules(1440, null).intervalMinutes)
        assertNull(RemotePolicy.rules(0, null).intervalMinutes)
        assertNull(RemotePolicy.rules(-5, null).intervalMinutes)
        assertNull(RemotePolicy.rules(1441, null).intervalMinutes)
    }

    @Test
    fun `an unknown tool name in a rule is dropped instead of guessed`() {
        val parsed = RemotePolicy.rules(null, listOf("photo", "shell", "PHOTO"))
        assertEquals(setOf("photo"), parsed.toolsAllowed)
        assertEquals(emptySet<String>(), RemotePolicy.rules(null, listOf("shell")).toolsAllowed)
        assertNull(RemotePolicy.rules(null, null).toolsAllowed)
    }

    @Test
    fun `every rule tool maps to a request the phone can receive`() {
        RemotePolicy.tools.forEach {
            assertTrue(DeviceCommandRouter.actions.contains("request_$it"))
        }
    }

    @Test
    fun `the default cadence matches what monitoring did before rules existed`() {
        assertEquals(5, RemotePolicy.DEFAULT_HEALTH_INTERVAL_MINUTES)
    }
}
