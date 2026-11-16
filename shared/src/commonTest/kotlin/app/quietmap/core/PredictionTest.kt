package app.quietmap.core

import app.quietmap.core.model.Activity
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.Point
import app.quietmap.core.model.Precision
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictionTest {
    private val t0 = 1_763_000_000L

    /** Driving due east at [speed] m/s along a straight line, or bending north when [winding]. */
    private fun driving(speed: Float = 20f, winding: Boolean = false, heading: Float? = 90f, precision: Precision? = null): MemberLocation {
        val points = (5 downTo 1).map { i ->
            val back = speed * i * 6.0
            val (lat, lon) = Prediction.offset(41.0, -87.6, 270.0, back)
            Point(if (winding) lat - i * i * 0.0004 else lat, lon, t0 - i * 6)
        }
        return MemberLocation(41.0, -87.6, accuracy = 6f, speed = speed, heading = heading, activity = Activity.DRIVING, time = t0, points = points, precision = precision)
    }

    private fun meters(loc: MemberLocation, e: Prediction.Estimate) = MotionPolicy.distanceMeters(loc.lat, loc.lon, e.lat, e.lon)

    @Test
    fun aStraightDriveIsCarriedForwardAtFullSpeedUntilTheNextUpdateIsDue() {
        val loc = driving()
        val e10 = Prediction.estimate(loc, t0 + 10.0)
        assertTrue(e10.predicted && e10.advancing)
        assertTrue(abs(meters(loc, e10) - 200) < 1, "10 s at 20 m/s: ${meters(loc, e10)}")
        assertTrue(abs(Prediction.bearing(loc.lat, loc.lon, e10.lat, e10.lon) - 90) < 0.5, "keeps heading east")
        val held = Prediction.estimate(loc, t0 + 80.0)
        assertFalse(held.advancing)
        assertTrue(abs(meters(loc, held) - 20 * Prediction.HORIZON_SECONDS) < 1, "stops at the horizon")
    }

    @Test
    fun aWindingPathSlowsDownAndStopsShort() {
        val loc = driving(winding = true)
        val far = Prediction.estimate(loc, t0 + 45.0)
        assertTrue(far.predicted)
        assertTrue(meters(loc, far) < 20 * 16, "winding path goes at most about 15 s ahead: ${meters(loc, far)}")
    }

    @Test
    fun withoutAHeadingThePathGivesTheDirection() {
        val loc = driving(heading = null)
        val e = Prediction.estimate(loc, t0 + 5.0)
        assertTrue(e.predicted)
        assertTrue(abs(Prediction.bearing(loc.lat, loc.lon, e.lat, e.lon) - 90) < 1)
    }

    @Test
    fun cautiousCasesShowTheRealPosition() {
        val loc = driving()
        fun shownAsIs(l: MemberLocation, now: Double) = Prediction.estimate(l, now).let { assertFalse(it.predicted); assertEquals(l.lat, it.lat); assertEquals(l.lon, it.lon) }
        shownAsIs(loc, t0 + 0.0)
        shownAsIs(loc, t0 + Prediction.STALE_SECONDS + 1.0) // may have stopped or turned since
        shownAsIs(loc.copy(activity = Activity.STILL), t0 + 10.0)
        shownAsIs(loc.copy(speed = 0.4f), t0 + 10.0)
        shownAsIs(loc.copy(speed = null), t0 + 10.0)
        shownAsIs(loc.copy(accuracy = 120f), t0 + 10.0)
        shownAsIs(driving(precision = Precision.NEIGHBORHOOD), t0 + 10.0) // approximate locations never move
        shownAsIs(loc.copy(heading = null, points = emptyList()), t0 + 10.0)
    }
}
