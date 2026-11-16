package app.quietmap.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quietmap.core.GeoPoint
import app.quietmap.core.HistoryPoint
import app.quietmap.core.HistorySegment
import app.quietmap.core.LocationHistory
import app.quietmap.core.MotionPolicy
import app.quietmap.core.Prediction
import app.quietmap.core.RoadSnap
import app.quietmap.core.model.Activity
import app.quietmap.core.model.MemberLocation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Position

/**
 * One person on the map. [color] is a hex color such as "#1251D3"; offline people are drawn grey.
 * [location] is their latest update, used to move the dot ahead between updates while they travel.
 */
data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val initial: String,
    val color: String,
    val kind: Kind,
    val location: MemberLocation? = null,
) {
    enum class Kind { ME, ONLINE, OFFLINE }
}

/** Ask the map to fly to a point. [seq] makes repeated requests for the same place still move. */
data class MapFocus(val lat: Double, val lon: Double, val seq: Int)

/** City-level zoom used when the map first opens. */
private const val CITY_ZOOM = 12.5

/** Street-level zoom used when centering on someone. */
private const val STREET_ZOOM = 15.5

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** OpenFreeMap's dark style, for people who turn on Settings → Display → Dark map. */
private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

private fun mapStyle(dark: Boolean): BaseStyle = BaseStyle.Uri(if (dark) DARK_STYLE_URL else STYLE_URL)

/** Text the map must show for OpenFreeMap / OpenMapTiles / OpenStreetMap data. */
const val MAP_ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"

/** Rotation is off, as it was with the Android-only map. */
private val Interactions = MapInteractions(MapInteractions.Standard) {
    camera { rotate { enabled = false } }
}

/**
 * MapLibre with OpenFreeMap tiles, on Android and iOS. The tile server learns roughly which area
 * is on screen, never anyone's exact location.
 *
 * - The map stays composed while other screens slide over it, so it keeps its position.
 * - It opens at city level around you, and [focus] glides the camera to a point (yourself for the
 *   recenter button, or a person you tapped).
 * - People close together merge into one bubble with a count, and separate as you zoom in.
 * - [bottomInset] keeps centred points above the people sheet.
 * - The built-in scale bar, compass and logo are off; [MAP_ATTRIBUTION] is shown by the home screen.
 */
@Composable
fun MapPane(markers: List<MapMarker>, focusKey: Any?, focus: MapFocus?, bottomInset: Dp, modifier: Modifier = Modifier, darkMap: Boolean = false) {
    val shown = rememberMovingMarkers(markers)
    val geoJson = remember(shown) { markersToGeoJson(shown) }
    val mapState = rememberMapState(baseStyle = mapStyle(darkMap)) {
        val people = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson)) {
            cluster = true
            clusterRadius = 36
            clusterMaxZoom = 16
        }
        val isCluster = feature.has("point_count")
        // Merged bubble for people standing close together.
        CircleLayer(
            id = "people-cluster",
            source = people,
            filter = isCluster,
            radius = const(18.dp),
            color = const(Palette.Ultramarine),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
        SymbolLayer(
            id = "people-cluster-count",
            source = people,
            filter = isCluster,
            textField = format(span(feature["point_count_abbreviated"].asString())),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(13.sp),
            textColor = const(Color.White),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
        )
        // One dot per person.
        CircleLayer(
            id = "people-dot",
            source = people,
            filter = !isCluster,
            radius = const(15.dp),
            color = feature["color"].convertToColor(),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
        SymbolLayer(
            id = "people-initial",
            source = people,
            filter = !isCluster,
            textField = format(span(feature["initial"].asString())),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(13.sp),
            // From the feature, like the dot colour: const(Color.White) rendered magenta here (#63).
            textColor = feature["textColor"].convertToColor(),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
        )
    }

    // Open once per circle at city level around me (or whoever has a location first).
    var framedFor by remember { mutableStateOf<Any?>(Unit) }
    LaunchedEffect(mapState, focusKey, markers.isEmpty()) {
        if (markers.isEmpty() || framedFor == focusKey) return@LaunchedEffect
        framedFor = focusKey
        val center = markers.firstOrNull { it.kind == MapMarker.Kind.ME } ?: markers.first()
        mapState.setCameraPosition(CameraPosition(center = Position(center.lon, center.lat), zoom = CITY_ZOOM))
    }

    // Glide to a requested point.
    LaunchedEffect(mapState, focus) {
        val f = focus ?: return@LaunchedEffect
        mapState.animateCamera(CameraUpdate(center = Position(f.lon, f.lat), zoom = STREET_ZOOM))
    }

    MaplibreMap(
        modifier = modifier,
        state = mapState,
        viewportInsets = PaddingValues(bottom = bottomInset),
        interactions = Interactions,
        overlay = {},
    )
}

