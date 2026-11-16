package app.quietmap.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** A position on the map, in degrees. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Tidies a recorded path for drawing by lining it up with nearby roads.
 *
 * GPS wobbles by several metres, so a drive along a road is recorded as a zigzag beside it. Each
 * point within [snap]'s `maxMeters` of a road is moved onto that road, and between two points on
 * the same road the line follows the road's own bends.
 *
 * It only changes how the line is drawn on this phone. Stored history is never altered, the roads
 * come from the map tiles already on screen (no location is sent to anyone to do this), and it
 * errs on the side of the recorded position:
 * - a point further than `maxMeters` from every road stays exactly where it was recorded;
 * - a single point that happens to be near a road (walking across one) isn't pulled onto it;
 * - a detour along the road much longer than the straight line is not invented.
 */
object RoadSnap {
    /** Keep following the road a path was already on unless another is clearly closer. */
    private const val STAY_BONUS_METERS = 6.0

    private class Road(val xs: DoubleArray, val ys: DoubleArray) {
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    }

    private class Hit(val road: Int, val seg: Int, val x: Double, val y: Double, val dist: Double)

    fun snap(path: List<GeoPoint>, roads: List<List<GeoPoint>>, maxMeters: Double): List<GeoPoint> {
        if (path.size < 2 || roads.isEmpty() || maxMeters <= 0) return path
        // Flat metres around the path's start: accurate to centimetres over a day's travel on screen.
        val lat0 = path.first().lat
        val lon0 = path.first().lon
        val mLat = 6_371_000.0 * PI / 180
        val mLon = mLat * cos(lat0 * PI / 180)
        fun x(p: GeoPoint) = (p.lon - lon0) * mLon
        fun y(p: GeoPoint) = (p.lat - lat0) * mLat
        fun geo(px: Double, py: Double) = GeoPoint(lat0 + py / mLat, lon0 + px / mLon)

        val lines = roads.filter { it.size >= 2 }.map { r -> Road(DoubleArray(r.size) { x(r[it]) }, DoubleArray(r.size) { y(r[it]) }) }

        fun nearestOn(roadIndex: Int, px: Double, py: Double, within: Double): Hit? {
            val r = lines[roadIndex]
            if (px < r.minX - within || px > r.maxX + within || py < r.minY - within || py > r.maxY + within) return null
            var best: Hit? = null
            for (s in 0 until r.xs.size - 1) {
                val ax = r.xs[s]; val ay = r.ys[s]
                val dx = r.xs[s + 1] - ax; val dy = r.ys[s + 1] - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = ax + t * dx; val qy = ay + t * dy
                val d = hypot(px - qx, py - qy)
                if (d <= within && (best == null || d < best.dist)) best = Hit(roadIndex, s, qx, qy, d)
            }
            return best
        }

        val hits = arrayOfNulls<Hit>(path.size)
        var previous: Hit? = null
        for (i in path.indices) {
            val px = x(path[i]); val py = y(path[i])
            var best: Hit? = null
            for (r in lines.indices) {
                val h = nearestOn(r, px, py, best?.dist ?: maxMeters) ?: continue
                if (best == null || h.dist < best.dist) best = h
            }
            val stay = previous?.let { nearestOn(it.road, px, py, maxMeters) }
            if (best != null && stay != null && stay.dist <= best.dist + STAY_BONUS_METERS) best = stay
            hits[i] = best
            previous = best
        }
        // One point near a road with real positions either side is someone crossing it, not on it.
        val keep = BooleanArray(path.size) { i -> hits[i] != null && (hits.getOrNull(i - 1) != null || hits.getOrNull(i + 1) != null) }

        val out = ArrayList<GeoPoint>(path.size)
        for (i in path.indices) {
            val h = hits[i].takeIf { keep[i] }
            if (h == null) {
                out += path[i]
                continue
            }
            val before = hits.getOrNull(i - 1)?.takeIf { keep[i - 1] }
            if (before != null && before.road == h.road && before.seg != h.seg) {
                // Follow the road's bends between the two points, unless that would be a long way round.
                val r = lines[h.road]
                val forward = h.seg > before.seg
                val vertices = if (forward) (before.seg + 1..h.seg) else (before.seg downTo h.seg + 1)
                var along = 0.0
                var lx = before.x; var ly = before.y
                for (v in vertices) { along += hypot(r.xs[v] - lx, r.ys[v] - ly); lx = r.xs[v]; ly = r.ys[v] }
                along += hypot(h.x - lx, h.y - ly)
                val straight = hypot(h.x - before.x, h.y - before.y)
                if (along <= straight * 1.5 + 2 * maxMeters) for (v in vertices) out += geo(r.xs[v], r.ys[v])
            }
            out += geo(h.x, h.y)
        }
        return out
    }
}
