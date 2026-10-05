package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

class ReadinessPolicyTest {
    @Test fun savedCredentialsAloneDoNotMeanConnected() {
        assertEquals("Check the server connection", ReadinessPolicy.title(true, true, false, true, true))
    }
    @Test fun stoppedStateIsReportedClearly() {
        assertEquals("Ready — monitoring stopped", ReadinessPolicy.title(true, true, true, false, true))
    }
    @Test fun uploadIsRequiredBeforeShowingMonitoringConnected() {
        assertEquals("Waiting for the first upload", ReadinessPolicy.title(true, true, true, true, false))
        assertEquals("Connected and monitoring", ReadinessPolicy.title(true, true, true, true, true))
    }
    @Test fun enrollmentAndNotificationNeedsComeFirst() {
        assertEquals("Connect this phone", ReadinessPolicy.title(false, true, true, true, true))
        assertEquals("Status notifications needed", ReadinessPolicy.title(true, false, true, true, true))
    }
}
