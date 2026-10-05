package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import com.example.utility.ScreenMonitorService
import kotlinx.coroutines.*

/** Three-stage, resumable setup. Permissions never enable sensitive sharing by themselves. */
class PermissionSetupActivity : Activity() {
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private lateinit var allow: Button
    private val choices = linkedMapOf<String, CheckBox>()
    private var queue = listOf<String>()
    private var waitingForPermission = false
    private var explanation = "Choose the tools to prepare. Already allowed permissions are skipped."
    private var stage = 0
    private var visible = false
    private var connected = false
    private var checking = false
    private var connectionMessage = "Connecting to your dashboard…"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        stage = state?.getInt("stage") ?: getSharedPreferences(PREFS, 0).getInt("stage", 0)
        stage = stage.coerceIn(0, 2)
        queue = state?.getStringArrayList("queue")?.toList().orEmpty()
        waitingForPermission = state?.getBoolean("waiting") ?: false
        explanation = state?.getString("explanation") ?: explanation
        connected = SyncSettingsStore.hasConfiguration(this)
        if (state != null) {
            val selected = PermissionPlan.steps(Build.VERSION.SDK_INT).filter { state.getBoolean("selected_${it.id}") }.map { it.id }.toSet()
            getSharedPreferences(PREFS, 0).edit().putStringSet("selected", selected).apply()
        }
        renderStage()
        if (queue.isNotEmpty() && !waitingForPermission) advance()
    }

    private fun renderStage() {
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }
        setContentView(ScrollView(this).apply { addView(layout) })
        label("SYSTEM HEALTH", 14f)
        label("${stage + 1} of 3 · ${arrayOf("Connect", "Permissions", "Check")[stage]}", 26f)
        label("Connect  ·  Permissions  ·  Check", 15f)
        status = label("")
        choices.clear()
        when (stage) {
            0 -> {
                label("This phone connects to your dashboard automatically. No server address, phone ID or token to type.")
                allow = button("Continue to permissions") { goTo(1) }
                button("Retry connection") { connect() }
            }
            1 -> {
                label("Choose the tools to prepare. Granting permissions does not start recording, tracking or app-text sharing.")
                val selected = getSharedPreferences(PREFS, 0).getStringSet("selected", setOf("notifications")).orEmpty()
                for (step in PermissionPlan.steps(Build.VERSION.SDK_INT)) {
                    choices[step.id] = CheckBox(this).apply {
                        text = "${step.title}\n${step.detail}"
                        isChecked = step.id in selected || step.permissions.all { isGranted(it) }
                        this@PermissionSetupActivity.layout.addView(this)
                    }
                }
                allow = button("Allow selected permissions and continue") {
                    saveChoices()
                    val selectedIds = choices.filterValues { it.isChecked }.keys.toSet()
                    val granted = PermissionPlan.steps(Build.VERSION.SDK_INT).flatMap { it.permissions }.filter { isGranted(it) }.toSet()
                    queue = PermissionPlan.pending(Build.VERSION.SDK_INT, selectedIds, granted).map { it.id }
                    advance()
                }
                label("Special access — optional", 20f)
                label("Enable one accessibility reader and Notification Access only if you want those tools. Approve app-text sharing separately from the home screen.")
                button("Open accessibility settings") { saveChoices(); open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                button("Open notification access settings") { saveChoices(); open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                button("Background location for geofences") { saveChoices(); backgroundLocation() }
                button("App permissions and notification settings") { saveChoices(); open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                label("Android asks for screenshot consent when you use that tool. Files use Android's file picker.")
            }
            2 -> {
                label("The app checks that the server is reachable and accepts this phone. Successful upload time changes only after a server acknowledgement.")
                allow = button("Check connection again") { checkConnection() }
                button("Review permissions") { goTo(1) }
            }
        }
        if (stage > 0) button("Back") { saveChoices(); goTo(stage - 1) }
        button(if (stage == 2) "Finish — open home" else "Finish later / return to app") {
            saveChoices()
            if (stage == 2) getSharedPreferences(PREFS, 0).edit().putBoolean("completed", true).apply()
            finish()
        }
        refresh()
    }

    private fun saveChoices() {
        if (choices.isNotEmpty()) getSharedPreferences(PREFS, 0).edit().putStringSet("selected", choices.filterValues { it.isChecked }.keys.toSet()).apply()
    }
    private fun goTo(next: Int) {
        networkJob?.cancel(); networkJob = null; checking = false
        stage = next.coerceIn(0, 2)
        getSharedPreferences(PREFS, 0).edit().putInt("stage", stage).apply()
        renderStage()
        if (visible && stage == 0) connect()
        if (visible && stage == 2) checkConnection()
    }
    private fun connect() {
        if (!visible || networkJob?.isActive == true) return
        networkJob = scope.launch {
            while (visible && stage == 0) {
                try {
                    val result = AutomaticEnrollment.check(this@PermissionSetupActivity)
                    connected = result.state == "approved"
                    connectionMessage = if (connected) "Phone linked to your dashboard. Continue to permissions." else result.message
                    refresh()
                    if (connected || result.state in listOf("rejected", "disabled")) return@launch
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { connectionMessage = "Waiting for internet or the server to wake up. Retrying automatically."; refresh() }
                delay(10_000)
            }
        }
    }
    private fun checkConnection() {
        if (!visible || networkJob?.isActive == true) return
        networkJob = scope.launch {
            checking = true; refresh()
            try {
                if (!SyncSettingsStore.hasConfiguration(this@PermissionSetupActivity)) {
                    val result = AutomaticEnrollment.check(this@PermissionSetupActivity)
                    connected = result.state == "approved"
                    if (!connected) { connectionMessage = result.message; return@launch }
                }
                if (notificationsAllowed() && AutomaticEnrollment.consumeAutoStart(this@PermissionSetupActivity)) {
                    runCatching { CoreService.start(this@PermissionSetupActivity) }
                }
                val result = ConnectionDiagnostics.probe(this@PermissionSetupActivity)
                connectionMessage = result.message
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connectionMessage = "Connection check failed. Check internet and retry." }
            finally { checking = false; refresh() }
        }
    }

    private fun advance() {
        while (queue.isNotEmpty()) {
            val step = PermissionPlan.steps(Build.VERSION.SDK_INT).first { it.id == queue.first() }
            if (step.permissions.all { isGranted(it) }) { queue = queue.drop(1); continue }
            waitingForPermission = true
            explanation = "Android is asking for ${step.title.lowercase()}. You may allow or decline."
            if (step.id == "notifications") getSharedPreferences(PREFS, 0).edit().putBoolean("notifications_requested", true).apply()
            refresh()
            requestPermissions(step.permissions.toTypedArray(), REQUEST_PERMISSION)
            return
        }
        waitingForPermission = false
        explanation = "Selected permissions checked. Missing permissions can be enabled later."
        goTo(2)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST_PERMISSION) { waitingForPermission = false; queue = queue.drop(1); advance() }
        else if (code == REQUEST_BACKGROUND) refresh()
    }
    private fun backgroundLocation() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            explanation = "Enable precise foreground location first. Background access is needed only for geofences."; refresh(); return
        }
        if (Build.VERSION.SDK_INT < 29 || isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            explanation = "Background access already available. Location sharing has not been started."; refresh(); return
        }
        AlertDialog.Builder(this).setTitle("Background location for geofences")
            .setMessage("Allow location all the time only if you choose to use geofences. This setup does not add a geofence or start tracking.")
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                if (Build.VERSION.SDK_INT == 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND)
                else open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.show()
    }
    private fun notificationsAllowed() = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || isGranted(Manifest.permission.POST_NOTIFICATIONS))
    private fun refresh() {
        if (!::status.isInitialized || !::allow.isInitialized) return
        when (stage) {
            0 -> { status.text = connectionMessage; allow.isEnabled = connected }
            1 -> {
                allow.isEnabled = !waitingForPermission
                choices.values.forEach { it.isEnabled = !waitingForPermission }
                status.text = buildString {
                    append(explanation).append("\n\n")
                    for (step in PermissionPlan.steps(Build.VERSION.SDK_INT)) {
                        val access = if (step.id == "location" && isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) && !isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) "approximate only"
                            else if (step.permissions.all { isGranted(it) }) "allowed" else "not allowed"
                        append("${step.title}: $access\n")
                    }
                    val readers = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it) }
                    append("Accessibility reader: ${if (readers.any { it == ComponentName(this@PermissionSetupActivity, ScreenMonitorService::class.java) || it == ComponentName(this@PermissionSetupActivity, AccessibilityHelperService::class.java) }) "enabled" else "not enabled"}\n")
                    append("Notification access: ${if (NotificationManagerCompat.getEnabledListenerPackages(this@PermissionSetupActivity).contains(packageName)) "enabled" else "not enabled"}")
                }
            }
            2 -> {
                allow.isEnabled = !checking
                val upload = ConnectionDiagnostics.lastUpload(this)
                val title = ReadinessPolicy.title(SyncSettingsStore.hasConfiguration(this), notificationsAllowed(), ConnectionDiagnostics.verified(this), CoreService.isRunning, upload > 0)
                status.text = if (checking) "Checking server and phone enrollment…" else "$title\n\n$connectionMessage\n\nLast successful upload: ${ConnectionDiagnostics.timestamp(upload)}\nHealth monitoring: ${if (CoreService.isRunning) "active" else "stopped"}\nStatus notifications: ${if (notificationsAllowed()) "allowed" else "enable to start monitoring"}"
            }
        }
    }
    private fun isGranted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 10, 0, 10); this@PermissionSetupActivity.layout.addView(this) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() }; this@PermissionSetupActivity.layout.addView(this) }
    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { explanation = "Open the corresponding page in your phone's Settings app."; refresh() }
    }
    override fun onResume() {
        super.onResume(); visible = true; handler.post(ticker)
        if (stage == 0) connect()
        if (stage == 2) checkConnection()
    }
    override fun onPause() {
        visible = false; handler.removeCallbacks(ticker); networkJob?.cancel(); networkJob = null; checking = false
        saveChoices(); super.onPause()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("stage", stage); state.putStringArrayList("queue", ArrayList(queue)); state.putBoolean("waiting", waitingForPermission)
        state.putString("explanation", explanation)
        val selected = if (choices.isNotEmpty()) choices.filterValues { it.isChecked }.keys else getSharedPreferences(PREFS, 0).getStringSet("selected", setOf("notifications")).orEmpty()
        PermissionPlan.steps(Build.VERSION.SDK_INT).forEach { state.putBoolean("selected_${it.id}", it.id in selected) }
        super.onSaveInstanceState(state)
    }
    companion object {
        private const val PREFS = "permission_setup"
        private const val REQUEST_PERMISSION = 70
        private const val REQUEST_BACKGROUND = 71
        fun showOnFirstOpen(context: Context): Boolean {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getInt("wizard_version", 0) == 1) return false
            p.edit().putInt("wizard_version", 1).putBoolean("shown", true).putInt("stage", 0).apply()
            context.startActivity(Intent(context, PermissionSetupActivity::class.java)); return true
        }
        fun notificationSetupShown(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("shown", false)
    }
}
