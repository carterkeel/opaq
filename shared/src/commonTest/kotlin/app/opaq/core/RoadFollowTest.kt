package app.opaq.core

import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Prediction along roads (#97) and drawing travelling people on the road they're on (#92). */
class RoadFollowTest {
    private val t0 = 1_763_000_000L

    /** A point [east] and [north] metres from a fixed origin. */
    private fun at(east: Double, north: Double): GeoPoint {
        val (lat, _) = Prediction.offset(41.0, -87.6, 0.0, north)
        val (_, lon) = Prediction.offset(41.0, -87.6, 90.0, east)
        return GeoPoint(lat, lon)
    }

    private fun meters(a: GeoPoint, b: GeoPoint) = MotionPolicy.distanceMeters(a.lat, a.lon, b.lat, b.lon)
    private fun Prediction.Estimate.point() = GeoPoint(lat, lon)

    private fun mover(p: GeoPoint, heading: Float, speed: Float = 20f, activity: Activity = Activity.DRIVING) =
        MemberLocation(p.lat, p.lon, accuracy = 5f, speed = speed, heading = heading, activity = activity, time = t0)

    /** East for 300 m, a round bend of radius 50 m, then north for 600 m. */
    private val bend: List<GeoPoint> = buildList {
        add(at(0.0, 0.0))
        add(at(300.0, 0.0))
        for (i in 1..9) {
            val a = i * PI / 20 // 0..90 degrees in 10 steps
            add(at(300.0 + 50 * sin(a), 50 - 50 * cos(a)))
        }
        add(at(350.0, 50.0))
        add(at(350.0, 650.0))
    }
    private val bendLength = 300 + 50 * PI / 2 + 600

    /** The point [d] metres along [bend]. */
    private fun alongBend(d: Double): GeoPoint = Polyline(bend).at(d)

    @Test
    fun aDriverFollowsTheRoadRoundABend() {
        val loc = mover(at(100.0, 3.0), heading = 90f)
        val roads = Roads(listOf(bend))
        for (t in listOf(5.0, 10.0, 20.0, 30.0)) {
            val e = Prediction.estimate(loc, t0 + t, roads)
            val expected = alongBend(100 + 20 * t)
            assertTrue(meters(e.point(), expected) < 1.0, "at $t s: ${meters(e.point(), expected)} m off the road")
        }
        // Without the road it would have gone straight on, far from where the road goes.
        val straight = Prediction.estimate(loc, t0 + 30.0)
        assertTrue(meters(straight.point(), alongBend(700.0)) > 200)
    }

    @Test
    fun aDriverNearTheRoadIsDrawnOnIt() {
        val loc = mover(at(100.0, 4.0), heading = 90f)
        val plan = Prediction.plan(loc, Roads(listOf(bend)))
        assertTrue(plan.onRoad)
        assertTrue(meters(plan.start, at(100.0, 0.0)) < 0.1, "within 6 m: on the road")
    }

    @Test
    fun beyondSixMetresTheDotStaysWhereItReallyIs() {
        val roads = Roads(listOf(bend))
        // A walker 9 m from the road is somewhere else (a path, a park): not moved, not following it.
        val walker = mover(at(100.0, 9.0), heading = 90f, speed = 1.4f, activity = Activity.WALKING)
        val w = Prediction.plan(walker, roads)
        assertFalse(w.onRoad)
        assertEquals(GeoPoint(walker.lat, walker.lon), w.start)
        // A driver 9 m off the centre line (a wide road) still starts where they really are...
        val driver = mover(at(100.0, 9.0), heading = 90f)
        val d = Prediction.plan(driver, roads)
        assertEquals(GeoPoint(driver.lat, driver.lon), d.start)
        // ...and someone 30 m away is off-road altogether.
        val far = mover(at(100.0, 30.0), heading = 90f)
        val f = Prediction.plan(far, roads)
        assertFalse(f.onRoad)
        assertEquals(GeoPoint(far.lat, far.lon), f.start)
        assertTrue(abs(meters(f.at(t0 + 10.0).point(), at(300.0, 30.0))) < 1, "straight on along the heading")
    }

    @Test
    fun someoneCrossingARoadIsNotPutOnIt() {
        val loc = mover(at(100.0, 3.0), heading = 0f, speed = 1.5f, activity = Activity.WALKING)
        val plan = Prediction.plan(loc, Roads(listOf(bend)))
        assertFalse(plan.onRoad)
        assertEquals(GeoPoint(loc.lat, loc.lon), plan.start)
    }

    @Test
    fun itStopsWhereTheKnownRoadEnds() {
        val road = listOf(at(0.0, 0.0), at(200.0, 0.0))
        val loc = mover(at(50.0, 0.0), heading = 90f)
        val roads = Roads(listOf(road))
        var last = 0.0
        for (t in 1..60) {
            val e = Prediction.estimate(loc, t0 + t.toDouble(), roads)
            val d = meters(at(50.0, 0.0), e.point())
            assertTrue(d >= last - 1e-6, "never goes back")
            assertTrue(d <= 150.0 + 1e-6, "never past the end of the road: $d")
            last = d
        }
        assertTrue(last > 149, "gets to the end: $last")
        assertFalse(Prediction.plan(loc, roads).advancing(t0 + 60.0))
    }

    @Test
    fun itStopsAtAJunctionInsteadOfGuessingATurn() {
        // The road ends at a T: left and right are both possible.
        val road = listOf(at(0.0, 0.0), at(200.0, 0.0))
        val cross = listOf(at(200.0, -300.0), at(200.0, 300.0))
        val loc = mover(at(50.0, 0.0), heading = 90f)
        val e = Prediction.estimate(loc, t0 + 40.0, Roads(listOf(road, cross)))
        assertTrue(meters(e.point(), at(200.0, 0.0)) < 1, "waits at the junction: ${meters(e.point(), at(200.0, 0.0))}")

        // A fork into two roads going roughly ahead: no guess either.
        val left = listOf(at(200.0, 0.0), at(400.0, 60.0))
        val right = listOf(at(200.0, 0.0), at(400.0, -60.0))
        val f = Prediction.estimate(loc, t0 + 40.0, Roads(listOf(road, left, right)))
        assertTrue(meters(f.point(), at(200.0, 0.0)) < 1)
    }

    @Test
    fun aRoadSplitIntoPiecesIsFollowedStraightOn() {
        // Map data splits roads at tile edges (often overlapping a little) and where details change.
        val a = listOf(at(0.0, 0.0), at(205.0, 0.0))
        val b = listOf(at(195.0, 0.0), at(400.0, 0.0), at(600.0, 10.0))
        val side = listOf(at(400.0, 0.0), at(400.0, 300.0)) // a side street at right angles doesn't stop it
        val loc = mover(at(50.0, 0.0), heading = 90f)
        val e = Prediction.estimate(loc, t0 + 20.0, Roads(listOf(a, side, b)))
        assertTrue(meters(e.point(), Polyline(b).at(450.0 - 195.0)) < 1.5, "450 m along: ${e.lat}, ${e.lon}")
    }

    @Test
    fun followingGoesTheWayTheyAreHeading() {
        val roads = Roads(listOf(bend))
        val loc = mover(alongBend(500.0), heading = 180f) // southbound on the north leg, towards the bend
        val e = Prediction.estimate(loc, t0 + 10.0, roads)
        assertTrue(meters(e.point(), alongBend(300.0)) < 1)
    }

    @Test
    fun polylineLookups() {
        val line = Polyline(listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0)))
        assertTrue(abs(line.length - 200) < 0.1)
        assertTrue(meters(line.at(150.0), at(100.0, 50.0)) < 0.1)
        assertEquals(line.start, line.at(-5.0))
        assertEquals(line.end, line.at(500.0))
        assertTrue(abs(line.from(50.0).length - 150) < 0.1)
        assertNotNull(Roads(listOf(bend)).along(at(10.0, 1.0), 90.0, 6.0))
        assertNull(Roads(listOf(bend)).along(at(10.0, 10.0), 90.0, 6.0))
    }
}
