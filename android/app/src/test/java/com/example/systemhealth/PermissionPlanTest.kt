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
}
