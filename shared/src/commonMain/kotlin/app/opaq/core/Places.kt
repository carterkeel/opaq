package app.opaq.core

import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Place
import app.opaq.core.model.Precision
import kotlin.math.max

/**
 * Alert areas (#151, planned in #101): whether someone arrived at or left a [Place], from an update
 * this phone received anyway. Pure, so it's tested on its own; nothing here is sent anywhere.
 */
object PlaceAlerts {
    /** About 100 ft, the smallest area worth alerting on with phone GPS. */
    const val MIN_RADIUS = 30.0
    const val MAX_RADIUS = 5000.0
    const val DEFAULT_RADIUS = 150.0
    const val MAX_PLACES = 20

    /** Updates older than this on arrival (a relay's backlog after reconnecting) never alert. */
    const val MAX_AGE_SECONDS = 10 * 60L

    /** The same alert for the same person and place at most this often. */
    const val REPEAT_SECONDS = 10 * 60L

    /** Leaving needs this much past the edge (or the fix's accuracy, if worse), so GPS wobble at the edge doesn't fire. */
    const val EDGE_METERS = 25.0

    enum class Change { ARRIVED, LEFT, NONE }

    /** What an update means for one place: what changed, and whether they're inside now. */
    data class Result(val change: Change, val inside: Boolean)

    /**
     * Checks [loc] against [place] for someone who [wasInside] it.
     *
     * Only exact, fresh updates accurate to within the place's radius count: approximate ones,
     * stale ones and "sharing paused" never change anything. Someone who passed through between two
     * updates (seen in the update's recent points) still counts as having arrived.
     */
    fun check(place: Place, wasInside: Boolean, loc: MemberLocation, now: Long): Result {
        val unchanged = Result(Change.NONE, wasInside)
        if (!exact(loc) || loc.status == MemberLocation.PAUSED) return unchanged
        if (now - loc.time > MAX_AGE_SECONDS) return unchanged
        if (loc.accuracy > max(place.radius, EDGE_METERS)) return unchanged
        val d = distance(place, loc.lat, loc.lon)
        if (wasInside) {
            return if (d > place.radius + max(EDGE_METERS, loc.accuracy.toDouble())) Result(Change.LEFT, false) else unchanged
        }
        if (d <= place.radius) return Result(Change.ARRIVED, true)
        val passedThrough = loc.points.any { p -> now - p.t <= MAX_AGE_SECONDS && distance(place, p.lat, p.lon) <= place.radius }
        // Through and out again: they arrived, but aren't inside, so no "left" follows.
        return if (passedThrough) Result(Change.ARRIVED, false) else unchanged
    }

    /** Whether [loc] is inside [place] right now, for setting up a new or moved place without alerts. */
    fun isInside(place: Place, loc: MemberLocation): Boolean =
        exact(loc) && distance(place, loc.lat, loc.lon) <= place.radius

    private fun exact(loc: MemberLocation) = loc.precision == null || loc.precision == Precision.EXACT

    fun distance(place: Place, lat: Double, lon: Double): Double = MotionPolicy.distanceMeters(place.lat, place.lon, lat, lon)
}
