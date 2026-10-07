package com.example.systemhealth

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Completes one dashboard camera or microphone request without showing any interface.
 *
 * Android hands a background app camera and microphone access only for the lifetime of a foreground
 * service that declared those subtypes while the app was visible, so this runs in the process
 * CoreService keeps alive instead of a service started here: a camera or microphone service launched
 * from the background poll is rejected by the system. Android's own sensor indicator stays on for the
 * duration of the capture and cannot be suppressed.
 *
 * The request goes "running" while the lens or microphone is busy and only "completed" once the
 * upload itself is confirmed by FeatureBridge, so a full queue or a dropped connection can never
 * look like a delivered capture. */
object HeadlessCapture {
    private const val ACTION_AUDIO = "request_audio"
    private const val RECORDING_MS = 15_000L
    private const val FINALIZE_MS = 4_000L

    private val handler = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var camera: CameraController? = null
    private var microphone: AudioRecorder? = null
    @Volatile private var app: Context? = null
    @Volatile private var requestId: String? = null
    @Volatile private var answered = false
    private var claimed = false

    /** True while the camera or microphone holds a capture. The sensor serves one request at a
     * time, so the poll loop queues the next one instead of answering it with a failure. */
    fun isBusy(): Boolean = requestId != null

    /** Null when this capture took over the request; otherwise the reason to report back. */
    fun start(context: Context, id: String, action: String): String? {
        if (!CoreService.isRunning) return "Start monitoring on the phone, then send the request again."
        val wanted = if (action == ACTION_AUDIO) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (CoreService.acceptedSensorTypes and wanted == 0)
            return "Android gave this monitoring session no " + (if (action == ACTION_AUDIO) "microphone" else "camera") +
                " access. Open the app on the phone, tap Start monitoring, then send the request again."
        if (requestId != null) return "Phone is already finishing another capture."
        app = context.applicationContext
        requestId = id
        answered = false
        claimed = false
        val audio = action == ACTION_AUDIO
        io.launch {
            FeatureBridge.finishRequest(context, id, "running",
                if (audio) "Recording 15 seconds of microphone audio…" else "Taking one photo…")
        }
        handler.post { if (audio) recordAudio() else takePhoto() }
        return null
    }

    /** Answers an unfinished request when monitoring stops, so the dashboard never waits forever. */
    fun cancel() {
        val context = app ?: return
        val id = requestId ?: return
        handler.post {
            release()
            io.launch { FeatureBridge.finishRequest(context, id, "failed", "Monitoring stopped before the capture finished.") }
        }
    }

    private fun takePhoto() {
        val context = app ?: return
        val facing = context.getSharedPreferences("feature_options", 0).getInt("camera_facing", CameraSelection.FRONT)
        camera = CameraController(context,
            { file -> queue(file, "image/jpeg", "photo", "Photo") },
            { error -> fail(error.message ?: "Camera capture failed.") })
        camera?.capturePhoto(facing)
    }

    private fun recordAudio() {
        val context = app ?: return
        microphone = AudioRecorder(context,
            { file -> queue(file, "audio/mp4", "audio", "Recording") },
            { error -> fail(error.message ?: "Microphone unavailable.") })
        microphone?.startRecording()
        handler.postDelayed({ microphone?.stopRecording() }, RECORDING_MS)
        handler.postDelayed({
            fail("No microphone audio was captured.")
            release()
        }, RECORDING_MS + FINALIZE_MS)
    }

    /** Hands the finished file to the upload queue. Only the first chunk of a recording represents
     * the request, so later chunks cannot answer it twice. */
    private fun queue(file: File, mime: String, kind: String, label: String) {
        val context = app ?: return
        val id = synchronized(this) { if (claimed) null else requestId.also { claimed = true } }
        io.launch {
            val outcome = runCatching {
                FeatureBridge.queueFile(context, file, file.name, mime, kind, id, "$label uploaded to the dashboard Files.")
            }
            if (outcome.isFailure) fail("$label could not be saved on the phone.")
            else NotificationPresentation.captureAlert(context, "$label captured for your dashboard",
                "Saved on the phone for upload. Turn these alerts off in Notification settings.")
            file.delete()
            // A successful capture answers the request without going through fail(), so this is the
            // only place that clears the busy flag. Without it every later request is refused.
            if (microphone == null) handler.post { release() }
        }
    }

    private fun fail(detail: String) {
        val context = app ?: return
        val id = requestId ?: return
        synchronized(this) {
            if (answered || claimed) return
            answered = true
        }
        io.launch { FeatureBridge.finishRequest(context, id, "failed", detail) }
        // A microphone session keeps recording until its own timer stops it.
        if (microphone == null) handler.post { release() }
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        camera?.close()
        camera = null
        microphone?.close()
        microphone = null
        requestId = null
        answered = false
        claimed = false
        app = null
    }
}
