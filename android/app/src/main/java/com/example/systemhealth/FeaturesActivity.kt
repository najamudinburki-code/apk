package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import com.fleet.tracking.FleetGeofence
import com.fleet.tracking.LocationSettingsUnavailable
import com.fleet.tracking.LocationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * The visible tools screen: everything the owner can start, stop or look at on this phone. Controls
 * are grouped into cards by what they are for, each named in plain language with one line saying what
 * it does, and a search box narrows the page without ever hiding a way to stop something.
 *
 * Dashboard requests that need a decision on this screen arrive here through `auto_request_id`; the
 * set of actions accepted below must stay equal to the user-consent set in `DeviceCommandRouter`.
 */
class FeaturesActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var kit: ScreenKit
    private lateinit var statusCard: ScreenKit.Card
    private lateinit var feedback: ScreenKit.StateRow
    private var appliedFacts: List<Fact>? = null
    private var camera: CameraController? = null
    private var audio: AudioRecorder? = null
    private var player: MediaPlayer? = null
    private var permissionAction: (() -> Unit)? = null
    private var exportFile: File? = null
    private var requestId: String? = null
    private var projectionRequest: String? = null
    private var recording = false
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        kit = ScreenKit(this)
        setContentView(kit.scrollContent())
        kit.eyebrow("SYSTEM HEALTH · ${BuildConfig.VERSION_NAME}")
        kit.screenTitle("Device tools")
        kit.paragraph("Requests your dashboard sends run as soon as they reach the phone while " +
            "monitoring is on. A remote photo or microphone recording is captured in the background " +
            "and never opens this screen; Android still shows its own camera and microphone " +
            "indicator. Screenshots still need this screen, because that is where Android asks you.")
        // A longer hint fits at normal text size but ellipsizes to "…" at enlarged text, which reads
        // as a broken field.
        kit.searchField("Search tools") { kit.filter(it) }

        statusCard = kit.card("Right now", ScreenKit.Tone.EMPHASIS)
        feedback = statusCard.state("Latest message from this phone", "Nothing has happened on this screen yet.")

        val requests = kit.card(ToolCatalog.REQUESTS)
        requests.note("Monitoring checks the server on its own, so a request usually starts by itself. " +
            "Use these only to look at what is waiting, or to cancel one.", id = "run_requests")
        requests.button("Run a waiting request", id = "run_requests") { runPendingRequests() }
        requests.button("Cancel the active request", ScreenKit.Weight.STOP, id = "cancel_request") { cancelActive() }

        val capture = kit.card(ToolCatalog.CAPTURE)
        capture.choice("Which camera", listOf("Front camera", "Rear camera"),
            if (photoFacing() == CameraSelection.REAR) 1 else 0, id = "camera_lens") { position ->
            getSharedPreferences(PREFS, 0).edit()
                .putInt(KEY_CAMERA_FACING, if (position == 0) CameraSelection.FRONT else CameraSelection.REAR)
                .apply()
        }
        capture.button("Take one photo", id = "photo") {
            consent("Take one ${CameraSelection.label(photoFacing())}-camera photo and upload it to your dashboard?") { takePhoto() }
        }
        capture.button("Record microphone audio", id = "recording_start") {
            consent("Record microphone audio while this screen is visible? Each 10-second audio file is uploaded. Tap Stop to finish.") { startAudio() }
        }
        capture.button("Stop the recording", ScreenKit.Weight.STOP, id = "recording_stop") {
            audio?.stopRecording(); recording = false; refresh()
        }
        capture.button("Take one screenshot", id = "screenshot") {
            consent("Android will ask what to share. One screenshot is captured after 5 seconds, then sharing stops. Secure screens remain protected.") { screenshot() }
        }
        capture.note("Live camera view", id = "live_view_allow")
        capture.note("A live view streams about ${LiveViewPolicy.framesPerSecond()} small frames a " +
            "second from the camera for at most ${LiveViewPolicy.MAX_SESSION_MS / 1000} seconds, then " +
            "stops by itself. Android keeps its own camera indicator lit the whole time, every frame " +
            "lands in your dashboard Files, and a dashboard rule can keep the tool off. Nothing " +
            "streams unless you allow it here.", id = "live_view_allow")
        capture.toggle("Allow the dashboard to start a live camera view",
            "Off means every live view request comes back refused.",
            LiveStreamBridge.allowedByOwner(this), id = "live_view_allow") { enabled ->
            LiveStreamBridge.setAllowedByOwner(this, enabled)
            message(if (enabled) "Live view allowed. Stop monitoring, or use Stop below, to end a stream at any time."
            else "Live view is off. A dashboard request for it now comes back refused.")
        }
        capture.button("Stop the live camera view now", ScreenKit.Weight.STOP, id = "live_view_stop") {
            message(LiveStreamBridge.stopByOwner()
                ?: "Live view stopped. Frames already uploaded stay in your dashboard Files.")
        }

        val places = kit.card(ToolCatalog.LOCATION)
        places.button("Start sharing location", id = "location_start") {
            consent("Share your GPS location while monitoring is on? An ongoing Android location notification is shown. Stop location or Stop monitoring ends sharing.") { startLocation() }
        }
        places.button("Stop sharing location", ScreenKit.Weight.STOP, id = "location_stop") {
            FeatureBridge.setLocationApproved(this, false); LocationTracker.stopTracking(); refresh()
        }
        places.button("Watch an area", id = "geofence_add") { addGeofence() }
        places.button("See or remove watched areas", id = "geofence_list") { listGeofences() }

        val nearby = kit.card(ToolCatalog.NEARBY)
        nearby.note("A scan lists the Wi-Fi networks and Bluetooth devices this phone can hear right now.",
            id = "scan")
        nearby.button("Scan nearby Wi-Fi and Bluetooth", id = "scan") {
            consent("Run one nearby Wi-Fi and Bluetooth discovery scan and upload its results? Enable Location, Wi-Fi and Bluetooth first.") { scanEnvironment() }
        }

        val reports = kit.card(ToolCatalog.REPORTS)
        reports.note("A report you start here repeats on its own while monitoring is on, and stops with " +
            "monitoring. Only report-only tools can repeat; a camera, microphone or screenshot always " +
            "stays a one-off choice. A tool the dashboard has turned off stays off.", id = "report_cadence")
        reports.choice("How often", ReportSchedule.intervals.map { minutes ->
            when (minutes) { 60 -> "Every hour"; 240 -> "Every 4 hours"; else -> "Every $minutes minutes" }
        }, ReportSchedule.intervals.indexOf(ReportSchedule.intervalMinutes(this)).coerceAtLeast(0),
            id = "report_cadence") { position ->
            ReportSchedule.setInterval(this, ReportSchedule.intervals[position])
        }
        ReportSchedule.tools.forEach { tool ->
            reports.toggle("Repeat the ${ReportSchedule.label(tool)}", "",
                tool in ReportSchedule.enabled(this), id = "report_$tool") { enabled ->
                message(ReportSchedule.setEnabled(this, tool, enabled))
            }
        }

        val files = kit.card(ToolCatalog.FILES)
        files.button("Choose a folder to browse", id = "folder_pick") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 45)
        }
        files.button("Files saved on this phone", id = "local_files") { showFiles() }
        files.button("Clear waiting uploads", id = "clear_queue") {
            AlertDialog.Builder(this).setTitle("Clear waiting uploads?")
                .setMessage("This deletes unsent tool reports and uploads from the phone queue. Local saved files and cloud files remain.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ -> scope.launch(Dispatchers.IO) { FeatureBridge.clearPending(this@FeaturesActivity) } }
                .show()
        }

        val notices = kit.card(ToolCatalog.NOTICES)
        notices.note("Android always keeps one notice while monitoring runs; these choices only change " +
            "the extra alerts and the detail line.", id = "capture_alert")
        notices.toggle("Alert me when a dashboard request used the camera or microphone",
            "The monitoring notice and Android's own indicators stay whatever you choose here.",
            NotificationPresentation.captureAlerts(this), id = "capture_alert") { enabled ->
            NotificationPresentation.setCaptureAlerts(this, enabled)
        }
        notices.toggle("Show recent uploads in the monitoring notice", "",
            NotificationPresentation.detailInOngoing(this), id = "notice_detail") { enabled ->
            NotificationPresentation.setDetailInOngoing(this, enabled)
        }
        notices.button("See what this phone has sent", id = "activity_log") { showActivityLog() }
        notices.button("Back to monitoring", ScreenKit.Weight.QUIET, id = "back_home",
            staysVisible = true) { finish() }
        handler.postDelayed({ handleAutoRequest(intent) }, 500)
    }

    private fun cancelActive() {
        val id = requestId
        requestId = null
        if (id == null) message("No active dashboard request.")
        else {
            audio?.stopRecording(); recording = false; camera?.close()
            FeatureBridge.awaitLocationResult(null)
            FeatureBridge.finishRequest(this, id, "declined", "Cancelled on phone")
            message("Active request cancelled.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoRequest(intent)
    }

    private fun handleAutoRequest(intent: Intent?) {
        val id = intent?.getStringExtra("auto_request_id") ?: return
        val action = intent.getStringExtra("auto_request_action") ?: return
        if (!CoreService.isSyncReady) {
            FeatureBridge.finishRequest(this, id, "failed", "Monitoring not active")
            return
        }
        if (requestId != null) {
            FeatureBridge.finishRequest(this, id, "failed", "Phone busy with another request")
            return
        }
        requestId = id
        val args = intent.getStringExtra("auto_request_args").orEmpty()
        // Only tools that need an owner's consent in a visible window arrive here. Photo, microphone,
        // nearby scan and status are answered by the background loop, so this list and
        // DeviceCommandRouter's USER set must stay equal; anything else is refused rather than guessed.
        when (action) {
            "request_screenshot" -> screenshot(id)
            "request_location" -> startLocation(id)
            "request_geofence" -> dashboardGeofence(id, args)
            else -> fail("This phone cannot run “${ToolCatalog.plainAction(action)}” from this screen.", id)
        }
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        feedback.value.text = text
        feedback.value.visibility = View.VISIBLE
        appliedFacts = null
    }

    private fun refresh() {
        if (!CoreService.isSyncReady && recording) { audio?.stopRecording(); recording = false }
        if (!::statusCard.isInitialized) return
        val pendingUploads = FeatureBridge.pendingCount(this@FeaturesActivity)
        val unsentUploads = FeatureBridge.failedCount(this@FeaturesActivity)
        val facts = buildList {
            add(Fact("Monitoring", PlainStatus.monitoring(CoreService.isRunning),
                needsAttention = !CoreService.isRunning))
            add(Fact("Microphone", PlainStatus.microphone(recording), needsAttention = recording))
            add(Fact("Location", PlainStatus.location(LocationTracker.isTracking.value)))
            add(Fact("Live camera view", PlainStatus.liveView(LiveStreamBridge.isStreaming(),
                LiveStreamBridge.allowedByOwner(this@FeaturesActivity)),
                needsAttention = LiveStreamBridge.isStreaming()))
            add(Fact("Uploads", PlainStatus.uploads(pendingUploads),
                needsAttention = pendingUploads > 0))
            if (unsentUploads > 0) add(Fact(
                "Could not be sent", PlainStatus.unsent(unsentUploads),
                needsAttention = true))
            add(Fact("Dashboard requests", PlainStatus.requests(FeatureBridge.pendingRequests(this@FeaturesActivity).size)))
            add(Fact("Dashboard rules", PlainStatus.rules(RemotePolicy.summary(this@FeaturesActivity))))
            add(Fact("Reports you set to repeat", PlainStatus.schedule(ReportSchedule.summary(this@FeaturesActivity))))
            if (requestId != null) add(Fact("Working on", "One dashboard request is in progress on this screen",
                needsAttention = true))
        }
        if (appliedFacts == facts) return
        statusCard.replaceFacts(facts)
        appliedFacts = facts
    }

    private fun consent(text: String, action: () -> Unit) {
        if (requestId != null) { message("Finish or cancel the active dashboard request first."); return }
        if (!CoreService.isSyncReady) { message("Connect this phone and start monitoring first."); return }
        AlertDialog.Builder(this).setTitle("Approve this action").setMessage(text)
            .setNegativeButton("Cancel", null).setPositiveButton("Approve") { _, _ -> action() }.show()
    }

    private fun permitted(permissions: Array<String>, action: () -> Unit) {
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            handler.postDelayed({ if (!isFinishing && !isDestroyed) action() }, 250)
        } else { permissionAction = action; requestPermissions(permissions, 10) }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code != 10) return
        val action = permissionAction; permissionAction = null
        if (grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            handler.postDelayed({ if (!isFinishing && !isDestroyed) action?.invoke() }, 350)
        } else {
            val refused = permissions.indices
                .filter { grants.getOrNull(it) != PackageManager.PERMISSION_GRANTED }
                .firstOrNull() ?: permissions.lastIndex
            fail(PlainStatus.permissionDenied(permissions[refused]))
        }
    }

    /** Tells the dashboard the phone is working, so a 35-second scan is not mistaken for a freeze. */
    private fun running(detail: String, id: String?) {
        if (id != null) FeatureBridge.finishRequest(this, id, "running", detail)
    }

    private fun fail(text: String, id: String? = requestId) {
        message(text); recording = false
        if (requestId == id) requestId = null
        if (id != null) FeatureBridge.finishRequest(this, id, "failed", text)
    }

    /** The owner said no, which is not a failure and must not look like one on the dashboard. */
    private fun cancelled(text: String, id: String? = requestId) {
        message(text)
        if (requestId == id) requestId = null
        if (id != null) FeatureBridge.finishRequest(this, id, "declined", text)
    }

    /** For a tool whose whole output is a change on the phone, such as a watched boundary. */
    private fun completed(text: String, id: String? = requestId) {
        message(text)
        if (requestId == id) requestId = null
        if (id != null) FeatureBridge.finishRequest(this, id, "completed", text)
    }

    private fun share(file: File, mime: String, kind: String, deleteTemp: Boolean = true, remote: String? = null) {
        // Saving a completed microphone chunk must survive the Activity being closed.
        val app = applicationContext
        if (requestId == remote) requestId = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FeatureBridge.queueFile(app, file, file.name, mime, kind, remote,
                    "$kind uploaded to the dashboard Files.")
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) message(PlainStatus.savedLocally(kind))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (remote != null) FeatureBridge.finishRequest(app, remote, "failed", "Output could not be queued on the phone")
                withContext(Dispatchers.Main) { if (!isDestroyed) fail(e.message ?: "Could not save output", null) }
            } finally { if (deleteTemp) file.delete() }
        }
    }

    private fun takePhoto(remote: String? = null) = permitted(arrayOf(Manifest.permission.CAMERA)) {
        if (remote != null && requestId != remote) return@permitted
        camera?.close()
        camera = CameraController(this, { share(it, "image/jpeg", "photo", remote = remote) }, { fail(it.message ?: "Camera unavailable", remote) })
        message("Taking one ${CameraSelection.label(photoFacing())}-camera photo…"); camera?.capturePhoto(photoFacing())
    }

    private fun photoFacing() = getSharedPreferences(PREFS, 0).getInt(KEY_CAMERA_FACING, CameraSelection.FRONT)

    private fun startAudio(remote: String? = null) = permitted(arrayOf(Manifest.permission.RECORD_AUDIO)) {
        if (remote != null && requestId != remote) return@permitted
        if (recording) { message("Already recording. Tap Stop to finish."); return@permitted }
        audio?.close()
        var firstRemote = remote
        audio = AudioRecorder(this, { file -> val id = firstRemote; firstRemote = null; share(file, "audio/mp4", "audio", remote = id) }, { fail(it.message ?: "Microphone unavailable", remote) })
        audio?.startRecording(); recording = true; refresh()
        if (remote != null) handler.postDelayed({ audio?.stopRecording(); recording = false }, 15_000)
    }

    private fun screenshot(remote: String? = null) {
        running("Android is asking which screen to share…", remote)
        projectionRequest = remote
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), 40)
    }

    private val locationPermissions get() = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun startLocation(remote: String? = null) = permitted(locationPermissions) {
        if (remote != null && requestId != remote) return@permitted
        scope.launch {
            val error = LocationTracker.checkLocationSettings().exceptionOrNull()
            if (error != null) {
                // No Play Services resolution dialog any more: Android's own location page is opened
                // instead, and returning from it retries the same request.
                val reason = (error as? LocationSettingsUnavailable)?.reason
                    ?: "Turn on location in Android settings, then try again."
                running("$reason Opening Android's location settings…", remote)
                if (!runCatching { startActivityForResult(LocationTracker.locationSettingsIntent(), 43) }.isSuccess) {
                    fail(reason, remote)
                }
                return@launch
            }
            try {
                FeatureBridge.setLocationApproved(this@FeaturesActivity, true)
                // A dashboard request is answered by the first fix that reaches the server, not by
                // starting the tracker, so an indoor phone with no fix never looks like a delivery.
                FeatureBridge.awaitLocationResult(remote)
                LocationTracker.startTracking()
                // The fix is answered by the background loop, not by this screen, so the screen must let
                // go of the slot. Holding it made every later on-screen request fail as busy: one
                // dashboard location request stranded the screenshot and boundary tools until the app was
                // reopened, which is what a handset showed on 2026-10-08.
                if (requestId == remote) requestId = null
                running("Location sharing started; waiting for the first GPS fix.", remote)
                message("Location sharing started; the dashboard waits for an accurate GPS fix.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                FeatureBridge.setLocationApproved(this@FeaturesActivity, false)
                FeatureBridge.awaitLocationResult(null)
                fail(e.message ?: "Location unavailable", remote)
            }
        }
    }

    private fun addGeofence() {
        if (!CoreService.isSyncReady) { message("Start monitoring first."); return }
        permitted(locationPermissions) {
            if (!LocationTracker.isTracking.value) {
                AlertDialog.Builder(this).setTitle("Start location sharing first")
                    .setMessage("A boundary is watched by this app's own location service, not by Google Play Services, so it only counts while location sharing runs. Android therefore needs no all-the-time location for geofences. Tap Start location sharing, then add the boundary.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start sharing") { _, _ -> startLocation() }.show()
                return@permitted
            }
            val fields = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            fun field(hint: String) = android.widget.EditText(this).apply { this.hint = hint; fields.addView(this) }
            val id = field("Name (for example Home)"); val lat = field("Latitude (-90 to 90)")
            val lon = field("Longitude (-180 to 180)"); val radius = field("Radius in meters (100 to 10000)")
            val dialog = AlertDialog.Builder(this).setTitle("Watch an area").setView(fields)
                .setNegativeButton("Cancel", null).setPositiveButton("Add", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val fence = runCatching {
                        require(id.text.length in 1..80)
                        val r = radius.text.toString().toFloat(); require(r in 100f..10000f)
                        FleetGeofence(id.text.toString().trim(), lat.text.toString().toDouble(), lon.text.toString().toDouble(), r)
                    }.getOrElse { message("Enter a name, valid coordinates and radius 100–10000 meters."); return@setOnClickListener }
                    scope.launch {
                        LocationTracker.addGeofence(fence).fold({ dialog.dismiss(); message("Area watched. Keep location sharing on for dashboard events.") }, { fail(it.message ?: "Could not watch this area") })
                    }
                }
            }; dialog.show()
        }
    }

    /** A boundary the dashboard proposed. Android watches it only after the owner agrees here. */
    private fun dashboardGeofence(remote: String?, args: String) {
        val fence = runCatching {
            val asked = JSONObject(args)
            FleetGeofence(asked.getString("name"), asked.getDouble("latitude"),
                asked.getDouble("longitude"), asked.getDouble("radius_meters").toFloat())
        }.getOrNull()
        if (fence == null) { fail("The dashboard sent an area this phone cannot read.", remote); return }
        permitted(locationPermissions) {
            if (!LocationTracker.isTracking.value) {
                fail("Start sharing location on the phone first — areas are watched by this app's location service, not by Google Play Services.", remote)
                return@permitted
            }
            val dialog = AlertDialog.Builder(this).setTitle("Your dashboard asks you to watch an area")
                .setMessage("${fence.id}\n${fence.latitude}, ${fence.longitude} · ${fence.radiusMeters.toInt()} m radius\n\n" +
                    "Allowing this makes the phone track that area in the background. Crossings reach your dashboard only while location sharing is running, and you can remove the area under “See or remove watched areas”.")
                .setNegativeButton("Decline", null).setPositiveButton("Allow", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    scope.launch {
                        LocationTracker.addGeofence(fence).fold(
                            { completed("“${fence.id}” is watched on this phone.", remote) },
                            { fail(it.message ?: "Could not watch this area", remote) }
                        )
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    dialog.dismiss(); cancelled("Declined the dashboard's area on the phone.", remote)
                }
            }
            dialog.setOnCancelListener { cancelled("Closed the dashboard's area request without answering.", remote) }
            dialog.show()
        }
    }

    private fun listGeofences() {
        val list = LocationTracker.registeredGeofences()
        if (list.isEmpty()) { message("No areas are watched on this phone."); return }
        AlertDialog.Builder(this).setTitle("Watched areas — tap to remove")
            .setItems(list.map { "${it.id}: ${it.latitude}, ${it.longitude} (${it.radiusMeters} m)" }.toTypedArray()) { _, index ->
                AlertDialog.Builder(this).setTitle("Remove ${list[index].id}?").setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    scope.launch { LocationTracker.removeGeofences(listOf(list[index].id)).fold({ message("Area removed.") }, { fail("Could not remove this area. Check location permissions.") }) }
                }.show()
            }.setNegativeButton("Close", null).setNeutralButton("Remove all") { _, _ ->
                AlertDialog.Builder(this).setTitle("Remove every watched area?").setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    scope.launch { LocationTracker.removeAllGeofences().fold({ message("All areas removed.") }, { fail("Could not remove the areas. Check location permissions.") }) }
                }.show()
            }.show()
    }

    private fun scanEnvironment(remote: String? = null) {
        val permissions = locationPermissions.toMutableList()
        if (Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        permitted(permissions.toTypedArray()) {
            running("Scanning nearby Wi-Fi and Bluetooth…", remote)
            message("Scanning; this may take up to 35 seconds…")
            scope.launch {
                try {
                    val result = HeadlessScan.scan(this@FeaturesActivity)
                        .put("type", "environment_scan").put("timestamp", Instant.now().toString())
                    withContext(Dispatchers.IO) { FeatureBridge.queueEvent(this@FeaturesActivity, result, remote, "Nearby scan uploaded.") }
                    showReport("Nearby scan", result, "document")
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e.message ?: "Scan unavailable. Enable Wi-Fi, Bluetooth and Location.", remote) }
            }
        }
    }

    private fun showReport(title: String, report: JSONObject, kind: String) {
        val view = android.widget.TextView(this).apply { text = report.toString(2); setPadding(24,24,24,24); setTextIsSelectable(true) }
        // Reading or exporting a report on the phone shares nothing beyond what already ran.
        val temp = File(cacheDir, "${kind}-${System.currentTimeMillis()}.json").apply { writeText(report.toString(2)) }
        AlertDialog.Builder(this).setTitle(title).setView(android.widget.ScrollView(this).apply { addView(view) })
            .setNegativeButton("Close", null)
            .setNeutralButton("Export") { _, _ -> export(temp, "application/json", temp.name) }
            .show()
    }

    private fun export(file: File, mime: String, name: String) {
        exportFile = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), 44)
    }

    private fun browseFolder(tree: Uri, documentId: String, depth: Int = 0) {
        if (depth > 32) { message("Folder depth limit reached."); return }
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val result = mutableListOf<Triple<String, String, String>>()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                    contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext() && result.size < 200) result += Triple(cursor.getString(0), cursor.getString(1) ?: "Unnamed", cursor.getString(2) ?: "application/octet-stream")
                    }
                    result.sortedWith(compareBy<Triple<String, String, String>> { it.third != DocumentsContract.Document.MIME_TYPE_DIR }.thenBy { it.second.lowercase() })
                }
                if (entries.isEmpty()) { message("This folder is empty or its contents are unavailable."); return@launch }
                AlertDialog.Builder(this@FeaturesActivity).setTitle("Selected folder — first 200 entries")
                    .setItems(entries.map { (if (it.third == DocumentsContract.Document.MIME_TYPE_DIR) "Folder: " else "File: ") + it.second }.toTypedArray()) { _, index ->
                        val entry = entries[index]
                        if (entry.third == DocumentsContract.Document.MIME_TYPE_DIR) browseFolder(tree, entry.first, depth + 1)
                        else consent("Copy ${entry.second} from this selected folder and upload it to your dashboard? Maximum 4 MiB.") {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.first)
                                        val temp = File(cacheDir, "folder-${System.currentTimeMillis()}.tmp")
                                        try {
                                            contentResolver.openInputStream(uri)?.use { input -> temp.writeBytes(readBounded(input)) } ?: error("Cannot read this file")
                                            FeatureBridge.queueFile(this@FeaturesActivity, temp, entry.second, entry.third.takeIf { Regex("[\\w.+-]+/[\\w.+-]+").matches(it) } ?: "application/octet-stream", "document")
                                        } finally { temp.delete() }
                                    }
                                    message("File saved on this phone and waiting to upload.")
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { fail(e.message ?: "Could not share this file", null) }
                            }
                        }
                    }.setNegativeButton("Close", null).setNeutralButton("Selected root") { _, _ -> browseFolder(tree, DocumentsContract.getTreeDocumentId(tree)) }.show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail("Folder access unavailable. Choose an accessible folder with Android's picker.", null) }
        }
    }

    private fun showFiles() {
        scope.launch {
            val files = withContext(Dispatchers.IO) { FeatureBridge.localFiles(this@FeaturesActivity) }
            if (files.isEmpty()) { message("Nothing has been saved on this phone yet."); return@launch }
            AlertDialog.Builder(this@FeaturesActivity).setTitle("Files saved on this phone")
                .setItems(files.map { "${it.getString("name")} · ${it.getLong("size") / 1024} KiB · ${if (it.optBoolean("uploaded")) "received by the server" else "waiting to upload"}" }.toTypedArray()) { _, index ->
                    val meta = files[index]; val file = FeatureBridge.localFile(this@FeaturesActivity, meta.getString("file_id"))
                    val choices = if (meta.getString("mime").startsWith("audio/")) arrayOf("Export", "Play audio", "Delete local copy") else arrayOf("Export", "Delete local copy")
                    AlertDialog.Builder(this@FeaturesActivity).setTitle(meta.getString("name")).setItems(choices) { _, action ->
                        when (choices[action]) {
                            "Export" -> export(file, meta.getString("mime"), meta.getString("name"))
                            "Play audio" -> try {
                                player?.release(); player = MediaPlayer().apply { setDataSource(file.path); prepare(); start() }
                                AlertDialog.Builder(this@FeaturesActivity).setTitle("Playing audio").setNegativeButton("Stop") { _, _ -> player?.release(); player = null }.setOnCancelListener { player?.release(); player = null }.show()
                            } catch (_: Exception) { fail("Could not play this audio file.") }
                            else -> AlertDialog.Builder(this@FeaturesActivity).setTitle("Delete local copy?").setMessage("This also cancels its pending upload. Uploaded cloud copies remain; delete those from the dashboard.")
                                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> scope.launch(Dispatchers.IO) { FeatureBridge.deleteLocal(this@FeaturesActivity, meta.getString("file_id")) } }.show()
                        }
                    }.show()
                }.setNegativeButton("Close", null).show()
        }
    }

    /** The owner's own list of what actually left the phone — server-acknowledged deliveries only. */
    private fun showActivityLog() {
        val entries = NotificationPresentation.activityLog(this)
        val body = if (entries.isEmpty()) "Nothing has reached the dashboard yet in this monitoring session."
        else entries.joinToString("\n") { "${it.optString("time")} · ${it.optString("label")}" }
        AlertDialog.Builder(this).setTitle("Uploads this phone delivered").setMessage(body)
            .setPositiveButton("Close", null).show()
    }

    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 43) { if (result == RESULT_OK) startLocation(requestId) else cancelled("Location setup cancelled.") ; return }
        if (result != RESULT_OK || data == null) {
            if (code == 40) cancelled("Screen sharing declined; no screenshot was taken.")
            return
        }
        if (code == 40) {
            val service = Intent(this, ScreenCaptureService::class.java).putExtra("result_code", result).putExtra("projection_data", data).putExtra("request_id", projectionRequest)
            if (requestId == projectionRequest) requestId = null
            projectionRequest = null
            try { startForegroundService(service); message("Screenshot in 5 seconds. Open the screen you chose to share.") }
            catch (_: Exception) { fail("Android could not start screen capture. Try again from this screen.") }
            return
        }
        val uri = data.data ?: return
        if (code == 45) {
            try {
                if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                browseFolder(uri, DocumentsContract.getTreeDocumentId(uri))
            } catch (_: Exception) { message("Could not open this folder. Select an accessible folder in Android's picker.") }
            return
        }
        if (code != 44) return
        scope.launch {
            try {
                val source = exportFile ?: error("Export source missing")
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Cannot write selected destination") }
                message("File exported.")
                exportFile = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { exportFile = null; fail(e.message ?: "File export failed", null) }
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= FeatureBridge.MAX_FILE) {
            val count = input.read(buffer, 0, minOf(buffer.size, FeatureBridge.MAX_FILE + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        require(output.size() <= FeatureBridge.MAX_FILE) { "Choose a file up to 4 MiB" }
        return output.toByteArray()
    }

    private fun runPendingRequests() {
        // Rules arrive from the background loop, so offering to run one again here would be misleading.
        val requests = FeatureBridge.pendingRequests(this)
            .filter { it.getString("action") != "request_settings" }
        if (requests.isEmpty()) { message("No requests are waiting. Monitoring checks the server about every 10 seconds."); return }
        if (requestId != null || recording) { message("Finish the active request or recording first."); return }
        AlertDialog.Builder(this).setTitle("Requests waiting for this phone")
            .setItems(requests.map { ToolCatalog.plainAction(it.getString("action")) }.toTypedArray()) { _, index ->
                val item = requests[index]; val action = item.getString("action")
                val id = item.getString("request_id")
                requestId = id
                when (action) {
                    "request_photo" -> takePhoto(id)
                    "request_screenshot" -> screenshot(id)
                    "request_audio" -> startAudio(id)
                    "request_location" -> startLocation(id)
                    "request_scan" -> scanEnvironment(id)
                    "request_geofence" -> dashboardGeofence(id, item.optJSONObject("args")?.toString() ?: "{}")
                    else -> scope.launch {
                        running("Reading phone status…", id)
                        try {
                            withContext(Dispatchers.IO) {
                                FeatureBridge.queueEvent(this@FeaturesActivity, FeatureBridge.deviceStatus(this@FeaturesActivity), id, "Phone status uploaded.")
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { fail(e.message ?: "Status upload failed", id) }
                    }
                }
            }.setNegativeButton("Close", null).show()
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }

    override fun onPause() {
        handler.removeCallbacks(refresher); audio?.stopRecording(); recording = false
        camera?.close()
        player?.release(); player = null
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); camera?.close(); audio?.close(); player?.release(); scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "feature_options"
        private const val KEY_CAMERA_FACING = "camera_facing"
    }
}
