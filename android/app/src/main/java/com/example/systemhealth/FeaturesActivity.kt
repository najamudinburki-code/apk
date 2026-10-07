package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.*
import com.fleet.tracking.FleetGeofence
import com.fleet.tracking.LocationTracker
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.time.Instant

class FeaturesActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
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
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }
        setContentView(ScrollView(this).apply { addView(layout) })
        label("Device tools", 27f)
        label("Dashboard requests run as soon as they reach the phone while monitoring is on. A remote photo or microphone recording is captured in the background and never opens this screen; Android still shows its own camera and microphone indicator. Screenshots still need this screen because Android asks for consent there.")
        status = label("")
        status.setPadding(28, 28, 28, 28)
        status.background = GradientDrawable().apply {
            setColor(themeColor(android.R.attr.colorBackground, 0xFFF5F7FA.toInt()))
            cornerRadius = 16f
            setStroke(2, themeColor(android.R.attr.textColorSecondary, 0xFF8899AA.toInt()))
        }
        button("Run pending dashboard requests") { runPendingRequests() }
        button("Cancel active dashboard request") {
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
        section("Camera, microphone and screenshot")
        label("Photo camera (front by default)")
        val cameraChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@FeaturesActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Front camera", "Rear camera"))
            setSelection(if (getSharedPreferences("feature_options", 0).getInt("camera_facing", CameraSelection.FRONT) == CameraSelection.REAR) 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    getSharedPreferences("feature_options", 0).edit().putInt("camera_facing", if (position == 0) CameraSelection.FRONT else CameraSelection.REAR).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        layout.addView(cameraChoice)
        button("Take and share photo") { consent("Take one ${CameraSelection.label(photoFacing())}-camera photo and upload it to your dashboard?") { takePhoto() } }
        button("Start and share microphone recording") { consent("Record microphone audio while this screen is visible? Each 10-second audio file is uploaded. Tap Stop to finish.") { startAudio() } }
        button("Stop microphone recording") { audio?.stopRecording(); recording = false; refresh() }
        button("Capture and share screenshot") { consent("Android will ask what to share. One screenshot is captured after 5 seconds, then sharing stops. Secure screens remain protected.") { screenshot() } }
        section("Location and geofences")
        button("Start sharing location") { consent("Share your GPS location while monitoring is on? An ongoing Android location notification is shown. Stop location or Stop monitoring ends sharing.") { startLocation() } }
        button("Stop location sharing") { FeatureBridge.setLocationApproved(this, false); LocationTracker.stopTracking(); refresh() }
        button("Add geofence") { addGeofence() }
        button("View / remove geofences") { listGeofences() }
        section("Nearby scan")
        button("Scan and share nearby Wi-Fi / Bluetooth") { consent("Run one nearby Wi-Fi and Bluetooth discovery scan and upload its results? Enable Location, Wi-Fi and Bluetooth first.") { scanEnvironment() } }
        section("Scheduled reports")
        label("A report you start here repeats on its own while monitoring is on, and stops with monitoring. Only report-only tools can repeat; a camera, microphone or screenshot always stays a one-off choice. A tool the dashboard has turned off stays off.")
        val cadenceNames = ReportSchedule.intervals.map { minutes ->
            when (minutes) { 60 -> "Every hour"; 240 -> "Every 4 hours"; else -> "Every $minutes minutes" }
        }
        layout.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@FeaturesActivity, android.R.layout.simple_spinner_dropdown_item, cadenceNames)
            setSelection(ReportSchedule.intervals.indexOf(ReportSchedule.intervalMinutes(this@FeaturesActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    ReportSchedule.setInterval(this@FeaturesActivity, ReportSchedule.intervals[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        ReportSchedule.tools.forEach { tool ->
            check("Repeat the ${ReportSchedule.label(tool)}", tool in ReportSchedule.enabled(this)) { enabled ->
                message(ReportSchedule.setEnabled(this, tool, enabled))
            }
        }
        section("Shared files")
        button("Choose / browse a folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 45)
        }
        button("View / export / delete local files") { showFiles() }
        button("Clear pending uploads") {
            AlertDialog.Builder(this).setTitle("Clear pending uploads?").setMessage("This deletes unsent tool reports and uploads from the phone queue. Local saved files and cloud files remain.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> scope.launch(Dispatchers.IO) { FeatureBridge.clearPending(this@FeaturesActivity) } }.show()
        }
        label("What the phone shows you", 20f)
        label("Android always keeps one notice while monitoring runs; these choices only change the extra alerts and the detail line.")
        check("Alert me when a dashboard request used the camera or microphone", NotificationPresentation.captureAlerts(this)) {
            NotificationPresentation.setCaptureAlerts(this, it)
        }
        check("Show recent uploads in the monitoring notice", NotificationPresentation.detailInOngoing(this)) {
            NotificationPresentation.setDetailInOngoing(this, it)
        }
        button("See what this phone has sent") { showActivityLog() }
        button("Back to monitoring") { finish() }
        handler.postDelayed({ handleAutoRequest(intent) }, 500)
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
            else -> fail("Phone build does not run $action from this screen.", id)
        }
    }

    private fun label(text: String, size: Float = 17f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, 12, 0, 12); this@FeaturesActivity.layout.addView(this)
    }
    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 21f; setPadding(0, 28, 0, 4)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        this@FeaturesActivity.layout.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        layout.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }
    private fun check(text: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        layout.addView(CheckBox(this).apply {
            this.text = text; isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
    }
    private fun showActivityLog() {
        val entries = NotificationPresentation.activityLog(this)
        val body = if (entries.isEmpty()) "Nothing has reached the dashboard yet in this monitoring session."
        else entries.joinToString("\n") { "${it.optString("time")} · ${it.optString("label")}" }
        AlertDialog.Builder(this).setTitle("Uploads this phone delivered")
            .setMessage(body).setPositiveButton("Close", null).show()
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); status.text = text }
    private fun refresh() {
        if (!CoreService.isSyncReady && recording) { audio?.stopRecording(); recording = false }
        if (::status.isInitialized) status.text = "Monitoring: ${CoreService.isRunning}\nLocation: ${LocationTracker.isTracking.value}\nMicrophone: ${if (recording) "recording" else "off"}\n${FeatureBridge.status}\nPending tool uploads: ${FeatureBridge.pendingCount(this)}\nUnsent items kept aside: ${FeatureBridge.failedCount(this)}\nPending dashboard requests: ${FeatureBridge.pendingRequests(this).size}\nDashboard rules: ${RemotePolicy.summary(this) ?: "none; this phone's choices govern"}\nScheduled reports: ${ReportSchedule.summary(this) ?: "none"}"
    }
    private fun consent(text: String, action: () -> Unit) {
        if (requestId != null) { message("Finish or cancel the active dashboard request first."); return }
        if (!CoreService.isSyncReady) { message("Configure your enrolled device and start monitoring first."); return }
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
        } else { fail("Required permission denied. You can enable it in app settings.") }
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
                withContext(Dispatchers.Main) { if (!isDestroyed) message("$kind saved and queued. Start monitoring to upload.") }
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
    private fun photoFacing() = getSharedPreferences("feature_options", 0).getInt("camera_facing", CameraSelection.FRONT)
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
    private val locationPermissions get() = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startLocation(remote: String? = null) = permitted(locationPermissions) {
        if (remote != null && requestId != remote) return@permitted
        scope.launch {
            val result = LocationTracker.checkLocationSettings()
            val error = result.exceptionOrNull()
            if (error is ResolvableApiException) { error.startResolutionForResult(this@FeaturesActivity, 43); return@launch }
            if (error != null) { fail("Enable precise location and GPS, then try again.", remote); return@launch }
            try {
                FeatureBridge.setLocationApproved(this@FeaturesActivity, true)
                // A dashboard request is answered by the first fix that reaches the server, not by
                // starting the tracker, so an indoor phone with no fix never looks like a delivery.
                FeatureBridge.awaitLocationResult(remote)
                LocationTracker.startTracking()
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
            if (!LocationTracker.hasBackgroundLocationPermission()) {
                AlertDialog.Builder(this).setTitle("Geofence location permission")
                    .setMessage("Geofences need Location → Allow all the time. Open app settings, grant it, return and tap Add geofence again. Geofence sharing also requires Start location sharing.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Open settings") { _, _ ->
                        if (Build.VERSION.SDK_INT == 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 11)
                        else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }.show()
                return@permitted
            }
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun field(hint: String) = EditText(this).apply { this.hint = hint; fields.addView(this) }
            val id = field("Name (for example Home)"); val lat = field("Latitude (-90 to 90)")
            val lon = field("Longitude (-180 to 180)"); val radius = field("Radius in meters (100 to 10000)")
            val dialog = AlertDialog.Builder(this).setTitle("Add geofence").setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Add", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val fence = runCatching {
                        require(id.text.length in 1..80)
                        val r = radius.text.toString().toFloat(); require(r in 100f..10000f)
                        FleetGeofence(id.text.toString().trim(), lat.text.toString().toDouble(), lon.text.toString().toDouble(), r)
                    }.getOrElse { message("Enter a name, valid coordinates and radius 100–10000 meters."); return@setOnClickListener }
                    scope.launch {
                        LocationTracker.addGeofence(fence).fold({ dialog.dismiss(); message("Geofence registered. Start location sharing for dashboard events.") }, { fail(it.message ?: "Geofence registration failed") })
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
        if (fence == null) { fail("The dashboard sent a boundary this phone cannot read.", remote); return }
        permitted(locationPermissions) {
            if (!LocationTracker.hasBackgroundLocationPermission()) {
                fail("Grant Location → Allow all the time on the phone, then retry from the dashboard.", remote)
                return@permitted
            }
            val dialog = AlertDialog.Builder(this).setTitle("Dashboard asks you to watch an area")
                .setMessage("${fence.id}\n${fence.latitude}, ${fence.longitude} · ${fence.radiusMeters.toInt()} m radius\n\n" +
                    "Allowing this makes the phone track that boundary in the background. Crossings reach your dashboard only while location sharing is running, and you can remove the boundary under View / remove geofences.")
                .setNegativeButton("Decline", null).setPositiveButton("Allow", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    scope.launch {
                        LocationTracker.addGeofence(fence).fold(
                            { completed("Boundary “${fence.id}” is watched on this phone.", remote) },
                            { fail(it.message ?: "Geofence registration failed", remote) }
                        )
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    dialog.dismiss(); cancelled("Declined the dashboard's boundary on the phone.", remote)
                }
            }
            dialog.setOnCancelListener { cancelled("Closed the dashboard's boundary request without answering.", remote) }
            dialog.show()
        }
    }
    private fun listGeofences() {
        val list = LocationTracker.registeredGeofences()
        if (list.isEmpty()) { message("No geofences registered."); return }
        AlertDialog.Builder(this).setTitle("Registered geofences — tap to remove")
            .setItems(list.map { "${it.id}: ${it.latitude}, ${it.longitude} (${it.radiusMeters} m)" }.toTypedArray()) { _, index ->
                AlertDialog.Builder(this).setTitle("Remove ${list[index].id}?").setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    scope.launch { LocationTracker.removeGeofences(listOf(list[index].id)).fold({ message("Geofence removed.") }, { fail("Could not remove geofence. Check permissions.") }) }
                }.show()
            }.setNegativeButton("Close", null).setNeutralButton("Remove all") { _, _ ->
                AlertDialog.Builder(this).setTitle("Remove all geofences?").setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    scope.launch { LocationTracker.removeAllGeofences().fold({ message("All geofences removed.") }, { fail("Could not remove geofences. Check permissions.") }) }
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
        val view = TextView(this).apply { text = report.toString(2); setPadding(24,24,24,24); setTextIsSelectable(true) }
        // Reading or exporting a report on the phone shares nothing beyond what already ran.
        val temp = File(cacheDir, "${kind}-${System.currentTimeMillis()}.json").apply { writeText(report.toString(2)) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) })
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
                                            contentResolver.openInputStream(uri)?.use { temp.writeBytes(readBounded(it)) } ?: error("Cannot read this file")
                                            FeatureBridge.queueFile(this@FeaturesActivity, temp, entry.second, entry.third.takeIf { Regex("[\\w.+-]+/[\\w.+-]+").matches(it) } ?: "application/octet-stream", "document")
                                        } finally { temp.delete() }
                                    }
                                    message("Selected file queued for upload.")
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
            if (files.isEmpty()) { message("No local shared files yet."); return@launch }
            AlertDialog.Builder(this@FeaturesActivity).setTitle("Local files")
                .setItems(files.map { "${it.getString("name")} · ${it.getLong("size") / 1024} KiB · ${if (it.optBoolean("uploaded")) "uploaded" else "queued"}" }.toTypedArray()) { _, index ->
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
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 43) { if (result == RESULT_OK) startLocation(requestId) else cancelled("GPS setup cancelled.") ; return }
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
        if (requests.isEmpty()) { message("No pending requests. Monitoring checks the server every 10 seconds."); return }
        if (requestId != null || recording) { message("Finish the active request or recording first."); return }
        AlertDialog.Builder(this).setTitle("Dashboard requests")
            .setItems(requests.map { it.getString("action").removePrefix("request_") }.toTypedArray()) { _, index ->
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
}
