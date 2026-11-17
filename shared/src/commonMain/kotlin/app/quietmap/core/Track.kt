package app.quietmap.core

/**
 * How a dot gets from where it's drawn to a new estimate (#93): it travels there instead of
 * jumping, along the person's recent path (and the road, when they're on one), then carries on
 * with the new [Prediction.Plan].
 *
 * - The dot moves at the person's own speed plus whatever it takes to close the gap, so it never
 *   stops or slows below them. The extra speed rises and falls smoothly, and the time it takes
 *   grows with the gap ([durationFor]).
 * - A dot already on the new estimate's way ahead (a good guess) just carries on along it. One that
 *   got ahead waits there for the estimate rather than going back, if the estimate will reach it.
 * - A gap too big to be ordinary travel ([JUMP_METERS]) isn't animated: [build] returns null and
 *   the dot is simply shown at the new estimate.
 */
class Track private constructor(
    /** From the dot to where it joins the plan's path. */
    private val lead: Polyline,
    /** How far along the plan's path the lead joins it. */
    private val join: Double,
    val plan: Prediction.Plan,
    val startTime: Double,
    val duration: Double,
    private val gap: Double,
) {
    private fun target(now: Double) = lead.length + plan.distanceAt(now) - join

    /** How far along the track the dot is at [now]: never goes backwards. */
    fun progress(now: Double): Double {
        val target = target(now)
        if (duration <= 0.0) return target.coerceAtLeast(0.0)
        val u = ((now - startTime) / duration).coerceIn(0.0, 1.0)
        val eased = u * u * (3 - 2 * u)
        return (target - gap * (1 - eased)).coerceAtLeast(0.0)
    }

    fun at(now: Double): GeoPoint {
        val s = progress(now)
        return if (s <= lead.length) lead.at(s) else plan.path.at(join + s - lead.length)
    }

    /** True once the dot has caught up and simply follows [plan]. */
    fun done(now: Double): Boolean = now - startTime >= duration && target(now) >= 0.0

    companion object {
        /** Further than this from the new estimate, the dot is moved at once: not ordinary travel. */
        const val JUMP_METERS = 3_000.0

        /** The longest a catch-up takes. */
        const val MAX_SECONDS = 8.0

        /** The shortest a (non-trivial) catch-up takes, so small corrections are smooth too. */
        const val MIN_SECONDS = 0.8

        /** The gap is closed at least this fast (m/s), or at the person's own speed if faster. */
        private const val MIN_CATCH_SPEED = 6.0

        /** A dot this close to the new estimate's way ahead is treated as already on it. */
        private const val ON_PATH_METERS = 5.0

        fun durationFor(gap: Double, speed: Double): Double =
            if (gap < 0.5) 0.0 else (gap / maxOf(MIN_CATCH_SPEED, speed)).coerceIn(MIN_SECONDS, MAX_SECONDS)

        /**
         * The way from [from] (where the dot is drawn at [now]) to [plan]. [recent] are the update's
         * recent points newer than the previous update, oldest first: the dot follows them from
         * the one nearest to it. With [roads], that stretch is lined up with the road ([RoadSnap]).
         */
        fun build(from: GeoPoint, plan: Prediction.Plan, now: Double, recent: List<GeoPoint> = emptyList(), roads: Roads? = null, snapMeters: Double = Prediction.FOLLOW_METERS): Track? {
            val target = plan.start
            if (Polyline.meters(from, target) > JUMP_METERS) return null

            // Already on (or just beside) the way ahead: carry on along it.
            if (plan.speed > 0.0 && plan.path.points.size > 1) {
                val (along, off) = plan.path.locate(from)
                if (along > 0.0 && off <= ON_PATH_METERS) {
                    val lead = Polyline(listOf(from, plan.path.at(along)))
                    val gap = lead.length + plan.distanceAt(now) - along
                    // Ahead of the estimate: wait for it, as long as it will get there.
                    if (gap >= 0.0 || plan.limit - along > 1.0) {
                        val g = gap.coerceAtLeast(0.0)
                        return Track(lead, along, plan, now, durationFor(g, plan.speed), g)
                    }
                }
            }

            val via = recent + target
            val nearest = via.indices.minBy { Polyline.meters(from, via[it]) }
            var lead = listOf(from) + via.drop(nearest + 1)
            if (lead.size < 2) lead = listOf(from, target)
            if (roads != null && !roads.isEmpty()) {
                val snapped = RoadSnap.snap(lead, roads.lines, snapMeters)
                // The ends stay exactly where the dot is and where the plan starts.
                lead = listOf(from) + snapped.subList(1, snapped.size - 1) + target
            }
            val leadLine = Polyline(lead)
            // A route along the points much longer than the straight line isn't a sensible path.
            val straight = Polyline.meters(from, target)
            val line = if (leadLine.length > straight * 2 + 50) Polyline(listOf(from, target)) else leadLine
            val gap = line.length + plan.distanceAt(now)
            if (gap > JUMP_METERS) return null
            return Track(line, 0.0, plan, now, durationFor(gap, plan.speed), gap)
        }
    }
}
