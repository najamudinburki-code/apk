package com.example.systemhealth

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.example.utility.ScreenMonitorService
import com.example.utility.backup.SettingsBackupTool
import com.example.utility.security.SecurityAuditTool
import com.fleet.tracking.GeofenceEvent
import com.fleet.tracking.LocationPayload
import com.fleet.tracking.LocationTracker
import com.fleet.tracking.TrackingSink
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

class SystemHealthApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocationTracker.initialize(this, object : TrackingSink {
            override suspend fun onLocation(payload: LocationPayload) {
                if (CoreService.isMonitoringEnabled(this@SystemHealthApp) &&
                    FeatureBridge.locationApproved(this@SystemHealthApp)) {
                    FeatureBridge.queueEvent(this@SystemHealthApp, payload.toJson().put("type", "location"))
                }
            }
            override suspend fun onGeofenceEvent(event: GeofenceEvent) {
                if (CoreService.isMonitoringEnabled(this@SystemHealthApp) &&
                    FeatureBridge.locationApproved(this@SystemHealthApp)) {
                    FeatureBridge.queueEvent(this@SystemHealthApp, event.toJson().put("type", "geofence"))
                }
            }
        })
    }
}

/** Authenticated feature transport. Queued items stay tied to their original enrollment.
 * All media comes from visible phone actions. Remote requests only create review prompts. */
internal object FeatureBridge {
    const val MAX_FILE = 4 * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val lock = Any()
    @Volatile var status = "Tools ready. Start monitoring to upload."
        private set

