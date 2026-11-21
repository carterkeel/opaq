package app.opaq.core

import app.opaq.core.PlaceAlerts.Change
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Place
import app.opaq.core.model.Point
import app.opaq.core.model.Precision
import kotlin.test.Test
import kotlin.test.assertEquals

/** Alert areas (#151): when an update counts as arriving or leaving. */
class PlacesTest {
    private val now = 1_763_000_000L

    // A 30 m (about 100 ft) area round a house. 0.0001° of latitude is about 11 m.
    private val home = Place("p1", "Home", 30.0, -87.0, radius = 30.0)

    private fun at(lat: Double, accuracy: Float = 8f, age: Long = 0, points: List<Point> = emptyList()) =
        MemberLocation(lat = lat, lon = -87.0, accuracy = accuracy, time = now - age, points = points)

    @Test
    fun arrivingAndLeaving() {
        assertEquals(PlaceAlerts.Result(Change.ARRIVED, true), PlaceAlerts.check(home, false, at(30.0001), now))
        assertEquals(PlaceAlerts.Result(Change.NONE, true), PlaceAlerts.check(home, true, at(30.0002), now))
        assertEquals(PlaceAlerts.Result(Change.LEFT, false), PlaceAlerts.check(home, true, at(30.0006), now))
        assertEquals(PlaceAlerts.Result(Change.NONE, false), PlaceAlerts.check(home, false, at(30.0006), now))
    }

    @Test
    fun wobbleAtTheEdgeDoesNotLeave() {
        // 44 m out: past the 30 m edge but within the extra 25 m.
        assertEquals(Change.NONE, PlaceAlerts.check(home, true, at(30.0004), now).change)
    }

    @Test
    fun drivingThroughBetweenUpdatesCountsAsArriving() {
        val through = at(30.002, points = listOf(Point(29.998, -87.0, now - 40), Point(30.0, -87.0, now - 20)))
        assertEquals(PlaceAlerts.Result(Change.ARRIVED, false), PlaceAlerts.check(home, false, through, now))
    }

    @Test
    fun approximateStalePausedAndRoughUpdatesNeverCount() {
        val inside = at(30.0001)
        assertEquals(Change.NONE, PlaceAlerts.check(home, false, inside.copy(precision = Precision.NEIGHBORHOOD), now).change)
        assertEquals(Change.NONE, PlaceAlerts.check(home, false, at(30.0001, age = PlaceAlerts.MAX_AGE_SECONDS + 1), now).change)
        assertEquals(Change.NONE, PlaceAlerts.check(home, false, inside.copy(status = MemberLocation.PAUSED), now).change)
        assertEquals(Change.NONE, PlaceAlerts.check(home, false, at(30.0001, accuracy = 200f), now).change)
    }
}
