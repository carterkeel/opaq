package app.opaq.core

import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Precision
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Where someone who is moving probably is now, between their updates (#57, #93, #97, #98).
 *
 * Updates arrive about every [MotionPolicy.MOVING_INTERVAL] seconds while moving, so without this a
 * driver's dot sits still and then jumps. The estimate carries the last update forward at its real
 * speed, worked out on the viewer's phone from what the update already holds. Nothing extra is sent.
 *
 * - On a road (known from the map tiles on screen, [Roads]) it follows the road round its bends,
 *   and is drawn on the road when the update is within [SNAP_METERS] of it (#92).
 * - Off-road it goes straight along the heading.
 * - It keeps full speed until about when the next update is due ([HORIZON_SECONDS]), then eases to
 *   a stop, and it also stops where the known road ends or the way ahead isn't clear.
 *
 * It stays cautious, because a wrong guess is worse than a late one:
 * - only exact, accurate, moving updates with a known direction are carried forward;
 * - off-road, a winding path is only carried forward for [WINDING_SECONDS];
 * - an update older than [STALE_SECONDS] is shown where it really was (see [isStale]).
 */
object Prediction {
    /**
     * How long a moving person is carried forward: the slowest the next update can be due (the
     * moving interval plus its 15% jitter), plus time for it to be sent and to arrive.
     */
    const val HORIZON_SECONDS = MotionPolicy.MOVING_INTERVAL * 115 / 100 + 15

    /** Older than this, the person may have stopped or turned: show the real last position. */
    const val STALE_SECONDS = 120L

    /** Off-road on a winding path, how long to carry on along the heading before stopping. */
    const val WINDING_SECONDS = 15.0

    /** A travelling person is drawn on a road this close to their update (about 20 ft, #92). */
    const val SNAP_METERS = 6.0

    /**
     * How far from a road a driver or cyclist can be and still be following it. Wider than
     * [SNAP_METERS] because map roads are centre lines and a wide road's lanes sit metres off them;
     * between the two the dot starts where the update really is and merges onto the road ahead.
     */
    const val FOLLOW_METERS = 12.0

    /** The estimate eases to a stop over about this long, instead of halting at once. */
    private const val BRAKE_SECONDS = 3.0

    private const val MIN_SPEED = 1.0f // m/s; slower than a stroll is treated as standing still
    private const val MAX_ACCURACY = 50f
    private const val MAX_TURN_DEGREES = 25.0
    private const val MIN_CHORD_METERS = 20.0
    private const val MIN_SEGMENT_METERS = 8.0

    /** [predicted] is true while the estimate is ahead of the last update. */
    data class Estimate(val lat: Double, val lon: Double, val predicted: Boolean, val advancing: Boolean = false)

    /**
     * Where an update is drawn and where it goes from there. [path] starts at the drawn position;
     * the estimate moves along it at [speed] for at most [limit] metres, easing to a stop at the end.
     */
    class Plan(val path: Polyline, val time: Double, val speed: Double, val limit: Double, val onRoad: Boolean = false) {
        val start: GeoPoint get() = path.start

        /** How far along [path] the estimate is at [now] (seconds since the epoch). */
        fun distanceAt(now: Double): Double {
            if (speed <= 0.0 || limit <= 0.0) return 0.0
            val x = speed * (now - time).coerceAtLeast(0.0)
            val brake = min(limit, speed * BRAKE_SECONDS)
            val knee = limit - brake
            return if (x <= knee) x else limit - brake * exp(-(x - knee) / brake)
        }

        /** Whether the estimate is still visibly moving at [now]. */
        fun advancing(now: Double): Boolean = speed > 0.0 && limit - distanceAt(now) > 0.3

        fun at(now: Double): Estimate {
            val d = distanceAt(now)
            val p = path.at(d)
            return Estimate(p.lat, p.lon, predicted = d > 0.0, advancing = advancing(now))
        }

        companion object {
            /** Someone shown exactly where their update says, not moving. */
            fun still(p: GeoPoint, time: Double = 0.0) = Plan(Polyline(listOf(p)), time, 0.0, 0.0)
        }
    }

    /** Whether [loc] is too old at [now] to carry forward: it's shown where it really was. */
    fun isStale(loc: MemberLocation, now: Double): Boolean = now - loc.time > STALE_SECONDS

    /**
     * How [loc] should be drawn and carried forward, using [roads] (the roads on screen) when known.
     * Doesn't depend on the time, so it's worked out once per update; [Plan.at] is cheap per frame.
     * The caller checks [isStale] first.
     */
    fun plan(loc: MemberLocation, roads: Roads? = null): Plan {
        val here = GeoPoint(loc.lat, loc.lon)
        val time = loc.time.toDouble()
        val still = Plan.still(here, time)
        if (loc.precision != null && loc.precision != Precision.EXACT) return still
        if (loc.activity != Activity.WALKING && loc.activity != Activity.CYCLING && loc.activity != Activity.DRIVING) return still
        val speed = loc.speed ?: return still
        if (speed < MIN_SPEED || loc.accuracy > MAX_ACCURACY) return still
        val heading = loc.heading?.toDouble() ?: pathBearing(loc) ?: return still
        val reach = speed.toDouble() * HORIZON_SECONDS

        if (roads != null && !roads.isEmpty()) {
            val followMeters = if (loc.activity == Activity.WALKING) SNAP_METERS else FOLLOW_METERS
            val hit = roads.along(here, heading, followMeters)
            val ahead = hit?.let { roads.follow(it, heading, reach) }
            if (hit != null && ahead != null) {
                val path = if (hit.meters <= SNAP_METERS) {
                    ahead.path
                } else {
                    // Off the centre line: leave from the real position and merge onto the road ahead.
                    val merge = min(2 * hit.meters + 5, ahead.path.length)
                    Polyline(listOf(here)) + ahead.path.from(merge)
                }
                return Plan(path, time, speed.toDouble(), min(reach, path.length), onRoad = true)
            }
        }

        val limit = if (isStraight(loc, heading)) reach else speed * WINDING_SECONDS
        val (lat, lon) = offset(loc.lat, loc.lon, heading, limit)
        return Plan(Polyline(listOf(here, GeoPoint(lat, lon))), time, speed.toDouble(), limit)
    }

    /** The best guess for [loc] at [now] (seconds since the epoch, with a fraction for smooth motion). */
    fun estimate(loc: MemberLocation, now: Double, roads: Roads? = null): Estimate {
        if (isStale(loc, now)) return Estimate(loc.lat, loc.lon, false)
        return plan(loc, roads).at(now)
    }

    /** Bearing from the oldest to the newest recent point, if they're far enough apart to mean anything. */
    private fun pathBearing(loc: MemberLocation): Double? {
        val first = loc.points.firstOrNull() ?: return null
        if (MotionPolicy.distanceMeters(first.lat, first.lon, loc.lat, loc.lon) < maxOf(MIN_SEGMENT_METERS, loc.accuracy.toDouble())) return null
        return bearing(first.lat, first.lon, loc.lat, loc.lon)
    }

    /**
     * Whether the recent path runs straight along [heading]. Measured from each recent point to the
     * latest position, which a few metres of GPS wobble in one point can't swing the way it swings a
     * short step between two points.
     */
    private fun isStraight(loc: MemberLocation, heading: Double): Boolean {
        val minChord = maxOf(MIN_CHORD_METERS, 2.0 * loc.accuracy)
        var chords = 0
        for (p in loc.points) {
            if (MotionPolicy.distanceMeters(p.lat, p.lon, loc.lat, loc.lon) < minChord) continue
            chords++
            if (angleBetween(bearing(p.lat, p.lon, loc.lat, loc.lon), heading) > MAX_TURN_DEGREES) return false
        }
        // A short recent path (walking) doesn't show a turn; the phone's own heading is then the best guide.
        return chords >= 1 || loc.heading != null
    }

    /** Initial bearing in degrees clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dl = (lon2 - lon1) * PI / 180
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    fun bearing(a: GeoPoint, b: GeoPoint): Double = bearing(a.lat, a.lon, b.lat, b.lon)

    /** The difference between two bearings, 0 to 180 degrees. */
    fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Moves [distance] metres from a point along [heading]. Accurate to well under a metre at these distances. */
    fun offset(lat: Double, lon: Double, heading: Double, distance: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val h = heading * PI / 180
        val dLat = distance * cos(h) / r
        val dLon = distance * sin(h) / (r * cos(lat * PI / 180))
        return (lat + dLat * 180 / PI) to (lon + dLon * 180 / PI)
    }
}