/** How long a dot takes to glide to a new update. */
private const val GLIDE_MS = 1_000.0

/** A jump bigger than this (km) is shown at once rather than as a long slide across the map. */
private const val MAX_GLIDE_KM = 20.0

/** Map refreshes per second while dots are moving. */
private const val FRAMES_PER_SECOND = 30

/**
 * The markers as they should be drawn right now (#55, #57): a new update glides from where the dot
 * was instead of jumping, and someone travelling keeps moving ahead between updates
 * ([Prediction]). Runs at [FRAMES_PER_SECOND] only while something moves, and checks once a second
 * otherwise, so an estimate that has run out settles back on the real position.
 */
@Composable
private fun rememberMovingMarkers(markers: List<MapMarker>): List<MapMarker> {
    var shown by remember { mutableStateOf(markers) }
    val drawn = remember { HashMap<String, Pair<Double, Double>>() }
    LaunchedEffect(markers) {
        // Each dot glides from where it's drawn now; a new marker or a huge jump starts in place.
        class Glide(var from: Pair<Double, Double>?, var startMs: Long, var predicted: Boolean? = null)
        val glides = markers.associate { m ->
            val prev = drawn[m.id]
            val far = prev == null || MotionPolicy.distanceMeters(prev.first, prev.second, m.lat, m.lon) > MAX_GLIDE_KM * 1000
            m.id to Glide(if (far) null else prev, epochMillis())
        }
        var lastFrame = 0L
        while (true) {
            val nowMs = epochMillis()
            var moving = false
            val next = markers.map { m ->
                val g = glides.getValue(m.id)
                val est = m.location?.takeIf { m.kind != MapMarker.Kind.OFFLINE }?.let { Prediction.estimate(it, nowMs / 1000.0) }
                val predicted = est?.predicted == true
                // An estimate that ran out goes back to the real position: glide there too.
                if (g.predicted == true && !predicted) { g.from = drawn[m.id]; g.startMs = nowMs }
                g.predicted = predicted
                if (est?.advancing == true) moving = true
                val target = est?.let { it.lat to it.lon } ?: (m.lat to m.lon)
                val t = ((nowMs - g.startMs) / GLIDE_MS).coerceIn(0.0, 1.0)
                if (t < 1.0) moving = true
                val eased = 1 - (1 - t) * (1 - t) * (1 - t) // ease out, no overshoot
                val origin = g.from ?: target
                val pos = (origin.first + (target.first - origin.first) * eased) to (origin.second + (target.second - origin.second) * eased)
                drawn[m.id] = pos
                if (pos.first == m.lat && pos.second == m.lon) m else m.copy(lat = pos.first, lon = pos.second)
            }
            if (next != shown && (nowMs - lastFrame >= 1000 / FRAMES_PER_SECOND || !moving)) {
                shown = next
                lastFrame = nowMs
            }
            if (moving) withFrameMillis { } else delay(1_000)
        }
    }
    return shown
}

private fun markersToGeoJson(markers: List<MapMarker>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        markers.forEach { mk ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(mk.lon); add(mk.lat) }
                }
                putJsonObject("properties") {
                    put("initial", mk.initial)
                    put("textColor", "#FFFFFF")
                    put("color", if (mk.kind == MapMarker.Kind.OFFLINE) "#9E9E9E" else mk.color)
                }
            }
        }
    }
}.toString()

/**
 * A day of someone's history: the path coloured by activity (driving, cycling, walking) and a
 * dot where they stayed put. Frames the whole day, or [focus] (one tapped stretch) when set.
 *
 * The path is lined up with roads it runs close to ([RoadSnap]), using the roads in the map tiles
 * already on screen, so it's redone whenever the map stops moving. Nothing is sent anywhere.
 */
