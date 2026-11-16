package app.quietmap.core

import app.quietmap.core.model.Activity
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.Precision
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Where someone who is moving probably is now, between their updates (#57).
 *
 * Updates arrive about every half a minute while moving, so without this a driver's dot sits still
 * and then jumps. The estimate carries the last update forward along its heading at its speed,
 * worked out on the viewer's phone from what the update already holds. Nothing extra is sent.
 *
 * It stays cautious, because a wrong guess is worse than a late one:
 * - only exact, accurate, moving updates with a known direction are carried forward;
 * - on a straight path it goes at full speed until about when the next update is due, then stops;
 * - on a winding path it slows down and stops after a short distance;
 * - an update older than [STALE_SECONDS] is shown where it really was.
 */
object Prediction {
    /** How far ahead a straight path is carried forward: a little past the next expected update. */
    const val HORIZON_SECONDS = 45L

    /** Older than this, the person may have stopped or turned: show the real last position. */
    const val STALE_SECONDS = 120L

    /** Winding paths: the estimate slows with this time constant, so it stops after about speed × 15 s. */
    private const val DAMPING_SECONDS = 15.0

    private const val MIN_SPEED = 1.0f // m/s; slower than a stroll is treated as standing still
    private const val MAX_ACCURACY = 50f
    private const val MAX_TURN_DEGREES = 30.0
    private const val MIN_SEGMENT_METERS = 8.0

    /** [predicted] is true while the estimate is still moving ahead of the last update. */
    data class Estimate(val lat: Double, val lon: Double, val predicted: Boolean, val advancing: Boolean = false)

    /** The best guess for [loc] at [now] (seconds since the epoch, with a fraction for smooth motion). */
    fun estimate(loc: MemberLocation, now: Double): Estimate {
        val actual = Estimate(loc.lat, loc.lon, false)
        val age = now - loc.time
        if (age <= 0.0 || age > STALE_SECONDS) return actual
        if (loc.precision != null && loc.precision != Precision.EXACT) return actual
        if (loc.activity != Activity.WALKING && loc.activity != Activity.CYCLING && loc.activity != Activity.DRIVING) return actual
        val speed = loc.speed ?: return actual
        if (speed < MIN_SPEED || loc.accuracy > MAX_ACCURACY) return actual
        val heading = loc.heading?.toDouble() ?: pathBearing(loc) ?: return actual

        val t = min(age, HORIZON_SECONDS.toDouble())
        val distance = if (isStraight(loc, heading)) speed * t else speed * DAMPING_SECONDS * (1 - exp(-t / DAMPING_SECONDS))
        val (lat, lon) = offset(loc.lat, loc.lon, heading, distance)
        return Estimate(lat, lon, predicted = true, advancing = age < HORIZON_SECONDS)
    }

    /** Bearing from the oldest to the newest recent point, if they're far enough apart to mean anything. */
    private fun pathBearing(loc: MemberLocation): Double? {
        val first = loc.points.firstOrNull() ?: return null
        if (MotionPolicy.distanceMeters(first.lat, first.lon, loc.lat, loc.lon) < maxOf(MIN_SEGMENT_METERS, loc.accuracy.toDouble())) return null
        return bearing(first.lat, first.lon, loc.lat, loc.lon)
    }

    /** Whether every recent segment points roughly the same way as [heading]. */
    private fun isStraight(loc: MemberLocation, heading: Double): Boolean {
        val pts = loc.points.map { it.lat to it.lon } + (loc.lat to loc.lon)
        var segments = 0
        for (i in 1 until pts.size) {
            val (aLat, aLon) = pts[i - 1]
            val (bLat, bLon) = pts[i]
            if (MotionPolicy.distanceMeters(aLat, aLon, bLat, bLon) < MIN_SEGMENT_METERS) continue
            segments++
            if (angleBetween(bearing(aLat, aLon, bLat, bLon), heading) > MAX_TURN_DEGREES) return false
        }
        return segments >= 2
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

    private fun angleBetween(a: Double, b: Double): Double {
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
