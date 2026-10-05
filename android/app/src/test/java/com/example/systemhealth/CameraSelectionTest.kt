package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

class CameraSelectionTest {
    @Test fun frontIsDefaultEvenWhenRearIsListedFirst() {
        assertEquals("selfie", CameraSelection.select(listOf("main" to 1, "selfie" to 0)))
    }
    @Test fun explicitRearChoiceRemainsAvailable() {
        assertEquals("main", CameraSelection.select(listOf("selfie" to 0, "main" to 1), CameraSelection.REAR))
    }
    @Test fun missingFrontNeverSilentlyCapturesRear() {
        assertNull(CameraSelection.select(listOf("main" to 1)))
    }
}