@Composable
fun HistoryMap(points: List<HistoryPoint>, segments: List<HistorySegment>, focus: List<HistoryPoint>?, modifier: Modifier = Modifier, darkMap: Boolean = false) {
    var lines by remember(segments) { mutableStateOf(historyLines(segments, emptyList())) }
    val stops = remember(segments) { historyStops(segments) }
    val mapState = rememberMapState(baseStyle = mapStyle(darkMap)) {
        val lineSource = rememberGeoJsonSource(GeoJsonData.JsonString(lines))
        val stopSource = rememberGeoJsonSource(GeoJsonData.JsonString(stops))
        LineLayer(
            id = "history-path",
            source = lineSource,
            color = feature["color"].convertToColor(),
            width = const(5.dp),
        )
        CircleLayer(
            id = "history-stops",
            source = stopSource,
            radius = const(7.dp),
            color = feature["color"].convertToColor(),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )
    }
    LaunchedEffect(mapState, points, focus) {
        val frame = focus?.takeIf { it.isNotEmpty() } ?: points
        if (frame.isEmpty()) return@LaunchedEffect
        val minLat = frame.minOf { it.lat }; val maxLat = frame.maxOf { it.lat }
        val minLon = frame.minOf { it.lon }; val maxLon = frame.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2
        val span = maxOf(maxLat - minLat, (maxLon - minLon) * cos(midLat * PI / 180), 0.0005)
        // At zoom z the world is 512·2^z dp wide; leave room around the path in a ~300 dp map.
        val zoom = (ln(200.0 * 360 / (512 * span)) / ln(2.0)).coerceIn(3.0, STREET_ZOOM)
        mapState.animateCamera(CameraUpdate(center = Position((minLon + maxLon) / 2, midLat), zoom = zoom))
    }
    BoxWithConstraints(modifier) {
        val view = DpRect(0.dp, 0.dp, maxWidth, maxHeight)
        LaunchedEffect(mapState, segments) {
            snapshotFlow { mapState.isCameraMoving }.collectLatest { moving ->
                if (moving) return@collectLatest
                // Tiles for a new view arrive over a second or two, so look again as they do.
                var seen = -1
                for (wait in listOf(300L, 900L, 2000L)) {
                    delay(wait)
                    val roads = runCatching { visibleRoads(mapState.queryRenderedFeatures(view)) }.getOrDefault(emptyList())
                    if (roads.size == seen) continue
                    seen = roads.size
                    lines = withContext(Dispatchers.Default) { historyLines(segments, roads) }
                }
            }
        }
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = mapState, interactions = Interactions, overlay = {})
    }
}

/** Road classes in OpenMapTiles' transportation layer that people travel along (not rail or ferries). */
private val ROAD_CLASSES = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track", "path", "busway")

/** How close a recorded point must be to a road to be drawn on it. Walkers leave roads more, so less. */
private fun snapMeters(activity: Activity) = if (activity == Activity.WALKING) 12.0 else 25.0

/** The roads among the features drawn on the map, once each (a road is drawn by several layers). */
private fun visibleRoads(features: List<org.maplibre.spatialk.geojson.Feature<org.maplibre.spatialk.geojson.Geometry, kotlinx.serialization.json.JsonObject?>>): List<List<GeoPoint>> {
    val seen = HashSet<String>()
    val roads = ArrayList<List<GeoPoint>>()
    for (f in features) {
        val cls = (f.properties?.get("class") as? JsonPrimitive)?.content
        if (cls !in ROAD_CLASSES) continue
        val parts: List<List<Position>> = when (val g = f.geometry) {
            is LineString -> listOf(g.coordinates)
            is MultiLineString -> g.coordinates
            else -> continue
        }
        for (part in parts) {
            if (part.size < 2) continue
            val a = part.first(); val b = part.last()
            if (seen.add("${a.longitude},${a.latitude},${b.longitude},${b.latitude},${part.size}")) roads += part.map { GeoPoint(it.latitude, it.longitude) }
        }
    }
    return roads
}

private fun historyLines(segments: List<HistorySegment>, roads: List<List<GeoPoint>>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        segments.forEachIndexed { i, seg ->
            if (!LocationHistory.moving(seg.activity)) return@forEachIndexed
            // Start from where the previous stretch ended, so the path has no gaps.
            val recorded = (listOfNotNull(segments.getOrNull(i - 1)?.points?.lastOrNull()) + seg.points).map { GeoPoint(it.lat, it.lon) }
            if (recorded.size < 2) return@forEachIndexed
            val snapped = RoadSnap.snap(recorded, roads, snapMeters(seg.activity))
            // Still leave from the dot of the stop before, which is drawn where it was recorded.
            val fromStop = segments.getOrNull(i - 1)?.let { !LocationHistory.moving(it.activity) } == true
            val path = if (fromStop && snapped.first() != recorded.first()) listOf(recorded.first()) + snapped else snapped
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "LineString")
                    putJsonArray("coordinates") { path.forEach { p -> addJsonArray { add(p.lon); add(p.lat) } } }
                }
                putJsonObject("properties") { put("color", activityColorHex(seg.activity)) }
            }
        }
    }
}.toString()

private fun historyStops(segments: List<HistorySegment>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        segments.filter { !LocationHistory.moving(it.activity) }.forEach { seg ->
            val p = seg.points.last()
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(p.lon); add(p.lat) }
                }
                putJsonObject("properties") { put("color", activityColorHex(seg.activity)) }
            }
        }
    }
}.toString()
