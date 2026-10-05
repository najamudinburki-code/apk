@file:Suppress("DEPRECATION")

package com.example.systemmanagement

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.JobIntentService
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/*
 * Requires AndroidX Core and minSdk 21.
 * JobIntentService is deprecated; retained here as explicitly requested.
 * Its queued work does not bypass foreground/background service restrictions
 * or guarantee execution after a force-stop, reboot, or permission revocation.
 *
 * AndroidManifest.xml:
 * <uses-permission android:name="android.permission.WAKE_LOCK" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <application ...>
 *   <service
 *       android:name=".ServiceManagementJob"
 *       android:exported="false"
 *       android:permission="android.permission.BIND_JOB_SERVICE" />
 * </application>
 *
 * Declare each managed service separately, including its intent filters,
 * binding permissions, accessibility configuration, foreground service type,
 * and applicable type-specific permissions. Use the default application process.
 *
 * Lifecycle integration:
 * - Ordinary Service.onCreate(): ServiceManager.onServiceConnected(this)
 * - AccessibilityService.onServiceConnected(): same call, after super
 * - NotificationListenerService.onListenerConnected(): same call, after super
 * - NotificationListenerService.onListenerDisconnected():
 *       ServiceManager.onServiceDisconnected(this), after super
 * - Service.onDestroy()/AccessibilityService.onUnbind():
 *       ServiceManager.onServiceDisconnected(this)
 *
 * Cancel service-owned coroutines, listeners, receivers, and other resources
 * in each service's teardown callbacks. Repeated onStartCommand calls must be
 * idempotent. Foreground services must promptly call startForeground themselves.
 */
