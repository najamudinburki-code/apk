package com.example.systemhealth
import org.junit.Assert.*
import org.junit.Test

class LiveViewPolicyTest {
    @Test fun a_frame_is_due_every_half_second() {
        assertTrue(LiveViewPolicy.isDue(1_000, 500))
        assertTrue(LiveViewPolicy.isDue(500, 0))
        assertFalse(LiveViewPolicy.isDue(499, 0))
        // A clock that went backwards must not turn into a burst of frames.
        assertFalse(LiveViewPolicy.isDue(100, 5_000))
    }

    @Test fun the_rate_the_owner_is_told_matches_the_interval() {
        assertEquals(2, LiveViewPolicy.framesPerSecond())
    }

    @Test fun a_preview_is_the_largest_size_that_still_fits_inside_vga() {
        val sizes = listOf(1920 to 1080, 1280 to 720, 640 to 480, 352 to 288)
        assertEquals(640 to 480, LiveViewPolicy.pickPreviewSize(sizes))
    }

    @Test fun order_does_not_decide_the_choice() {
        assertEquals(640 to 480, LiveViewPolicy.pickPreviewSize(listOf(352 to 288, 640 to 480, 3840 to 2160)))
    }

    @Test fun a_frame_too_small_to_be_legible_is_only_a_last_resort() {
        // Nothing inside the useful range: the tiniest real size still proves the lens works, while an
        // oversized one must not be picked and shrunk after the camera already paid for it.
        assertEquals(1920 to 1080, LiveViewPolicy.pickPreviewSize(listOf(3840 to 2160, 1920 to 1080)))
        assertEquals(160 to 120, LiveViewPolicy.pickPreviewSize(listOf(160 to 120, 1920 to 1080)))
    }

    @Test fun a_camera_listing_nothing_usable_is_refused_rather_than_guessed() {
        assertNull(LiveViewPolicy.pickPreviewSize(emptyList()))
        assertNull(LiveViewPolicy.pickPreviewSize(listOf(0 to 0)))
        assertNull(LiveViewPolicy.pickPreviewSize(listOf(640 to 0)))
    }

    @Test fun a_session_ends_by_itself_at_two_minutes() {
        assertFalse(LiveViewPolicy.sessionExpired(0, LiveViewPolicy.MAX_SESSION_MS - 1))
        assertTrue(LiveViewPolicy.sessionExpired(0, LiveViewPolicy.MAX_SESSION_MS))
        assertTrue(LiveViewPolicy.sessionExpired(30_000, 30_000 + LiveViewPolicy.MAX_SESSION_MS))
    }

    @Test fun the_reason_a_session_ended_names_the_limit_that_was_reached() {
        assertNull(LiveViewPolicy.limitReached(0, 1_000, 1_024))
        assertEquals("Live view reached its 120-second limit.",
            LiveViewPolicy.limitReached(0, LiveViewPolicy.MAX_SESSION_MS, 0))
        assertEquals("Live view reached its 6-MiB upload limit.",
            LiveViewPolicy.limitReached(0, 1_000, LiveViewPolicy.MAX_SESSION_BYTES + 1))
    }

    @Test fun the_time_limit_is_stated_before_the_byte_limit() {
        // Both can be true at once; the owner should hear the plain "it ran out of time" first.
        val reason = LiveViewPolicy.limitReached(0, LiveViewPolicy.MAX_SESSION_MS, LiveViewPolicy.MAX_SESSION_BYTES * 2)
        assertTrue(reason!!.contains("second limit"))
    }

    @Test fun the_budget_is_small_enough_for_one_phone_to_carry() {
        // 6 MiB of frames must fit beside everything else the file vault and the 100 MiB quota hold.
        assertTrue(LiveViewPolicy.MAX_SESSION_BYTES < 10L * 1024 * 1024)
        assertEquals(1, LiveViewPolicy.PENDING_FRAMES)
        assertTrue(LiveViewPolicy.JPEG_QUALITY in 1..100)
    }
}
