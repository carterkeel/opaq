package app.quietmap.core

import app.quietmap.core.model.Activity
import app.quietmap.core.model.MemberLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The catch-up from where a dot is drawn to a new update (#93). */
class TrackTest {
    private val t0 = 1_763_000_000L

    private fun at(east: Double, north: Double): GeoPoint {
        val (lat, _) = Prediction.offset(41.0, -87.6, 0.0, north)
        val (_, lon) = Prediction.offset(41.0, -87.6, 90.0, east)
        return GeoPoint(lat, lon)
    }

    private fun meters(a: GeoPoint, b: GeoPoint) = MotionPolicy.distanceMeters(a.lat, a.lon, b.lat, b.lon)

    private fun driverAt(p: GeoPoint, speed: Float = 20f) =
        MemberLocation(p.lat, p.lon, accuracy = 5f, speed = speed, heading = 90f, activity = Activity.DRIVING, time = t0)

    @Test
    fun theDotTravelsToTheNewEstimateWithoutJumping() {
        val plan = Prediction.plan(driverAt(at(0.0, 0.0)))
        val now = t0 + 5.0 // the update arrives 5 s after its fix
        val from = at(-150.0, 0.0) // the old estimate fell 150 m behind
        val track = assertNotNull(Track.build(from, plan, now))
        assertEquals(from, track.at(now), "starts where the dot is")
        var last = -1.0
        var lastPoint = from
        var t = now
        while (t <= now + track.duration + 2) {
            val s = track.progress(t)
            assertTrue(s >= last, "never goes back")
            val p = track.at(t)
            // At 30 frames a second no step is a visible leap, and the dot never falls below their speed.
            val step = meters(lastPoint, p)
            assertTrue(step < 5.0, "step of $step m at ${t - now}")
            if (t > now) assertTrue(step >= 20.0 / 30 - 0.01, "slower than the car: $step m")
            last = s; lastPoint = p
            t += 1.0 / 30
        }
        val end = now + track.duration
        assertTrue(meters(track.at(end), plan.at(end).let { GeoPoint(it.lat, it.lon) }) < 0.01, "ends on the estimate")
        assertTrue(meters(track.at(end + 3), plan.at(end + 3).let { GeoPoint(it.lat, it.lon) }) < 0.01, "then follows it")
    }

    @Test
    fun theTimeTakenScalesWithTheGap() {
        assertEquals(0.0, Track.durationFor(0.1, 20.0))
        assertTrue(Track.durationFor(40.0, 20.0) < Track.durationFor(120.0, 20.0))
        assertTrue(Track.durationFor(120.0, 20.0) < Track.durationFor(400.0, 20.0))
        assertEquals(Track.MAX_SECONDS, Track.durationFor(2_000.0, 20.0))
        val plan = Prediction.plan(driverAt(at(0.0, 0.0)))
        val near = assertNotNull(Track.build(at(-10.0, 0.0), plan, t0.toDouble()))
        val far = assertNotNull(Track.build(at(-300.0, 0.0), plan, t0.toDouble()))
        assertTrue(near.duration < far.duration)
    }

    @Test
    fun aGoodGuessNeedsAlmostNoCorrection() {
        // The old estimate is where the new update says: the dot just carries on at the car's speed.
        val plan = Prediction.plan(driverAt(at(0.0, 0.0)))
        val now = t0 + 5.0
        val drawn = plan.at(now).let { GeoPoint(it.lat, it.lon) }
        val track = assertNotNull(Track.build(drawn, plan, now))
        assertEquals(0.0, track.duration)
        assertTrue(meters(track.at(now + 1), at(120.0, 0.0)) < 0.5)
    }

    @Test
    fun aDotThatGotAheadWaitsInsteadOfGoingBack() {
        val plan = Prediction.plan(driverAt(at(0.0, 0.0)))
        val now = t0 + 5.0 // the estimate is 100 m along; the dot was drawn 150 m along
        val drawn = at(150.0, 0.0)
        val track = assertNotNull(Track.build(drawn, plan, now))
        assertTrue(meters(track.at(now + 1), drawn) < 0.01, "waits")
        var last = -1.0
        for (i in 0..300) {
            val t = now + i / 30.0
            assertTrue(track.progress(t) >= last)
            last = track.progress(t)
        }
        assertTrue(meters(track.at(now + 5), at(200.0, 0.0)) < 0.01, "carries on with the estimate once it catches up")
        assertTrue(track.done(now + 5))
    }

    @Test
    fun theDotFollowsTheRecentPathRoundACorner() {
        // The dot was drawn at the old update; the new one turned a corner since.
        val plan = Prediction.plan(driverAt(at(200.0, 200.0)).copy(heading = 0f))
        val recent = listOf(at(100.0, 0.0), at(200.0, 0.0), at(200.0, 100.0))
        val track = assertNotNull(Track.build(at(0.0, 0.0), plan, t0.toDouble(), recent))
        // Halfway it is on the path the person took, not cutting across the corner.
        val mid = track.at(t0 + track.duration / 2)
        val onPath = listOf(Polyline(listOf(at(0.0, 0.0), at(200.0, 0.0))), Polyline(listOf(at(200.0, 0.0), at(200.0, 200.0))))
        val off = onPath.minOf { line -> (0..400).minOf { meters(line.at(it * 0.5), mid) } }
        assertTrue(off < 1.0, "$off m off the path")
    }

    @Test
    fun aHugeJumpIsShownAtOnce() {
        val plan = Prediction.plan(driverAt(at(0.0, 0.0)))
        assertNull(Track.build(at(-Track.JUMP_METERS - 500, 0.0), plan, t0.toDouble()))
        // Back to a still position (a stale update) is a short trip, not a jump.
        assertNotNull(Track.build(at(400.0, 0.0), Prediction.Plan.still(at(0.0, 0.0)), t0 + 200.0))
    }

    @Test
    fun onARoadTheCatchUpRunsAlongIt() {
        val road = Roads(listOf(listOf(at(-500.0, 0.0), at(0.0, 0.0), at(0.0, 500.0))))
        val loc = driverAt(at(0.0, 100.0)).copy(heading = 0f)
        val plan = Prediction.plan(loc, road)
        // Drawn before the corner; recent points wobble beside the road.
        val recent = listOf(at(-60.0, 4.0), at(-3.0, -4.0), at(4.0, 50.0))
        val track = assertNotNull(Track.build(at(-100.0, 0.0), plan, t0.toDouble(), recent, road))
        for (i in 0..20) {
            val p = track.at(t0 + track.duration * i / 20)
            val toRoad = minOf((0..1000).minOf { meters(at(-500.0 + it * 0.5, 0.0), p) }, (0..1000).minOf { meters(at(0.0, it * 0.5), p) })
            assertTrue(toRoad < 1.0, "step $i is $toRoad m off the road")
        }
    }
}
