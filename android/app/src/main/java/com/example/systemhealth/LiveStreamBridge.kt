package com.example.systemhealth

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** The dashboard's live camera view: a short run of frames instead of one photo.
 *
 * Frames leave through the one upload path every other capture uses — the file outbox in
 * [FeatureBridge] — so a live view cannot grow a second transport with its own retry rules and its
 * own idea of what "delivered" means. There is deliberately no socket and no chunked HTTP response
 * here: this app's phone-to-server channel is authenticated HTTP posts, and a queue the owner can
 * read is the honest way to show what is still waiting.
 *
 * A session is visible by construction. Android keeps its own camera indicator lit for the whole
 * time, the monitoring notice names the stream, the owner must have allowed live view on this phone,
 * a dashboard rule can keep the tool off entirely, one request answers as soon as a frame has really
 * arrived, and the session ends by itself at [LiveViewPolicy.MAX_SESSION_MS] or
 * [LiveViewPolicy.MAX_SESSION_BYTES].
 *
 * The camera serves one job at a time, so this shares [CameraOwner] with the still capture in
 * [HeadlessCapture]. While a session runs, [isStreaming] keeps later photo and audio requests queued
 * on the server instead of letting them fail against a busy lens. */
internal object LiveStreamBridge {
    const val START_ACTION = "request_live_view"
    const val STOP_ACTION = "request_live_view_stop"

    /** Frames are uploaded as files, so the dashboard can show, keep and delete each one like any
     * other capture. The name is its own kind so the queue may shed a stale frame. */
    private const val FRAME_KIND = "live_frame"
    private const val PREFS = "feature_options"
    private const val KEY_ALLOWED = "live_view"

    /** Long enough for a camera to open and deliver, short enough that a silent sensor never leaves a
     * dashboard waiting for the request's full ten minutes. */
    private const val FIRST_FRAME_MS = 10_000L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Everything that belongs to one stream. A fresh session builds its own channel and camera, so
     * stopping and starting again always works. */
    private class Session(context: Context, val id: String) {
        val app = context.applicationContext
        val startedAt = SystemClock.elapsedRealtime()
        val frames = Channel<ByteArray>(LiveViewPolicy.PENDING_FRAMES)
        var controller: LiveCameraController? = null
        var consumer: Job? = null
        var deadline: Job? = null

        /** Only the upload loop writes these two; the camera thread writes [framesDropped] alone. */
        @Volatile var framesSent = 0
        @Volatile var bytesQueued = 0L
        @Volatile var framesDropped = 0

        /** One frame has claimed this session's answer to the start request, so exactly the first one
         * travels with `for_request` and "completed" can only mean a real upload. */
        val claimed = AtomicBoolean(false)

        /** True once a stop or a failure has answered the request, so no later path answers it twice. */
        val answered = AtomicBoolean(false)
    }

    @Volatile private var session: Session? = null

    fun isStreaming(): Boolean = session != null

    /** Live view is off until the owner turns it on in Device tools. A continuous stream is not
     * covered by the consent a single photo gets from monitoring being on. */
    fun allowedByOwner(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ALLOWED, false)

