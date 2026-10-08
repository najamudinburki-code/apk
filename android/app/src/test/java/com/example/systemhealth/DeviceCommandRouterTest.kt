package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

class DeviceCommandRouterTest {
    // Mirrors ACTIONS in backend/features.cjs; a new server action must be routed here too.
    private val serverActions = setOf(
        "request_status", "request_screenshot", "request_photo", "request_audio", "request_location",
        "request_scan", "request_geofence", "request_settings",
        "request_live_view", "request_live_view_stop"
    )

    @Test fun everyServerActionHasARoute() {
        assertEquals(serverActions, DeviceCommandRouter.actions)
        serverActions.forEach { assertNotEquals(DeviceCommandRouter.Mode.UNKNOWN, DeviceCommandRouter.route(it)) }
    }

    @Test fun reportOnlyToolsRunWithoutAWindow() {
        listOf("request_status", "request_scan").forEach {
            assertEquals(DeviceCommandRouter.Mode.SILENT, DeviceCommandRouter.route(it))
        }
    }

    @Test fun dashboardRulesApplyWithoutAWindowAndWithoutOwnerApproval() {
        // Rules can only narrow behaviour, so they never need a visible prompt.
        assertEquals(DeviceCommandRouter.Mode.SILENT, DeviceCommandRouter.route("request_settings"))
    }

    @Test fun photoAndMicrophoneRunHeadlessWithoutOpeningTheApp() {
        listOf("request_photo", "request_audio").forEach {
            assertEquals(DeviceCommandRouter.Mode.CAPTURE, DeviceCommandRouter.route(it))
        }
    }

    @Test fun aLiveViewUsesTheCameraWithoutOpeningTheApp() {
        assertEquals(DeviceCommandRouter.Mode.CAPTURE, DeviceCommandRouter.route("request_live_view"))
    }

    @Test fun stoppingACameraIsNeverQueuedBehindTheCamera() {
        // The command that frees the lens must answer even while a capture is using it.
        assertEquals(DeviceCommandRouter.Mode.SILENT, DeviceCommandRouter.route("request_live_view_stop"))
    }

    @Test fun toolsThatNeedAndroidConsentStillNeedAVisibleScreen() {
        listOf("request_screenshot", "request_location", "request_geofence")
            .forEach { assertEquals(DeviceCommandRouter.Mode.USER, DeviceCommandRouter.route(it)) }
    }

    @Test fun unknownActionsFailInsteadOfOpeningTheApp() {
        assertEquals(DeviceCommandRouter.Mode.UNKNOWN, DeviceCommandRouter.route("request_shell"))
        assertEquals(DeviceCommandRouter.Mode.UNKNOWN, DeviceCommandRouter.route(""))
    }
}
