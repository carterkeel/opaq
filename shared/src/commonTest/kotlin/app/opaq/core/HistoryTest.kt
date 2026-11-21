package app.opaq.core

import app.opaq.core.crypto.PlatformCrypto
import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Point
import app.opaq.core.model.Precision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory [HistoryFiles] for tests. */
class MemoryHistoryFiles : HistoryFiles {
    val files = linkedMapOf<String, MutableList<String>>()
    override fun list() = files.keys.toList()
    override fun append(name: String, line: String) { files.getOrPut(name) { mutableListOf() } += line }
    override fun read(name: String) = files[name].orEmpty().toList()
    override fun delete(name: String) { files.remove(name) }
}

class HistoryTest {
    private val t0 = 1_763_000_000L
    private val key = PlatformCrypto.randomBytes(32)
    private val ben = "b".repeat(64)

    private fun at(t: Long, lat: Double, a: Activity, points: List<Point> = emptyList(), precision: Precision? = null) =
        MemberLocation(lat, -87.6, accuracy = 8f, activity = a, time = t, points = points, precision = precision)

    @Test
    fun keepsMovesAndActivityChangesButThinsOutStandingStill() = runTest {
        val files = MemoryHistoryFiles()
        val h = LocationHistory(files)
        // Still for an hour, an update every 5 minutes: kept every 15 minutes.
        (0 until 12).forEach { h.record(key, ben, at(t0 + it * 300L, 41.0, Activity.STILL)) }
        // Then drives off: every update (30 s, ~400 m apart) is kept, plus the path points inside.
        val drive = (1..10).map { i ->
            val t = t0 + 3600 + i * 30L
            at(t, 41.0 + i * 0.004, Activity.DRIVING, points = listOf(Point(41.0 + i * 0.004 - 0.002, -87.6, t - 15)))
        }
        drive.forEach { h.record(key, ben, it) }
        val got = h.read(key, ben, t0, t0 + 7200)
        val still = got.filter { it.a == Activity.STILL }
        assertEquals(listOf(t0, t0 + 900, t0 + 1800, t0 + 2700), still.map { it.t })
        assertEquals(20, got.count { it.a == Activity.DRIVING }, "updates and their path points")
        assertEquals(got.sortedBy { it.t }, got)
        // The same update arriving again (another circle, or a relay replay) isn't stored twice.
        h.record(key, ben, drive.last())
        assertEquals(got.size, h.read(key, ben, t0, t0 + 7200).size)
    }

    @Test
    fun drivesKeepTheirSpeedAndCyclingCountsAsDriving() = runTest {
        val h = LocationHistory(MemoryHistoryFiles())
        // 20 m/s (about 45 mph), with a path point 300 m back 15 s earlier.
        val t = t0 + 60
        h.record(key, ben, at(t, 41.0, Activity.DRIVING, points = listOf(Point(41.0 - 0.0027, -87.6, t - 15))).copy(speed = 20f))
        val got = h.read(key, ben, t0, t0 + 3600)
        assertEquals(20f, got.last().v)
        assertEquals(20.0, got.first().v!!.toDouble(), 1.0)
        // An older phone's "cycling" is stored as driving.
        h.record(key, ben, at(t + 120, 41.01, Activity.CYCLING).copy(speed = 6f))
        assertEquals(Activity.DRIVING, h.read(key, ben, t0, t0 + 3600).last().a)
        // Walking keeps no speed.
        h.record(key, ben, at(t + 600, 41.02, Activity.WALKING).copy(speed = 1.4f))
        assertEquals(null, h.read(key, ben, t0, t0 + 3600).last().v)
        val drive = HistorySegments.build(h.read(key, ben, t0, t0 + 3600)).first { it.activity == Activity.DRIVING }
        assertEquals(20f, drive.topSpeed)
    }

    @Test
    fun filesAreEncryptedAndDontNameThePerson() = runTest {
        val files = MemoryHistoryFiles()
        val h = LocationHistory(files)
        h.record(key, ben, at(t0, 41.123456, Activity.WALKING))
        val (name, lines) = files.files.entries.single()
        assertFalse(ben.take(16) in name)
        assertFalse(lines.any { "41.123456" in it || "WALKING" in it })
        assertTrue(h.read(PlatformCrypto.randomBytes(32), ben, t0 - 10, t0 + 10).isEmpty(), "unreadable without the key")
        assertEquals(41.123456, h.read(key, ben, t0 - 10, t0 + 10).single().lat)
    }

    @Test
    fun approximateUpdatesArentRecorded() = runTest {
        val h = LocationHistory(MemoryHistoryFiles())
        h.record(key, ben, at(t0, 41.0, Activity.STILL, precision = Precision.CITY))
        assertTrue(h.read(key, ben, t0 - 10, t0 + 10).isEmpty())
    }

    @Test
    fun pruningDropsOldDaysAndPeopleWhoLeft() = runTest {
        val files = MemoryHistoryFiles()
        val h = LocationHistory(files)
        val cal = "c".repeat(64)
        (0..9).forEach { d -> h.record(key, ben, at(t0 + d * 86_400L, 41.0 + d, Activity.STILL)) }
        h.record(key, cal, at(t0, 42.0, Activity.STILL))
        val now = t0 + 9 * 86_400L
        h.prune(now, keepDays = 7, keepPubkeys = setOf(ben))
        val left = h.read(key, ben, t0 - 86_400, now + 1).map { LocationHistory.day(it.t) }
        assertEquals((LocationHistory.day(now) - 6..LocationHistory.day(now)).toList(), left)
        assertTrue(h.read(key, cal, t0 - 10, t0 + 10).isEmpty(), "Cal left every circle: their history goes")
        h.prune(now, keepDays = 0, keepPubkeys = setOf(ben))
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun segmentsFoldShortStopsIntoTheDrive() {
        fun p(t: Long, lat: Double, a: Activity) = HistoryPoint(t0 + t, lat, -87.6, a)
        val points = listOf(
            p(0, 41.0, Activity.STILL), p(900, 41.0, Activity.STILL),
            p(1000, 41.01, Activity.DRIVING), p(1030, 41.02, Activity.DRIVING),
            p(1060, 41.02, Activity.STILL), // a red light
            p(1120, 41.03, Activity.DRIVING), p(1500, 41.10, Activity.DRIVING),
            p(1600, 41.10, Activity.STILL), p(3000, 41.10, Activity.STILL),
        )
        val segments = HistorySegments.build(points)
        assertEquals(listOf(Activity.STILL, Activity.DRIVING, Activity.STILL), segments.map { it.activity })
        assertEquals(t0 + 1000, segments[1].start)
        assertEquals(t0 + 1600, segments[1].end)
        assertTrue(segments[1].meters > 9_000, "about 11 km driven: ${segments[1].meters}")
        assertEquals(0.0, segments[0].meters)
    }
}
