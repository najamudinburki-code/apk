package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

/**
 * These are the sentences an owner reads when something on the phone went wrong or went quiet. A state
 * must be named exactly as true as the ledger says it — and a file that only reached this phone must
 * never be described as one the server received.
 */
class PlainStatusTest {

    @Test fun everyLedgerStateHasWordsAndNothingInventsASuccess() {
        assertEquals("Waiting for the phone", PlainStatus.requestState("pending"))
        assertEquals("Reached the phone", PlainStatus.requestState("delivered"))
        assertEquals("Working on it", PlainStatus.requestState("running"))
        assertEquals("Done — the phone finished it", PlainStatus.requestState("completed"))
        assertEquals("You read it on the phone; nothing was shared", PlainStatus.requestState("reviewed"))
        assertEquals("Not done — turned off by a rule, or cancelled on the phone", PlainStatus.requestState("declined"))
        assertEquals("Did not finish", PlainStatus.requestState("failed"))
        assertEquals("Timed out before the phone could answer", PlainStatus.requestState("expired"))
        // A state this build does not keep is passed through, not dressed up.
        assertEquals("queued", PlainStatus.requestState("queued"))
    }

    @Test fun androidPermissionNamesBecomeTheThingToLookForInSettings() {
        assertEquals("the camera", PlainStatus.permissionLabel("android.permission.CAMERA"))
        assertEquals("the microphone", PlainStatus.permissionLabel("android.permission.RECORD_AUDIO"))
        assertEquals("notifications for this app", PlainStatus.permissionLabel("android.permission.POST_NOTIFICATIONS"))
        assertEquals("location", PlainStatus.permissionLabel("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals("nearby devices", PlainStatus.permissionLabel("android.permission.BLUETOOTH_SCAN"))
        assertEquals("READ_CALENDAR", PlainStatus.permissionLabel("android.permission.READ_CALENDAR"))
    }

    @Test fun aRefusalIsANormalAnswerWithTheWayToChangeIt() {
        val words = PlainStatus.permissionDenied("android.permission.CAMERA")
        assertTrue(words, words.contains("nothing was shared"))
        assertTrue(words, words.contains("Settings → Apps"))
        assertTrue(words, words.contains("the camera"))
        // A refusal must not read as though something was captured.
        assertFalse(words.contains("uploaded"))
    }

    @Test fun everySetupStepSaysWhyBeforeAndroidAsks() {
        PermissionPlan.steps(35).forEach { step ->
            val reason = PlainStatus.whyPermissionIsNeeded(step.id)
            assertTrue("${step.id} has no reason", reason.length > 40)
            assertNotEquals("nearby fallback text", reason,
                PlainStatus.whyPermissionIsNeeded("a step this build does not know"))
        }
        assertTrue(PlainStatus.whyPermissionIsNeeded("notifications").contains("Stop"))
    }

    @Test fun anUnknownStepStillGetsAnHonestSentence() {
        assertTrue(PlainStatus.whyPermissionIsNeeded("telephony").startsWith("This app asks for it"))
    }

    @Test fun runningAndStoppedAreNeverConfused() {
        assertTrue(PlainStatus.monitoring(true).startsWith("On"))
        assertEquals("Off — nothing is being sent", PlainStatus.monitoring(false))
        assertEquals("Recording right now", PlainStatus.microphone(true))
        assertEquals("Not recording", PlainStatus.microphone(false))
        assertEquals("Sharing this phone's location", PlainStatus.location(true))
        assertEquals("Not sharing location", PlainStatus.location(false))
    }

    @Test fun aLiveCameraViewHasThreeStatesAndNotAllowedIsSaidPlainly() {
        assertEquals("Streaming to your dashboard now", PlainStatus.liveView(streaming = true, allowed = false))
        assertEquals("Allowed, not streaming", PlainStatus.liveView(streaming = false, allowed = true))
        assertEquals("Not allowed on this phone", PlainStatus.liveView(streaming = false, allowed = false))
    }

    @Test fun countsReadAsPeopleCountThem() {
        assertEquals("Nothing waiting to upload", PlainStatus.uploads(0))
        assertEquals("1 item waiting to upload", PlainStatus.uploads(1))
        assertEquals("7 items waiting to upload", PlainStatus.uploads(7))
        assertEquals("No unsent items kept aside", PlainStatus.unsent(0))
        assertEquals("1 item could not be sent and is kept aside", PlainStatus.unsent(1))
        assertEquals("3 items could not be sent and are kept aside", PlainStatus.unsent(3))
        assertEquals("No dashboard requests waiting", PlainStatus.requests(0))
        assertEquals("2 dashboard requests waiting", PlainStatus.requests(2))
        assertEquals("1 frame", PlainStatus.count(1, "frame"))
        assertEquals("0 frames", PlainStatus.count(0, "frame"))
        assertEquals("12 frames", PlainStatus.count(12, "frame"))
    }

    @Test fun noRulesAndNoRepeatingReportsSaySoInsteadOfGoingBlank() {
        assertEquals("None — this phone's own choices decide what runs", PlainStatus.rules(null))
        assertEquals("the dashboard may run photo", PlainStatus.rules("the dashboard may run photo"))
        assertEquals("None set", PlainStatus.schedule(null))
        assertEquals("every 30 min: phone status", PlainStatus.schedule("every 30 min: phone status"))
    }

    @Test fun aLocalFileIsNeverReportedAsUploaded() {
        val words = PlainStatus.savedLocally("photo")
        assertTrue(words.startsWith("Saved on this phone as a photo"))
        assertFalse(words, words.contains("uploaded"))
        assertTrue(words, words.contains("uploads once monitoring is on"))
    }
}
