package com.example.systemhealth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Read-only server/enrollment checks, plus timestamps only after acknowledged uploads. */
object ConnectionDiagnostics {
    private const val PREFS = "connection_diagnostics"
    data class Result(val accepted: Boolean, val message: String)

    suspend fun probe(context: Context): Result = withContext(Dispatchers.IO) {
        val settings = SyncSettingsStore.load(context)
            ?: return@withContext Result(false, "Connect the phone first using guided setup.")
        val result = try {
            val health = get(settings, "/health", false)
            check(health.optBoolean("ok")) { "Server health check did not pass." }
            UpdateAwareness.record(context, settings.serverUrl, health)
            val device = get(settings, "/api/device/requests", true)
            check(device.has("requests")) { "Update the backend before using device tools." }
            Result(true, "Server reachable and phone credentials accepted.")
        } catch (e: Exception) {
            Result(false, when (e) {
                is java.net.SocketTimeoutException -> "Server is slow or waking up. Keep the app open and retry."
                is java.net.UnknownHostException -> "No server connection. Check this phone's internet and retry."
                is DiagnosticFailure -> e.message ?: "Connection check failed."
                else -> "Connection not verified. Check internet or server availability, then retry."
            })
        }
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("server", settings.serverUrl).putString("device", settings.deviceId)
            .putBoolean("accepted", result.accepted).putString("message", result.message)
            .putLong("checked_at", System.currentTimeMillis()).apply()
        result
    }

    private class DiagnosticFailure(message: String) : Exception(message)
    private fun get(settings: SyncSettings, path: String, authenticated: Boolean): JSONObject {
        val connection = URL(settings.serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) {
                connection.setRequestProperty("Authorization", "Bearer ${settings.deviceToken}")
                connection.setRequestProperty("X-Device-Id", settings.deviceId)
            }
            when (connection.responseCode) {
                401, 403 -> throw DiagnosticFailure("Phone credentials were rejected. Check enrollment in Advanced settings.")
                404 -> throw DiagnosticFailure("Device tools API missing. Deploy the matching backend.")
                429 -> throw DiagnosticFailure("Server is busy. Wait briefly and retry.")
            }
            if (connection.responseCode !in 200..299) throw DiagnosticFailure("Server did not accept the connection check. Retry shortly.")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (output.size() <= 64 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 64 * 1024) throw DiagnosticFailure("Server response was too large. Check the backend.")
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    fun recordUpload(context: Context, server: String, device: String) {
        // A display-only timestamp must never turn a successful delivery into a retry.
        runCatching {
            context.getSharedPreferences(PREFS, 0).edit().putString("upload_server", server)
                .putString("upload_device", device).putLong("last_upload", System.currentTimeMillis()).apply()
        }
    }
    fun verified(context: Context): Boolean {
        val s = SyncSettingsStore.load(context) ?: return false
        val p = context.getSharedPreferences(PREFS, 0)
        return p.getString("server", "") == s.serverUrl && p.getString("device", "") == s.deviceId &&
            p.getBoolean("accepted", false) && System.currentTimeMillis() - p.getLong("checked_at", 0) in 0..120_000
    }
    fun lastUpload(context: Context): Long {
        val s = SyncSettingsStore.load(context) ?: return 0
        val p = context.getSharedPreferences(PREFS, 0)
        return if (p.getString("upload_server", "") == s.serverUrl && p.getString("upload_device", "") == s.deviceId) p.getLong("last_upload", 0) else 0
    }
    fun lastCheckMessage(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("message", "Connection not checked yet.") ?: "Connection not checked yet."
    fun timestamp(value: Long): String = if (value == 0L) "Waiting for first successful upload" else
        java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
}
