package com.example.systemhealth

/**
 * What the home screen knows about this phone, reduced to plain values. No preferences, no
 * timestamps and no Android types live here, so the answer to "what should I do next?" is a decision
 * a JVM test can make on its own. The caller reads each value from the same server-acknowledged
 * sources the previous screen used; nothing in this file can invent a success.
 */
internal data class HomeSignals(
    val enrolled: Boolean,
    val notificationsAllowed: Boolean,
    val serverVerified: Boolean,
    val monitoring: Boolean,
    val everUploaded: Boolean,
    val lastUploadLabel: String,
    val serverMessage: String,
    val pendingUploads: Int,
    val unsentItems: Int,
    val pendingRequests: Int,
    val appTextSharing: Boolean,
    val liveStreaming: Boolean,
    val rulesSummary: String?,
    val scheduledReports: String?,
    val updateAvailable: String?,
    val updateInstalled: String,
    val updateUrl: String,
    val serviceStatus: String = ""
)

/** The single action the home screen puts first for the situation it is in. Each one maps onto a
 * control that already existed, so reordering the screen never removes a way to do something. */
internal enum class HomeAction(val label: String) {
    CONNECT("Connect this phone"),
    NOTIFICATIONS("Allow status notifications"),
    CHECK("Check the connection again"),
    START("Start System Health Monitor")
}

/** One labelled line of state. A marked line is rendered where a plain reading of the screen cannot
 * miss it, because it is either wrong or something the owner is entitled to notice. */
internal data class Fact(val label: String, val value: String, val needsAttention: Boolean = false)

internal data class HomePlan(
    val headline: String,
    val subhead: String,
    val primary: HomeAction?,
    val alerts: List<String>,
    val facts: List<Fact>,
    val monitorStopVisible: Boolean,
    val sharingStopVisible: Boolean
)

/** Turns those signals into the five answers the home screen owes the owner: is it connected, is
 * monitoring on, is anything waiting, does something need attention, and what to do next. */
internal object HomeOverview {

    /** Every line CoreService writes when nothing is wrong, in its own words. */
    private val CALM_SERVICE_STATES = setOf(
        "Monitoring is stopped.",
        "Starting monitoring…",
        "Monitoring active. Check the dashboard to confirm server delivery.",
        "Monitoring active. The status notice is hidden until the next event."
    )

    fun plan(signals: HomeSignals): HomePlan = HomePlan(
        headline = ReadinessPolicy.title(signals.enrolled, signals.notificationsAllowed,
            signals.serverVerified, signals.monitoring, signals.everUploaded),
        subhead = subhead(signals),
        primary = primary(signals),
        alerts = alerts(signals),
        facts = facts(signals),
        // A Stop control is only shown when there is something to stop, but it is never tucked away:
        // both of these stay on the first card, where the owner found them before this redesign.
        monitorStopVisible = signals.monitoring,
        sharingStopVisible = signals.appTextSharing
    )

    private fun primary(signals: HomeSignals): HomeAction? = when {
        !signals.enrolled -> HomeAction.CONNECT
        !signals.notificationsAllowed -> HomeAction.NOTIFICATIONS
        !signals.serverVerified -> HomeAction.CHECK
        !signals.monitoring -> HomeAction.START
        // Connected, running and talking to the server: there is nothing to fix, and inventing a
        // button for that state would only make the screen look unfinished.
        else -> null
    }

    private fun subhead(signals: HomeSignals): String = when {
        !signals.enrolled ->
            "This phone has not joined your dashboard yet. One tap sets it up — no address or code to type."
        !signals.notificationsAllowed ->
            "Android needs a visible status notice before this app may keep running, so monitoring cannot start yet."
        !signals.serverVerified ->
            "This phone is set up, but the server has not confirmed it accepts it recently. Check the connection."
        !signals.monitoring ->
            "Sharing is off. Nothing reaches your dashboard until you start monitoring."
        !signals.everUploaded ->
            "Monitoring is running and waiting for the server to confirm its first sample."
        else ->
            "Sharing is on and your server is hearing from this phone. Last confirmed delivery: ${signals.lastUploadLabel}."
    }

    private fun alerts(signals: HomeSignals): List<String> = buildList {
        // Android refusing to keep monitoring running is the one thing the owner must not have to
        // dig out of a diagnostics block, so any service line that is not a known calm state surfaces.
        if (signals.serviceStatus.isNotBlank() && signals.serviceStatus !in CALM_SERVICE_STATES) add(
            signals.serviceStatus)
        if (signals.liveStreaming) add(
            "The camera is streaming to your dashboard right now. Open Device tools and tap " +
                "“Stop the live camera view now” to end it, or switch the tool off there to stop the next one.")
        if (signals.unsentItems > 0) add(
            "${PlainStatus.unsent(signals.unsentItems)} on this phone. " +
                "Nothing is lost yet; clear them from Device tools → Shared files if they keep failing.")
        if (signals.pendingUploads > 0 && !signals.monitoring) add(
            "${PlainStatus.count(signals.pendingUploads, "item")} cannot be sent while monitoring is off. " +
                "Start monitoring to send ${if (signals.pendingUploads == 1) "it" else "them"}.")
        signals.updateAvailable?.let {
            add("A newer version ($it) is available. This phone has ${signals.updateInstalled}.")
            add(if (signals.updateUrl.isNotBlank()) "Get it from: ${signals.updateUrl}"
            else "Ask the dashboard owner where to get the newer version.")
        }
    }

    private fun facts(signals: HomeSignals): List<Fact> = buildList {
        add(Fact("Connected to your dashboard",
            when {
                !signals.enrolled -> "No — not set up on this phone yet"
                signals.serverVerified -> "Yes — confirmed by the server"
                else -> "Not confirmed. Last report said: ${signals.serverMessage}"
            }, needsAttention = signals.enrolled && !signals.serverVerified))
        add(Fact("Monitoring",
            if (signals.monitoring) "On — sharing with your dashboard" else "Off — nothing is being sent"))
        add(Fact("Last delivery the server accepted", signals.lastUploadLabel,
            needsAttention = signals.monitoring && !signals.everUploaded))
        add(Fact("Waiting to upload", if (signals.pendingUploads == 0) "Nothing waiting"
        else "${PlainStatus.count(signals.pendingUploads, "item")} still on this phone"))
        // The most sensitive thing this app does is read screen text and notifications, so its state
        // belongs on the top of the home screen rather than buried in the diagnostics.
        add(Fact("Screen text and notifications",
            if (signals.appTextSharing) "Being shared with your dashboard" else "Not being shared",
            needsAttention = signals.appTextSharing))
        if (signals.pendingRequests > 0) add(Fact("Dashboard requests waiting",
            "${PlainStatus.count(signals.pendingRequests, "request")} for this phone"))
        if (signals.rulesSummary != null) add(Fact("Dashboard rules on this phone", signals.rulesSummary))
        if (signals.scheduledReports != null) add(Fact("Reports you set to repeat", signals.scheduledReports))
    }
}
