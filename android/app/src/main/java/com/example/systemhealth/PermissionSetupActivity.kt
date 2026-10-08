package com.example.systemhealth

import android.Manifest
import android.app.Activity
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
import androidx.core.app.NotificationManagerCompat
import com.example.utility.ScreenMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Three short, resumable steps: connect, choose, check. Permissions never enable sensitive sharing by
 * themselves, and this screen never claims a step succeeded because a button was pressed — every value
 * here is read back from Android or from the server's own acknowledgement, so "start requested" and
 * "the server received it" stay two different sentences.
 */
class PermissionSetupActivity : Activity() {

    private val choices = linkedMapOf<String, CheckBox>()
    private var queue = listOf<String>()
    private var waitingForPermission = false
    private var notice = "Choose the tools to prepare. Already allowed permissions are skipped."
    private var stage = 0
    private var visible = false
    private var connected = false
    private var checking = false
    private var startRequested = false
    private var connectionMessage = "Connecting to your dashboard…"
    private val declined = linkedSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }
    /** Fold states survive both a stage change and a rotation, so help the owner opened stays open. */
    private val sections = LinkedHashMap<String, Boolean>()
    private var kit: ScreenKit? = null

    private var progressCard: Once? = null
    private var feedbackCard: Once? = null
    private var factsCard: Once? = null
    private var declinedCard: Once? = null
    private var primaryButton: Button? = null

    /** A card that is redrawn only when its words change, so a screen reader is not repeating the same
     * sentence every two seconds while the owner is still reading it. */
    private inner class Once(private val card: ScreenKit.Card) {
        private var shownFacts: List<Fact>? = null
        private var shownLines: String? = null

        fun show(values: List<Fact>) {
            if (shownFacts == values) return
            shownFacts = values
            card.replaceFacts(values)
        }

        fun say(values: List<String>) {
            val joined = values.joinToString("\n")
            if (shownLines == joined) return
            shownLines = joined
            card.replaceLines(values)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        state?.keySet()?.forEach { key ->
            if (key.startsWith("section.")) sections[key.removePrefix("section.")] = state.getBoolean(key)
        }
        stage = state?.getInt("stage") ?: getSharedPreferences(PREFS, 0).getInt("stage", 0)
        stage = stage.coerceIn(0, 2)
        queue = state?.getStringArrayList("queue")?.toList().orEmpty()
        waitingForPermission = state?.getBoolean("waiting") ?: false
        notice = state?.getString("explanation") ?: notice
        connected = SyncSettingsStore.hasConfiguration(this)
        if (state != null) {
            val selected = PermissionPlan.steps(Build.VERSION.SDK_INT)
                .filter { state.getBoolean("selected_${it.id}") }.map { it.id }.toSet()
            getSharedPreferences(PREFS, 0).edit().putStringSet("selected", selected).apply()
        }
        // A fresh open lands on the first stage that still needs something from the owner instead of
        // walking through screens that are already satisfied.
        if (state == null) stage = firstNeededStage()
        renderStage()
        if (queue.isNotEmpty() && !waitingForPermission) advance()
    }

    private fun firstNeededStage(): Int = when {
        !shouldSkipStep("enrollment") -> 0
        pendingSteps().isNotEmpty() -> 1
        else -> 2
    }

    /** Step names come from the setup spec; an unknown name is never skipped. */
    private fun shouldSkipStep(step: String): Boolean {
        val type = when (step.lowercase()) {
            "enrollment" -> PermissionPlan.StepType.ENROLLMENT
            "notification", "notifications" -> PermissionPlan.StepType.NOTIFICATION
            "location" -> PermissionPlan.StepType.LOCATION
            "accessibility" -> PermissionPlan.StepType.ACCESSIBILITY
            else -> return false
        }
        return PermissionPlan.shouldSkip(type, Build.VERSION.SDK_INT, grantedSet(), connected)
    }

    private fun grantedSet() = PermissionPlan.steps(Build.VERSION.SDK_INT)
        .flatMap { it.permissions }.filter { isGranted(it) }.toSet()

    private fun pendingSteps() = PermissionPlan.steps(Build.VERSION.SDK_INT)
        .filter { it.id in chosenSteps() }
        .filterNot { step -> step.permissions.all { isGranted(it) } }

    private fun chosenSteps() =
        getSharedPreferences(PREFS, 0).getStringSet("selected", setOf("notifications")).orEmpty()

    private fun renderStage() {
        kit?.let { sections.putAll(it.sectionStates) }
        val screen = ScreenKit(this).apply { sectionStates.putAll(sections) }
        kit = screen
        progressCard = null; feedbackCard = null; factsCard = null; declinedCard = null; primaryButton = null
        setContentView(screen.scrollContent())
        screen.eyebrow("SYSTEM HEALTH · Guided setup")
        screen.screenTitle("Step ${stage + 1} of 3 · ${STAGES[stage]}")
        screen.paragraph("You can leave at any point and come back — this screen opens on the first step that " +
            "still needs you. Granting a permission does not start recording, tracking or sharing.")
        progressCard = Once(screen.card("Your progress"))
        when (stage) {
            0 -> connectStage(screen)
            1 -> permissionStage(screen)
            else -> checkStage(screen)
        }
        if (declined.isNotEmpty()) {
            val card = screen.card("Something Android did not allow", ScreenKit.Tone.ALERT)
            declinedCard = Once(card)
            card.button("Open this app's permission settings") {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        if (stage > 0) screen.card().button("Back to the previous step", ScreenKit.Weight.QUIET) {
            saveChoices(); goTo(stage - 1)
        }
        // The last step's way out is the one action on that screen, so it carries the filled weight the
        // home screen's primary button does. On earlier steps the same control only abandons setup, which
        // must not look like the main thing to press.
        screen.card().button(if (stage == 2) "Finish — open home" else "Finish later — return to the app",
            if (stage == 2) ScreenKit.Weight.PRIMARY else ScreenKit.Weight.QUIET) {
            saveChoices()
            if (stage == 2) markComplete()
            finish()
        }
        refresh()
    }

    private fun connectStage(screen: ScreenKit) {
        val card = screen.card("Connecting this phone", ScreenKit.Tone.EMPHASIS)
        card.note("This phone links to your dashboard on its own. There is no server address, phone ID or " +
            "token to type, and nothing is sent until you start monitoring in the last step.")
        feedbackCard = Once(card)
        primaryButton = card.button("", ScreenKit.Weight.PRIMARY) {
            if (connected) goTo(1) else connect()
        }
        screen.expandable("connect_help", "What this step does, and what to do if it waits",
            "Hide this help") { help ->
            help.note("The app asks the server to accept this phone, then tries again every 10 seconds " +
                "while this screen is open. If it keeps waiting, check this phone has internet.")
            help.note("If the person who looks after your dashboard has not approved this phone yet, the " +
                "wait is normal and nothing is wrong on this end.")
            help.button("Check the connection again") { connect() }
        }
    }

    private fun permissionStage(screen: ScreenKit) {
        val card = screen.card("Choose what this phone may do", ScreenKit.Tone.EMPHASIS)
        card.note("Each line says which tool needs it and what stays off without it. Android then asks " +
            "you once per line, and you may allow or decline.")
        choices.clear()
        val selected = chosenSteps()
        for (step in PermissionPlan.steps(Build.VERSION.SDK_INT)) {
            choices[step.id] = card.toggle(step.title, PlainStatus.whyPermissionIsNeeded(step.id),
                initial = step.id in selected || step.permissions.all { isGranted(it) }) { saveChoices() }
        }
        primaryButton = card.button("", ScreenKit.Weight.PRIMARY) { askSelected() }
        feedbackCard = Once(screen.card(null, ScreenKit.Tone.EMPHASIS))
        factsCard = Once(screen.card("What this phone allows today"))
        screen.expandable("special_access", "Optional system access", "Hide optional system access") { extra ->
            extra.note("Only enable an accessibility reader and Notification Access if you want those " +
                "tools. Sharing screen text is approved separately from the home screen, so nothing here " +
                "starts it by itself.")
            extra.button("Open accessibility settings") {
                saveChoices(); open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            extra.button("Open notification access settings") {
                saveChoices(); open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            extra.button("Open this app's Android settings") {
                saveChoices(); open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            extra.note("Android asks for your consent when a screenshot is taken, and files use Android's " +
                "own folder picker. Neither is set up here.")
        }
    }

    private fun checkStage(screen: ScreenKit) {
        val ready = PermissionPlan.blocking(Build.VERSION.SDK_INT, grantedSet(), connected).isEmpty()
        val card = screen.card(if (ready) "Ready to start" else "What is still missing", ScreenKit.Tone.EMPHASIS)
        card.note(if (ready) "One tap starts monitoring. It stays on until you press Stop, and Android " +
            "keeps a notice you can see while it runs."
        else "Monitoring needs a server this phone has reached and a status notice Android can show. " +
            "The list below says which of the two is missing.")
        feedbackCard = Once(card)
        primaryButton = card.button("", ScreenKit.Weight.PRIMARY) {
            if (startRequested) finish() else if (ready) enableMonitoring() else checkConnection()
        }
        factsCard = Once(screen.card("What this phone reported"))
        screen.card().button(if (ready) "Review permissions" else "Fix the missing access",
            ScreenKit.Weight.PLAIN) { saveChoices(); goTo(1) }
        screen.card().button("Check the connection again") { checkConnection() }
    }

    private fun saveChoices() {
        if (choices.isNotEmpty()) getSharedPreferences(PREFS, 0).edit()
            .putStringSet("selected", choices.filterValues { it.isChecked }.keys.toSet()).apply()
    }

    private fun markComplete() {
        getSharedPreferences(PREFS, 0).edit().putBoolean("completed", true).apply()
    }

    private fun goTo(next: Int) {
        saveChoices()
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
                    connectionMessage = if (connected) "Linked to your dashboard. You can continue." else result.message
                    refresh()
                    if (connected || result.state in listOf("rejected", "disabled")) return@launch
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    connectionMessage = "Waiting for internet or the server to wake up. Trying again automatically."
                    refresh()
                }
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
            catch (_: Exception) { connectionMessage = "Connection check failed. Check this phone's internet, then try again." }
            finally { checking = false; refresh() }
        }
    }

    private fun askSelected() {
        saveChoices()
        val selectedIds = choices.filterValues { it.isChecked }.keys.toSet()
        queue = PermissionPlan.pending(Build.VERSION.SDK_INT, selectedIds, grantedSet()).map { it.id }
        advance()
    }

    /** One Android dialog at a time, and a step already satisfied is never asked for again. */
    private fun advance() {
        while (queue.isNotEmpty()) {
            val step = PermissionPlan.steps(Build.VERSION.SDK_INT).first { it.id == queue.first() }
            if (shouldSkipStep(step.id) || step.permissions.all { isGranted(it) }) { queue = queue.drop(1); continue }
            waitingForPermission = true
            notice = "Android is now asking about ${step.title.lowercase()}. You may allow or decline."
            if (step.id == "notifications") {
                getSharedPreferences(PREFS, 0).edit().putBoolean("notifications_requested", true).apply()
            }
            refresh()
            requestPermissions(step.permissions.toTypedArray(), REQUEST_PERMISSION)
            return
        }
        waitingForPermission = false
        notice = "Nothing else to ask for. Missing permissions can be allowed later from this screen."
        goTo(2)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQUEST_PERMISSION) return
        waitingForPermission = false
        permissions.filterIndexed { index, _ -> results.getOrNull(index) != PackageManager.PERMISSION_GRANTED }
            .forEach { declined += it }
        notice = if (declined.isEmpty()) "Allowed. Nothing has been recorded or shared by it."
        else "Android did not allow one or more of these. The tools that need them stay off until you " +
            "change your mind in Android Settings."
        queue = queue.drop(1)
        advance()
    }

    /** Monitoring begins on the owner's tap, never as a side effect of the wizard finishing. */
    private fun enableMonitoring() {
        val started = runCatching { CoreService.start(this) }
        if (started.isFailure) {
            notice = "Android refused to start monitoring (${started.exceptionOrNull()?.javaClass?.simpleName}). " +
                "Check the connection and the missing access below, then tap again."
            refresh()
            return
        }
        startRequested = true
        markComplete()
        notice = "Start requested. The list below shows what Android and the server confirm — an ongoing " +
            "notice should appear on this phone."
        refresh()
    }

    private fun notificationsAllowed() =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || isGranted(Manifest.permission.POST_NOTIFICATIONS))

    private fun refresh() {
        declined.retainAll { !isGranted(it) }
        progressCard?.show(listOf(
            Fact(STAGES[0], if (connected) "Done" else if (stage == 0) "You are here" else "Not finished",
                needsAttention = !connected && stage > 0),
            Fact(STAGES[1], if (stage > 1) "Done" else if (stage == 1) "You are here" else "Up next"),
            Fact(STAGES[2], when {
                startRequested && CoreService.isRunning -> "Done — monitoring is on"
                stage == 2 -> "You are here"
                else -> "Up next" }, needsAttention = stage == 2 && !startRequested)
        ))
        when (stage) {
            0 -> refreshConnect()
            1 -> refreshPermissions()
            else -> refreshCheck()
        }
        declinedCard?.say(declined.map { PlainStatus.permissionDenied(it) })
    }

    private fun refreshConnect() {
        feedbackCard?.show(listOf(Fact(
            if (connected) "Connected to your dashboard" else "Waiting to connect",
            connectionMessage, needsAttention = !connected)))
        setPrimary(if (connected) "Continue to permissions" else "Check the connection again",
            enabled = !checking)
    }

    private fun refreshPermissions() {
        feedbackCard?.say(listOf(notice))
        factsCard?.show(PermissionPlan.steps(Build.VERSION.SDK_INT).map { step ->
            Fact(step.title, when {
                step.id == "location" && isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    !isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> "Allowed, approximate only"
                step.permissions.all { isGranted(it) } -> "Allowed"
                else -> "Not allowed" },
                needsAttention = step.id in chosenSteps() &&
                    !step.permissions.all { isGranted(it) })
        } + readerFacts())
        choices.values.forEach { it.isEnabled = !waitingForPermission }
        setPrimary(if (waitingForPermission) "Android is asking…"
            else "Allow the permissions I chose and continue", enabled = !waitingForPermission)
    }

    private fun refreshCheck() {
        val blocked = PermissionPlan.blocking(Build.VERSION.SDK_INT, grantedSet(), connected)
        val upload = ConnectionDiagnostics.lastUpload(this)
        feedbackCard?.say(listOf(when {
            checking -> "Checking the server and this phone's enrollment…"
            blocked.isEmpty() -> notice
            else -> "Still needed: ${blocked.joinToString { if (it == PermissionPlan.StepType.ENROLLMENT)
                "a connection to your dashboard" else "status notifications" }}."
        }))
        factsCard?.show(listOf(
            Fact("Connected to your dashboard", if (connected) "Yes" else "Not yet", !connected),
            Fact("Last upload the server accepted", ConnectionDiagnostics.timestamp(upload), upload == 0L),
            Fact("Monitoring", PlainStatus.monitoring(CoreService.isRunning)),
            Fact("Status notifications", if (notificationsAllowed()) "Allowed"
                else "Needed before monitoring can start", !notificationsAllowed())
        ) + readerFacts())
        setPrimary(when {
            startRequested -> "Open the home screen"
            blocked.isEmpty() -> "Start System Health Monitor"
            else -> "Check the connection again" }, enabled = !checking)
    }

    private fun readerFacts() = listOf(
        Fact("Screen text reader", if (readersEnabled()) "Enabled in Android" else "Not enabled"),
        Fact("Notification access",
            if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
                "Enabled in Android" else "Not enabled"))

    private fun readersEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == ComponentName(this, ScreenMonitorService::class.java) ||
                it == ComponentName(this, AccessibilityHelperService::class.java) }

    private fun setPrimary(label: String, enabled: Boolean) {
        val button = primaryButton ?: return
        if (button.text.toString() != label) button.text = label
        button.isEnabled = enabled && !waitingForPermission
    }

    private fun isGranted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            notice = "Open the corresponding page in your phone's Settings app."
            refresh()
        }
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

    override fun onDestroy() {
        scope.cancel(); super.onDestroy()
    }

    override fun onSaveInstanceState(state: Bundle) {
        kit?.let { sections.putAll(it.sectionStates) }
        sections.forEach { (key, open) -> state.putBoolean("section.$key", open) }
        state.putInt("stage", stage); state.putStringArrayList("queue", ArrayList(queue))
        state.putBoolean("waiting", waitingForPermission)
        state.putString("explanation", notice)
        val selected = if (choices.isNotEmpty()) choices.filterValues { it.isChecked }.keys
            else chosenSteps()
        PermissionPlan.steps(Build.VERSION.SDK_INT)
            .forEach { state.putBoolean("selected_${it.id}", it.id in selected) }
        super.onSaveInstanceState(state)
    }

    companion object {
        private const val PREFS = "permission_setup"
        private const val REQUEST_PERMISSION = 70
        private val STAGES = listOf("Connect this phone", "Choose what this phone may do", "Check and start")

        fun showOnFirstOpen(context: Context): Boolean {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getInt("wizard_version", 0) == 1) return false
            p.edit().putInt("wizard_version", 1).putBoolean("shown", true).putInt("stage", 0).apply()
            context.startActivity(Intent(context, PermissionSetupActivity::class.java)); return true
        }

        fun notificationSetupShown(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("shown", false)
    }
}
