package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// minSdk 23; compileSdk 35+. Declare and request RECORD_AUDIO before starting.
// Invoke from the visible recording UI. Leaving the activity stops recording.
// AAC is not MP3: each completed chunk is AAC-LC inside a .m4a container.
// Callbacks run on the main thread. stopRecording() finalizes a shorter last chunk.
class AudioRecorder(
    private val activity: Activity,
    private val onChunkReady: (File) -> Unit,
    private val onError: (Exception) -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    private var closed = false

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(a: Activity) { if (a === activity) stopRecording() }
        override fun onActivityDestroyed(a: Activity) { if (a === activity) close() }
        override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
    }

    fun startRecording() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) { onError(IllegalStateException("Recorder is closed")); return }
        if (worker != null) return
        if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            onError(IllegalStateException("A visible recording activity is required"))
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            onError(SecurityException("RECORD_AUDIO permission denied"))
            return
        }
        running.set(true)
        val thread = Thread({ recordLoop() }, "AudioChunkRecorder")
        worker = thread
        thread.start()
    }

    fun stopRecording() {
        check(Looper.myLooper() == Looper.getMainLooper())
        running.set(false)
        // Unblock a READ_BLOCKING read. Only the worker releases the recorder.
        try { recorder?.stop() } catch (_: IllegalStateException) {
        } catch (error: SecurityException) { onError(error) }
    }

    private fun recordLoop() {
        var local: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "Unsupported microphone format" }
            local = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, SAMPLE_RATE * 2)
            )
            check(local.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            recorder = local
            if (running.get()) {
                local.startRecording()
                check(local.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Microphone did not start"
                }
            }
            while (running.get()) {
                val file = encodeChunk(local)
                if (file != null) main.post { onChunkReady(file) }
            }
        } catch (error: SecurityException) {
            main.post { onError(error) }
        } catch (error: Exception) {
            if (running.get()) main.post { onError(error) }
        } finally {
            running.set(false)
            try { local?.stop() } catch (_: RuntimeException) { }
            runCatching { local?.release() }
            recorder = null
            worker = null
        }
    }

    private fun encodeChunk(input: AudioRecord): File? {
        val file = File.createTempFile("audio_chunk_", ".m4a", activity.cacheDir)
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var encoderStarted = false
        var successful = false
        var frames = 0L
        var samplesWritten = 0
        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = codec
            codec.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            val writer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            codec.start()
            encoderStarted = true
            val pcm = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inputEnded = false
            var outputEnded = false
            var finishDeadline = Long.MAX_VALUE
            var lastProgress = SystemClock.elapsedRealtime()

            while (!outputEnded) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val remaining = SAMPLE_RATE * 10L - frames
                        val wanted = minOf(pcm.size.toLong(), buffer.remaining().toLong(), remaining * 2)
                            .toInt().let { it - it % 2 }
                        val read = if (running.get() && wanted > 0) {
                            input.read(pcm, 0, wanted, AudioRecord.READ_BLOCKING)
                        } else 0
                        if (read < 0 && running.get()) error("AudioRecord.read failed: $read")
                        val timestampUs = frames * 1_000_000L / SAMPLE_RATE
                        if (read > 0) {
                            buffer.put(pcm, 0, read)
                            codec.queueInputBuffer(index, 0, read, timestampUs, 0)
                            frames += read / 2
                        } else {
                            codec.queueInputBuffer(index, 0, 0, timestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                            finishDeadline = SystemClock.elapsedRealtime() + 10_000L
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed twice" }
                        track = writer.addTrack(codec.outputFormat)
                        writer.start()
                        muxerStarted = true
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                    index >= 0 -> {
                        try {
                            if (info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "Encoded data arrived before output format" }
                                val buffer = checkNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                writer.writeSampleData(track, buffer, info)
                                samplesWritten++
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val now = SystemClock.elapsedRealtime()
                check(now <= finishDeadline && now - lastProgress < 15_000L) { "AAC encoder stalled" }
            }
            if (frames == 0L || samplesWritten == 0) return null
            check(muxerStarted)
            writer.stop()
            muxerStarted = false
            successful = true
            return file
        } finally {
            if (encoderStarted) try { encoder?.stop() } catch (_: RuntimeException) { }
            runCatching { encoder?.release() }
            if (muxerStarted) try { muxer?.stop() } catch (_: RuntimeException) { }
            runCatching { muxer?.release() }
            if (!successful) file.delete()
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        stopRecording()
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
    }

    companion object { private const val SAMPLE_RATE = 44_100 }
}
