package com.example.systemhealth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Display

/** Holds the camera open for the dashboard's live view and hands back JPEG frames.
 *
 * Deliberately separate from [CameraController], which captures exactly one still. A continuous
 * stream cannot use a JPEG image surface, because Android only allows that format for a still-capture
 * request, so a live view reads YUV_420_888 preview frames and converts them itself. The two share
 * [CameraOwner] because the hardware serves one client at a time.
 *
 * Every Android callback for this class is posted to one thread of its own. Nothing is added to the
 * main looper, so a stream cannot slow down whatever the owner is doing on screen. */
class LiveCameraController(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val manager = checkNotNull(context.getSystemService(CameraManager::class.java))
    private val display = checkNotNull(context.getSystemService(DisplayManager::class.java))
        .getDisplay(Display.DEFAULT_DISPLAY)

    // The camera thread and the thread that calls stop() both reach these, so every one of them is
    // published rather than assumed to be seen.
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var producer: FrameProducer? = null

    /** One frame's buffers are read on the camera thread and closed on the caller's, so the two take
     * turns instead of overlapping: closing a reader under a frame still being copied is undefined. */
    private val frameLock = Any()
    private val timeout = Runnable { fail("Android did not hand the camera over in time.") }

    @Volatile private var running = false
    @Volatile private var closed = false

    /** A camera this build can use, with the turning its frames need. */
    private class Chosen(val id: String, val sensorOrientation: Int, val front: Boolean)

    /** Asks Android for the camera. Null means the request went out; any other answer is the reason
     * this phone cannot stream, and nothing was left open. */
    fun start(facing: Int): String? {
        if (closed) return "This live view was already stopped."
        if (running) return "A live view is already running."
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return "System Health no longer has camera permission. Re-grant it on the phone."
        val chosen = runCatching { selectCamera(facing) }
            .getOrElse { return "The camera could not be read (${it.javaClass.simpleName})." }
            ?: return "No ${CameraSelection.label(facing)} camera is available. Choose the other camera in Device tools."
        val size = runCatching { previewSize(chosen.id) }.getOrNull()
            ?: return "This camera has no preview size the live view can use."
        val thread = HandlerThread("LiveCamera").also { it.start() }
        worker = thread
        val callbacks = Handler(thread.looper)
        handler = callbacks
        val source = ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 2)
        reader = source
        producer = FrameProducer(source, frameLock,
            CameraSelection.orientation(chosen.sensorOrientation,
                CameraSelection.rotationDegrees(display.rotation), chosen.front),
            onFrame, ::fail)
        running = true
        callbacks.post { open(chosen.id, size, callbacks) }
        return null
    }

    /** Frees the hardware at once, then lets the camera thread finish and exit.
     *
     * Closing inline is what [CameraController] does too: a stop that queued the close behind a long
     * frame callback would keep Android holding the camera while the next dashboard request was
     * already being answered as "busy". */
    fun stop() {
        if (closed) return
        closed = true
        running = false
        release()
        val thread = worker
        val callbacks = handler
        worker = null
        handler = null
        if (callbacks == null) thread?.quitSafely() else callbacks.post { thread?.quitSafely() }
    }

    override fun close() = stop()

    private fun selectCamera(facing: Int): Chosen? {
        val cameras = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
        val id = CameraSelection.select(
            cameras.map { (id, details) -> id to details.get(CameraCharacteristics.LENS_FACING) }, facing) ?: return null
        val details = cameras.first { it.first == id }.second
        return Chosen(id, details.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            details.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
    }

    private fun previewSize(id: String): Pair<Int, Int>? =
        LiveViewPolicy.pickPreviewSize(manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty().map { it.width to it.height })

    @Suppress("MissingPermission")
    private fun open(id: String, size: Pair<Int, Int>, callbacks: Handler) {
        if (closed) return
        handler?.postDelayed(timeout, TIMEOUT_MS)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running || closed) { camera.close(); return }
                    device = camera
                    configure(camera, size, callbacks)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail("The camera was taken by another app.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail(reason(error))
                }
            }, callbacks)
        } catch (refused: SecurityException) {
            fail("Android refused camera access for this monitoring session.")
        } catch (error: Exception) {
            fail("The camera could not be opened (${error.javaClass.simpleName}).")
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice, size: Pair<Int, Int>, callbacks: Handler) {
        val source = reader ?: return fail("The frame reader was closed before the preview started.")
        try {
            camera.createCaptureSession(listOf(source.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!running || closed) { configured.close(); return }
                    session = configured
                    repeat(camera, configured, source, callbacks)
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    fail("Android could not set up a ${size.first}×${size.second} preview.")
                }
            }, callbacks)
        } catch (error: Exception) {
            fail("The preview could not start (${error.javaClass.simpleName}).")
        }
    }

    private fun repeat(
        camera: CameraDevice,
        configured: CameraCaptureSession,
        source: ImageReader,
        callbacks: Handler,
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(source.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            producer?.start(callbacks)
            handler?.removeCallbacks(timeout)
            configured.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) { fail("Android stopped the preview (reason ${failure.reason}).") }
            }, callbacks)
        } catch (error: Exception) {
            fail("The preview could not keep running (${error.javaClass.simpleName}).")
        }
    }

    private fun reason(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Another app or the live view already holds the camera."
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "This device's policy has the camera switched off."
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Android reported a camera failure; the phone may need a reboot."
        else -> "The camera reported error $error."
    }

    private fun fail(detail: String) {
        if (closed) return
        stop()
        onError(detail)
    }

    private fun release() {
        handler?.removeCallbacks(timeout)
        synchronized(frameLock) {
            producer?.stop()
            producer = null
            runCatching { reader?.close() }
            reader = null
        }
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    private companion object {
        /** Android hands a camera over in a fraction of a second normally; this only catches a device
         * that stays silent, which would otherwise leave a dashboard request waiting forever. */
        const val TIMEOUT_MS = 15_000L
    }
}
