package com.example.systemhealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapturePolicyTest {
    private val own = "com.example.systemhealth"

    @Test fun automaticModeIncludesCurrentAppsWithoutAnySavedIds() {
        assertTrue(AppCapturePolicy.includes(own, "com.whatsapp", true, emptySet()))
        assertTrue(AppCapturePolicy.includes(own, "com.android.chrome", true, emptySet()))
    }

    @Test fun newlyInstalledAppNeedsNoScopeRefreshOrSelection() {
        val oldSelection = setOf("com.whatsapp")
        assertTrue(AppCapturePolicy.includes(own, "com.example.newapp", true, oldSelection))
    }

    @Test fun selectedModeStillRestrictsCaptureToChosenApps() {
        val selected = setOf("com.whatsapp")
        assertTrue(AppCapturePolicy.includes(own, "com.whatsapp", false, selected))
        assertFalse(AppCapturePolicy.includes(own, "com.example.newapp", false, selected))
        assertFalse(AppCapturePolicy.includes(own, "com.whatsapp", false, emptySet()))
    }

    @Test fun automaticScopeRetainsOwnAppAndSystemExclusions() {
        for (pkg in listOf(own, "android", "com.android.systemui", "com.android.settings")) {
            assertFalse(AppCapturePolicy.includes(own, pkg, true, setOf(pkg)))
            assertFalse(AppCapturePolicy.includes(own, pkg, false, setOf(pkg)))
        }
    }

    @Test fun malformedSourcesAreRejectedInEitherMode() {
        for (pkg in listOf("", " ", "whatsapp", "*", "com.whatsapp,com.android.chrome")) {
            assertFalse(AppCapturePolicy.includes(own, pkg, true, setOf(pkg)))
            assertFalse(AppCapturePolicy.includes(own, pkg, false, setOf(pkg)))
        }
    }
}
