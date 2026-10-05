package com.example.systemhealth

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Per-installation credentials, never a shared token embedded in the APK. */
internal object EnrollmentIdentity {
    fun deviceId(): String = "phone-${UUID.randomUUID()}"
    fun deviceToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try { Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
        finally { bytes.fill(0) }
    }
}
