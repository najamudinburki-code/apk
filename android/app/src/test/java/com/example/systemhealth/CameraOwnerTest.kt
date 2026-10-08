package com.example.systemhealth
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraOwnerTest {
    // The token is process-wide, so a leaked holder would make a later test fail for the wrong reason.
    @After fun releaseEverything() {
        CameraOwner.release(CameraOwner.PHOTO)
        CameraOwner.release(CameraOwner.LIVE_VIEW)
    }

    @Test fun the_first_job_to_ask_gets_the_camera() {
        assertTrue(CameraOwner.acquire(CameraOwner.PHOTO))
        assertEquals(CameraOwner.PHOTO, CameraOwner.holder())
    }

    @Test fun a_live_view_cannot_take_the_camera_from_a_photo() {
        assertTrue(CameraOwner.acquire(CameraOwner.PHOTO))
        assertFalse(CameraOwner.acquire(CameraOwner.LIVE_VIEW))
        assertEquals(CameraOwner.PHOTO, CameraOwner.holder())
    }

    @Test fun the_holder_can_hand_the_camera_on() {
        assertTrue(CameraOwner.acquire(CameraOwner.LIVE_VIEW))
        assertTrue(CameraOwner.release(CameraOwner.LIVE_VIEW))
        assertTrue(CameraOwner.acquire(CameraOwner.PHOTO))
    }

    @Test fun a_late_teardown_cannot_cut_another_job_off() {
        CameraOwner.acquire(CameraOwner.PHOTO)
        // A stopped live view reporting in late must not free the photo that is mid-capture.
        assertFalse(CameraOwner.release(CameraOwner.LIVE_VIEW))
        assertEquals(CameraOwner.PHOTO, CameraOwner.holder())
    }

    @Test fun releasing_twice_is_harmless() {
        CameraOwner.acquire(CameraOwner.LIVE_VIEW)
        assertTrue(CameraOwner.release(CameraOwner.LIVE_VIEW))
        assertFalse(CameraOwner.release(CameraOwner.LIVE_VIEW))
        assertNull(CameraOwner.holder())
    }

    @Test fun only_one_of_two_threads_starting_at_once_wins() {
        val attempts = 8
        val pool = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val winners = AtomicInteger(0)
        repeat(attempts) {
            pool.execute {
                ready.await()
                if (CameraOwner.acquire(CameraOwner.LIVE_VIEW)) winners.incrementAndGet()
                done.countDown()
            }
        }
        ready.countDown()
        assertTrue("the camera never answered the token", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, winners.get())
    }
}
