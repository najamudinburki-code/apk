package com.example.systemhealth

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import java.io.File

/** A single, OS-approved screenshot. No reusable projection token or silent capture. */
class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var requestId: String? = null
    private var captureAt = 0L
    private var saving = false
    private var finished = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { if (!finished && !saving) end("declined", "Screen sharing stopped before capture.") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            // Keep one virtual display per consent grant on Android 14+.
            if (width > 0 && height > 0 && !saving && !finished) resize(width, height)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { end("declined", "Screenshot cancelled on phone."); return START_NOT_STICKY }
        if (projection != null || saving) return START_NOT_STICKY
        requestId = intent?.getStringExtra("request_id")
        try {
            check(CoreService.isMonitoringEnabled(this)) { "Start monitoring before screen sharing" }
            NotificationPresentation.applyChannels(this)
            val notification = Notification.Builder(this, NotificationPresentation.SCREEN_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Screenshot pending")
                .setContentText("Android will capture one frame five seconds after you choose what to share.")
                .setOnlyAlertOnce(true).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_SECRET).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(3020, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(3020, notification)
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("projection_data") ?: error("Android screen-sharing consent missing")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result_code", 0), data)
            projection?.registerCallback(callback, handler)
            val metrics = resources.displayMetrics
            val size = dimensions(metrics.widthPixels, metrics.heightPixels)
            reader = makeReader(size.first, size.second)
            captureAt = SystemClock.elapsedRealtime() + 5000
            display = projection?.createVirtualDisplay("Approved screenshot", size.first, size.second, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
            handler.postDelayed({ capture() }, 5200)
            handler.postDelayed({ if (!saving && !finished) end("failed", "No screen frame received. Try sharing the entire screen.") }, 15000)
        } catch (e: Exception) { end("failed", e.message?.take(120) ?: "Screen capture could not start.") }
        return START_NOT_STICKY
    }
    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, 1600.0 / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
    private fun makeReader(width: Int, height: Int) = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ source ->
            if (!saving && !finished && SystemClock.elapsedRealtime() >= captureAt) capture()
            else runCatching { source.acquireLatestImage()?.close() } // Drain early frames so the 5-second capture is fresh.
        }, handler)
    }
    private fun resize(width: Int, height: Int) {
        if (display == null) return
        val size = dimensions(width, height)
        if (reader?.width == size.first && reader?.height == size.second) return
        try {
            val previous = reader
            reader = makeReader(size.first, size.second)
            display?.resize(size.first, size.second, resources.displayMetrics.densityDpi)
            display?.surface = reader?.surface
            previous?.close()
        } catch (_: Exception) { end("failed", "Screen resize failed. Try again.") }
    }
    private fun capture() {
        if (saving || finished || SystemClock.elapsedRealtime() < captureAt) return
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null } ?: return
        saving = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    try {
                        val plane = image.planes[0]
                        val width = image.width; val height = image.height
                        val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                        val output = File(cacheDir, "screenshot-${System.currentTimeMillis()}.jpg")
                        try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) } }
                        finally { if (bitmap !== padded) bitmap.recycle(); padded.recycle() }
                        output
                    } finally { image.close() }
                }
                val queued = withContext(Dispatchers.IO) {
                    try {
                        FeatureBridge.queueFile(this@ScreenCaptureService, file, file.name, "image/jpeg", "screenshot",
                            requestId, "Screenshot uploaded to the dashboard Files.")
                        true
                    } finally { file.delete() }
                }
                // The dashboard answer comes from FeatureBridge once the upload is confirmed, so the
                // capture only reports that the file is on its way.
                if (queued) end("running", "Screenshot captured and queued for upload.")
            } catch (e: CancellationException) { image.close(); throw e }
            catch (_: Exception) { end("failed", "Screenshot could not be saved or queued.") }
        }
    }
    private fun end(state: String, detail: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(callback); projection?.stop(); projection = null
        val id = requestId; requestId = null
        if (id != null) CoroutineScope(Dispatchers.IO).launch { FeatureBridge.finishRequest(applicationContext, id, state, detail) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (!finished) end("failed", "Screenshot service stopped.")
        scope.cancel()
        super.onDestroy()
    }
}