    fun locationApproved(c: Context) = c.getSharedPreferences("feature_options", 0).getBoolean("location", false)
    fun setLocationApproved(c: Context, enabled: Boolean) {
        c.getSharedPreferences("feature_options", 0).edit().putBoolean("location", enabled).apply()
    }
    private fun root(c: Context) = File(c.filesDir, "shared_tools").apply { mkdirs() }
    private fun outbox(c: Context) = File(root(c), "outbox").apply { mkdirs() }
    private fun vault(c: Context) = File(root(c), "vault").apply { mkdirs() }
    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        check(temp.renameTo(file)) { "Could not save queued item" }
    }
    private fun settings(c: Context) = SyncSettingsStore.load(c) ?: error("Configure an enrolled device first")
    private fun wrapper(s: SyncSettings, path: String, body: JSONObject) = JSONObject()
        .put("server", s.serverUrl).put("device", s.deviceId).put("path", path).put("body", body)
    private fun enqueue(c: Context, item: JSONObject) = synchronized(lock) {
        val dir = outbox(c)
        check((dir.listFiles()?.count { it.extension == "json" } ?: 0) < 200) {
            "Upload queue full. Start monitoring to upload, or clear pending uploads."
        }
        write(File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json"), item.toString())
    }
    fun queueEvent(c: Context, payload: JSONObject) {
        require(payload.toString().toByteArray().size <= 48 * 1024) { "Report too large" }
        enqueue(c, wrapper(settings(c), "/api/device/events", JSONObject()
            .put("event_id", UUID.randomUUID().toString()).put("payload", payload)))
    }
    fun queueFile(c: Context, source: File, name: String, mime: String, kind: String): String = synchronized(lock) {
        require(source.length() in 1..MAX_FILE.toLong()) { "Choose a nonempty file up to 4 MiB" }
        val s = settings(c)
        val dir = vault(c)
        check((dir.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0) + source.length() <= 50L * 1024 * 1024) {
            "Phone file vault full (50 MiB). Export or delete old local files."
        }
        val id = UUID.randomUUID().toString()
        val bytes = File(dir, "$id.bin")
        source.copyTo(bytes, overwrite = false)
        val meta = JSONObject().put("file_id", id).put("name", name.take(180).replace(Regex("[\\p{Cntrl}/\\\\]"), "_"))
            .put("mime", mime).put("kind", kind).put("size", bytes.length()).put("created_at", Instant.now().toString())
            .put("uploaded", false).put("server", s.serverUrl).put("device", s.deviceId)
        try {
            write(File(dir, "$id.json"), meta.toString())
            enqueue(c, wrapper(s, "/api/device/files", JSONObject(meta.toString())).put("local_file", id))
        } catch (e: Exception) { bytes.delete(); File(dir, "$id.json").delete(); throw e }
        id
    }
    fun localFiles(c: Context): List<JSONObject> = synchronized(lock) {
        vault(c).listFiles()?.filter { it.extension == "json" }?.mapNotNull {
            runCatching { JSONObject(it.readText()) }.getOrNull()
        }?.sortedByDescending { it.optString("created_at") }.orEmpty()
    }
    fun localFile(c: Context, id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return File(vault(c), "$id.bin")
    }
    fun deleteLocal(c: Context, id: String) = synchronized(lock) {
        localFile(c, id).delete(); File(vault(c), "$id.json").delete()
        outbox(c).listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            if (runCatching { JSONObject(file.readText()).optString("local_file") == id }.getOrDefault(false)) file.delete()
        }
        Unit
    }
    fun pendingCount(c: Context) = outbox(c).listFiles()?.count { it.extension == "json" } ?: 0
    fun clearPending(c: Context) = synchronized(lock) {
        outbox(c).listFiles()?.forEach { it.delete() }; status = "Pending uploads cleared. Local files retained."
    }
    @Synchronized fun start(c: Context) {
        if (job?.isActive == true) return
        val app = c.applicationContext
        app.getSharedPreferences("phone_requests", 0).edit()
            .putString("pending", "[]")
            .putStringSet("dispatched", emptySet())
            .apply()
        job = scope.launch {
            while (isActive) {
                try {
                    val s = settings(app)
                    flush(app, s)
                    pollRequests(app, s)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = e.message?.takeIf { !it.contains("://") }?.take(150) ?: "Server unavailable; uploads will retry." }
                delay(30_000)
            }
        }
    }
    @Synchronized fun stop(c: Context) {
        job?.cancel(); job = null
        setLocationApproved(c, false)
        LocationTracker.stopTracking()
        c.getSystemService(NotificationManager::class.java).cancel(3010)
        NotificationPresentation.refresh(c)
        status = "Monitoring stopped. Pending uploads retained."
    }
    private suspend fun flush(c: Context, s: SyncSettings) {
        val files = synchronized(lock) { outbox(c).listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty() }
        var blocked: String? = null
        for (file in files.take(20)) {
            currentCoroutineContext().ensureActive()
            if (!CoreService.isMonitoringEnabled(c)) return
            val item = synchronized(lock) { if (file.exists()) JSONObject(file.readText()) else null } ?: continue
            if (item.getString("server") != s.serverUrl || item.getString("device") != s.deviceId) {
                status = "Older enrollment has pending uploads; use its configuration or clear the queue."
                continue
            }
            val body = item.getJSONObject("body")
            val id = item.optString("local_file")
            if (id.isNotEmpty()) {
                val data = synchronized(lock) { localFile(c, id).takeIf { it.exists() }?.readBytes() }
                if (data == null) { file.delete(); continue }
                body.put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            }
            try {
                request(s, item.getString("path"), body)
            } catch (e: HttpFailure) {
                // Expired request results no longer need retries; other failures remain visible.
                if (item.getString("path").startsWith("/api/device/requests/") && e.code in listOf(404, 410)) {
                    file.delete(); continue
                }
                if (e.code in listOf(400, 409, 413)) { blocked = e.message; continue }
                throw e
            }
            synchronized(lock) {
                if (id.isNotEmpty()) {
                    val meta = File(vault(c), "$id.json")
                    if (meta.exists()) write(meta, JSONObject(meta.readText()).put("uploaded", true).toString())
                }
                file.delete()
            }
            ConnectionDiagnostics.recordUpload(c, s.serverUrl, s.deviceId)
        }
        status = blocked ?: "Uploads checked at ${java.time.LocalTime.now().withNano(0)}; ${pendingCount(c)} pending."
    }
    private class HttpFailure(val code: Int) : Exception(when(code) {
        401 -> "Enrollment rejected. Check your device ID and token."
        404 -> "New tools API missing. Deploy the updated backend first."
        409 -> "Upload blocked. Check the dashboard file quota (100 MiB/device)."
        429 -> "Server busy; uploads will retry."
        else -> "Server returned HTTP $code; uploads will retry."
    })
    private fun request(s: SyncSettings, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(s.serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${s.deviceToken}")
            connection.setRequestProperty("X-Device-Id", s.deviceId)
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpFailure(code)
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096); val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer); if (count < 0) break
                    result.append(buffer, 0, count)
                    require(result.length <= 100_000) { "Unexpected server response" }
                }
                result.toString()
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
    private fun autoDispatch(c: Context, item: JSONObject) {
        val id = item.getString("request_id")
        when (val action = item.getString("action")) {
            "request_status" -> scope.launch {
                runCatching {
                    queueEvent(c, JSONObject().put("type", "device_status")
                        .put("timestamp", Instant.now().toString())
                        .put("monitoring", CoreService.isRunning)
                        .put("pending_uploads", pendingCount(c)))
                    finishRequest(c, id, "completed", "Phone status queued for upload.")
                }.onFailure { runCatching { finishRequest(c, id, "failed", it.message ?: "Status failed") } }
            }
            "request_audit" -> scope.launch {
                runCatching {
                    val report = SecurityAuditTool(c).scanAppDirectories()
                    val temp = java.io.File(c.cacheDir, "audit-${System.currentTimeMillis()}.json")
                        .apply { writeText(report.toString(2)) }
                    try { queueFile(c, temp, temp.name, "application/json", "audit")
                        finishRequest(c, id, "completed", "Security audit queued for upload.")
                    } finally { temp.delete() }
                }.onFailure { runCatching { finishRequest(c, id, "failed", it.message ?: "Audit failed") } }
            }
            "request_backup" -> scope.launch {
                runCatching {
                    val report = SettingsBackupTool(c).backupAppSettings(c.packageName, listOf(ScreenMonitorService.PREFS_NAME))
                    val packages = c.getSharedPreferences(ScreenMonitorService.PREFS_NAME, 0)
                        .getStringSet(ScreenMonitorService.KEY_ALLOWED_PACKAGES, emptySet()).orEmpty().sorted()
                    val geofences = JSONArray(LocationTracker.registeredGeofences().map {
                        JSONObject().put("id", it.id).put("latitude", it.latitude)
                            .put("longitude", it.longitude).put("radius_meters", it.radiusMeters)
                    })
                    val allApps = c.getSharedPreferences(ScreenMonitorService.PREFS_NAME, 0)
                        .getBoolean(ScreenMonitorService.KEY_ALL_APPS, false)
                    report.put("format", "system-health-settings-v1")
                        .put("approved_packages", JSONArray(packages))
                        .put("all_apps", allApps).put("geofences", geofences)
                    report.getJSONObject("settings").optJSONObject(ScreenMonitorService.PREFS_NAME)
                        ?.remove(ScreenMonitorService.KEY_ENABLED)
                    val temp = java.io.File(c.cacheDir, "backup-${System.currentTimeMillis()}.json")
                        .apply { writeText(report.toString(2)) }
                    try { queueFile(c, temp, temp.name, "application/json", "backup")
                        finishRequest(c, id, "completed", "Settings backup queued for upload.")
                    } finally { temp.delete() }
                }.onFailure { runCatching { finishRequest(c, id, "failed", it.message ?: "Backup failed") } }
            }
            else -> c.startActivity(
                Intent(c, FeaturesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("auto_request_id", id)
                    .putExtra("auto_request_action", action)
            )
        }
    }

    private fun pollRequests(c: Context, s: SyncSettings) {
        val incoming = request(s, "/api/device/requests").getJSONArray("requests")
        val prefs = c.getSharedPreferences("phone_requests", 0)
        val done = prefs.getStringSet("done", emptySet()).orEmpty()
        val dispatched = prefs.getStringSet("dispatched", emptySet()).orEmpty()
        val items = JSONArray()
        val toDispatch = mutableListOf<JSONObject>()
        for (i in 0 until incoming.length()) {
            val item = incoming.getJSONObject(i)
            val reqId = item.getString("request_id")
            if (reqId !in done) {
                items.put(item)
                if (reqId !in dispatched) toDispatch.add(item)
            }
        }
        if (toDispatch.isNotEmpty()) {
            prefs.edit().putStringSet("dispatched",
                (dispatched + toDispatch.map { it.getString("request_id") }).toList().takeLast(200).toSet()
            ).apply()
            for (item in toDispatch) autoDispatch(c, item)
        }
        prefs.edit().putString("pending", items.toString())
            .putString("server", s.serverUrl).putString("device", s.deviceId).apply()
    }


    fun pendingRequests(c: Context): List<JSONObject> {
        val prefs = c.getSharedPreferences("phone_requests", 0)
        val array = JSONArray(prefs.getString("pending", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { runCatching { Instant.parse(it.getString("expires_at")) > Instant.now() }.getOrDefault(false) }
    }
    fun finishRequest(c: Context, id: String, state: String, detail: String) {
        val s = settings(c)
        val prefs = c.getSharedPreferences("phone_requests", 0)
        require(prefs.getString("server", "") == s.serverUrl && prefs.getString("device", "") == s.deviceId) {
            "Request belongs to a different enrollment"
        }
        enqueue(c, wrapper(s, "/api/device/requests/$id/result", JSONObject().put("status", state).put("detail", detail.take(800))))
        prefs.edit().putStringSet("done", (prefs.getStringSet("done", emptySet()).orEmpty() + id).toList().takeLast(200).toSet())
            .putString("pending", JSONArray(pendingRequests(c).filter { it.getString("request_id") != id }).toString()).apply()
        c.getSystemService(NotificationManager::class.java).cancel(3010)
        NotificationPresentation.refresh(c)
    }
}
