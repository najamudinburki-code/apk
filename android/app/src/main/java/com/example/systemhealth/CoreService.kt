package com.example.systemhealth

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.BatteryManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import org.json.JSONObject

class CoreService : Service() {

    private var foregroundStarted = false
    /** Foreground types Android accepted for this service; every re-post must reuse exactly these. */
    private var activeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startupJob: Job? = null
    private var samplingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationPresentation.applyChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isMonitoringEnabled(this)) {
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
            notificationManager.getNotificationChannel(NotificationPresentation.MONITORING_CHANNEL)?.importance !=
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
                    // The sensor subtypes are what let a dashboard camera or microphone request run
                    // after monitoring hides its window, and Android accepts them only when the start
                    // came from a visible app — boot auto-start gets refused.
                    startForegroundMonitoring(notification, monitoringTypes())
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    startForeground(
                        NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                    )
                    activeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                    // Before Android 14 any running foreground service could reach the sensors.
                    acceptedSensorTypes = SENSOR_SUBTYPES
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                    activeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                    acceptedSensorTypes = SENSOR_SUBTYPES
                }
                foregroundStarted = true
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
        if (isSyncReady || startupJob?.isActive == true) return
        startupJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SyncSettingsStore.load(this@CoreService)
                } ?: error("Device enrollment is not configured")

                // One upload path from here on: every report, capture and health sample enters the
                // same HTTP outbox, so nothing waits on a second socket engine and its retry loop.
                FeatureBridge.start(this@CoreService)
                isSyncReady = true
                ParentalCapture.onJson = { payload -> shareApprovedCapture(payload) }
                samplingJob = serviceScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            FeatureBridge.queueEvent(this@CoreService, healthPayload())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w(TAG, "Health sample could not be queued: ${error.javaClass.simpleName}")
                        }
                        // A dashboard rule may ask for a different cadence; the next wait reads it fresh.
                        delay(60_000L * RemotePolicy.intervalMinutes(this@CoreService))
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

    /** Approved screen text and fields queue on the same path as everything else, off the main thread. */
    private fun shareApprovedCapture(payload: JSONObject) {
        val session = ParentalCapture.session
        serviceScope.launch(Dispatchers.IO) {
            if (session != ParentalCapture.session || !isSyncReady) return@launch
            try {
                FeatureBridge.queueEvent(applicationContext, payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Approved app data could not be queued: ${error.javaClass.simpleName}")
            }
        }
    }

    // Identity comes from the enrolled device credential; this contains system-health data only.
    private fun healthPayload(): JSONObject {
        val battery = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return JSONObject()
            .put("type", "system_health")
            .put("timestamp", utcTimestamp())
            .put("battery_percent", if (battery in 0..100) battery else JSONObject.NULL)
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("android_version", Build.VERSION.RELEASE)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("app_version", AppIdentity.VERSION_NAME)
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
        // Cancelling the loop leaves the file outbox intact, so a later start delivers what is queued.
    }

    /** Adds only the sensor subtypes already granted, so a refused permission cannot block monitoring. */
    private fun monitoringTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }

    /** Records which sensor subtypes Android accepted, because a headless capture may use only those. */
    private fun startForegroundMonitoring(notification: Notification, types: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                activeTypes = types
                acceptedSensorTypes = types and SENSOR_SUBTYPES
            } catch (refused: SecurityException) {
                startForeground(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                activeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                acceptedSensorTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                Log.w(TAG, "Android refused sensor access for this monitoring session", refused)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            activeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            acceptedSensorTypes = types and SENSOR_SUBTYPES
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NotificationPresentation.MONITORING_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Monitoring on")
            .setContentText(NotificationPresentation.ongoingText(this))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    /** Android needs the ongoing notice while monitoring runs, so this refreshes it instead of hiding it. */
    private fun repostNotification() {
        if (!foregroundStarted || !isRunning) return
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification, activeTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopMonitoring() {
        HeadlessCapture.cancel()
        acceptedSensorTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
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
            activeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
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
        if (instance === this) instance = null
        HeadlessCapture.cancel()
        stopSync()
        serviceScope.cancel()
        isRunning = false
        ServiceManager.onServiceDisconnected(this)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CoreService"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "system_health_settings"
        private const val KEY_ENABLED = "monitoring_enabled"

        // Service and receiver run in the same default process. Repeated starts are idempotent.
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: CoreService? = null

        /** Updates the ongoing monitoring notice after a delivery or a preference change. Safe from any
         * thread and a no-op when monitoring is not running, so callers never have to check first. */
        fun refreshNotification() {
            val service = instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) service.repostNotification()
            else Handler(Looper.getMainLooper()).post { service.repostNotification() }
        }

        @Volatile
        var isSyncReady: Boolean = false
            private set

        internal const val SENSOR_SUBTYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        /** Subtypes Android accepted for the running service: a headless capture may use nothing else. */
        @Volatile
        var acceptedSensorTypes: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
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


