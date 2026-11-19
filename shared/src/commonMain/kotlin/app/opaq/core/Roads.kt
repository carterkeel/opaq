package app.opaq.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A line on the map with its length worked out once, so finding the point a given distance along
 * it is cheap enough to do every frame for every moving person.
 */
class Polyline(points: List<GeoPoint>) {
    val points: List<GeoPoint> = points.ifEmpty { error("A polyline needs at least one point") }
    private val along = DoubleArray(this.points.size)

    init {
        for (i in 1 until this.points.size) along[i] = along[i - 1] + meters(this.points[i - 1], this.points[i])
    }

    val start: GeoPoint get() = points.first()
    val end: GeoPoint get() = points.last()
    val length: Double get() = along.last()

    /** The point [distance] metres along the line, held at either end. */
    fun at(distance: Double): GeoPoint {
        if (distance <= 0.0 || points.size == 1) return points.first()
        if (distance >= length) return points.last()
        var lo = 0
        var hi = along.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (along[mid] <= distance) lo = mid else hi = mid
        }
        val seg = along[hi] - along[lo]
        if (seg <= 0.0) return points[hi]
        val f = (distance - along[lo]) / seg
        val a = points[lo]
        val b = points[hi]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** The point of the line closest to [p]: how far along the line it is, and how far from [p]. */
    fun locate(p: GeoPoint): Pair<Double, Double> {
        if (points.size == 1) return 0.0 to meters(p, points[0])
        val mLat = 6_371_000.0 * PI / 180
        val mLon = mLat * cos(p.lat * PI / 180)
        var bestAlong = 0.0
        var bestDist = Double.MAX_VALUE
        for (s in 0 until points.size - 1) {
            val ax = (points[s].lon - p.lon) * mLon; val ay = (points[s].lat - p.lat) * mLat
            val bx = (points[s + 1].lon - p.lon) * mLon; val by = (points[s + 1].lat - p.lat) * mLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestDist) {
                bestDist = d
                bestAlong = along[s] + t * (along[s + 1] - along[s])
            }
        }
        return bestAlong to bestDist
    }

    /** The rest of the line from [distance] metres along it. */
    fun from(distance: Double): Polyline {
        if (distance <= 0.0) return this
        val first = at(distance)
        val rest = points.indices.filter { along[it] > distance }.map { points[it] }
        return Polyline(listOf(first) + rest)
    }

    /** This line followed by [next] (which should start where this one ends). */
    operator fun plus(next: Polyline): Polyline {
        val tail = if (meters(end, next.start) < 0.01) next.points.drop(1) else next.points
        return Polyline(points + tail)
    }

    companion object {
        fun meters(a: GeoPoint, b: GeoPoint) = MotionPolicy.distanceMeters(a.lat, a.lon, b.lat, b.lon)
    }
}

/**
 * The road shapes in the map tiles on screen, set up for looking up "which road is this person on
 * and where does it go next". Display only: the roads come from tiles the map already has, and no
 * location is sent anywhere to use them.
 *
 * [follow] walks along a road from a point for a given distance, round its bends. Map data splits a
 * road into pieces (at tile edges and wherever its details change), so at the end of a piece it
 * carries on into the next one, but only where the road clearly goes straight on. Where the road
 * ends, forks, or meets a junction with no clear way ahead, it stops rather than guess a turn.
 */
class Roads(lines: List<List<GeoPoint>>) {
    val lines: List<List<GeoPoint>> = lines.filter { it.size >= 2 }
    private val minLat = DoubleArray(this.lines.size) { i -> this.lines[i].minOf { it.lat } }
    private val maxLat = DoubleArray(this.lines.size) { i -> this.lines[i].maxOf { it.lat } }
    private val minLon = DoubleArray(this.lines.size) { i -> this.lines[i].minOf { it.lon } }
    private val maxLon = DoubleArray(this.lines.size) { i -> this.lines[i].maxOf { it.lon } }

    /** The closest point of road [line] to a position: on segment [seg] (from vertex seg to seg + 1). */
    class Hit(val line: Int, val seg: Int, val point: GeoPoint, val meters: Double)

    /** What [follow] found: the way ahead, and whether it reached the distance asked for. */
    class Ahead(val path: Polyline, val complete: Boolean)

    fun isEmpty() = lines.isEmpty()

    /** The nearest point of each road within [maxMeters] of [p], nearest first. */
    fun near(p: GeoPoint, maxMeters: Double): List<Hit> {
        val dLat = maxMeters / M_PER_DEG
        val dLon = maxMeters / (M_PER_DEG * cos(p.lat * PI / 180).coerceAtLeast(0.01))
        val hits = ArrayList<Hit>()
        for (i in lines.indices) {
            if (p.lat < minLat[i] - dLat || p.lat > maxLat[i] + dLat || p.lon < minLon[i] - dLon || p.lon > maxLon[i] + dLon) continue
            nearestOn(i, p)?.takeIf { it.meters <= maxMeters }?.let { hits += it }
        }
        return hits.sortedBy { it.meters }
    }

    /**
     * The nearest road within [maxMeters] of [p] that runs the way someone heading [bearing] is
     * going (within [MAX_ALONG_DEGREES], either direction of the road). Null when there's none: the
     * person is off-road, or crossing a road rather than travelling along it.
     */
    fun along(p: GeoPoint, bearing: Double, maxMeters: Double): Hit? =
        near(p, maxMeters).firstOrNull { direction(it, bearing) != 0 }

