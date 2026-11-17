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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quietmap.core.GeoPoint
import app.quietmap.core.HistoryPoint
import app.quietmap.core.HistorySegment
import app.quietmap.core.LocationHistory
import app.quietmap.core.Prediction
import app.quietmap.core.RoadSnap
import app.quietmap.core.Roads
import app.quietmap.core.Track
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
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
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
    /** Their profile photo (base64 JPEG), drawn in the dot instead of the initial. */
    val photo: String? = null,
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
 * - A person's dot shows their profile photo if they have one, otherwise their initial.
 * - People close together merge into one bubble with a count, and separate as you zoom in.
 * - Moving people travel smoothly between updates, along the roads on screen ([rememberMovingMarkers]).
 * - [bottomInset] keeps centred points above the people sheet.
 * - The built-in scale bar, compass and logo are off; [MAP_ATTRIBUTION] is shown by the home screen.
 */
@Composable
fun MapPane(markers: List<MapMarker>, focusKey: Any?, focus: MapFocus?, bottomInset: Dp, modifier: Modifier = Modifier, darkMap: Boolean = false) {
    // Roads in the tiles on screen, for drawing travelling people along them. Nothing is sent anywhere.
    var roads by remember { mutableStateOf<Roads?>(null) }
    val shown = rememberMovingMarkers(markers, roads)
    // Profile photos, cropped to circles. Remembered per photo so the map registers each image once.
    val photos = markers.mapNotNull { m -> m.photo?.let { m.id to it } }
    val photoIcons = remember(photos) {
        photos.mapNotNull { (id, b64) -> PhotoCache.get(b64)?.let { id to CirclePhotoPainter(it) } }
    }
    val photoIds = remember(photoIcons) { photoIcons.map { it.first }.toSet() }
    val geoJson = remember(shown, photoIds) { markersToGeoJson(shown, photoIds) }
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
        // A profile photo fills the dot, inside its white ring, in place of the initial.
        if (photoIcons.isNotEmpty()) {
            val icons = photoIcons.map { (id, painter) -> id to image(painter, size = DpSize(30.dp, 30.dp)) }
            SymbolLayer(
                id = "people-photo",
                source = people,
                filter = feature.has("photo"),
                iconImage = switch(feature["photo"].asString(), icons.map { case(it.first, it.second) }, fallback = icons.first().second),
                iconOpacity = feature["photoOpacity"].asNumber(const(1f)),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )
        }
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

    BoxWithConstraints(modifier) {
        val view = DpRect(0.dp, 0.dp, maxWidth, maxHeight)
        // Look up the roads again whenever the map stops moving, never per frame. The position is
        // watched too, since a camera that's set rather than animated (opening the map) never "moves".
        LaunchedEffect(mapState, view) {
            snapshotFlow { mapState.isCameraMoving to mapState.cameraPosition }.collectLatest { (moving, _) ->
                if (moving) return@collectLatest
                // Tiles for a new view arrive over a second or two, so look again as they do.
                var seen = -1
                for (wait in listOf(300L, 900L, 2000L)) {
                    delay(wait)
                    if (mapState.cameraPosition.zoom < ROAD_ZOOM) {
                        // Zoomed out, road shapes are simplified and minor roads missing: go straight.
                        if (roads != null) roads = null
                        seen = -1
                        continue
                    }
                    val lines = runCatching { visibleRoads(mapState.queryRenderedFeatures(view)) }.getOrDefault(emptyList())
                    if (lines.size == seen) continue
                    seen = lines.size
                    roads = if (lines.isEmpty()) null else withContext(Dispatchers.Default) { Roads(lines) }
                }
            }
        }
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            viewportInsets = PaddingValues(bottom = bottomInset),
            interactions = Interactions,
            overlay = {},
        )
    }
}

/** Below this zoom, people aren't moved along roads (the tiles' road shapes are too coarse). */
private const val ROAD_ZOOM = 12.0

