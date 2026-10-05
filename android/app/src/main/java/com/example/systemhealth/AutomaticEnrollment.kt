package com.example.systemhealth

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

/** Run only from the visible app. Existing manual enrollments are left intact. */
internal object AutomaticEnrollment {
    const val SERVER_URL = BuildConfig.API_BASE_URL
    private const val PREFS = "automatic_enrollment"
    data class Result(val state: String, val message: String, val deviceId: String = "")

    @Synchronized
    fun requestAutoStart(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("start_when_approved", true).apply()
    }
    @Synchronized
    fun cancelAutoStart(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("start_when_approved", false).apply()
    }
    @Synchronized
    fun consumeAutoStart(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, 0)
        val start = preferences.getBoolean("start_when_approved", false)
        if (start) check(preferences.edit().putBoolean("start_when_approved", false).commit())
        return start
    }

    @Synchronized
    private fun initializeAutoStart(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, 0)
        if (!preferences.contains("start_when_approved")) {
            check(preferences.edit().putBoolean("start_when_approved", true).commit())
        }
    }

    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        if (SyncSettingsStore.hasConfiguration(c)) return@withContext Result("approved", "Connection settings saved.")
        val existing = SyncSettingsStore.load(c)
        val settings = existing ?: SyncSettingsStore.validate(SERVER_URL, EnrollmentIdentity.deviceId(), EnrollmentIdentity.deviceToken()).also {
            SyncSettingsStore.savePending(c, it)
            initializeAutoStart(c)
        }
        val response = call(settings, "/api/enrollment/status")
        var state = response.optString("state")
        if (state == "missing" || state == "expired" || state == "pending") {
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".replace(Regex("[\\p{Cntrl}]"), " ").trim().take(200).ifBlank { "Android phone" }
            state = call(settings, "/api/enrollment/register", JSONObject()
                .put("device_id", settings.deviceId).put("device_token", settings.deviceToken)
                .put("installation_key", InstallationEnrollment.KEY).put("name", name)).getString("state")
        }
        when (state) {
            "approved" -> {
                SyncSettingsStore.markApproved(c)
                Result(state, "Connected automatically. Starting health monitoring.", settings.deviceId)
            }
            "pending" -> Result(state, "Connecting automatically. Update the matching backend if this continues.", settings.deviceId)
            "rejected" -> Result(state, "This phone's registration was declined in the dashboard.", settings.deviceId)
            "disabled" -> Result(state, "This phone has been disabled by the dashboard owner.", settings.deviceId)
            else -> error("Unexpected enrollment response")
        }
    }

    private fun call(settings: SyncSettings, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(settings.serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${settings.deviceToken}")
            connection.setRequestProperty("X-Device-Id", settings.deviceId)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                try { connection.outputStream.use { it.write(bytes) } } finally { bytes.fill(0) }
            }
            when (connection.responseCode) {
                404 -> error("Update the matching backend to enable automatic connection.")
                401 -> error("This enrollment was not accepted. Use advanced settings to recover the existing connection.")
                403 -> error("This APK's invitation does not match the server. Use the matching backend files.")
                429 -> error("Enrollment is busy. Please wait and try again.")
                503 -> error("Automatic connection is unavailable. Check the deployed backend's installation setting.")
            }
            check(connection.responseCode in 200..299) { "Server unavailable. Automatic connection will retry." }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4096) { "Invalid enrollment response" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
