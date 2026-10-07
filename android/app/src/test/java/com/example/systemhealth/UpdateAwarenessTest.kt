package com.example.systemhealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAwarenessTest {
    @Test
    fun `a higher dotted version is an upgrade`() {
        assertTrue(UpdateAwareness.isNewer("0.6.0", "0.5.0"))
        assertTrue(UpdateAwareness.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateAwareness.isNewer("0.5.1", "0.5.0"))
        assertTrue(UpdateAwareness.isNewer("0.5.0.2", "0.5.0"))
    }

    @Test
    fun `the same or an older version is not an upgrade`() {
        assertFalse(UpdateAwareness.isNewer("0.5.0", "0.5.0"))
        assertFalse(UpdateAwareness.isNewer("0.4.9", "0.5.0"))
        assertFalse(UpdateAwareness.isNewer("0.5", "0.5.0"))
    }

    @Test
    fun `build suffixes never look like an upgrade`() {
        assertFalse(UpdateAwareness.isNewer("0.5.0", "0.5.0-dev"))
        assertFalse(UpdateAwareness.isNewer("0.5.0-release", "0.5.0"))
        assertFalse(UpdateAwareness.isNewer("", "0.5.0"))
        assertFalse(UpdateAwareness.isNewer("not-a-version", "0.5.0"))
    }

    @Test
    fun `a genuinely newer version still wins over a suffix`() {
        assertTrue(UpdateAwareness.isNewer("0.6.0-rc1", "0.5.0-dev"))
    }
}
