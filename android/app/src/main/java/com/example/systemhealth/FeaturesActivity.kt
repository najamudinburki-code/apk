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
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.*
import com.example.utility.ScreenMonitorService
import com.example.utility.backup.SettingsBackupTool
import com.example.utility.security.SecurityAuditTool
import com.fleet.tracking.FleetGeofence
import com.fleet.tracking.LocationTracker
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.coroutines.*
import org.json.JSONArray
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
    private var scanner: EnvironmentScanner? = null
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
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(layout) })
        label("Device tools", 24f)
        label("Dashboard requests run as soon as they reach the phone while monitoring is on. Camera, microphone, screenshots and file picking still need Android permissions and this visible screen.")
        status = label("")
        button("Run pending dashboard requests") { runPendingRequests() }
        button("Cancel active dashboard request") {
            val id = requestId
            requestId = null
            if (id == null) message("No active dashboard request.")
            else {
                audio?.stopRecording(); recording = false; camera?.close(); scanner?.close()
                scope.launch(Dispatchers.IO) { runCatching { FeatureBridge.finishRequest(this@FeaturesActivity, id, "declined", "Cancelled on phone") } }
                message("Active request cancelled.")
            }
        }
        label("Camera, microphone and screenshot", 20f)
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
        label("Location and geofences", 20f)
        button("Start sharing location") { consent("Share your GPS location while monitoring is on? An ongoing Android location notification is shown. Stop location or Stop monitoring ends sharing.") { startLocation() } }
        button("Stop location sharing") { FeatureBridge.setLocationApproved(this, false); LocationTracker.stopTracking(); refresh() }
        button("Add geofence") { addGeofence() }
        button("View / remove geofences") { listGeofences() }
        button("Restore backed-up geofences") { restoreGeofences() }
        label("Scans, reports and settings", 20f)
        button("Scan and share nearby Wi-Fi / Bluetooth") { consent("Run one nearby Wi-Fi and Bluetooth discovery scan and upload its results? Enable Location, Wi-Fi and Bluetooth first.") { scanEnvironment() } }
        button("Run local security audit") { audit() }
        button("Create settings backup") { backup() }
        button("Restore settings backup") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 42) }
        label("Shared files", 20f)
        button("Select a file to share") { consent("Select one document with Android's file picker and upload it to your dashboard? Only the selected file is copied, up to 4 MiB.") { pickFile() } }
        button("Choose / browse a folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 45)
        }
        button("View / export / delete local files") { showFiles() }
        button("Clear pending uploads") {
            AlertDialog.Builder(this).setTitle("Clear pending uploads?").setMessage("This deletes unsent tool reports and uploads from the phone queue. Local saved files and cloud files remain.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> scope.launch(Dispatchers.IO) { FeatureBridge.clearPending(this@FeaturesActivity) } }.show()
        }
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
            scope.launch(Dispatchers.IO) {
                runCatching { FeatureBridge.finishRequest(this@FeaturesActivity, id, "failed", "Monitoring not active") }
            }
            return
        }
        if (requestId != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { FeatureBridge.finishRequest(this@FeaturesActivity, id, "failed", "Phone busy with another request") }
            }
            return
        }
        requestId = id
        when (action) {
            "request_photo" -> takePhoto(id)
            "request_screenshot" -> screenshot(id)
            "request_audio" -> startAudio(id)
            "request_location" -> startLocation(id)
            "request_scan" -> scanEnvironment(id)
            "request_audit" -> audit(id)
            "request_backup" -> backup(id)
            "request_files" -> pickFile()
            else -> scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        FeatureBridge.queueEvent(this@FeaturesActivity, JSONObject()
                            .put("type", "device_status").put("timestamp", Instant.now().toString())
                            .put("monitoring", CoreService.isRunning)
                            .put("location", LocationTracker.isTracking.value)
                            .put("pending_uploads", FeatureBridge.pendingCount(this@FeaturesActivity)))
                    }
                    complete("Phone status queued for upload.", id)
                } catch (e: Exception) { fail(e.message ?: "Status upload failed", id) }
            }
        }
    }

    private fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, 12, 0, 12); this@FeaturesActivity.layout.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        layout.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); status.text = text }
    private fun refresh() {
        if (!CoreService.isSyncReady && recording) { audio?.stopRecording(); recording = false }
        if (::status.isInitialized) status.text = "Monitoring: ${CoreService.isRunning}\nLocation: ${LocationTracker.isTracking.value}\nMicrophone: ${if (recording) "recording" else "off"}\n${FeatureBridge.status}\nPending tool uploads: ${FeatureBridge.pendingCount(this)}\nUnsent items kept aside: ${FeatureBridge.failedCount(this)}\nPending dashboard requests: ${FeatureBridge.pendingRequests(this).size}"
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
    private fun complete(detail: String, id: String? = requestId) {
        if (id == null) return
        if (requestId == id) requestId = null
        scope.launch(Dispatchers.IO) { runCatching { FeatureBridge.finishRequest(this@FeaturesActivity, id, "completed", detail) } }
    }
    private fun fail(text: String, id: String? = requestId) {
        message(text); recording = false
        if (requestId == id) requestId = null
        if (id != null) scope.launch(Dispatchers.IO) { runCatching { FeatureBridge.finishRequest(this@FeaturesActivity, id, "failed", text) } }
    }
    private fun share(file: File, mime: String, kind: String, deleteTemp: Boolean = true, remote: String? = null) {
        // Saving a completed microphone chunk must survive the Activity being closed.
        val app = applicationContext
        if (requestId == remote) requestId = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FeatureBridge.queueFile(app, file, file.name, mime, kind)
                if (remote != null) FeatureBridge.finishRequest(app, remote, "completed", "$kind output saved to phone upload queue. Confirm delivery in dashboard Files.")
                withContext(Dispatchers.Main) { if (!isDestroyed) message("$kind saved and queued. Start monitoring to upload.") }
            } catch (e: Exception) {
                if (remote != null) runCatching { FeatureBridge.finishRequest(app, remote, "failed", "Output could not be queued") }
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
            if (error != null) { fail("Enable precise location and GPS, then try again."); return@launch }
            try {
                FeatureBridge.setLocationApproved(this@FeaturesActivity, true)
                LocationTracker.startTracking()
                complete("Location sharing started on phone. Await a fresh GPS fix in the dashboard.", remote)
                message("Location sharing started; waiting for an accurate GPS fix.")
            } catch (e: Exception) { FeatureBridge.setLocationApproved(this@FeaturesActivity, false); fail(e.message ?: "Location unavailable") }
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
            scanner?.close(); scanner = EnvironmentScanner(this)
            message("Scanning; this may take 30 seconds…")
            scanner?.scan({ result ->
                result.put("type", "environment_scan").put("timestamp", Instant.now().toString())
                scope.launch {
                    try { withContext(Dispatchers.IO) { FeatureBridge.queueEvent(this@FeaturesActivity, result) }; complete("Nearby scan queued for upload.", remote); showReport("Nearby scan", result, "document", false) }
                    catch (e: Exception) { fail(e.message ?: "Could not queue scan") }
                }
            }, { fail(it.message ?: "Scan unavailable. Enable Wi-Fi, Bluetooth and Location.", remote) })
        }
    }
    private fun audit(remote: String? = null) {
        scope.launch {
            try {
                message("Scanning this app's own data for possible exposed secrets…")
                val report = SecurityAuditTool(this@FeaturesActivity).scanAppDirectories()
                showReport("Local security audit (redacted)", report, "audit", true, remote)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("Audit failed: ${e.javaClass.simpleName}") }
        }
    }
    private fun backup(remote: String? = null) {
        scope.launch {
            val report = SettingsBackupTool(this@FeaturesActivity).backupAppSettings(packageName, listOf(ScreenMonitorService.PREFS_NAME))
            val packages = getSharedPreferences(ScreenMonitorService.PREFS_NAME, 0).getStringSet(ScreenMonitorService.KEY_ALLOWED_PACKAGES, emptySet()).orEmpty().sorted()
            val geofences = JSONArray(LocationTracker.registeredGeofences().map {
                JSONObject().put("id", it.id).put("latitude", it.latitude).put("longitude", it.longitude).put("radius_meters", it.radiusMeters)
            })
            val allApps = getSharedPreferences(ScreenMonitorService.PREFS_NAME, 0).getBoolean(ScreenMonitorService.KEY_ALL_APPS, false)
            report.put("format", "system-health-settings-v1").put("approved_packages", JSONArray(packages)).put("all_apps", allApps).put("geofences", geofences)
            // No enrollment credentials, activation state, or private settings of other apps.
            report.getJSONObject("settings").optJSONObject(ScreenMonitorService.PREFS_NAME)?.remove(ScreenMonitorService.KEY_ENABLED)
            showReport("Settings backup", report, "backup", true, remote)
        }
    }
    private fun showReport(title: String, report: JSONObject, kind: String, shareable: Boolean, remote: String? = null) {
        val view = TextView(this).apply { text = report.toString(2); setPadding(24,24,24,24); setTextIsSelectable(true) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) }).setNegativeButton("Close") { _, _ -> if (remote != null) { complete("Report reviewed locally; no output shared.", remote) } }
        val temp = File(cacheDir, "${kind}-${System.currentTimeMillis()}.json").apply { writeText(report.toString(2)) }
        dialog.setNeutralButton("Export") { _, _ -> export(temp, "application/json", temp.name); complete("Report reviewed locally; chose export instead of upload.", remote) }
        if (shareable) dialog.setPositiveButton("Share report") { _, _ ->
            if (CoreService.isSyncReady) share(temp, "application/json", kind, false, remote) else message("Start monitoring before sharing the report.")
        }
        dialog.show()
    }
    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 41)
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
        if (code == 43) { if (result == RESULT_OK) startLocation(requestId) else fail("GPS setup cancelled."); return }
        if (result != RESULT_OK || data == null) { if (code == 40 || code == 41) fail("Capture or file selection cancelled."); return }
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
        if (code == 42) CoreService.disableAppCapture(this)
        scope.launch {
            try {
                if (code == 44) {
                    val source = exportFile ?: error("Export source missing")
                    withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Cannot write selected destination") }
                    message("File exported."); exportFile = null; return@launch
                }
                val source = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { readBounded(it) } ?: error("Cannot read selected file")
                    require(bytes.isNotEmpty() && bytes.size <= FeatureBridge.MAX_FILE) { "Choose a file up to 4 MiB" }
                    if (code == 42) { restore(JSONObject(String(bytes, Charsets.UTF_8))); null }
                    else {
                        var name = "document-${System.currentTimeMillis()}"
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
                        val file = File(cacheDir, "document-${System.currentTimeMillis()}.tmp").apply { writeBytes(bytes) }
                        try { FeatureBridge.queueFile(this@FeaturesActivity, file, name, contentResolver.getType(uri)?.takeIf { Regex("[\\w.+-]+/[\\w.+-]+").matches(it) } ?: "application/octet-stream", "document") }
                        finally { file.delete() }
                        file
                    }
                }
                if (code == 42) message("App sharing selection restored. Sharing stays off until you approve it. Tap Restore backed-up geofences to register saved boundaries.")
                else if (source != null) { complete("Selected document queued for upload."); message("Selected file queued for upload.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message ?: "File operation failed") }
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
    private fun restore(report: JSONObject) {
        require(report.getString("format") == "system-health-settings-v1" && report.getString("package_name") == packageName) { "Choose a System Health settings backup" }
        val array = report.getJSONArray("approved_packages"); require(array.length() <= 100)
        val packages = (0 until array.length()).map { array.getString(it) }.toSet()
        require(packages.all { Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) && it != packageName && it != "android" && (!it.startsWith("com.android.") || it == "com.android.chrome") }) { "Backup contains invalid app IDs" }
        val fences = report.optJSONArray("geofences") ?: JSONArray()
        require(fences.length() <= 100) { "Too many geofences in backup" }
        for (i in 0 until fences.length()) decodeFence(fences.getJSONObject(i))
        check(getSharedPreferences("feature_options", 0).edit().putString("geofence_restore", fences.toString()).commit())
        // Restore selection only. Consent and runtime permissions are never restored from a file.
        getSharedPreferences(ScreenMonitorService.PREFS_NAME, 0).edit()
            .putStringSet(ScreenMonitorService.KEY_ALLOWED_PACKAGES, packages)
            .putBoolean(ScreenMonitorService.KEY_ALL_APPS, report.optBoolean("all_apps", false))
            .putBoolean(ScreenMonitorService.KEY_ENABLED, false).commit()
    }
    private fun decodeFence(json: JSONObject): FleetGeofence {
        val id = json.getString("id"); val radius = json.getDouble("radius_meters").toFloat()
        require(id.isNotBlank() && id.length <= 80 && radius in 100f..10000f) { "Invalid geofence in backup" }
        return FleetGeofence(id, json.getDouble("latitude"), json.getDouble("longitude"), radius)
    }
    private fun restoreGeofences() {
        if (!CoreService.isSyncReady) { message("Start monitoring first."); return }
        if (!LocationTracker.hasFineLocationPermission() || !LocationTracker.hasBackgroundLocationPermission()) {
            message("Grant precise and Allow all the time location permission using Add geofence first, then restore."); return
        }
        val array = JSONArray(getSharedPreferences("feature_options", 0).getString("geofence_restore", "[]"))
        if (array.length() == 0) { message("No geofence definitions waiting to restore."); return }
        val fences = (0 until array.length()).map { decodeFence(array.getJSONObject(it)) }
        AlertDialog.Builder(this).setTitle("Register ${fences.size} backed-up geofences?")
            .setMessage("These saved boundaries will be merged by name with existing geofences. Sharing requires Start location sharing.")
            .setNegativeButton("Cancel", null).setPositiveButton("Register") { _, _ -> scope.launch {
                LocationTracker.addGeofences(fences).fold({
                    getSharedPreferences("feature_options", 0).edit().remove("geofence_restore").apply(); message("Geofences restored.")
                }, { fail(it.message ?: "Geofence restore failed") })
            } }.show()
    }
    private fun runPendingRequests() {
        val requests = FeatureBridge.pendingRequests(this)
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
                    "request_audit" -> audit(id)
                    "request_backup" -> backup(id)
                    "request_files" -> pickFile()
                    else -> scope.launch {
                        try {
                            withContext(Dispatchers.IO) { FeatureBridge.queueEvent(this@FeaturesActivity, JSONObject().put("type", "device_status").put("timestamp", Instant.now().toString()).put("monitoring", CoreService.isRunning).put("location", LocationTracker.isTracking.value).put("pending_uploads", FeatureBridge.pendingCount(this@FeaturesActivity))) }
                            complete("Phone status queued for upload.")
                        } catch (e: Exception) { fail(e.message ?: "Status upload failed") }
                    }
                }
            }.setNegativeButton("Close", null).show()
    }
    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() {
        handler.removeCallbacks(refresher); audio?.stopRecording(); recording = false
        player?.release(); player = null
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); camera?.close(); audio?.close(); scanner?.close(); player?.release(); scope.cancel()
        super.onDestroy()
    }
}
