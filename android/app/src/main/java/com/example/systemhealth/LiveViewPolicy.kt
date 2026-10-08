package com.example.systemhealth

/** Every timing and size decision behind the dashboard's live camera view, kept free of Android so
 * it can be tested directly.
 *
 * A live view differs from a photo in one way that matters to the phone: it repeats. These numbers
 * are what stop a repeat from becoming an unlimited upload, and they are written down here rather
 * than buried in the camera code so the whole budget is readable in one place. */
internal object LiveViewPolicy {
    /** Two frames a second is enough to follow what the lens sees, and it is the rate the queue is
     * sized for. A camera delivers far faster than this, so the extras are ignored before any
     * conversion work happens. */
    const val FRAME_INTERVAL_MS = 500L

    /** A session ends by itself. Android keeps its camera indicator lit for the whole time, and an
     * owner should never have to wonder when the phone stopped streaming. */
    const val MAX_SESSION_MS = 120_000L

    /** Bytes one session may add to the phone's file vault and to the dashboard's 100 MiB quota.
     * At two frames a second this is about a minute of video; whichever limit comes first wins. */
    const val MAX_SESSION_BYTES = 6L * 1024 * 1024

    /** A late frame shows a moment that has already passed, so the pipeline holds one unsent frame
     * and drops whatever arrives behind it instead of building a backlog of the past. */
    const val PENDING_FRAMES = 1

    /** Frames only need to be legible in a dashboard list, so both the size and the compression are
     * chosen to keep each upload small rather than sharp. */
    const val JPEG_QUALITY = 40
    const val TARGET_WIDTH = 640
    const val TARGET_HEIGHT = 480
    const val MIN_WIDTH = 320
    const val MIN_HEIGHT = 240

    private const val TARGET_AREA = TARGET_WIDTH.toLong() * TARGET_HEIGHT

    /** True when the next frame may be converted. The camera's own cadence is never the limit. */
    fun isDue(nowMs: Long, lastFrameMs: Long): Boolean = nowMs - lastFrameMs >= FRAME_INTERVAL_MS

    /** The rate the owner is told about, read from the interval rather than repeated in prose. */
    fun framesPerSecond(): Int = (1_000L / FRAME_INTERVAL_MS).toInt()

    /** The largest size the device really lists that still fits inside VGA, so a frame stays small
     * whatever the hardware offers. Falls back to the smallest usable size, and to null when the
     * camera lists nothing the pipeline could read. */
    fun pickPreviewSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
        sizes.filter { (width, height) ->
            width >= MIN_WIDTH && height >= MIN_HEIGHT && width.toLong() * height <= TARGET_AREA
        }.maxByOrNull { (width, height) -> width.toLong() * height }?.let { return it }
        // Nothing in the useful range: a tiny frame still proves the lens works, while an oversized
        // one must not be chosen and then shrunk after the camera has already paid for it.
        return sizes.filter { it.first > 0 && it.second > 0 }.minByOrNull { it.first.toLong() * it.second }
    }

    fun sessionExpired(startElapsedMs: Long, nowElapsedMs: Long): Boolean =
        nowElapsedMs - startElapsedMs >= MAX_SESSION_MS

    /** Why a session ended on its own, or null while it still has room. The same words go to the
     * owner's phone and to the dashboard, so neither has to guess. */
    fun limitReached(startElapsedMs: Long, nowElapsedMs: Long, bytesSoFar: Long): String? = when {
        sessionExpired(startElapsedMs, nowElapsedMs) -> "Live view reached its ${MAX_SESSION_MS / 1000}-second limit."
        bytesSoFar > MAX_SESSION_BYTES -> "Live view reached its ${MAX_SESSION_BYTES / (1024 * 1024)}-MiB upload limit."
        else -> null
    }
}