class ServiceManager(
    context: Context,
    private val services: List<ServiceSpec>
) {
    private val app = context.applicationContext

    enum class Kind { ORDINARY, ACCESSIBILITY, NOTIFICATION_LISTENER }
    enum class RuntimeState { RUNNING, STOPPED, UNKNOWN }
    enum class Result {
        ALREADY_RUNNING,
        START_REQUESTED,
        REBIND_REQUESTED,
        STOP_REQUESTED,
        ALREADY_STOPPED,
        USER_ACTION_REQUIRED,
        WAITING_FOR_SYSTEM,
        SYSTEM_MANAGED,
        RESTRICTED,
        INVALID_CONFIGURATION
    }

    data class ServiceSpec(
        val component: ComponentName,
        val kind: Kind,
        val foreground: Boolean = false
    )

    data class Status(
        val available: Boolean,
        val accessGranted: Boolean,
        val runtime: RuntimeState
    )

    init {
        require(services.map { it.component }.distinct().size == services.size) {
            "Duplicate service components"
        }
        require(services.all { it.component.packageName == app.packageName }) {
            "Only services belonging to this application can be managed"
        }
    }

    fun checkServiceStatus(spec: ServiceSpec): Status {
        val available = isAvailable(spec)
        val granted = available && when (spec.kind) {
            Kind.ORDINARY -> true
            Kind.ACCESSIBILITY -> {
                val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as AccessibilityManager
                manager.isEnabled && manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ).any { info ->
                    val service = info.resolveInfo.serviceInfo
                    ComponentName(service.packageName, service.name) == spec.component
                }
            }
            Kind.NOTIFICATION_LISTENER -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager
                    manager.isNotificationListenerAccessGranted(spec.component)
                } else {
                    Settings.Secure.getString(
                        app.contentResolver, "enabled_notification_listeners"
                    ).orEmpty().split(':').any {
                        ComponentName.unflattenFromString(it) == spec.component
                    }
                }
            }
        }
        val runtime = when {
            connected[spec.component]?.get() != null -> RuntimeState.RUNNING
            disconnected.containsKey(spec.component) -> RuntimeState.STOPPED
            else -> RuntimeState.UNKNOWN
        }
        return Status(available, granted, runtime)
    }

    fun checkAllServiceStatuses(): Map<ComponentName, Status> =
        services.associate { it.component to checkServiceStatus(it) }

    fun isServiceRunning(spec: ServiceSpec): Boolean =
        checkServiceStatus(spec).let {
            it.available && it.accessGranted && it.runtime == RuntimeState.RUNNING
        }

    // Call directly from a visible Activity when an ordinary service must start.
    // Queuing a job does not give it permission to start an otherwise restricted FGS.
    fun startService(spec: ServiceSpec): Result = record(spec) {
        val status = checkServiceStatus(spec)
        when {
            !status.available -> Result.INVALID_CONFIGURATION
            !status.accessGranted -> Result.USER_ACTION_REQUIRED
            status.runtime == RuntimeState.RUNNING -> Result.ALREADY_RUNNING
            spec.kind == Kind.ACCESSIBILITY -> Result.WAITING_FOR_SYSTEM
            spec.kind == Kind.NOTIFICATION_LISTENER -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(spec.component)
                    Result.REBIND_REQUESTED
                } else {
                    Result.WAITING_FOR_SYSTEM
                }
            }
            else -> {
                val intent = Intent().setComponent(spec.component)
                val started = if (spec.foreground) {
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    app.startService(intent)
                }
                if (started == null) Result.INVALID_CONFIGURATION
                else Result.START_REQUESTED
            }
        }
    }

    fun startAllServices(): Map<ComponentName, Result> =
        services.associate { it.component to startService(it) }

    // Never treats an enabled setting alone as evidence of a connected service.
    // Ordinary services receive an idempotent start request when status is unknown.
    fun restartIfNeeded(spec: ServiceSpec): Result = startService(spec)

    fun restartAllIfNeeded(): Map<ComponentName, Result> = startAllServices()

    fun stopService(spec: ServiceSpec): Result = record(spec) {
        when {
            !isAvailable(spec) -> Result.INVALID_CONFIGURATION
            spec.kind != Kind.ORDINARY -> Result.SYSTEM_MANAGED
            app.stopService(Intent().setComponent(spec.component)) -> Result.STOP_REQUESTED
            else -> Result.ALREADY_STOPPED
        }
    }

    fun stopAllServices(): Map<ComponentName, Result> =
        services.associate { it.component to stopService(it) }

    // Finite, serialized reconciliation work; no polling loops or permanent wake locks.
    fun enqueueHealthCheck() {
        services.forEach { enqueue(it, OP_ENSURE) }
    }

    fun enqueueStopAll() {
        services.forEach { enqueue(it, OP_STOP) }
    }

    // This is the last dispatch result, not proof of successful service startup.
    fun lastRequestResult(spec: ServiceSpec): Result? {
        val value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(spec.component.flattenToString(), null) ?: return null
        return Result.values().firstOrNull { it.name == value }
    }

    // Launch only from a user-facing Activity following an explicit user action.
    fun settingsIntent(spec: ServiceSpec): Intent? = when (spec.kind) {
        Kind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Kind.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Kind.ORDINARY -> null
    }

    private fun enqueue(spec: ServiceSpec, operation: String) {
        require(isAvailable(spec)) { "Invalid service configuration: ${spec.component}" }
        JobIntentService.enqueueWork(
            app,
            ServiceManagementJob::class.java,
            JOB_ID,
            Intent().apply {
                putExtra(EXTRA_COMPONENT, spec.component.flattenToString())
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_FOREGROUND, spec.foreground)
                putExtra(EXTRA_OPERATION, operation)
            }
        )
    }

    private fun isAvailable(spec: ServiceSpec): Boolean {
        if (spec.component.packageName != app.packageName) return false
        if (spec.component.className == ServiceManagementJob::class.java.name) return false
        return try {
            val info = app.packageManager.getServiceInfo(spec.component, 0)
            val enabled = when (
                app.packageManager.getComponentEnabledSetting(spec.component)
            ) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.enabled
                else -> false
            }
            val expectedPermission = when (spec.kind) {
                Kind.ACCESSIBILITY -> Manifest.permission.BIND_ACCESSIBILITY_SERVICE
                Kind.NOTIFICATION_LISTENER -> Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
                Kind.ORDINARY -> null
            }
            val validPermission = if (expectedPermission != null) {
                info.permission == expectedPermission && !spec.foreground
            } else {
                info.permission != Manifest.permission.BIND_ACCESSIBILITY_SERVICE &&
                    info.permission != Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE &&
                    info.permission != "android.permission.BIND_JOB_SERVICE"
            }
            enabled && info.applicationInfo.enabled && validPermission &&
                info.processName == app.applicationInfo.processName
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private inline fun record(spec: ServiceSpec, operation: () -> Result): Result {
        val result = try {
            operation()
        } catch (error: SecurityException) {
            Log.w(TAG, "Service access denied: ${spec.component}", error)
            Result.RESTRICTED
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Service operation restricted: ${spec.component}", error)
            Result.RESTRICTED
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(spec.component.flattenToString(), result.name).apply()
        return result
    }

    companion object {
        private const val TAG = "ServiceManager"
        private const val PREFS = "service_manager_results"
        private const val JOB_ID = 41001 // Reserve this ID application-wide.
        internal const val EXTRA_COMPONENT = "service.component"
        internal const val EXTRA_KIND = "service.kind"
        internal const val EXTRA_FOREGROUND = "service.foreground"
        internal const val EXTRA_OPERATION = "service.operation"
        internal const val OP_ENSURE = "ensure"
        internal const val OP_STOP = "stop"

        private val connected = ConcurrentHashMap<ComponentName, WeakReference<Service>>()
        private val disconnected = ConcurrentHashMap<ComponentName, Boolean>()

        @Synchronized
        fun onServiceConnected(service: Service) {
            val component = ComponentName(service, service.javaClass)
            disconnected.remove(component)
            connected[component] = WeakReference(service)
        }

        @Synchronized
        fun onServiceDisconnected(service: Service) {
            val component = ComponentName(service, service.javaClass)
            val current = connected[component]?.get()
            if (current != null && current !== service) return
            connected.remove(component)
            disconnected[component] = true
        }
    }
}

class ServiceManagementJob : JobIntentService() {
    override fun onHandleWork(intent: Intent) {
        val component = intent.getStringExtra(ServiceManager.EXTRA_COMPONENT)
            ?.let(ComponentName::unflattenFromString) ?: return
        val kindName = intent.getStringExtra(ServiceManager.EXTRA_KIND) ?: return
        val kind = ServiceManager.Kind.values().firstOrNull { it.name == kindName } ?: return
        if (component.packageName != packageName) return

        val spec = ServiceManager.ServiceSpec(
            component,
            kind,
            intent.getBooleanExtra(ServiceManager.EXTRA_FOREGROUND, false)
        )
        val manager = ServiceManager(this, listOf(spec))
        val result = when (intent.getStringExtra(ServiceManager.EXTRA_OPERATION)) {
            ServiceManager.OP_ENSURE -> manager.restartIfNeeded(spec)
            ServiceManager.OP_STOP -> manager.stopService(spec)
            else -> return
        }
        Log.d("ServiceManagementJob", "$component: $result")
    }

    // Interrupted reconciliation is safe to redeliver because operations are idempotent.
    override fun onStopCurrentWork(): Boolean = true
}
