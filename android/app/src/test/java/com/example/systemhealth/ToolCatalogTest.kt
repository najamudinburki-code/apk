package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

/**
 * The tools screen builds its headings, labels and search filter from this catalog, so these checks are
 * what keep the screen and the words that find a control from drifting apart.
 */
class ToolCatalogTest {

    /** Every id the tools screen tags a control with, as it appears there. */
    private val taggedOnScreen = setOf(
        "run_requests", "cancel_request", "camera_lens", "photo", "recording_start", "recording_stop",
        "screenshot", "live_view_allow", "live_view_stop", "location_start", "location_stop",
        "geofence_add", "geofence_list", "scan", "report_cadence", "report_status", "report_scan",
        "folder_pick", "local_files", "clear_queue", "capture_alert", "notice_detail", "activity_log",
        "back_home")

    @Test fun everyTaggedControlOnTheToolsScreenIsNamedInTheCatalog() {
        assertEquals(taggedOnScreen, ToolCatalog.tools.map { it.id }.toSet())
        assertEquals("ids must be unique", ToolCatalog.tools.size, ToolCatalog.tools.map { it.id }.distinct().size)
    }

    @Test fun everyCataloguedControlBelongsToAShownCategory() {
        assertTrue(ToolCatalog.categories.containsAll(ToolCatalog.tools.map { it.category }))
        ToolCatalog.categories.forEach { category ->
            assertTrue("Category \"$category\" has no tool", ToolCatalog.tools.any { it.category == category })
        }
    }

    @Test fun everyControlHasANameAndAnExplanation() {
        ToolCatalog.tools.forEach { tool ->
            assertTrue(tool.id + " needs a plain name", tool.name.isNotBlank())
            assertTrue(tool.id + " needs a one-line explanation", tool.blurb.length > 20)
        }
    }

    @Test fun anEmptySearchShowsTheWholeScreen() {
        assertNull(ToolCatalog.matching(""))
        assertNull(ToolCatalog.matching("   "))
    }

    @Test fun searchingForStopFindsEveryWayToStopSomething() {
        val kept = ToolCatalog.matching("stop")!!
        setOf("cancel_request", "recording_stop", "live_view_stop", "location_stop").forEach {
            assertTrue(" \"$it\" must stay reachable when searching \"stop\"", it in kept)
        }
    }

    @Test fun aWordInTheSentenceFindsTheControl() {
        assertTrue("photo" in ToolCatalog.matching("picture")!!)
        assertTrue("scan" in ToolCatalog.matching("bluetooth")!!)
        assertTrue("geofence_add" in ToolCatalog.matching("radius")!!)
        assertTrue("activity_log" in ToolCatalog.matching("history")!!)
        assertTrue("folder_pick" in ToolCatalog.matching("folder")!!)
    }

    @Test fun moreWordsNarrowTheScreenInsteadOfWideningIt() {
        val stop = ToolCatalog.matching("stop")!!
        val cameraStop = ToolCatalog.matching("stop camera")!!
        assertTrue(cameraStop.isNotEmpty())
        assertTrue(cameraStop.size < stop.size)
        assertTrue(cameraStop.all { it in stop })
    }

    @Test fun aWordThatMatchesNothingLeavesNothingShownRatherThanEverything() {
        assertTrue(ToolCatalog.matching("quantum")!!.isEmpty())
    }

    @Test fun knownDashboardRequestsReadAsActions() {
        assertEquals("Take one photo", ToolCatalog.plainAction("request_photo"))
        assertEquals("Record microphone audio", ToolCatalog.plainAction("request_audio"))
        assertEquals("Start a live camera view", ToolCatalog.plainAction("request_live_view"))
        assertEquals("Apply dashboard rules", ToolCatalog.plainAction("request_settings"))
    }

    @Test fun anUnknownDashboardRequestKeepsTheServersOwnName() {
        // Guessing a label for an action this build does not know would misdescribe what gets run.
        assertEquals("run diagnostics", ToolCatalog.plainAction("request_run_diagnostics"))
        assertEquals("something new", ToolCatalog.plainAction("something_new"))
    }

    @Test fun anUnknownIdFallsBackToItselfInsteadOfCrashing() {
        assertEquals("not_a_tool", ToolCatalog.name("not_a_tool"))
        assertEquals("Take one photo", ToolCatalog.name("photo"))
    }
}
