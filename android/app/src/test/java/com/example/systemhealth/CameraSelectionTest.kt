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

    @Test fun androidRotationCodesBecomeDegrees() {
        // 0..3 are Surface.ROTATION_0/90/180/270 in that order; anything else is held upright.
        assertEquals(0, CameraSelection.rotationDegrees(0))
        assertEquals(90, CameraSelection.rotationDegrees(1))
        assertEquals(180, CameraSelection.rotationDegrees(2))
        assertEquals(270, CameraSelection.rotationDegrees(3))
        assertEquals(0, CameraSelection.rotationDegrees(7))
    }

    @Test fun aFrontFrameTurnsWithTheDisplayAndARearFrameAgainstIt() {
        val sensor = 90
        assertEquals(180, CameraSelection.orientation(sensor, 90, front = true))
        assertEquals(0, CameraSelection.orientation(sensor, 90, front = false))
    }

    @Test fun anUprightPhoneUsesTheSensorOrientationAlone() {
        assertEquals(270, CameraSelection.orientation(270, 0, front = true))
        assertEquals(270, CameraSelection.orientation(270, 0, front = false))
    }

    @Test fun turningNeverLeavesTheRangeAndroidExpectS() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                for (front in listOf(true, false)) {
                    val degrees = CameraSelection.orientation(sensor, display, front)
                    assertTrue("a $sensor/$display/front=$front turn must be a quarter turn",
                        degrees in setOf(0, 90, 180, 270))
                }
            }
        }
    }

    @Test fun a_label_reads_as_a_lens_not_a_number() {
        assertEquals("front", CameraSelection.label(CameraSelection.FRONT))
        assertEquals("rear", CameraSelection.label(CameraSelection.REAR))
    }
}
