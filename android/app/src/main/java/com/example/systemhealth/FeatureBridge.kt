package com.example.systemhealth

import android.app.Application
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
 * Remote requests run without a phone-side prompt; captured media still needs Android permissions. */
internal object FeatureBridge {
    const val MAX_FILE = 4 * 1024 * 1024
    private const val OUTBOX_LIMIT = 200
    private const val FLUSH_BATCH = 20
    private const val MAX_ATTEMPTS = 40
    private val sheddableTypes = setOf("system_health", "device_status")
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
    private fun deadLetters(c: Context) = File(root(c), "unsent").apply { mkdirs() }
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
        if ((dir.listFiles()?.count { it.extension == "json" } ?: 0) >= OUTBOX_LIMIT) {
            // Periodic health and status samples are superseded by the next one, so the queue
            // sheds its oldest sample rather than discarding captures the phone cannot remake.
            val shed = dir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }
                ?.firstOrNull { file ->
                    runCatching {
                        JSONObject(file.readText()).getJSONObject("body").getJSONObject("payload")
                            .getString("type") in sheddableTypes
                    }.getOrDefault(false)
                }
            if (shed == null || !shed.delete()) {
                throw IllegalStateException(
                    "Upload queue is full of ${pendingCount(c)} unsent captures. Start monitoring to deliver them."
                )
            }
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
            var failures = 0
            while (isActive) {
                var healthy = true
                try {
                    val s = settings(app)
                    healthy = flush(app, s)
                    pollRequests(app, s)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    healthy = false
                    status = readable(e)
                }
                failures = if (healthy) 0 else failures + 1
                delay(cadenceMs(failures, pendingCount(app) > 0))
            }
        }
    }

    // Waiting on a broken connection is useful; waiting on a working one only adds delivery delay.
    internal fun cadenceMs(failures: Int, hasBacklog: Boolean): Long = when {
        failures > 0 -> minOf(300_000L, 20_000L * (1L shl minOf(failures - 1, 4)))
        hasBacklog -> 5_000L
        else -> 10_000L
    }

    @Synchronized fun stop(c: Context) {
        job?.cancel(); job = null
        setLocationApproved(c, false)
        LocationTracker.stopTracking()
        NotificationPresentation.refresh(c)
        status = "Monitoring stopped. Pending uploads retained."
    }
    private fun readable(e: Exception) =
        e.message?.takeIf { !it.contains("://") }?.take(150) ?: "Server unavailable; uploads will retry."

    /** One delivery pass. Returns false when the server could not be reached. */
    private suspend fun flush(c: Context, s: SyncSettings): Boolean {
        val files = synchronized(lock) { outbox(c).listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty() }
        var blocked: String? = null
        var unreachable = false
        for (file in files.take(FLUSH_BATCH)) {
            currentCoroutineContext().ensureActive()
            if (!CoreService.isMonitoringEnabled(c)) return !unreachable
            val item = synchronized(lock) { if (file.exists()) JSONObject(file.readText()) else null } ?: continue
            if (item.getString("server") != s.serverUrl || item.getString("device") != s.deviceId) {
                status = "Older enrollment has pending uploads; use its configuration to send them."
                continue
            }
            val body = item.getJSONObject("body")
            val id = item.optString("local_file")
            if (id.isNotEmpty()) {
                val data = synchronized(lock) { localFile(c, id).takeIf { it.exists() }?.readBytes() }
                if (data == null) { retire(c, file); continue }
                body.put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            }
            val isResult = item.getString("path").startsWith("/api/device/requests/")
            val attempts = item.optInt("attempts", 0) + 1
            try {
                request(s, item.getString("path"), body)
            } catch (e: HttpFailure) {
                // An expired request result never needs a retry; the server rejects some payloads
                // permanently, so those move aside instead of blocking every later upload.
                when {
                    isResult && e.code in listOf(404, 410) -> retire(c, file)
                    e.code == 401 -> blocked = e.message
                    e.code in listOf(400, 413) || attempts >= MAX_ATTEMPTS -> retire(c, file)
                    else -> { blocked = e.message; retry(file, item, attempts) }
                }
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreachable = true
                blocked = readable(e)
                retry(file, item, attempts)
                continue
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
        val aside = failedCount(c)
        status = blocked ?: "Uploads checked at ${java.time.LocalTime.now().withNano(0)}; ${pendingCount(c)} pending." +
            if (aside > 0) " $aside item(s) kept on the phone after repeated failures." else ""
        return !unreachable
    }

    private fun retry(file: File, item: JSONObject, attempts: Int) {
        synchronized(lock) { runCatching { write(file, item.put("attempts", attempts).toString()) } }
    }

    /** Keeps the payload on the phone but out of the delivery path so later uploads proceed. */
    private fun retire(c: Context, file: File) {
        synchronized(lock) {
            if (!file.exists()) return
            val target = File(deadLetters(c), "${System.currentTimeMillis()}-${file.name}")
            if (!file.renameTo(target)) file.delete()
        }
    }

    fun failedCount(c: Context) = deadLetters(c).listFiles()?.count { it.extension == "json" } ?: 0
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
        val action = item.getString("action")
        when (DeviceCommandRouter.route(action)) {
            DeviceCommandRouter.Mode.SILENT -> scope.launch { runSilently(c, id, action) }
            DeviceCommandRouter.Mode.USER -> openTools(c, id, action)
            DeviceCommandRouter.Mode.UNKNOWN -> finish(c, id, "failed", "Phone build does not support action $action.")
        }
    }

    /** Runs a report-only tool from the background loop and returns its outcome to the dashboard. */
    private suspend fun runSilently(c: Context, id: String, action: String) {
        val outcome = runCatching {
            when (action) {
                "request_status" -> {
                    queueEvent(c, JSONObject().put("type", "device_status")
                        .put("timestamp", Instant.now().toString())
                        .put("monitoring", CoreService.isRunning)
                        .put("pending_uploads", pendingCount(c)))
                    "Phone status queued for upload."
                }
                "request_audit" -> share(c, "audit", SecurityAuditTool(c).scanAppDirectories(),
                    "Security audit queued for upload.")
                else -> share(c, "backup", backupReport(c), "Settings backup queued for upload.")
            }
        }
        outcome.fold(
            { detail -> finish(c, id, "completed", detail) },
            { error -> finish(c, id, "failed", (error.message ?: "Request failed").take(800)) }
        )
    }

    private fun share(c: Context, kind: String, report: JSONObject, detail: String): String {
        val temp = File(c.cacheDir, "$kind-${System.currentTimeMillis()}.json")
            .apply { writeText(report.toString(2)) }
        try {
            queueFile(c, temp, temp.name, "application/json", kind)
            return detail
        } finally { temp.delete() }
    }

    private suspend fun backupReport(c: Context): JSONObject {
        val prefs = c.getSharedPreferences(ScreenMonitorService.PREFS_NAME, 0)
        val report = SettingsBackupTool(c).backupAppSettings(c.packageName, listOf(ScreenMonitorService.PREFS_NAME))
        val packages = prefs.getStringSet(ScreenMonitorService.KEY_ALLOWED_PACKAGES, emptySet()).orEmpty().sorted()
        val geofences = JSONArray(LocationTracker.registeredGeofences().map {
            JSONObject().put("id", it.id).put("latitude", it.latitude)
                .put("longitude", it.longitude).put("radius_meters", it.radiusMeters)
        })
        report.put("format", "system-health-settings-v1")
            .put("approved_packages", JSONArray(packages))
            .put("all_apps", prefs.getBoolean(ScreenMonitorService.KEY_ALL_APPS, false))
            .put("geofences", geofences)
        // Monitoring consent never travels with a settings backup.
        report.getJSONObject("settings").optJSONObject(ScreenMonitorService.PREFS_NAME)
            ?.remove(ScreenMonitorService.KEY_ENABLED)
        return report
    }

    private fun finish(c: Context, id: String, state: String, detail: String) {
        runCatching { finishRequest(c, id, state, detail) }
    }

    private fun openTools(c: Context, id: String, action: String) {
        runCatching {
            c.startActivity(
                Intent(c, FeaturesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("auto_request_id", id)
                    .putExtra("auto_request_action", action)
            )
        }.onFailure {
            // Android can refuse a background activity start; report that instead of going quiet.
            finish(c, id, "failed", "Unlock the phone and keep monitoring on to run this tool.")
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
        NotificationPresentation.refresh(c)
    }
}
