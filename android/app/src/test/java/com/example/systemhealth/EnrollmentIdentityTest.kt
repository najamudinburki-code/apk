package com.example.systemhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EnrollmentIdentityTest {
    @Test fun differentInstallationsHaveSeparateIdentitiesAndTokens() {
        val ids = (1..100).map { EnrollmentIdentity.deviceId() }.toSet()
        val tokens = (1..100).map { EnrollmentIdentity.deviceToken() }.toSet()
        assertEquals(100, ids.size)
        assertEquals(100, tokens.size)
        assertTrue(ids.all { Regex("phone-[a-f0-9-]{36}").matches(it) })
        assertTrue(tokens.all { Regex("[A-Za-z0-9_-]{43}").matches(it) && Base64.getUrlDecoder().decode(it).size == 32 })
    }
}
