package com.example.systemhealth

import org.junit.Assert.*
import org.junit.Test

class PermissionPlanTest {
    @Test fun permissionPromptsFollowAndroidVersion() {
        assertFalse(PermissionPlan.steps(26).any { it.id == "notifications" || it.id == "nearby" })
        assertTrue(PermissionPlan.steps(31).any { it.id == "nearby" })
        assertFalse(PermissionPlan.steps(31).any { it.id == "notifications" })
        assertTrue(PermissionPlan.steps(33).any { it.id == "notifications" })
    }
    @Test fun onlyChosenToolsAreRequested() {
        assertEquals(listOf("camera"), PermissionPlan.pending(35, setOf("camera"), emptySet()).map { it.id })
        assertTrue(PermissionPlan.pending(35, emptySet(), emptySet()).isEmpty())
    }
    @Test fun alreadyGrantedPermissionsAreSkipped() {
        val camera = PermissionPlan.steps(35).first { it.id == "camera" }
        assertTrue(PermissionPlan.pending(35, setOf("camera"), camera.permissions.toSet()).isEmpty())
    }
    @Test fun preciseUpgradeStillRequestsForegroundPairTogether() {
        val pending = PermissionPlan.pending(35, setOf("location"), setOf("android.permission.ACCESS_COARSE_LOCATION"))
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"), pending.single().permissions)
    }
    @Test fun specialAccessIsNeverRequestedInRuntimeBatch() {
        val permissions = PermissionPlan.steps(35).flatMap { it.permissions }
        assertFalse(permissions.any { "BACKGROUND" in it || "ACCESSIBILITY" in it || "BIND_NOTIFICATION" in it })
    }
    @Test fun finishedStepsAreSkipped() {
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val notifications = "android.permission.POST_NOTIFICATIONS"
        assertTrue(PermissionPlan.shouldSkip(PermissionPlan.StepType.ENROLLMENT, 35, emptySet(), enrolled = true))
        assertFalse(PermissionPlan.shouldSkip(PermissionPlan.StepType.ENROLLMENT, 35, emptySet(), enrolled = false))
        assertTrue(PermissionPlan.shouldSkip(PermissionPlan.StepType.NOTIFICATION, 35, setOf(notifications), true))
        assertFalse(PermissionPlan.shouldSkip(PermissionPlan.StepType.NOTIFICATION, 35, emptySet(), true))
        // Android 12 and older have no runtime notification permission to ask for at all.
        assertTrue(PermissionPlan.shouldSkip(PermissionPlan.StepType.NOTIFICATION, 30, emptySet(), true))
    }
    @Test fun approximateLocationAloneIsNotEnoughToSkip() {
        val coarse = "android.permission.ACCESS_COARSE_LOCATION"
        assertFalse(PermissionPlan.shouldSkip(PermissionPlan.StepType.LOCATION, 35, setOf(coarse), true))
        assertTrue(PermissionPlan.shouldSkip(
            PermissionPlan.StepType.LOCATION, 35, setOf(coarse, "android.permission.ACCESS_FINE_LOCATION"), true))
    }
    @Test fun accessibilityIsAlwaysOfferedAndNeverBlocks() {
        assertFalse(PermissionPlan.shouldSkip(PermissionPlan.StepType.ACCESSIBILITY, 35, emptySet(), true))
        assertTrue(PermissionPlan.blocking(35, emptySet(), enrolled = true).none { it == PermissionPlan.StepType.ACCESSIBILITY })
    }
    @Test fun onlyEnrolmentAndNotificationsBlockMonitoring() {
        // Location is a tool the owner starts later, so it must not stand in the way of a fast setup.
        val empty = PermissionPlan.blocking(35, emptySet(), enrolled = true)
        assertEquals(listOf(PermissionPlan.StepType.NOTIFICATION), empty)
        assertTrue(PermissionPlan.blocking(35, emptySet(), enrolled = false).contains(PermissionPlan.StepType.ENROLLMENT))
        assertTrue(PermissionPlan.blocking(35, setOf("android.permission.POST_NOTIFICATIONS"), enrolled = true).isEmpty())
        assertTrue(PermissionPlan.blocking(30, emptySet(), enrolled = true).isEmpty())
    }
}
