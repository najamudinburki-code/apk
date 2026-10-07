package com.example.systemhealth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Reports the owner chose to repeat on this phone. They run only while monitoring is running, in
 *  the same loop that delivers uploads, so stopping monitoring stops them and no new scheduler,
 *  alarm or background permission is needed. */
internal object ReportSchedule {
    val intervals: List<Int> = listOf(15, 30, 60, 240)
    val tools: List<String> = listOf("status", "scan")
    private const val PREFS = "report_schedule"
    private const val KEY_TOOLS = "tools"
    private const val KEY_INTERVAL = "interval_minutes"
    private const val DEFAULT_INTERVAL = 60

    /** A report never run before is due at once, so the owner sees it work soon after opting in. */
    fun isDue(lastRun: Long, now: Long, intervalMinutes: Int): Boolean =
        now - lastRun >= intervalMinutes * 60_000L

    fun label(tool: String): String = when (tool) {
        "scan" -> "nearby Wi-Fi and Bluetooth scan"
        else -> "phone status report"
    }

    fun intervalMinutes(context: Context): Int =
        preferences(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    fun setInterval(context: Context, minutes: Int) {
        val chosen = if (minutes in intervals) minutes else DEFAULT_INTERVAL
        check(preferences(context).edit().putInt(KEY_INTERVAL, chosen).commit()) {
            "The report schedule could not be saved"
        }
    }

    /** Reading an older phone's saved schedule can name a tool this build no longer has, so the
     *  stale choice is ignored rather than answered with some other report. */
    fun enabled(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_TOOLS, emptySet()).orEmpty().filter { it in tools }.toSet()

    fun lastRun(context: Context, tool: String): Long =
        preferences(context).getLong("last_run_$tool", 0L)

    fun markRun(context: Context, tool: String, now: Long) {
        preferences(context).edit().putLong("last_run_$tool", now).apply()
    }

    /** Turns one schedule choice on or off and reports what the phone will now do. */
    fun setEnabled(context: Context, tool: String, on: Boolean): String {
        val tools = if (on) enabled(context) + tool else enabled(context) - tool
        // A fresh opt-in clears the old stamp, so the owner sees the first report soon.
        val editor = preferences(context).edit().putStringSet(KEY_TOOLS, tools).remove("last_run_$tool")
        check(editor.commit()) { "The scheduled report choice could not be saved" }
        return summary(context) ?: "No scheduled reports."
    }

    fun summary(context: Context): String? {
        val tools = enabled(context).map { label(it) }.sorted()
        if (tools.isEmpty()) return null
        return "${tools.joinToString()} every ${intervalMinutes(context)} min"
    }

    /** Travels with the status report so the dashboard can see what this phone repeats on its own. */
    fun describe(context: Context): JSONObject {
        val list = JSONArray()
        enabled(context).sorted().forEach { list.put(it) }
        return JSONObject().put("tools", list).put("interval_minutes", intervalMinutes(context))
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
