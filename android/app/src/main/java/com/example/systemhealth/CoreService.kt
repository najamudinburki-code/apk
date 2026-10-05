package com.example.systemhealth

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.BatteryManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import com.example.systemmanagement.ServiceManager
import com.example.utility.sync.NetworkMonitor
import com.example.utility.sync.SyncManager
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
import org.json.JSONObject

class CoreService : Service() {

    private var foregroundStarted = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startupJob: Job? = null
    private var samplingJob: Job? = null
    private var syncManager: SyncManager? = null
    private var networkMonitor: NetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "System Health Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent system-health monitoring status"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !isMonitoringEnabled(this)) {
            updateStatus("Monitoring is stopped.")
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
            stopMonitoring()
            return START_NOT_STICKY
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val channelVisible = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notificationManager.getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
        if (!notificationPermission || !notificationManager.areNotificationsEnabled() || !channelVisible) {
            updateStatus("Monitoring stopped. Enable app and channel notifications, then tap Start.")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (!foregroundStarted) {
            try {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Select only specialUse; do not activate location/media/dataSync as keep-alive types.
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    startForeground(
                        NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
                NotificationPresentation.refresh(this)

                isRunning = true
                ServiceManager.onServiceConnected(this)
            } catch (exception: RuntimeException) {
                updateStatus("Android could not start monitoring. Open the app and try again.")
                Log.e(TAG, "Unable to start foreground monitoring", exception)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
                stopMonitoring()
                return START_NOT_STICKY
            }
        }

        startSyncIfNeeded()
        // START_STICKY requests recreation; Android does not guarantee uninterrupted execution.
        return START_STICKY
    }

    private fun startSyncIfNeeded() {
        if (syncManager != null || startupJob?.isActive == true) return
        startupJob = serviceScope.launch {
            try {
                val settings = withContext(Dispatchers.IO) {
                    SyncSettingsStore.load(this@CoreService)
                } ?: error("Device enrollment is not configured")

                // Ownership is assigned on main before starting network callbacks.
                val sync = SyncManager(
                    applicationContext, settings.serverUrl, settings.deviceId,
                    allowLocalHttp = BuildConfig.DEBUG
                ) { settings.deviceToken }
                syncManager = sync
                networkMonitor = NetworkMonitor(applicationContext, sync).also { it.start() }
                ParentalCapture.onJson = { payload ->
                    val session = ParentalCapture.session
                    val serialized = payload.toString()
                    serviceScope.launch {
                        if (session == ParentalCapture.session && isSyncReady) {
                            try {
                                sync.queueData(serialized)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w(TAG, "Approved app data could not be queued: ${error.javaClass.simpleName}")
                            }
                        }
                    }
                }
                isSyncReady = true
                FeatureBridge.start(this@CoreService)
                samplingJob = serviceScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            sync.queueData(healthPayload().toString())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w(TAG, "Health sample could not be queued: ${error.javaClass.simpleName}")
                        }
                        delay(5 * 60_000L)
                    }
                }
                updateStatus("Monitoring active. Check the dashboard to confirm server delivery.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Do not log enrollment credentials or payload contents.
                Log.e(TAG, "Sync initialization failed: ${error.javaClass.simpleName}")
                updateStatus("Sync could not start. Stop, check device configuration, then try again.")
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, false).apply()
                stopMonitoring()
            }
        }
    }

    // Identity comes from the enrolled socket; this contains system-health data only.
    private fun healthPayload(): JSONObject {
        val battery = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0)
        return JSONObject()
            .put("type", "system_health")
            .put("timestamp", utcTimestamp())
            .put("battery_percent", if (battery in 0..100) battery else JSONObject.NULL)
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("android_version", Build.VERSION.RELEASE)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("app_version", version.versionName ?: "unknown")
    }

    private fun stopSync() {
        FeatureBridge.stop(this)
        stopService(Intent(this, ScreenCaptureService::class.java))
        isSyncReady = false
        disableAppCapture(this)
        ParentalCapture.onJson = null
        startupJob?.cancel()
        startupJob = null
        val sampling = samplingJob
        samplingJob = null
        sampling?.cancel()
        try {
            networkMonitor?.close() // Main-thread-only API.
        } catch (error: RuntimeException) {
            Log.w(TAG, "Network cleanup failed: ${error.javaClass.simpleName}")
        } finally {
            networkMonitor = null
        }
        val sync = syncManager
        syncManager = null
        sync?.pauseUploads()
        if (sync != null) {
            // Finite cleanup outlives onDestroy without blocking its main thread.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sampling?.join()
                    sync.close()
                } catch (error: Exception) {
                    Log.w(TAG, "Sync cleanup failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CoreService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("System Health Monitor")
            .setContentText("Sharing health status with your enrolled server")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setGroup(NotificationPresentation.GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .build()
    }

    private fun stopMonitoring() {
        stopSync()
        isRunning = false
        ServiceManager.onServiceDisconnected(this)
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foregroundStarted = false
            NotificationPresentation.refresh(this)
        }
        stopSelf()
    }

    private fun updateStatus(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", message).apply()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        updateStatus("Android stopped monitoring. Open the app to start it again.")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        stopMonitoring()
    }

    override fun onDestroy() {
        stopSync()
        serviceScope.cancel()
        isRunning = false
        ServiceManager.onServiceDisconnected(this)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        NotificationPresentation.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CoreService"
        private const val CHANNEL_ID = "system_health_monitor"
        private const val NOTIFICATION_ID = 1001
        internal const val ACTION_STOP = "com.example.systemhealth.STOP_MONITORING"
        private const val PREFS = "system_health_settings"
        private const val KEY_ENABLED = "monitoring_enabled"

        // Service and receiver run in the same default process. Repeated starts are idempotent.
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isSyncReady: Boolean = false
            private set

        fun enableAppCapture(context: Context, packages: Set<String>): Boolean {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (!isRunning || !isSyncReady) return false
            val approved = packages.filter { AppCapturePolicy.isEligible(context.packageName, it) }.toSet()
            if (approved.isEmpty() || approved.size != packages.size) return false
            if (!ParentalCapture.enable(context, approved)) return false
            context.getSharedPreferences(ScreenMonitorService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(ScreenMonitorService.KEY_ALLOWED_PACKAGES, approved)
                .putBoolean(ScreenMonitorService.KEY_ALL_APPS, false)
                .putBoolean(ScreenMonitorService.KEY_ENABLED, true).apply()
            return true
        }

        fun enableAllAppCapture(context: Context): Boolean {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (!isRunning || !isSyncReady || !ParentalCapture.enableAllApps(context)) return false
            context.getSharedPreferences(ScreenMonitorService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(ScreenMonitorService.KEY_ALL_APPS, true)
                .putBoolean(ScreenMonitorService.KEY_ENABLED, true).apply()
            return true
        }

        fun disableAppCapture(context: Context) {
            ParentalCapture.disable(context)
            context.getSharedPreferences(ScreenMonitorService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(ScreenMonitorService.KEY_ENABLED, false).apply()
        }

        fun isMonitoringEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun status(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("status", "Monitoring is stopped.") ?: "Monitoring is stopped."

        fun start(context: Context) {
            check(SyncSettingsStore.hasConfiguration(context)) {
                "Configure an enrolled device first"
            }
            if (isRunning) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, true).putString("status", "Starting monitoring…").apply()
            val intent = Intent(context, CoreService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: RuntimeException) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, false)
                    .putString("status", "Android could not start monitoring. Open the app and try again.").apply()
                throw error
            }
        }

        fun stop(context: Context) {
            AutomaticEnrollment.cancelAutoStart(context)
            FeatureBridge.stop(context)
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            disableAppCapture(context)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).putString("status", "Monitoring is stopped.").apply()
            context.stopService(Intent(context, CoreService::class.java))
        }
    }
}

// Activity and original sample classes retain their existing qualified names.
class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView
    private var enrollmentJob: Job? = null
    private var connectionStatus = ""
    private var visible = false
    private lateinit var readyView: TextView
    private lateinit var advancedView: LinearLayout
    private var diagnosticJob: Job? = null
    private val homeHandler = Handler(Looper.getMainLooper())
    private val homeTicker = object : Runnable {
        override fun run() { refreshStatus(); homeHandler.postDelayed(this, 2000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String, size: Float = 16f, parent: LinearLayout = root): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 12, 0, 12); parent.addView(this)
        }
        fun button(text: String, parent: LinearLayout = root, action: () -> Unit): Button = Button(this).apply {
            this.text = text; setOnClickListener { action() }; parent.addView(this)
        }
        label("SYSTEM HEALTH · ${BuildConfig.VERSION_NAME}", 14f)
        label("Your phone, connected", 26f)
        readyView = label("Checking status…", 17f)
        readyView.setPadding(24, 24, 24, 24)
        readyView.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(236, 243, 250)); cornerRadius = 16f
            setStroke(1, android.graphics.Color.rgb(198, 213, 231))
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
        label("Connection and reader diagnostics", 20f, advanced)
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
        } else startMonitoring()
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
        NotificationPresentation.refresh(this)
        if (PermissionSetupActivity.showOnFirstOpen(this)) return
        homeHandler.post(homeTicker)
        connectAutomatically()
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        if (::readyView.isInitialized && diagnosticJob?.isActive != true) {
            val notices = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            val upload = ConnectionDiagnostics.lastUpload(this)
            val verified = ConnectionDiagnostics.verified(this)
            readyView.text = buildString {
                append(ReadinessPolicy.title(SyncSettingsStore.hasConfiguration(this@MainActivity), notices, verified, CoreService.isRunning, upload > 0)).append("\n\n")
                append("Server: ").append(if (verified) "verified recently" else ConnectionDiagnostics.lastCheckMessage(this@MainActivity)).append("\n")
                append("Status notifications: ").append(if (notices) "allowed" else "enable in guided setup").append("\n")
                append("Monitoring: ").append(if (CoreService.isRunning) "active" else "stopped").append("\n")
                append("Last successful upload: ").append(ConnectionDiagnostics.timestamp(upload)).append("\n")
                append("Pending tool uploads: ").append(FeatureBridge.pendingCount(this@MainActivity))
            }
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
        statusView.text = buildString {
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
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}

// Implement only the explicitly enabled accessibility feature here.
class HealthAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

// Implement notification-listener features only after the user enables notification access.
class HealthNotificationListenerService : NotificationListenerService()