/** Map refreshes per second while dots are moving. */
private const val FRAMES_PER_SECOND = 30

/** How one person's dot is moving: the plan for their latest update, and the catch-up to it. */
private class Motion(val key: MotionKey, val plan: Prediction.Plan, val track: Track?)

/** What a [Motion] was worked out from; when any of it changes, the dot sets off on a new one. */
private data class MotionKey(val time: Long?, val lat: Double, val lon: Double, val roads: Roads?)

/**
 * The markers as they should be drawn right now (#55, #57, #92, #93, #97, #98).
 *
 * - Someone travelling is carried forward at their speed, along the road they're on when it's on
 *   screen, and drawn on that road when within about 20 ft of it ([Prediction.plan]).
 * - A new update doesn't make the dot jump: it travels from where it's drawn to the new estimate
 *   along the recent path and the road ([Track]).
 * - An update gone stale, or someone offline, is shown where they really were.
 *
 * Plans and catch-ups are worked out once per update (or when the roads on screen change); each
 * frame only looks up a point along a line. Runs at [FRAMES_PER_SECOND] only while something
 * moves, and checks once a second otherwise, so an update that goes stale settles back in place.
 */
@Composable
private fun rememberMovingMarkers(markers: List<MapMarker>, roads: Roads?): List<MapMarker> {
    var shown by remember { mutableStateOf(markers) }
    // Kept across new marker lists, so a dot always leaves from where it is drawn.
    val drawn = remember { HashMap<String, GeoPoint>() }
    val motions = remember { HashMap<String, Motion>() }
    LaunchedEffect(markers, roads) {
        val ids = markers.map { it.id }.toSet()
        drawn.keys.retainAll(ids)
        motions.keys.retainAll(ids)
        var lastFrame = 0L
        while (true) {
            val nowMs = epochMillis()
            val now = nowMs / 1000.0
            var moving = false
            val next = markers.map { m ->
                val loc = m.location?.takeIf { m.kind != MapMarker.Kind.OFFLINE && !Prediction.isStale(it, now) }
                val key = MotionKey(loc?.time, m.lat, m.lon, roads.takeIf { loc != null })
                val old = motions[m.id]
                val motion = if (old != null && old.key == key) old else {
                    val plan = loc?.let { Prediction.plan(it, roads) } ?: Prediction.Plan.still(GeoPoint(m.lat, m.lon))
                    val previous = old?.key?.time
                    // The path taken since the update the dot was following.
                    val recent = loc?.let { l ->
                        l.points.filter { (previous == null || it.t > previous) && it.t < l.time }.map { GeoPoint(it.lat, it.lon) }
                    }.orEmpty()
                    val snap = if (loc?.activity == Activity.WALKING) Prediction.SNAP_METERS else Prediction.FOLLOW_METERS
                    val track = drawn[m.id]?.let { Track.build(it, plan, now, recent, roads, snap) }
                    Motion(key, plan, track).also { motions[m.id] = it }
                }
                val track = motion.track?.takeUnless { it.done(now) }
                val pos = track?.at(now) ?: motion.plan.at(now).let { GeoPoint(it.lat, it.lon) }
                if (track != null || motion.plan.advancing(now)) moving = true
                drawn[m.id] = pos
                if (pos.lat == m.lat && pos.lon == m.lon) m else m.copy(lat = pos.lat, lon = pos.lon)
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

private fun markersToGeoJson(markers: List<MapMarker>, photoIds: Set<String>): String = buildJsonObject {
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
                    val offline = mk.kind == MapMarker.Kind.OFFLINE
                    if (mk.id in photoIds) {
                        // The photo layer picks the image by id; offline people's photos are dimmed.
                        put("photo", mk.id)
                        put("photoOpacity", if (offline) 0.45 else 1.0)
                        put("initial", "")
                    } else {
                        put("initial", mk.initial)
                    }
                    put("textColor", "#FFFFFF")
                    put("color", if (offline) "#9E9E9E" else mk.color)
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
