package app.quietmap.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadSnapTest {
    /** A point [east] and [north] metres from a fixed origin. */
    private fun at(east: Double, north: Double): GeoPoint {
        val (lat, _) = Prediction.offset(41.0, -87.6, 0.0, north)
        val (_, lon) = Prediction.offset(41.0, -87.6, 90.0, east)
        return GeoPoint(lat, lon)
    }

    private fun meters(a: GeoPoint, b: GeoPoint) = MotionPolicy.distanceMeters(a.lat, a.lon, b.lat, b.lon)

    /** A road running east for 400 m, then north for 400 m. */
    private val road = listOf(at(0.0, 0.0), at(400.0, 0.0), at(400.0, 400.0))

    @Test
    fun wobbleBesideARoadIsPulledOntoIt() {
        val path = listOf(at(50.0, 8.0), at(120.0, -9.0), at(200.0, 6.0), at(300.0, -7.0))
        val out = RoadSnap.snap(path, listOf(road), 20.0)
        assertEquals(4, out.size)
        out.forEachIndexed { i, p ->
            assertTrue(meters(p, at(listOf(50.0, 120.0, 200.0, 300.0)[i], 0.0)) < 0.5, "point $i should sit on the road")
        }
    }

    @Test
    fun pointsFarFromAnyRoadStayWhereTheyWere() {
        val path = listOf(at(50.0, 60.0), at(120.0, 75.0), at(200.0, 90.0))
        assertEquals(path, RoadSnap.snap(path, listOf(road), 20.0))
    }

    @Test
    fun leavingTheRoadKeepsTheRealPositions() {
        val path = listOf(at(50.0, 5.0), at(120.0, -4.0), at(180.0, 40.0), at(220.0, 90.0))
        val out = RoadSnap.snap(path, listOf(road), 20.0)
        assertTrue(meters(out[1], at(120.0, 0.0)) < 0.5)
        assertEquals(path[2], out[2])
        assertEquals(path[3], out[3])
    }

    @Test
    fun crossingARoadOnceDoesNotSnap() {
        val path = listOf(at(100.0, -80.0), at(100.0, -40.0), at(102.0, 3.0), at(100.0, 45.0), at(100.0, 90.0))
        assertEquals(path, RoadSnap.snap(path, listOf(road), 20.0))
    }

    @Test
    fun theLineFollowsTheRoadRoundACorner() {
        val path = listOf(at(300.0, 6.0), at(360.0, -5.0), at(405.0, 60.0), at(394.0, 140.0))
        val out = RoadSnap.snap(path, listOf(road), 20.0)
        // The corner itself is added between the last point before it and the first after it.
        assertEquals(5, out.size)
        assertTrue(meters(out[2], at(400.0, 0.0)) < 0.5)
        assertTrue(meters(out[3], at(400.0, 60.0)) < 0.5)
    }

    @Test
    fun aLongWayRoundIsNotInvented() {
        // A hairpin: two points 30 m apart across the bend are not joined along 800 m of road.
        val hairpin = listOf(at(0.0, 0.0), at(400.0, 0.0), at(400.0, 30.0), at(0.0, 30.0))
        val path = listOf(at(100.0, 2.0), at(100.0, 28.0))
        assertEquals(2, RoadSnap.snap(path, listOf(hairpin), 20.0).size)
    }

    @Test
    fun aPathStaysOnItsRoadPastASideStreet() {
        val side = listOf(at(200.0, 0.0), at(200.0, 300.0))
        // The middle point is a touch nearer the side street, but the path is travelling the main road.
        val path = listOf(at(150.0, 4.0), at(199.0, 5.0), at(250.0, 4.0))
        val out = RoadSnap.snap(path, listOf(side, road), 20.0)
        assertTrue(meters(out[1], at(199.0, 0.0)) < 0.5)
    }

    @Test
    fun nothingToSnapToLeavesThePathAlone() {
        val path = listOf(at(0.0, 0.0), at(10.0, 0.0))
        assertEquals(path, RoadSnap.snap(path, emptyList(), 20.0))
    }
}
