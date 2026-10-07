package com.example.systemhealth

import android.content.Context
import org.json.JSONObject

/** The backend advertises the release it packages. This phone only ever reports a newer one. */
object UpdateAwareness {
    private const val PREFS = "update_awareness"

    data class Available(val version: String, val url: String, val installed: String)

    /** True when `remote` is a strictly higher dotted number than `local`. Suffixes such as -dev
     *  are ignored so a development build is never told a same-number release is an upgrade. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = components(remote)
        val b = components(local)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0L }
            val right = b.getOrElse(index) { 0L }
            if (left != right) return left > right
        }
        return false
    }

    private fun components(version: String): List<Long> = version
        .trim().removePrefix("v").split('.', '-', '+')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toLongOrNull() }

    /** Called from the connection probe, which already fetches /health for other checks. */
    fun record(context: Context, serverUrl: String, health: JSONObject) {
        runCatching {
            context.getSharedPreferences(PREFS, 0).edit()
                .putString("server", serverUrl)
                .putString("available", health.optString("latest_app_version"))
                .putString("url", health.optString("latest_app_url"))
                .apply()
        }
    }

    fun available(context: Context): Available? {
        val settings = SyncSettingsStore.load(context) ?: return null
        val preferences = context.getSharedPreferences(PREFS, 0)
        if (preferences.getString("server", "") != settings.serverUrl) return null
        val version = preferences.getString("available", "") ?: ""
        if (version.isBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) return null
        return Available(version, preferences.getString("url", "") ?: "", BuildConfig.VERSION_NAME)
    }
}