    /**
     * The way ahead along the road at [hit] for someone heading [bearing], up to [maxMeters]. Null if
     * the road doesn't run that way.
     */
    fun follow(hit: Hit, bearing: Double, maxMeters: Double): Ahead? {
        val dir = direction(hit, bearing)
        if (dir == 0) return null
        val out = arrayListOf(hit.point)
        var line = hit.line
        var next = if (dir > 0) hit.seg + 1 else hit.seg
        var step = dir
        var left = maxMeters
        var hops = 0
        while (left > 0) {
            val pts = lines[line]
            if (next !in pts.indices) {
                // End of this piece of road: carry on only if it clearly goes straight on.
                val end = out.last()
                val inBearing = incomingBearing(out) ?: return Ahead(Polyline(out), false)
                val cont = continuation(end, inBearing, line) ?: return Ahead(Polyline(out), false)
                if (++hops > MAX_HOPS) return Ahead(Polyline(out), false)
                line = cont.first.line
                step = cont.second
                next = if (step > 0) cont.first.seg + 1 else cont.first.seg
                if (Polyline.meters(end, cont.first.point) > 0.01) {
                    left -= Polyline.meters(end, cont.first.point)
                    out += cont.first.point
                }
                continue
            }
            val v = pts[next]
            val last = out.last()
            val d = Polyline.meters(last, v)
            if (d >= left) {
                val f = left / d
                out += GeoPoint(last.lat + (v.lat - last.lat) * f, last.lon + (v.lon - last.lon) * f)
                return Ahead(Polyline(out), true)
            }
            if (d > 0.0) out += v
            left -= d
            next += step
        }
        return Ahead(Polyline(out), true)
    }

    /** +1 to travel the road's way, -1 against it, 0 if [bearing] runs across it. */
    private fun direction(hit: Hit, bearing: Double): Int {
        val pts = lines[hit.line]
        val a = Prediction.angleBetween(Prediction.bearing(pts[hit.seg], pts[hit.seg + 1]), bearing)
        return when {
            a <= MAX_ALONG_DEGREES -> 1
            a >= 180 - MAX_ALONG_DEGREES -> -1
            else -> 0
        }
    }

    private fun incomingBearing(path: List<GeoPoint>): Double? {
        val end = path.last()
        for (i in path.size - 2 downTo 0) {
            if (Polyline.meters(path[i], end) >= 1.0) return Prediction.bearing(path[i], end)
        }
        return null
    }

    /** The one road piece that carries straight on from [end], and which way along it. */
    private fun continuation(end: GeoPoint, inBearing: Double, from: Int): Pair<Hit, Int>? {
        class Option(val hit: Hit, val step: Int, val turn: Double, val bearing: Double)
        val options = ArrayList<Option>()
        for (hit in near(end, JOIN_METERS)) {
            if (hit.line == from) continue
            for (step in intArrayOf(1, -1)) {
                val b = bearingAway(hit, step) ?: continue
                val turn = Prediction.angleBetween(b, inBearing)
                if (turn < BACK_DEGREES) options += Option(hit, step, turn, b)
            }
        }
        val straight = options.filter { it.turn <= STRAIGHT_DEGREES }
        if (straight.isEmpty()) return null
        // The same stretch of road can come back more than once (tile edges, several layers).
        if (straight.any { Prediction.angleBetween(it.bearing, straight[0].bearing) > SAME_DEGREES }) return null
        if (options.any { it.turn > STRAIGHT_DEGREES && it.turn < SIDE_DEGREES }) return null
        val best = straight.minBy { it.hit.meters }
        return best.hit to best.step
    }

    /** The bearing of road [hit] going [step] from the hit, measured over a few metres; null if it ends there. */
    private fun bearingAway(hit: Hit, step: Int): Double? {
        val pts = lines[hit.line]
        var i = if (step > 0) hit.seg + 1 else hit.seg
        var far: GeoPoint? = null
        while (i in pts.indices) {
            far = pts[i]
            if (Polyline.meters(hit.point, pts[i]) >= 5.0) break
            i += step
        }
        if (far == null || Polyline.meters(hit.point, far) < 1.0) return null
        return Prediction.bearing(hit.point, far)
    }

    private fun nearestOn(i: Int, p: GeoPoint): Hit? {
        val pts = lines[i]
        val mLon = M_PER_DEG * cos(p.lat * PI / 180)
        var best: Hit? = null
        for (s in 0 until pts.size - 1) {
            val ax = (pts[s].lon - p.lon) * mLon; val ay = (pts[s].lat - p.lat) * M_PER_DEG
            val bx = (pts[s + 1].lon - p.lon) * mLon; val by = (pts[s + 1].lat - p.lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val qx = ax + t * dx; val qy = ay + t * dy
            val d = hypot(qx, qy)
            if (best == null || d < best.meters) {
                best = Hit(i, s, GeoPoint(p.lat + qy / M_PER_DEG, p.lon + qx / mLon), d)
            }
        }
        return best
    }

    companion object {
        private const val M_PER_DEG = 6_371_000.0 * PI / 180

        /** How far a road may run from someone's heading and still count as the road they're on. */
        const val MAX_ALONG_DEGREES = 40.0

        /** Pieces of road whose ends are this close are joined. */
        private const val JOIN_METERS = 3.0

        /** Straight on: the next piece turns no more than this. */
        private const val STRAIGHT_DEGREES = 35.0

        /** A road turning off by less than this beside the straight-on piece makes the way ahead unclear. */
        private const val SIDE_DEGREES = 60.0

        /** Pieces turning more than this lead back the way we came. */
        private const val BACK_DEGREES = 150.0

        /** Two straight-on pieces this close in direction are the same road drawn twice. */
        private const val SAME_DEGREES = 10.0

        private const val MAX_HOPS = 64
    }
}
