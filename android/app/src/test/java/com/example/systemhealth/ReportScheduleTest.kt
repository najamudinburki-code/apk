package com.example.systemhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportScheduleTest {
    private val minute = 60_000L

    @Test
    fun `a report repeats only once the whole interval has passed`() {
        assertTrue(ReportSchedule.isDue(lastRun = 0, now = 15 * minute, intervalMinutes = 15))
        assertFalse(ReportSchedule.isDue(lastRun = 0, now = 15 * minute - 1, intervalMinutes = 15))
        assertTrue(ReportSchedule.isDue(lastRun = 1_000, now = 1_000 + 240 * minute, intervalMinutes = 240))
        assertFalse(ReportSchedule.isDue(lastRun = 1_000, now = 1_000 + 240 * minute - 1, intervalMinutes = 240))
    }

    @Test
    fun `a schedule never used runs at the next check-in`() {
        // Turning a report on must not make the owner wait a whole interval to see it work, and no
        // real clock reading is ever a full interval before 1970.
        ReportSchedule.intervals.forEach {
            assertTrue(ReportSchedule.isDue(lastRun = 0, now = System.currentTimeMillis(), intervalMinutes = it))
        }
    }

    @Test
    fun `only tools that need no window can repeat`() {
        ReportSchedule.tools.forEach {
            assertEquals(DeviceCommandRouter.Mode.SILENT, DeviceCommandRouter.route("request_$it"))
        }
    }

    @Test
    fun `every cadence is a readable whole number of minutes`() {
        ReportSchedule.intervals.forEach { assertTrue("$it is outside the allowed range", it in 1..1440) }
        assertEquals(listOf(15, 30, 60, 240), ReportSchedule.intervals)
    }

    @Test
    fun `each schedule names its report in plain words`() {
        ReportSchedule.tools.forEach { assertTrue(ReportSchedule.label(it).length > 3) }
    }
}