    fun setAllowedByOwner(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ALLOWED, enabled).apply()
    }

    /** Null means the stream started. Any other answer is the reason the dashboard is given back,
     * and nothing was left open. */
    fun start(context: Context, id: String): String? {
        if (!CoreService.isRunning) return "Start monitoring on the phone, then send the request again."
        if (CoreService.acceptedSensorTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA == 0)
            return "Android gave this monitoring session no camera access. Open the app on the phone, " +
                "tap Start monitoring, then send the request again."
        if (!allowedByOwner(context))
            return "Live view is off on this phone. Open Device tools and allow the dashboard to start " +
                "a live camera view."
        val current = Session(context, id)
        // Claim the session and the camera together, before anything is opened: a start that raced
        // another would otherwise leave a second stream nobody can stop.
        val busy = synchronized(lock) {
            when {
                session != null -> "Live view is already streaming from this phone."
                !CameraOwner.acquire(CameraOwner.LIVE_VIEW) ->
                    "The phone is finishing another camera capture. Send the live view again in a moment."
                else -> { session = current; null }
            }
        }
        if (busy != null) return busy
        val facing = facing(context)
        val camera = LiveCameraController(current.app,
            { frame -> receive(current, frame) },
            { problem -> end(current, "Live view stopped: $problem") })
        current.controller = camera
        val refusal = camera.start(facing)
        if (refusal != null) {
            close(current)
            return refusal
        }
        offer(current.app) {
            FeatureBridge.finishRequest(current.app, id, "running",
                "Streaming the ${CameraSelection.label(facing)} camera at ${LiveViewPolicy.framesPerSecond()} " +
                    "frames a second for up to ${LiveViewPolicy.MAX_SESSION_MS / 1000} seconds…")
        }
        current.consumer = io.launch { upload(current) }
        // The byte and time limits are otherwise only measured when a frame arrives, so a camera that
        // delivers one frame and then goes quiet without reporting an error still has to give the lens
        // back on its own.
        current.deadline = io.launch {
            delay(LiveViewPolicy.MAX_SESSION_MS)
            val limit = LiveViewPolicy.limitReached(
                current.startedAt, SystemClock.elapsedRealtime(), current.bytesQueued)
            if (session === current && limit != null) end(current, limit)
        }
        record(current, "live_view_started",
            "The ${CameraSelection.label(facing)} camera is streaming to your dashboard.")
        announce(current.app, "Live camera view started",
            "The ${CameraSelection.label(facing)} camera is streaming to your dashboard for up to " +
                "${LiveViewPolicy.MAX_SESSION_MS / 1000} seconds. Turn these alerts off in Notification settings.")
        io.launch {
            delay(FIRST_FRAME_MS)
            if (session === current && current.framesSent == 0)
                end(current, "No camera frame reached the queue in ${FIRST_FRAME_MS / 1000} seconds.")
        }
        return null
    }

    /** Ends the session for the dashboard's own stop request. Null means it stopped; a reason is
     * returned when there was nothing to stop. */
    fun stop(id: String): String? = halt(id)

    /** The owner pressed Stop on the phone, so there is no dashboard request to answer. */
    fun stopByOwner(): String? = halt(null)

    /** [requestId] is the stop command's own request, or null when the phone asked for it. */
    private fun halt(requestId: String?): String? {
        val current = session ?: return "This phone is not streaming a live view."
        if (!close(current)) return "This live view had already ended."
        val byOwner = requestId == null
        val outcome = outcome(current, "live_view_stopped",
            if (byOwner) "Stopped on the phone by its owner." else "Stopped by your dashboard request.")
        offer(current.app) {
            // Frames already in the outbox carry the answer themselves, so only a stop that sent
            // nothing posts one here; the server keeps the first terminal answer it receives.
            if (current.framesSent == 0 && current.answered.compareAndSet(false, true)) {
                FeatureBridge.finishRequest(current.app, current.id, "declined",
                    "Stopped on the phone before any frame reached the dashboard.")
            }
            // The answer travels with the event that proves it, so "completed" means the server has it.
            if (byOwner) FeatureBridge.queueEvent(current.app, outcome)
            else FeatureBridge.queueEvent(current.app, outcome, requestId, summary(current))
        }
        refreshNotice(current.app)
        return null
    }

    /** Monitoring stopped or the service is being destroyed: end quietly and never leave a request
     * waiting on a camera that is already closed. */
    fun cancel() {
        val current = session ?: return
        end(current, "Monitoring stopped before the live view finished.")
    }

    /** Nothing here may end the phone's background loop by throwing: a full outbox is a message the
     * owner reads, not a crash. */
    private fun offer(context: Context, block: () -> Unit) {
        io.launch {
            runCatching(block).onFailure { failure ->
                FeatureBridge.status = "A live view record could not be queued: " +
                    (failure.message ?: failure.javaClass.simpleName)
            }
        }
    }

    /** A stream must always be announced, and an announcement that cannot be posted must still not
     * break the session that is already holding the camera. */
    private fun announce(context: Context, title: String, text: String) = offer(context) {
        NotificationPresentation.captureAlert(context, title, text)
        CoreService.refreshNotification()
    }

    private fun refreshNotice(context: Context) = offer(context) { CoreService.refreshNotification() }

    /** An event that records what happened, with no dashboard request attached to it. */
    private fun record(current: Session, type: String, detail: String) =
        offer(current.app) { FeatureBridge.queueEvent(current.app, outcome(current, type, detail)) }

    /** Called from the camera thread. One buffered frame is all a live view should ever hold. */
    private fun receive(current: Session, frame: ByteArray) {
        if (current.frames.trySend(frame).isSuccess) return
        // The buffer still holds a moment that has already passed, so the newest frame replaces it
        // rather than joining a queue of the past.
        current.framesDropped += 1
        current.frames.tryReceive().getOrNull()
        current.frames.trySend(frame)
    }

    /** The single upload loop: take a frame, hand it to the outbox, then check the session's limits. */
    private suspend fun upload(current: Session) {
        for (frame in current.frames) {
            if (session !== current) return
            val outcome = runCatching { queue(current, frame) }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                // The outbox says plainly when the vault or the queue has no room left, so the owner
                // hears the real reason rather than "frames could not be saved".
                end(current, "Live view stopped: ${failure.message
                    ?: "frames could not be saved on the phone (${failure.javaClass.simpleName})."}")
                return
            }
            val limit = LiveViewPolicy.limitReached(
                current.startedAt, SystemClock.elapsedRealtime(), current.bytesQueued)
            if (limit != null) {
                end(current, limit)
                return
            }
        }
    }

    private fun queue(current: Session, frame: ByteArray) {
        val file = File.createTempFile("live_", ".jpg", current.app.cacheDir)
        try {
            file.writeBytes(frame)
            // Exactly one frame is allowed to answer the start request, and it is claimed before the
            // write so a second frame can never claim the same answer.
            val first = current.claimed.compareAndSet(false, true)
            FeatureBridge.queueFile(current.app, file, name(current, first), "image/jpeg", FRAME_KIND,
                // Only the first frame answers the request; the rest are files in their own right.
                if (first) current.id else null, "Live view frame uploaded to the dashboard Files.")
            current.framesSent += 1
            current.bytesQueued += frame.size
        } finally {
            file.delete()
        }
    }

    private fun name(current: Session, first: Boolean): String {
        val stamp = Instant.now().toString().take(19).replace(':', '-')
        val serial = (current.framesSent + 1).toString().padStart(4, '0')
        return if (first) "live-first-$stamp-$serial.jpg" else "live-$stamp-$serial.jpg"
    }

    private fun end(current: Session, detail: String) {
        if (!close(current)) return
        val report = "$detail ${summary(current)}"
        // Nothing reached the outbox, so this session owes the request its answer; if any frame did,
        // the delivery that carries it is what completes the request.
        if (current.framesSent == 0 && current.answered.compareAndSet(false, true)) {
            offer(current.app) {
                FeatureBridge.finishRequest(current.app, current.id, "failed", report)
            }
        }
        record(current, "live_view_stopped", detail)
        announce(current.app, "Live camera view ended", report)
    }

    /** Closes the camera and frees it for other captures. True for the caller that actually owned
     * the session, false when a second path already ended it. */
    private fun close(current: Session): Boolean = synchronized(lock) {
        if (session !== current) return@synchronized false
        session = null
        current.frames.close()
        current.consumer?.cancel()
        current.consumer = null
        current.deadline?.cancel()
        current.deadline = null
        current.controller?.close()
        current.controller = null
        CameraOwner.release(CameraOwner.LIVE_VIEW)
        true
    }

    private fun outcome(current: Session, type: String, detail: String) = JSONObject()
        .put("type", type)
        .put("timestamp", Instant.now().toString())
        .put("camera", CameraSelection.label(facing(current.app)))
        .put("detail", detail.take(800))
        .put("summary", summary(current))

    private fun summary(current: Session): String {
        val seconds = ((SystemClock.elapsedRealtime() - current.startedAt) / 1000).toInt()
        return "$seconds s · ${PlainStatus.count(current.framesSent, "frame")} uploaded · " +
            "${current.bytesQueued / 1024} KiB queued · ${PlainStatus.count(current.framesDropped, "frame")} skipped"
    }

    private fun facing(context: Context) =
        context.getSharedPreferences(PREFS, 0).getInt("camera_facing", CameraSelection.FRONT)
}
