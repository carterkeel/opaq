package app.opaq.core

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * People whose dots would overlap on the map at a zoom level are drawn as one group of faces (#146).
 * Display only, on the viewer's phone.
 *
 * Distances are in map points (dp): MapLibre draws the world 512 points wide at zoom 0, doubling
 * with each zoom level.
 */
object Grouping {
    /** Dots closer than this (in dp) are grouped. A dot is 36 dp across. */
    const val GAP_DP = 40.0

    /** Past this zoom, tapping a group lists the people instead of zooming further. */
    const val MAX_SPLIT_ZOOM = 19.0

    /**
     * Groups [points] (latitude, longitude) at [zoom]. Returns indices into [points], one list per
     * group, in the order given, so put whoever should stay put and be drawn in front first.
     *
     * Each group is anchored on its first point; a later point joins the first group whose anchor
     * is within [gapDp], so a group never drifts as people come and go around it.
     */
    fun group(points: List<Pair<Double, Double>>, zoom: Double, gapDp: Double = GAP_DP): List<List<Int>> {
        val scale = worldSize(zoom)
        val xy = points.map { (lat, lon) -> project(lat, lon) }
        val anchors = ArrayList<Pair<Double, Double>>()
        val groups = ArrayList<MutableList<Int>>()
        xy.forEachIndexed { i, p ->
            val at = anchors.indexOfFirst { a -> hypot(a.first - p.first, a.second - p.second) * scale < gapDp }
            if (at >= 0) groups[at].add(i) else {
                anchors.add(p)
                groups.add(mutableListOf(i))
            }
        }
        return groups
    }

    /**
     * The zoom at which every pair of [points] is at least [gapDp] apart, so a group of them splits
     * into separate dots. Null when two of them are in the same place (or within a few metres), where
     * no sensible zoom would split them.
     */
    fun splitZoom(points: List<Pair<Double, Double>>, gapDp: Double = GAP_DP): Double? {
        if (points.size < 2) return null
        val xy = points.map { (lat, lon) -> project(lat, lon) }
        var closest = Double.MAX_VALUE
        for (i in xy.indices) for (j in i + 1 until xy.size) {
            closest = min(closest, hypot(xy[i].first - xy[j].first, xy[i].second - xy[j].second))
        }
        if (closest <= 0.0) return null
        // gap = closest * 512 * 2^z, with a little room so they're clearly apart.
        val zoom = log2(gapDp * 1.2 / (closest * 512.0))
        return zoom.takeIf { it <= MAX_SPLIT_ZOOM }?.let { max(it, 0.0) }
    }

    private fun worldSize(zoom: Double) = 512.0 * pow2(zoom)

    private fun pow2(z: Double) = kotlin.math.exp(z * ln(2.0))

    /** Web Mercator, as a fraction of the world (0..1 across and down). */
    private fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val phi = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val x = (lon + 180.0) / 360.0
        val y = (1.0 - ln(tan(phi) + 1.0 / kotlin.math.cos(phi)) / PI) / 2.0
        return x to y
    }
}
