package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

class DeviceCommandRouterTest {
    // Mirrors ACTIONS in backend/features.cjs; a new server action must be routed here too.
    private val serverActions = setOf(
        "request_status", "request_screenshot", "request_photo", "request_audio", "request_location",
        "request_scan", "request_audit", "request_files", "request_backup"
    )

    @Test fun everyServerActionHasARoute() {
        assertEquals(serverActions, DeviceCommandRouter.actions)
        serverActions.forEach { assertNotEquals(DeviceCommandRouter.Mode.UNKNOWN, DeviceCommandRouter.route(it)) }
    }

    @Test fun reportOnlyToolsRunWithoutAWindow() {
        listOf("request_status", "request_audit", "request_backup").forEach {
            assertEquals(DeviceCommandRouter.Mode.SILENT, DeviceCommandRouter.route(it))
        }
    }

    @Test fun captureToolsNeedAVisibleScreen() {
        listOf("request_photo", "request_screenshot", "request_audio", "request_location", "request_scan", "request_files")
            .forEach { assertEquals(DeviceCommandRouter.Mode.USER, DeviceCommandRouter.route(it)) }
    }

    @Test fun unknownActionsFailInsteadOfOpeningTheApp() {
        assertEquals(DeviceCommandRouter.Mode.UNKNOWN, DeviceCommandRouter.route("request_shell"))
        assertEquals(DeviceCommandRouter.Mode.UNKNOWN, DeviceCommandRouter.route(""))
    }
}
