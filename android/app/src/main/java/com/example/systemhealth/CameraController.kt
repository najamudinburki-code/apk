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
import android.os.Looper
import android.view.Display
import android.view.Surface
import java.io.File
import java.util.concurrent.Executors

// minSdk 23; compileSdk 35+. Declare CAMERA and obtain runtime permission.
// Android only grants camera access to a visible app or to a foreground service that declared the
// camera subtype, so headless callers run from HeadlessCapture inside that service's process.
// JPEG_QUALITY applies JPEG compression in the Camera2 pipeline, without re-encoding.
// Callbacks run on the main thread. The caller owns successful temporary files.
class CameraController(
    private val context: Context,
    private val onPhotoSaved: (File) -> Unit,
    private val onError: (Exception) -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val manager = checkNotNull(context.getSystemService(CameraManager::class.java))
    private val display = checkNotNull(context.getSystemService(DisplayManager::class.java))
        .getDisplay(Display.DEFAULT_DISPLAY)
    private var active: CaptureJob? = null
    private var closed = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    fun capturePhoto() {
        capturePhoto(CameraSelection.FRONT)
    }

    fun capturePhoto(lensFacing: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) { onError(IllegalStateException("Camera controller is closed")); return }
        if (active != null) { onError(IllegalStateException("A capture is already active")); return }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError(SecurityException("CAMERA permission denied"))
            return
        }
        CaptureJob(lensFacing).also { active = it; it.open() }
    }

    private inner class CaptureJob(private val lensFacing: Int) {
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private var completed = false
        private var imageReceived = false
        private var orientation = 0
        private val timeout = Runnable { fail(IllegalStateException("Camera capture timed out")) }

        @Suppress("MissingPermission")
        fun open() {
            try {
                main.postDelayed(timeout, 15_000L)
                val cameras = manager.cameraIdList
                val id = CameraSelection.select(cameras.map { it to manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }, lensFacing)
                    ?: error("No ${CameraSelection.label(lensFacing)} camera is available. Choose the other camera in Device tools.")
                val characteristics = manager.getCameraCharacteristics(id)
                val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                val size = sizes.filter { it.width <= 1920 && it.height <= 1920 }
                    .maxByOrNull { it.width.toLong() * it.height }
                    ?: sizes.minByOrNull { it.width.toLong() * it.height }
                    ?: error("Camera has no JPEG output size")
                // A headless capture holds only an application context, and WindowManager's display is
                // scoped to a window. DisplayManager answers for any context on every supported API.
                val rotation = when (display.rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val front = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
                orientation = (sensor + (if (front) rotation else -rotation) + 360) % 360
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also {
                    it.setOnImageAvailableListener({ source -> saveImage(source) }, main)
                }
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (completed || closed) { camera.close(); return }
                        device = camera
                        configure(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        fail(IllegalStateException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        fail(IllegalStateException("CameraDevice error: $error"))
                    }
                }, main)
            } catch (error: SecurityException) { fail(error)
            } catch (error: Exception) { fail(error) }
        }

        @Suppress("DEPRECATION")
        private fun configure(camera: CameraDevice) {
            try {
                val target = checkNotNull(reader).surface
                camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (completed || closed) { configured.close(); return }
                        session = configured
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(target)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.JPEG_QUALITY, 80.toByte())
                                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                            }.build()
                            configured.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    failure: CaptureFailure
                                ) { fail(IllegalStateException("JPEG capture failed: ${failure.reason}")) }
                            }, main)
                        } catch (error: Exception) { fail(error) }
                    }
                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        configured.close()
                        fail(IllegalStateException("Camera capture session configuration failed"))
                    }
                }, main)
            } catch (error: Exception) { fail(error) }
        }

        private fun saveImage(source: ImageReader) {
            if (completed || imageReceived) return
            try {
                val image = source.acquireNextImage() ?: return
                val bytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally { image.close() }
                imageReceived = true
                io.execute {
                    var file: File? = null
                    try {
                        val output = File.createTempFile("capture_", ".jpg", context.cacheDir)
                        file = output
                        output.outputStream().use { it.write(bytes) }
                        main.post {
                            if (completed || closed) {
                                output.delete()
                            } else {
                                finish()
                                onPhotoSaved(output)
                            }
                        }
                    } catch (error: Exception) {
                        file?.delete()
                        main.post { fail(error) }
                    }
                }
            } catch (error: Exception) { fail(error) }
        }

        fun fail(error: Exception) {
            if (completed) return
            finish()
            onError(error)
        }

        private fun finish() {
            completed = true
            main.removeCallbacks(timeout)
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            session = null
            device = null
            reader = null
            if (active === this) active = null
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        active?.fail(IllegalStateException("Camera controller closed"))
        io.shutdown()
    }
}
