package com.fleet.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The proximity watch replaces Google's geofence daemon, so its arithmetic and its edge-triggering
 * are checked here on the JVM. Nothing in this file proves the GPS path works on a handset. */
class ProximityWatchTest {

    private val home = GeoPoint("Home", 51.5074, -0.1278)
    private val cafe = GeoPoint("Cafe", 51.5078, -0.1278, radiusMeters = 60.0)

    @Test fun knownLongitudeDistanceIsWithinHalfPercent() {
        // 51.5074,-0.1278 to 48.8566,2.3522 is about 343.6 km great circle.
        val meters = haversineMeters(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343_560.0, meters, 1_800.0)
    }

    @Test fun pointsJustInsideAndOutsideTheRadius() {
        // One degree of latitude is ~111.195 km, so 0.00045° is ~50 m and 0.00135° is ~150 m.
        val near = GeoPoint("Near", home.latitude + 0.00045, home.longitude)
        val far = GeoPoint("Far", home.latitude + 0.00135, home.longitude)
        assertTrue(haversineMeters(home.latitude, home.longitude, near.latitude, near.longitude) < 100)
        assertTrue(haversineMeters(home.latitude, home.longitude, far.latitude, far.longitude) > 100)
        assertEquals("Home", nearestWithin(near.latitude, near.longitude, listOf(home))?.id)
        assertNull(nearestWithin(far.latitude, far.longitude, listOf(home)))
    }

    @Test fun overlappingPointsReportTheNearest() {
        val found = nearestWithin(51.5078, -0.1278, listOf(home, cafe))
        assertEquals(cafe.id, found?.id)
    }

    @Test fun arrivalFiresOnceAndDepartureFiresOnce() {
        val watch = ProximityWatch()
        assertEquals(listOf("Home"), watch.crossings(home.latitude, home.longitude, listOf(home)).entered.map { it.id })
        assertTrue(watch.crossings(home.latitude, home.longitude, listOf(home)).entered.isEmpty())
        assertTrue(watch.crossings(home.latitude, home.longitude, listOf(home)).exited.isEmpty())
        assertEquals(listOf("Home"), watch.crossings(home.latitude + 0.00135, home.longitude, listOf(home)).exited.map { it.id })
        assertTrue(watch.crossings(home.latitude + 0.00135, home.longitude, listOf(home)).exited.isEmpty())
    }

    @Test fun everyPointIsTrackedIndependently() {
        val watch = ProximityWatch()
        val crossings = watch.crossings(cafe.latitude, cafe.longitude, listOf(home, cafe))
        assertEquals(setOf("Home", "Cafe"), crossings.entered.map { it.id }.toSet())
        val left = watch.crossings(61.5074, -0.1278, listOf(home, cafe))
        assertEquals(setOf("Home", "Cafe"), left.exited.map { it.id }.toSet())
    }

    @Test fun aDeletedPointStopsBeingRemembered() {
        val watch = ProximityWatch()
        watch.crossings(home.latitude, home.longitude, listOf(home))
        watch.crossings(home.latitude, home.longitude, emptyList())
        // Re-adding the same boundary while still standing in it must report arrival again.
        assertEquals(listOf("Home"), watch.crossings(home.latitude, home.longitude, listOf(home)).entered.map { it.id })
    }

    @Test fun coordinatesAreValidatedBeforeAnythingIsStored() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint("Bad", 91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint("Bad", 0.0, 181.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint("Bad", 0.0, 0.0, radiusMeters = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(" ", 0.0, 0.0) }
    }

    @Test fun boundariesCarryTheirRadiusIntoTheWatch() {
        // Parsing saved boundaries needs org.json, which Android stubs out in JVM tests, so only the
        // radius and coordinate handoff is checked here. Reading real stored preferences is a handset
        // test: add a boundary, reboot, and confirm the next crossing still reports it.
        val fence = FleetGeofence("Home", 51.5074, -0.1278, 250f).toGeoPoint()
        val default = GeoPoint("Default", fence.latitude, fence.longitude)
        // 150 m from the centre: outside a default boundary, inside this one.
        val northOfCentre = fence.latitude + 0.00135
        assertEquals(fence.id, nearestWithin(northOfCentre, fence.longitude, listOf(fence))?.id)
        assertNull(nearestWithin(northOfCentre, fence.longitude, listOf(default)))
        val crossings = ProximityWatch().crossings(northOfCentre, fence.longitude, listOf(fence))
        assertEquals(listOf(fence.id), crossings.entered.map { it.id })
    }

    /** The store is what makes a killed-and-restarted service idempotent. Android's SharedPreferences
     * is stubbed out in JVM tests, so the in-memory stand-in from the same file is used here. */
    @Test fun restartingInsideABoundaryReportsNothingNew() {
        val store = TransientProximityState()
        val arrived = ProximityWatch(store).crossings(home.latitude, home.longitude, listOf(home))
        assertEquals(listOf("Home"), arrived.entered.map { it.id })
        val afterRestart = ProximityWatch(store).crossings(home.latitude, home.longitude, listOf(home))
        assertTrue("arrival repeated across a restart", afterRestart.entered.isEmpty())
        assertTrue(afterRestart.exited.isEmpty())
    }

    /** Ids left in the store that the owner no longer has as boundaries are forgotten quietly, not
     * announced as departures on the first fix after an upgrade or a deletion. */
    @Test fun restoredStateForUnknownBoundariesIsForgottenNotReported() {
        val store = TransientProximityState()
        store.save(setOf("Home", "Somewhere The Owner Deleted"))
        val crossings = ProximityWatch(store).crossings(home.latitude, home.longitude, listOf(home))
        assertTrue(crossings.entered.isEmpty())
        assertTrue(crossings.exited.isEmpty())
        assertTrue(ProximityWatch(store).crossings(home.latitude, home.longitude, listOf(home)).entered.isEmpty())
    }

    @Test fun aDepartureThatHappenedWhileTheServiceWasDeadIsReportedOnce() {
        val store = TransientProximityState()
        ProximityWatch(store).crossings(home.latitude, home.longitude, listOf(home))
        val away = home.latitude + 0.00135
        val exited = ProximityWatch(store).crossings(away, home.longitude, listOf(home)).exited.map { it.id }
        assertEquals(listOf("Home"), exited)
        assertTrue(ProximityWatch(store).crossings(away, home.longitude, listOf(home)).exited.isEmpty())
    }

    @Test fun stoppingSharingLetsTheNextStartAnnounceArrivalAgain() {
        val store = TransientProximityState()
        val watch = ProximityWatch(store)
        watch.crossings(home.latitude, home.longitude, listOf(home))
        watch.reset()
        assertEquals(listOf("Home"), ProximityWatch(store).crossings(home.latitude, home.longitude, listOf(home)).entered.map { it.id })
    }
}
