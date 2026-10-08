package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

/**
 * The home screen's decisions, checked without Android. These are the answers a non-technical owner
 * reads first, so a wrong one here is a wrong screen on the phone: the action offered must match the
 * real blocker, a calm state must not be dressed up as a problem, and nothing may claim the server
 * received something it did not acknowledge.
 */
class HomeOverviewTest {

    private fun signals(
        enrolled: Boolean = true,
        notificationsAllowed: Boolean = true,
        serverVerified: Boolean = true,
        monitoring: Boolean = true,
        everUploaded: Boolean = true,
        lastUploadLabel: String = "10:04",
        serverMessage: String = "Server accepted this phone",
        pendingUploads: Int = 0,
        unsentItems: Int = 0,
        pendingRequests: Int = 0,
        appTextSharing: Boolean = false,
        liveStreaming: Boolean = false,
        rulesSummary: String? = null,
        scheduledReports: String? = null,
        updateAvailable: String? = null,
        updateInstalled: String = "1.4.0",
        updateUrl: String = "",
        serviceStatus: String = ""
    ) = HomeSignals(enrolled, notificationsAllowed, serverVerified, monitoring, everUploaded,
        lastUploadLabel, serverMessage, pendingUploads, unsentItems, pendingRequests, appTextSharing,
        liveStreaming, rulesSummary, scheduledReports, updateAvailable, updateInstalled, updateUrl,
        serviceStatus)

    private fun HomePlan.fact(label: String) = facts.first { it.label == label }

    @Test fun unenrolledPhoneOffersConnectionFirst() {
        val plan = HomeOverview.plan(signals(enrolled = false, notificationsAllowed = false))
        assertEquals("Connect this phone", plan.headline)
        assertEquals(HomeAction.CONNECT, plan.primary)
        assertTrue(plan.subhead.contains("no address or code", ignoreCase = true))
        assertEquals("No — not set up on this phone yet", plan.fact("Connected to your dashboard").value)
    }

    @Test fun missingNotificationsAreTheNextBlocker() {
        val plan = HomeOverview.plan(signals(notificationsAllowed = false))
        assertEquals(HomeAction.NOTIFICATIONS, plan.primary)
        assertTrue(plan.subhead.contains("status notice", ignoreCase = true))
    }

    @Test fun unverifiedServerIsCheckedRatherThanStarted() {
        val plan = HomeOverview.plan(signals(serverVerified = false, monitoring = false))
        assertEquals(HomeAction.CHECK, plan.primary)
        assertTrue(plan.fact("Connected to your dashboard").needsAttention)
        assertEquals("Not confirmed. Last report said: Server accepted this phone",
            plan.fact("Connected to your dashboard").value)
    }

    @Test fun aRunningSessionWithNoActionLeavesThePrimaryButtonAway() {
        val plan = HomeOverview.plan(signals())
        assertNull(plan.primary)
        assertEquals("Connected and monitoring", plan.headline)
        assertTrue(plan.alerts.isEmpty())
        assertTrue(plan.subhead.contains("10:04"))
    }

    @Test fun monitoringWithoutAServerAcknowledgementIsNotReportedAsDelivered() {
        val plan = HomeOverview.plan(signals(everUploaded = false))
        assertEquals("Waiting for the first upload", plan.headline)
        assertTrue(plan.subhead.contains("waiting for the server", ignoreCase = true))
        assertTrue(plan.fact("Last delivery the server accepted").needsAttention)
    }

    @Test fun stoppedMonitoringIsDescribedAsSendingNothing() {
        val plan = HomeOverview.plan(signals(monitoring = false))
        assertEquals("Off — nothing is being sent", plan.fact("Monitoring").value)
        assertEquals(HomeAction.START, plan.primary)
        assertFalse(plan.monitorStopVisible)
    }

