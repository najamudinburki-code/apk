package com.example.systemhealth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Turns a camera's raw preview into small JPEG frames on a fixed cadence.
 *
 * A preview stream arrives at the sensor's own rate, far faster than this phone can upload, so the
 * interval is applied before any expensive work: a frame that is not due is closed unread. Every
 * image is closed on every path, because an ImageReader that runs out of buffers stops the camera
 * producing at all.
 *
 * The pixels are copied row by row rather than handed over whole. Camera2 reports each plane with its
 * own row and pixel padding, which varies by device, so a single-buffer assumption is the one thing a
 * frame converter may not make.
 *
 * Runs on the live view's own thread and never touches a window, so the owner keeps seeing whatever
 * they were doing. */
class FrameProducer(
    private val reader: ImageReader,
    private val frameLock: Any,
    private val orientationDegrees: Int,
    private val onFrame: (ByteArray) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private var lastFrameMs = 0L
    private var problemReported = false

    @Volatile private var running = false

    /** Callbacks arrive on `handler`'s thread, which must not be the main one. */
    fun start(handler: Handler) {
        running = true
        reader.setOnImageAvailableListener({ source -> deliver(source) }, handler)
    }

    /** Stops delivery immediately. The caller still owns closing the reader. */
    fun stop() {
        running = false
        reader.setOnImageAvailableListener(null, null)
    }

    /** Reads, converts and releases the frame under the lock the teardown uses, because a buffer that
     * the camera thread is still copying must not be closed from another thread. */
    private fun deliver(source: ImageReader) {
        val outcome = runCatching { read(source) }
        val frame = outcome.getOrNull()
        if (frame != null) onFrame(frame) else reportOnce(outcome.exceptionOrNull())
    }

    /** Null means there was nothing to send: no new frame, or one too soon to upload. */
    private fun read(source: ImageReader): ByteArray? {
        return synchronized(frameLock) {
            if (!running) return null
            // acquireLatestImage closes the frames it discards, which is exactly what a live view wants:
            // the newest moment is the only one still worth uploading.
            val image = source.acquireLatestImage() ?: return null
            try {
                val now = SystemClock.elapsedRealtime()
                if (!LiveViewPolicy.isDue(now, lastFrameMs)) return null
                val jpeg = encode(image)
                if (jpeg.isEmpty()) error("Android returned an empty frame")
                lastFrameMs = now
                jpeg
            } finally {
                image.close()
            }
        }
    }

    private fun encode(image: Image): ByteArray {
        check(image.planes.size >= 3) { "Camera frame is not YUV_420_888" }
        val compressed = ByteArrayOutputStream()
        val raw = YuvImage(toNv21(image), ImageFormat.NV21, image.width, image.height, null)
        check(raw.compressToJpeg(Rect(0, 0, image.width, image.height), LiveViewPolicy.JPEG_QUALITY, compressed)) {
            "Android could not compress a camera frame"
        }
        val jpeg = compressed.toByteArray()
        // A repeating preview request carries no JPEG_ORIENTATION key, so the frame is turned here.
        return if (orientationDegrees % 360 == 0) jpeg else turn(jpeg)
    }

    private fun turn(jpeg: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("A camera frame could not be decoded for turning.")
        try {
            val turned = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                Matrix().apply { postRotate(orientationDegrees.toFloat()) }, true)
            val output = ByteArrayOutputStream()
            try {
                check(turned.compress(Bitmap.CompressFormat.JPEG, LiveViewPolicy.JPEG_QUALITY, output)) {
                    "Android could not re-compress a turned frame"
                }
            } finally {
                if (turned !== source) turned.recycle()
            }
            return output.toByteArray()
        } finally {
            source.recycle()
        }
    }

    /** NV21 is one luma plane followed by interleaved chroma in V,U order. */
    private fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val lumaSize = width * height
        val frame = ByteArray(lumaSize + lumaSize / 2)
        val luma = image.planes[0]
        val chromaU = image.planes[1]
        val chromaV = image.planes[2]
        copyRows(luma.buffer, luma.rowStride, frame, 0, width, height)
        var position = lumaSize
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                frame[position++] = sample(chromaV.buffer, row * chromaV.rowStride + column * chromaV.pixelStride)
                frame[position++] = sample(chromaU.buffer, row * chromaU.rowStride + column * chromaU.pixelStride)
            }
        }
        return frame
    }

    private fun copyRows(source: ByteBuffer, rowStride: Int, target: ByteArray, offset: Int, width: Int, height: Int) {
        for (row in 0 until height) {
            val start = row * rowStride
            val length = minOf(width, source.limit() - start)
            if (length <= 0) return
            source.position(start)
            source.get(target, offset + row * width, length)
        }
    }

    /** Padding rows can sit past the plane's end on some devices; a zero sample is a black edge, not a
     * crash, and one bad edge must not end a whole session. */
    private fun sample(source: ByteBuffer, index: Int): Byte =
        if (index in 0 until source.limit()) source.get(index) else 0

    private fun reportOnce(error: Throwable?) {
        if (error == null || problemReported) return
        problemReported = true
        onProblem("Camera frames could not be converted (${error.javaClass.simpleName}).")
    }
}
