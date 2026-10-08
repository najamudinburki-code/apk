package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
 * The phone's home screen. It answers five questions in order — is this phone connected, is
 * monitoring on, is anything waiting to upload, does something need me, what do I do next — and puts
 * one action forward for the situation it found. Live state is read off the main thread because the
 * service, the preferences and the upload queue all touch Settings and disk, and the page is redrawn
 * only when a value actually changes, so a TalkBack reader is not re-announcing the same words every
 * two seconds.
 */
class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var kit: ScreenKit
    private lateinit var headline: TextView
    private lateinit var subhead: TextView
    private lateinit var attentionCard: ScreenKit.Card
    private lateinit var statusCard: ScreenKit.Card
    private lateinit var primaryButton: Button
    private lateinit var stopMonitoringButton: Button
    private lateinit var stopSharingButton: Button
    private lateinit var statusView: TextView
    private var enrollmentJob: Job? = null
    private var connectionStatus = ""
    private var visible = false
    private var diagnosticJob: Job? = null
    private var statusJob: Job? = null
    private var applied: HomePlan? = null
    private var appliedDetail = ""
    private var primaryAction: HomeAction? = null
    private val homeHandler = Handler(Looper.getMainLooper())
    private val homeTicker = object : Runnable {
        override fun run() { refreshStatus(); homeHandler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = ScreenKit(this)
        restoreSections(savedInstanceState)
        setContentView(kit.scrollContent())
        kit.eyebrow("SYSTEM HEALTH · ${BuildConfig.VERSION_NAME}")
        headline = kit.screenTitle("Checking this phone's status…")

        // What the app shares belongs above the controls, not under them: it is the sentence the
        // owner is agreeing to.
        kit.paragraph("Health monitoring shares battery, uptime and device details every five " +
            "minutes. Other tools share only after you enable them, and every capture of the camera, " +
            "microphone or screen shows up here and in Android's own indicators.")

        val controlCard = kit.card(null, ScreenKit.Tone.EMPHASIS)
        subhead = controlCard.note("Reading what this phone is doing…")
        primaryButton = controlCard.button("", ScreenKit.Weight.PRIMARY) { runPrimaryAction() }
        stopMonitoringButton = controlCard.button("Stop System Health Monitor",
            ScreenKit.Weight.STOP) { CoreService.stop(this); applied = null; refreshStatus() }
        stopSharingButton = controlCard.button("Stop sharing screen text and notifications",
            ScreenKit.Weight.STOP) { CoreService.disableAppCapture(this); applied = null; refreshStatus() }

        attentionCard = kit.card("Needs your attention", ScreenKit.Tone.ALERT)
        statusCard = kit.card("What is happening on this phone")

        val goTo = kit.card("Where to go from here")
        goTo.button("Open device tools") {
            startActivity(Intent(this, FeaturesActivity::class.java))
        }
        goTo.underButton("Camera, microphone, screenshots, live view, location, shared files and the " +
            "requests your dashboard has sent.")
        goTo.button("App text and notification sharing") { configureAppCapture() }
        goTo.underButton("Choose whether the text on screen and new message notifications are shared.")
        goTo.button("Guided setup and permissions") {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
        }
        goTo.underButton("See what this app may do. Steps this phone has already finished are skipped.")

        kit.expandable("diagnostics", "Show details and diagnostics", "Hide details and diagnostics") { details ->
            details.note("For checking what this phone is doing, or for reading out to the person who " +
                "looks after your dashboard. Nothing here changes what is shared.")
            details.button("Check the connection now") { checkConnection() }
            details.button("Refresh this screen") { applied = null; refreshStatus() }
            details.button("Connect automatically") {
                AutomaticEnrollment.requestAutoStart(this); connectAutomatically()
            }
            details.button("Advanced connection settings") { configureDevice() }
            details.button("Accessibility settings") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            details.button("Notification access settings") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            details.button("Reconnect enabled readers") { reconnectReaders() }
            statusView = TextView(this).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.themeColor(android.R.attr.textColorSecondary, 0xFF8A8F99.toInt()))
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            details.content(statusView)
        }
    }

    private fun reconnectReaders() {
        val readers = listOf(
            ServiceManager.ServiceSpec(ComponentName(this, AccessibilityHelperService::class.java),
                ServiceManager.Kind.ACCESSIBILITY),
            ServiceManager.ServiceSpec(ComponentName(this, ScreenMonitorService::class.java),
                ServiceManager.Kind.ACCESSIBILITY),
            ServiceManager.ServiceSpec(ComponentName(this, NotificationReaderService::class.java),
                ServiceManager.Kind.NOTIFICATION_LISTENER)
        )
        ServiceManager(this, readers).enqueueHealthCheck()
        toast("Reader check queued. Android manages accessibility connections; enable the readers in Settings.")
        refreshStatus()
    }

    /** The single action card button, resolved against the state that was drawn. */
    private fun runPrimaryAction() {
        when (primaryAction) {
            HomeAction.CONNECT -> {
                AutomaticEnrollment.requestAutoStart(this); connectAutomatically()
            }
            HomeAction.NOTIFICATIONS -> requestNotifications()
            HomeAction.CHECK -> checkConnection()
            HomeAction.START -> requestMonitoringStart()
            null -> Unit
        }
    }

    private fun checkConnection() {
        if (!visible || diagnosticJob?.isActive == true) return
        diagnosticJob = activityScope.launch {
            subhead.text = "Checking the server and this phone's enrollment…"
            try {
                val result = ConnectionDiagnostics.probe(this@MainActivity)
                connectionStatus = result.message
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connectionStatus = "Connection check failed. Check internet and retry." }
            finally { applied = null; refreshStatus() }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        kit.sectionStates.forEach { (key, open) -> state.putBoolean("section.$key", open) }
        super.onSaveInstanceState(state)
    }

    private fun restoreSections(state: Bundle?) {
        state?.keySet()?.forEach { key ->
            if (key.startsWith("section.")) kit.sectionStates[key.removePrefix("section.")] = state.getBoolean(key)
        }
    }

    private fun configureAppCapture() {
        if (!CoreService.isSyncReady) {
            toast("Start monitoring first, then choose which apps to share from.")
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
                    applied = null
                    refreshStatus()
                    if (result.state == "approved") {
                        if (visible && AutomaticEnrollment.consumeAutoStart(this@MainActivity)) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                                PermissionSetupActivity.notificationSetupShown(this@MainActivity)) {
                                connectionStatus = "Connected. Enable status notifications in Permission setup, then tap Start."
                                applied = null
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
                    applied = null
                    refreshStatus()
                } catch (_: Exception) {
                    connectionStatus = "Connecting to your dashboard. Waiting for internet or the server to wake up."
                    applied = null
                    refreshStatus()
                }
                delay(10_000)
            }
        }
    }

    private fun requestMonitoringStart() {
        if (!visible) return
        if (!notificationsReady()) {
            requestNotifications()
            return
        }
        startMonitoring()
    }

    /** Android needs both the runtime permission and the app's own notification switch, and monitoring
     * refuses to run without the activity notice it must keep on screen. Take the owner to whichever
     * is missing instead of reporting a start that never happened. */
    private fun notificationsReady(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            toast("Turn on notifications for this app to start monitoring.")
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun approveAllApps() {
        AlertDialog.Builder(this).setTitle("Share from all supported apps?")
            .setMessage("Visible screen text and notification contents from all supported apps, including newly installed apps, will be sent to your enrolled server and stored in its history. No package names are needed. The app's own screen and Android system components are excluded; passwords stay redacted. Approve only a device you are authorized to monitor. A sharing notification stays visible and Stop ends sharing. This app selection is saved, but approval expires when the app process ends. Enable one screen reader and Notification Reader in Android Settings.")
            .setNegativeButton("Cancel", null).setPositiveButton("Approve sharing") { _, _ ->
                if (CoreService.enableAllAppCapture(this)) {
                    toast("Automatic app sharing approved. New apps are included without entering names.")
                    applied = null
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
                        if (CoreService.enableAppCapture(this, selected)) {
                            toast("Sharing approved. Enable one screen reader and Notification Reader in Settings.")
                            applied = null
                            refreshStatus()
                        } else toast("Select at least one app. Start monitoring and enable app notifications first.")
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
        applied = null
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
                        applied = null
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

    private data class Snapshot(val plan: HomePlan, val detail: String)

    private fun refreshStatus() {
        if (!::statusView.isInitialized || statusJob?.isActive == true) return
        statusJob = activityScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readStatus() }
            if (!visible) return@launch
            // A running connection check owns the headline line, so the ticker stands down while it
            // works; the check re-renders when it finishes. Otherwise the page is redrawn only when a
            // value really changed, which keeps a screen reader from repeating the same words.
            if (diagnosticJob?.isActive != true) {
                if (applied != snapshot.plan) {
                    render(snapshot.plan)
                    applied = snapshot.plan
                }
            }
            if (appliedDetail != snapshot.detail) {
                statusView.text = snapshot.detail
                appliedDetail = snapshot.detail
            }
        }
    }

    private fun render(plan: HomePlan) {
        headline.text = plan.headline
        subhead.text = plan.subhead
        attentionCard.replaceLines(plan.alerts)
        statusCard.replaceFacts(plan.facts)
        primaryAction = plan.primary
        primaryButton.visibility = if (plan.primary == null) View.GONE else View.VISIBLE
        plan.primary?.let { primaryButton.text = it.label }
        stopMonitoringButton.visibility = if (plan.monitorStopVisible) View.VISIBLE else View.GONE
        stopSharingButton.visibility = if (plan.sharingStopVisible) View.VISIBLE else View.GONE
    }

    private fun readStatus(): Snapshot {
        val notices = notificationsReady()
        val upload = ConnectionDiagnostics.lastUpload(this)
        val verified = ConnectionDiagnostics.verified(this)
        val update = UpdateAwareness.available(this)
        val plan = HomeOverview.plan(HomeSignals(
            enrolled = SyncSettingsStore.hasConfiguration(this),
            notificationsAllowed = notices,
            serverVerified = verified,
            monitoring = CoreService.isRunning,
            everUploaded = upload > 0,
            lastUploadLabel = ConnectionDiagnostics.timestamp(upload),
            serverMessage = ConnectionDiagnostics.lastCheckMessage(this),
            pendingUploads = FeatureBridge.pendingCount(this),
            unsentItems = FeatureBridge.failedCount(this),
            pendingRequests = FeatureBridge.pendingRequests(this).size,
            appTextSharing = getSharedPreferences(ScreenMonitorService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(ScreenMonitorService.KEY_ENABLED, false),
            liveStreaming = LiveStreamBridge.isStreaming(),
            rulesSummary = RemotePolicy.summary(this),
            scheduledReports = ReportSchedule.summary(this),
            updateAvailable = update?.version,
            updateInstalled = update?.installed ?: BuildConfig.VERSION_NAME,
            updateUrl = update?.url ?: "",
            serviceStatus = CoreService.status(this)
        ))
        return Snapshot(plan, detail())
    }

    /** The technical block. It stays available because the person reading a fault out needs the exact
     * words, but it is no longer the first thing the owner is shown. */
    private fun detail(): String {
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
        return buildString {
            append(CoreService.status(this@MainActivity)).append('\n')
            if (connectionStatus.isNotBlank()) append("$connectionStatus\n")
            append("Sync ready: ${CoreService.isSyncReady}\n")
            val selection = getSharedPreferences(ScreenMonitorService.PREFS_NAME, MODE_PRIVATE)
            append("App selection: ")
            append(if (selection.getBoolean(ScreenMonitorService.KEY_ALL_APPS, true))
                "all supported apps automatically\n" else "chosen apps\n")
            append("${FeatureBridge.status}\n")
            append("Tool uploads pending: ${FeatureBridge.pendingCount(this@MainActivity)}\n")
            append("Unsent items kept aside: ${FeatureBridge.failedCount(this@MainActivity)}\n")
            for (spec in specs) {
                val state = manager.checkServiceStatus(spec)
                append("${spec.component.shortClassName.substringAfterLast('.')}: ")
                append(if (!state.available) "unavailable" else if (!state.accessGranted)
                    "needs Settings approval" else state.runtime.name.lowercase())
                append('\n')
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
