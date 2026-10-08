package com.example.systemhealth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a dashboard asked this phone to obey. */
internal data class PhoneRules(val intervalMinutes: Int?, val toolsAllowed: Set<String>?)

/** Rules delivered by request_settings. They only narrow behaviour: a tool the dashboard leaves out
 *  stays off, an unrecognised name changes nothing, and no rule can grant an Android permission,
 *  start monitoring or hide the stop control. */
internal object RemotePolicy {
    const val DEFAULT_HEALTH_INTERVAL_MINUTES = 5
    // Names a dashboard rule may keep on, matching the backend's list. A live view is governable
    // because it is the one camera tool that repeats; stopping one is not, so a narrowed phone can
    // always be released from a stream by the same dashboard that started it.
    val tools: Set<String> =
        setOf("photo", "audio", "screenshot", "location", "scan", "geofence", "live_view")
    private const val PREFS = "remote_policy"
    private const val KEY_INTERVAL = "health_interval_minutes"
    private const val KEY_TOOLS = "tools_allowed"
    private const val MIN_INTERVAL = 1
    private const val MAX_INTERVAL = 1440

    /** Drops a cadence outside the allowed range and any tool name this build does not know. */
    fun rules(intervalMinutes: Int?, requestedTools: List<String>?): PhoneRules = PhoneRules(
        intervalMinutes?.takeIf { it in MIN_INTERVAL..MAX_INTERVAL },
        requestedTools?.filter { it in tools }?.toSet()
    )

    /** A null rule list means no dashboard has spoken yet, so the phone's own choices govern. */
    fun allows(tool: String, allowed: Set<String>?): Boolean =
        allowed == null || tool !in tools || tool in allowed

    fun parse(args: JSONObject): PhoneRules = rules(
        (args.opt(KEY_INTERVAL) as? Number)?.toInt(),
        if (args.isNull(KEY_TOOLS)) null else args.optJSONArray(KEY_TOOLS)?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }
    )

    /** Saves the rules the payload actually holds and says what changed, or returns nothing. */
    fun apply(context: Context, args: JSONObject): String {
        val parsed = parse(args)
        val editor = preferences(context).edit()
        val notes = mutableListOf<String>()
        parsed.intervalMinutes?.let {
            editor.putInt(KEY_INTERVAL, it)
            notes += "health samples every $it min"
        }
        parsed.toolsAllowed?.let { allowed ->
            editor.putStringSet(KEY_TOOLS, allowed)
            notes += describeTools(allowed)
        }
        if (notes.isEmpty()) return ""
        check(editor.commit()) { "Rules could not be saved on the phone" }
        return notes.joinToString(" · ")
    }

    fun intervalMinutes(context: Context): Int =
        preferences(context).getInt(KEY_INTERVAL, DEFAULT_HEALTH_INTERVAL_MINUTES)

    /** Rules belong to the enrollment that received them, so configuring a server clears the old set. */
    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    fun allowedTools(context: Context): Set<String>? = preferences(context).getStringSet(KEY_TOOLS, null)

    fun allows(context: Context, tool: String): Boolean = allows(tool, allowedTools(context))

    /** Plain words for the phone's own screens, or null while nothing is restricted. */
    fun summary(context: Context): String? {
        val cadence = if (preferences(context).contains(KEY_INTERVAL))
            "health samples every ${intervalMinutes(context)} min" else null
        val list = allowedTools(context)?.let { describeTools(it) }
        return listOfNotNull(cadence, list).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** Travels with the status report so the dashboard can see what the phone really obeys. */
    fun describe(context: Context): JSONObject {
        val allowed = allowedTools(context)
        val list = JSONArray()
        allowed?.sorted()?.forEach { list.put(it) }
        return JSONObject()
            .put("health_interval_minutes", intervalMinutes(context))
            .put("tools_allowed", if (allowed == null) JSONObject.NULL else list)
            .put("explanation", summary(context)
                ?: "No dashboard rule is in effect; this phone's own choices govern.")
    }

    private fun describeTools(allowed: Set<String>): String =
        if (allowed.isEmpty()) "no dashboard tool runs"
        else "the dashboard may run ${tools.filter { it in allowed }.sorted().joinToString()}"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