    @Test fun everyStopControlAppearsOnlyWithSomethingToStop() {
        val running = HomeOverview.plan(signals(monitoring = true, appTextSharing = true))
        assertTrue(running.monitorStopVisible)
        assertTrue(running.sharingStopVisible)
        assertEquals("Being shared with your dashboard", running.fact("Screen text and notifications").value)
        assertTrue(running.fact("Screen text and notifications").needsAttention)
        val quiet = HomeOverview.plan(signals(appTextSharing = false))
        assertFalse(quiet.sharingStopVisible)
        assertEquals("Not being shared", quiet.fact("Screen text and notifications").value)
    }

    @Test fun calmServiceLinesStayOutOfTheAttentionCard() {
        listOf("Monitoring is stopped.", "Starting monitoring…",
            "Monitoring active. Check the dashboard to confirm server delivery.",
            "Monitoring active. The status notice is hidden until the next event.", "").forEach {
            assertTrue("Unexpected alert for \"$it\"", HomeOverview.plan(signals(serviceStatus = it)).alerts.isEmpty())
        }
    }

    @Test fun androidRefusingToKeepMonitoringRunningComesToTheTop() {
        val plan = HomeOverview.plan(signals(serviceStatus = "Android stopped monitoring. Open the app to start it again."))
        assertEquals(1, plan.alerts.size)
        assertTrue(plan.alerts.first().startsWith("Android stopped monitoring"))
    }

    @Test fun aLiveStreamAndUnsentItemsAreBothAnnouncedWithTheWayOut() {
        val plan = HomeOverview.plan(signals(liveStreaming = true, unsentItems = 2, pendingUploads = 3))
        assertTrue(plan.alerts.any {
            it.contains("streaming") && it.contains("Stop the live camera view now")
        })
        assertTrue(plan.alerts.any { it.contains("2 items could not be sent") })
        // Nothing is missing while monitoring runs, so a full queue is a fact rather than an alert.
        assertFalse(plan.alerts.any { it.contains("cannot be sent while monitoring is off") })
        assertEquals("3 items still on this phone", plan.fact("Waiting to upload").value)
    }

    @Test fun waitingUploadsWhileStoppedTellTheOwnerToStart() {
        val plan = HomeOverview.plan(signals(monitoring = false, pendingUploads = 1))
        assertTrue(plan.alerts.any { it.contains("1 item cannot be sent") &&
            it.contains("Start monitoring to send it") })
        val many = HomeOverview.plan(signals(monitoring = false, pendingUploads = 4))
        assertTrue(many.alerts.any { it.contains("4 items cannot be sent") &&
            it.contains("Start monitoring to send them") })
    }

    @Test fun anAvailableUpdateNamesTheVersionAndWhereToGetIt() {
        val withUrl = HomeOverview.plan(signals(updateAvailable = "1.5.0", updateInstalled = "1.4.0",
            updateUrl = "https://example.test/app.apk"))
        assertTrue(withUrl.alerts.any { it.contains("1.5.0") && it.contains("1.4.0") })
        assertTrue(withUrl.alerts.any { it.contains("https://example.test/app.apk") })
        val noUrl = HomeOverview.plan(signals(updateAvailable = "1.5.0"))
        assertTrue(noUrl.alerts.any { it.contains("dashboard owner") })
    }

    @Test fun onlyTheRulesAndReportsThatExistAreListed() {
        val bare = HomeOverview.plan(signals())
        assertTrue(bare.facts.none { it.label == "Dashboard rules on this phone" })
        assertTrue(bare.facts.none { it.label == "Reports you set to repeat" })
        assertTrue(bare.facts.none { it.label == "Dashboard requests waiting" })
        val governed = HomeOverview.plan(signals(rulesSummary = "the dashboard may run photo",
            scheduledReports = "every 30 min: phone status", pendingRequests = 2))
        assertEquals("the dashboard may run photo", governed.fact("Dashboard rules on this phone").value)
        assertEquals("every 30 min: phone status", governed.fact("Reports you set to repeat").value)
        assertEquals("2 requests for this phone", governed.fact("Dashboard requests waiting").value)
    }
}
