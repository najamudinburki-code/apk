package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.systemmanagement.ServiceManager
import com.example.utility.ScreenMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's home screen: what is connected, what is running, and the controls to change either.
 * Live status is read off the main thread because service state, preferences and the upload queue
 * all touch Settings and disk.
 */
class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView
    private var enrollmentJob: Job? = null
    private var connectionStatus = ""
    private var visible = false
    private lateinit var readyView: TextView
    private lateinit var advancedView: LinearLayout
    private var diagnosticJob: Job? = null
    private var statusJob: Job? = null
    private val homeHandler = Handler(Looper.getMainLooper())
    private val homeTicker = object : Runnable {
        override fun run() { refreshStatus(); homeHandler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String, size: Float = 17f, parent: LinearLayout = root): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 12, 0, 12); parent.addView(this)
        }
        fun button(text: String, parent: LinearLayout = root, action: () -> Unit): Button = Button(this).apply {
            this.text = text; setOnClickListener { action() }; parent.addView(this)
        }
        label("SYSTEM HEALTH · ${BuildConfig.VERSION_NAME}", 15f)
        label("Your phone, connected", 27f)
        readyView = label("Checking status…", 18f)
        readyView.setPadding(28, 28, 28, 28)
        readyView.background = GradientDrawable().apply {
            setColor(themeColor(android.R.attr.colorBackground, 0xFFF5F7FA.toInt()))
            cornerRadius = 16f
            setStroke(2, themeColor(android.R.attr.textColorSecondary, 0xFF8899AA.toInt()))
        }
        label("Health monitoring shares battery, uptime and device details every five minutes. Other tools share only after you enable them.")
        button("Check connection") { checkConnection() }
        button("Device tools, location and shared files") { startActivity(Intent(this, FeaturesActivity::class.java)) }
        button("App text and notification sharing") { configureAppCapture() }
        button("Start System Health Monitor") {
            if (SyncSettingsStore.hasConfiguration(this)) requestMonitoringStart()
            else { AutomaticEnrollment.requestAutoStart(this); connectAutomatically() }
        }
        button("Stop System Health Monitor") { CoreService.stop(this); refreshStatus() }
        button("Stop app text and notification sharing") { CoreService.disableAppCapture(this); refreshStatus() }
        button("Guided setup / permissions") { startActivity(Intent(this, PermissionSetupActivity::class.java)) }
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (savedInstanceState?.getBoolean("advanced", false) == true) android.view.View.VISIBLE else android.view.View.GONE
        }
        button(if (advanced.visibility == android.view.View.VISIBLE) "Hide Advanced settings" else "Advanced settings") {
            advanced.visibility = if (advanced.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
            (root.getChildAt(root.indexOfChild(advanced) - 1) as? Button)?.text = if (advanced.visibility == android.view.View.VISIBLE) "Hide Advanced settings" else "Advanced settings"
        }
        root.addView(advanced)
        advancedView = advanced
        label("Connection and reader diagnostics", 21f, advanced)
        statusView = label("", parent = advanced)
        button("Connect automatically", advanced) { AutomaticEnrollment.requestAutoStart(this); connectAutomatically() }
        button("Advanced connection settings", advanced) { configureDevice() }
        button("Accessibility settings", advanced) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        button("Notification access settings", advanced) { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        button("Reconnect enabled readers", advanced) {
            val readers = listOf(
                ServiceManager.ServiceSpec(ComponentName(this, AccessibilityHelperService::class.java), ServiceManager.Kind.ACCESSIBILITY),
                ServiceManager.ServiceSpec(ComponentName(this, ScreenMonitorService::class.java), ServiceManager.Kind.ACCESSIBILITY),
                ServiceManager.ServiceSpec(ComponentName(this, NotificationReaderService::class.java), ServiceManager.Kind.NOTIFICATION_LISTENER)
            )
            ServiceManager(this, readers).enqueueHealthCheck()
            toast("Reader check queued. Android manages accessibility connections; enable the readers in Settings.")
            refreshStatus()
        }
        button("Refresh status", advanced) { refreshStatus() }
    }

    private fun checkConnection() {
        if (!visible || diagnosticJob?.isActive == true) return
        diagnosticJob = activityScope.launch {
            readyView.text = "Checking server and phone enrollment…"
            try {
                val result = ConnectionDiagnostics.probe(this@MainActivity)
                connectionStatus = result.message
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connectionStatus = "Connection check failed. Check internet and retry." }
            finally { refreshStatus() }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("advanced", ::advancedView.isInitialized && advancedView.visibility == android.view.View.VISIBLE)
        super.onSaveInstanceState(state)
    }

    private fun configureAppCapture() {
        if (!CoreService.isSyncReady) {
            toast("Configure and start System Health monitoring first, then refresh status.")
            return
        }
        val preferences = getSharedPreferences(ScreenMonitorService.PREFS_NAME, MODE_PRIVATE)
        var scope = if (preferences.getBoolean(ScreenMonitorService.KEY_ALL_APPS, true)) 0 else 1
        val dialog = AlertDialog.Builder(this)
            .setTitle("App text and notification sharing")
            .setSingleChoiceItems(arrayOf("All supported apps automatically", "Choose apps by name"), scope) { _, which -> scope = which }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                if (scope == 0) approveAllApps() else chooseInstalledApps()
            }
            .create()
        dialog.show()
    }

    override fun onPause() {
        visible = false
        enrollmentJob?.cancel()
        enrollmentJob = null
        diagnosticJob?.cancel(); diagnosticJob = null
        statusJob?.cancel(); statusJob = null
        homeHandler.removeCallbacks(homeTicker)
        super.onPause()
    }

    private fun connectAutomatically() {
        if (!visible || enrollmentJob?.isActive == true) return
        enrollmentJob = activityScope.launch {
            while (visible) {
                try {
                    val result = AutomaticEnrollment.check(this@MainActivity)
                    connectionStatus = result.message + if (result.deviceId.isNotBlank()) "\nPhone ID: ${result.deviceId}" else ""
                    refreshStatus()
                    if (result.state == "approved") {
                        if (visible && AutomaticEnrollment.consumeAutoStart(this@MainActivity)) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                                PermissionSetupActivity.notificationSetupShown(this@MainActivity)) {
                                connectionStatus = "Connected. Enable status notifications in Permission setup, then tap Start."
                                refreshStatus()
                            } else requestMonitoringStart()
                        }
                        checkConnection()
                        return@launch
                    }
                    if (result.state == "rejected" || result.state == "disabled") return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IllegalStateException) {
                    connectionStatus = error.message ?: "Automatic connection will retry."
                    refreshStatus()
                } catch (_: Exception) {
                    connectionStatus = "Connecting to your dashboard. Waiting for internet or the server to wake up."
                    refreshStatus()
                }
                delay(10_000)
            }
        }
    }

    private fun requestMonitoringStart() {
        if (!visible) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        // The app's own notification switch lives outside the runtime permission, and monitoring
        // stops itself rather than run without the activity notice it must keep on screen. Send the
        // owner to that switch instead of reporting a start that never happened.
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            toast("Turn on notifications for this app to start monitoring.")
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            return
        }
        startMonitoring()
    }

    private fun approveAllApps() {
        AlertDialog.Builder(this).setTitle("Share from all supported apps?")
            .setMessage("Visible screen text and notification contents from all supported apps, including newly installed apps, will be sent to your enrolled server and stored in its history. No package names are needed. The app's own screen and Android system components are excluded; passwords stay redacted. Approve only a device you are authorized to monitor. A sharing notification stays visible and Stop ends sharing. This app selection is saved, but approval expires when the app process ends. Enable one screen reader and Notification Reader in Android Settings.")
            .setNegativeButton("Cancel", null).setPositiveButton("Approve sharing") { _, _ ->
                if (CoreService.enableAllAppCapture(this)) {
                    toast("Automatic app sharing approved. New apps are included without entering names.")
                    refreshStatus()
                } else toast("Start monitoring and enable this app's notifications before approving sharing.")
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun chooseInstalledApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { AppCapturePolicy.isEligible(packageName, it.packageName) }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        if (apps.isEmpty()) { toast("No selectable apps found. You can use automatic app sharing instead."); return }
        val previous = getSharedPreferences(ScreenMonitorService.PREFS_NAME, MODE_PRIVATE)
            .getStringSet(ScreenMonitorService.KEY_ALLOWED_PACKAGES, emptySet()).orEmpty()
        val checked = BooleanArray(apps.size) { apps[it].packageName in previous }
        AlertDialog.Builder(this).setTitle("Choose apps to share")
            .setMultiChoiceItems(apps.map { packageManager.getApplicationLabel(it).toString() }.toTypedArray(), checked) { _, which, selected -> checked[which] = selected }
            .setNegativeButton("Cancel", null).setPositiveButton("Review selection") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }.toSet()
                val labels = apps.filterIndexed { i, _ -> checked[i] }.map { packageManager.getApplicationLabel(it).toString() }
                AlertDialog.Builder(this).setTitle("Approve app text and notifications?")
                    .setMessage("Share visible screen text and notifications from: ${labels.joinToString()}. Password fields are redacted. An ongoing notice stays visible. Stop ends sharing; consent expires when the app process ends.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Approve sharing") { _, _ ->
                        if (CoreService.enableAppCapture(this, selected)) { toast("Sharing approved. Enable one screen reader and Notification Reader in Settings."); refreshStatus() }
                        else toast("Select at least one app. Start monitoring and enable app notifications first.")
                    }.show()
            }.show()
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: android.content.ActivityNotFoundException) {
            toast("Open the corresponding access page in your phone's Settings app.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        try {
            CoreService.start(this)
            toast("Starting monitoring. Check the ongoing notification.")
        } catch (_: RuntimeException) {
            toast("Connect automatically and enable notifications before starting.")
        }
        refreshStatus()
    }

    private fun configureDevice() {
        if (CoreService.isRunning || CoreService.isMonitoringEnabled(this)) {
            toast("Stop monitoring before changing configuration.")
            return
        }
        enrollmentJob?.cancel()
        enrollmentJob = null
        val url = EditText(this).apply {
            hint = if (BuildConfig.DEBUG) "Server URL (HTTPS or local HTTP)" else "HTTPS server URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(AutomaticEnrollment.SERVER_URL)
        }
        val deviceId = EditText(this).apply {
            hint = "Enrolled device ID"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val token = EditText(this).apply {
            hint = "Device enrollment token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(url)
            addView(deviceId)
            addView(token)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enrolled device configuration")
            .setMessage("Enter the device ID and token issued by your management backend.")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (CoreService.isRunning || CoreService.isMonitoringEnabled(this)) {
                    toast("Stop monitoring before saving configuration.")
                    return@setOnClickListener
                }
                val settings = try {
                    SyncSettingsStore.validate(
                        url.text.toString().trim(), deviceId.text.toString().trim(),
                        token.text.toString()
                    )
                } catch (_: IllegalArgumentException) {
                    toast("Enter a valid server URL, device ID, and enrollment token.")
                    return@setOnClickListener
                }
                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                activityScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SyncSettingsStore.save(this@MainActivity, settings)
                            AutomaticEnrollment.cancelAutoStart(this@MainActivity)
                        }
                        token.text.clear()
                        dialog.dismiss()
                        connectionStatus = "Manual connection settings saved."
                        toast("Configuration saved. Tap Start to enable monitoring.")
                        refreshStatus()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast("Configuration could not be saved. Please try again.")
                        saveButton.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        refreshStatus()
        CoreService.refreshNotification()
        if (PermissionSetupActivity.showOnFirstOpen(this)) return
        homeHandler.post(homeTicker)
        connectAutomatically()
    }

    private data class HomeStatus(val card: String, val detail: String)

    private fun refreshStatus() {
        if (!::statusView.isInitialized || statusJob?.isActive == true) return
        statusJob = activityScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readStatus() }
            if (!visible) return@launch
            // A running connection check owns the card, so the ticker must not overwrite its text.
            if (diagnosticJob?.isActive != true) readyView.text = snapshot.card
            statusView.text = snapshot.detail
        }
    }

    private fun readStatus(): HomeStatus {
        val notices = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val upload = ConnectionDiagnostics.lastUpload(this)
        val verified = ConnectionDiagnostics.verified(this)
        val update = UpdateAwareness.available(this)
        val card = buildString {
            append(ReadinessPolicy.title(SyncSettingsStore.hasConfiguration(this@MainActivity), notices, verified, CoreService.isRunning, upload > 0)).append("\n\n")
            append("Server: ").append(if (verified) "verified recently" else ConnectionDiagnostics.lastCheckMessage(this@MainActivity)).append("\n")
            append("Status notifications: ").append(if (notices) "allowed" else "enable in guided setup").append("\n")
            append("Monitoring: ").append(if (CoreService.isRunning) "active" else "stopped").append("\n")
            append("Last successful upload: ").append(ConnectionDiagnostics.timestamp(upload)).append("\n")
            append("Pending tool uploads: ").append(FeatureBridge.pendingCount(this@MainActivity))
            RemotePolicy.summary(this@MainActivity)?.let {
                append("\n\nDashboard rules on this phone: ").append(it)
            }
            if (update != null) append("\n\nUpdate available: version ${update.version} (this phone has ${update.installed}).").append(
                if (update.url.isNotBlank()) "\n${update.url}" else "\nAsk the dashboard owner for the new APK.")
        }
        val specs = listOf(
            ServiceManager.ServiceSpec(ComponentName(this, CoreService::class.java),
                ServiceManager.Kind.ORDINARY, foreground = true),
            ServiceManager.ServiceSpec(ComponentName(this, AccessibilityHelperService::class.java),
                ServiceManager.Kind.ACCESSIBILITY),
            ServiceManager.ServiceSpec(ComponentName(this, ScreenMonitorService::class.java),
                ServiceManager.Kind.ACCESSIBILITY),
            ServiceManager.ServiceSpec(ComponentName(this, NotificationReaderService::class.java),
                ServiceManager.Kind.NOTIFICATION_LISTENER)
        )
        val manager = ServiceManager(this, specs)
        val detail = buildString {
            append("\n${CoreService.status(this@MainActivity)}\n")
            if (connectionStatus.isNotBlank()) append("$connectionStatus\n")
            append("Sync ready: ${CoreService.isSyncReady}\n")
            val selection = getSharedPreferences(ScreenMonitorService.PREFS_NAME, MODE_PRIVATE)
            append("App selection: ")
            append(if (selection.getBoolean(ScreenMonitorService.KEY_ALL_APPS, true)) "all supported apps automatically\n" else "chosen apps\n")
            append("${FeatureBridge.status}\n")
            append("Tool uploads pending: ${FeatureBridge.pendingCount(this@MainActivity)}\n")
            for (spec in specs) {
                val state = manager.checkServiceStatus(spec)
                append("${spec.component.shortClassName.substringAfterLast('.')}: ")
                append(if (!state.available) "unavailable" else if (!state.accessGranted)
                    "needs Settings approval" else state.runtime.name.lowercase())
                append('\n')
            }
        }
        return HomeStatus(card, detail)
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
