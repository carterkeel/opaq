package app.opaq.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opaq.core.GeoPoint
import app.opaq.core.Grouping
import app.opaq.core.HistoryPoint
import app.opaq.core.MotionPolicy
import app.opaq.core.HistorySegment
import app.opaq.core.LocationHistory
import app.opaq.core.Prediction
import app.opaq.core.RoadSnap
import app.opaq.core.Roads
import app.opaq.core.Track
import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Place
import app.opaq.core.model.Precision
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asDpOffset
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
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Point
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
    /** The ring round their face: blue exact, green approximate, grey offline. */
    val ring: Format.Ring = Format.Ring.EXACT,
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
 * - Each face stands on a white pointer whose tip is the person's spot. People whose dots would
 *   overlap are stacked in a ring above a shared pointer: two side by side, three in a triangle,
 *   four in a square ([Grouping], [stackSlot]), with one pointer per area they're in. Faces glide
 *   into and out of a stack as you zoom (#146). They're drawn over the map in Compose
 *   ([PeopleOverlay]), not as map symbols, so they never fade or swap order while zooming.
 * - Tapping a person's dot calls [onPersonClick] with their id. Tapping a stack calls
 *   [onGroupClick] with everyone in it.
 * - Moving people travel smoothly between updates, along the roads on screen ([rememberMovingMarkers]).
 * - [bottomInset] keeps centred points above the people sheet, [endInset] clear of the side panel in landscape.
 * - [places] (alert areas, #151) are drawn as faint circles, true to scale, under the people.
 * - The built-in scale bar, compass and logo are off; [MAP_ATTRIBUTION] is shown by the home and history screens.
 */
@Composable
fun MapPane(
    markers: List<MapMarker>,
    focusKey: Any?,
    focus: MapFocus?,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    darkMap: Boolean = false,
    onPersonClick: (id: String) -> Unit = {},
    endInset: Dp = 0.dp,
    onGroupClick: (ids: List<String>) -> Unit = {},
    places: List<Place> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    val onPerson by rememberUpdatedState(onPersonClick)
    val onGroup by rememberUpdatedState(onGroupClick)
    // Roads in the tiles on screen, for drawing travelling people along them. Nothing is sent anywhere.
    var roads by remember { mutableStateOf<Roads?>(null) }
    val shown = rememberMovingMarkers(markers, roads)
    // People whose dots would overlap at this zoom, regrouped every half zoom level (#146). You come
    // first, then people online, so your face is in front and your group doesn't move as others pass.
    var groupZoom by remember { mutableStateOf(CITY_ZOOM) }
    val groups = remember(shown, groupZoom) {
        val ordered = shown.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        Grouping.group(ordered.map { it.lat to it.lon }, groupZoom).map { g -> g.map { ordered[it] } }
    }
    // Each face glides into its place in a stack, and back out, rather than jumping.
    var zoom by remember { mutableStateOf(CITY_ZOOM) }
    val faces = rememberStackedFaces(groups, zoom)
    // One picture per person: their photo or initial inside their status ring, dimmed when offline.
    val measurer = rememberTextMeasurer()
    val ringColors = Format.Ring.entries.associateWith { ringColor(it) }
    val faceIcons = remember(shown.map(::faceKey).toSet(), ringColors) {
        shown.distinctBy { it.id }.map { m ->
            val offline = m.kind == MapMarker.Kind.OFFLINE
            faceKey(m) to FacePainter(GroupFace(if (offline) Color(0xFF9E9E9E) else hexColor(m.color), m.initial, m.photo?.let { PhotoCache.get(it) }, offline, ringColors.getValue(m.ring)), measurer)
        }
    }
    val placesGeo = remember(places) { placesToGeoJson(places) }
    // People sharing only roughly where they are (#157): the area they could be anywhere in.
    val roughGeo = remember(shown.map { Triple(it.id, it.location?.precision, it.lat to it.lon) }) { approximateAreasToGeoJson(shown) }
    // Faces are drawn over the map but take no touches themselves, so a pinch or drag that starts on
    // a face still moves the map. A tap is matched to a face here instead.
    val hits = remember { FaceHits() }
    val mapRef = remember { arrayOfNulls<org.maplibre.compose.map.MapState>(1) }
    val peopleInteractions = remember {
        MapInteractions(MapInteractions.Standard) {
            camera { rotate { enabled = false } }
            callbacks {
                click {
                    onEvent { e ->
                        val hit = mapRef[0]?.let { state -> hits.at(e.screenOffset) { lat, lon -> state.screenLocationFromPosition(Position(lon, lat)) } }
                        when {
                            hit == null -> ClickResult.Pass
                            hit.group != null -> { onGroup(hit.group); ClickResult.Consume }
                            else -> { onPerson(hit.id); ClickResult.Consume }
                        }
                    }
                }
            }
        }
    }
    val mapState = rememberMapState(baseStyle = mapStyle(darkMap)) {
        val rough = rememberGeoJsonSource(GeoJsonData.JsonString(roughGeo))
        FillLayer(id = "rough-fill", source = rough, color = feature["color"].convertToColor(), opacity = const(0.10f))
        LineLayer(
            id = "rough-edge",
            source = rough,
            color = feature["color"].convertToColor(),
            width = const(1.5.dp),
            opacity = const(0.8f),
            dasharray = const(listOf(2f, 2f)),
        )
        // Alert areas, only on this phone's map.
        val areas = rememberGeoJsonSource(GeoJsonData.JsonString(placesGeo))
        FillLayer(id = "places-fill", source = areas, color = const(Palette.Ultramarine), opacity = const(0.12f))
        LineLayer(id = "places-edge", source = areas, color = const(Palette.Ultramarine), width = const(1.5.dp), opacity = const(0.7f))
        SymbolLayer(
            id = "places-name",
            source = areas,
            textField = format(span(feature["name"].asString())),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(12.sp),
            textColor = const(Palette.Ultramarine),
            textHaloColor = const(Color.White),
            textHaloWidth = const(1.5.dp),
        )
    }
    mapRef[0] = mapState

    // Regroup every half zoom level, as the map zooms.
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraPosition.zoom }.collect {
            zoom = it
            groupZoom = floor(it * 2) / 2
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
            viewportInsets = PaddingValues(bottom = bottomInset, end = endInset),
            cameraConstraints = worldFills(maxHeight),
            interactions = peopleInteractions,
            uiOptions = remember { overlayMapUiOptions() },
            overlay = { PeopleOverlay(groups, faces, faceIcons.toMap(), ::faceKey, zoom, hits) },
        )
    }
}

/**
 * The map on the place alert screen (#151). Move and zoom it freely; a tap puts the place there
 * ([onPick]). The area is drawn true to scale round [lat], [lon].
 */
@Composable
fun PlacePickerMap(lat: Double, lon: Double, radius: Double, darkMap: Boolean, onPick: (lat: Double, lon: Double) -> Unit, modifier: Modifier = Modifier) {
    val pick by rememberUpdatedState(onPick)
    val interactions = remember {
        MapInteractions(MapInteractions.Standard) {
            camera { rotate { enabled = false } }
            callbacks {
                click {
                    onEvent { e ->
                        val at = e.position
                        if (at != null) pick(at.latitude, ((at.longitude + 540.0) % 360.0) - 180.0)
                        ClickResult.Consume
                    }
                }
            }
        }
    }
    // Picking a spot glides the area there on a spring that settles without bouncing. Picking again
    // mid-glide carries on from where it's drawn, at the speed it's going, so it never jerks. A
    // ripple spreads from where you tapped and the dot swells as it lands, and changing the radius
    // grows or shrinks the area on the same spring.
    // Positions are animated as millionths of a degree from where the screen opened: small enough
    // for a Float to be exact to well under a metre.
    val origin = remember { doubleArrayOf(lat, lon) }
    fun micro(la: Double, lo: Double) = Offset(((lo - origin[1]) * 1e6).toFloat(), ((la - origin[0]) * 1e6).toFloat())
    val glideSpring = spring<Offset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val shown = remember { Animatable(micro(lat, lon), Offset.VectorConverter) }
    val ripple = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val picked = remember { doubleArrayOf(lat, lon) }
    SideEffect {
        if (picked[0] != lat || picked[1] != lon) {
            picked[0] = lat; picked[1] = lon
            val to = micro(lat, lon)
            // Launched (not a LaunchedEffect restart, which would stop the glide first) so the new
            // glide starts at the current speed.
            scope.launch { shown.animateTo(to, glideSpring) }
            scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(700, easing = LinearOutSlowInEasing)) }
        }
    }
    val drawnRadius by animateFloatAsState(radius.toFloat(), spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "place radius")
    val shownLat = origin[0] + shown.value.y / 1e6
    val shownLon = origin[1] + shown.value.x / 1e6
    val r = ripple.value
    val pop = kotlin.math.sin(PI * r).toFloat()
    val area = placesToGeoJson(listOf(Place("", "", shownLat, shownLon, drawnRadius.toDouble())))
    val spot = buildJsonObject {
        put("type", "Point")
        putJsonArray("coordinates") { add(shownLon); add(shownLat) }
    }.toString()
    val tapped = buildJsonObject {
        put("type", "Point")
        putJsonArray("coordinates") { add(lon); add(lat) }
    }.toString()
    val mapState = rememberMapState(baseStyle = mapStyle(darkMap)) {
        val areaSource = rememberGeoJsonSource(GeoJsonData.JsonString(area))
        FillLayer(id = "pick-fill", source = areaSource, color = const(Palette.Ultramarine), opacity = const(0.15f))
        LineLayer(id = "pick-edge", source = areaSource, color = const(Palette.Ultramarine), width = const(2.dp))
        // The ripple where you tapped: it spreads out and fades.
        CircleLayer(
            id = "pick-ripple",
            source = rememberGeoJsonSource(GeoJsonData.JsonString(tapped)),
            radius = const((6f + 30f * r).dp),
            color = const(Palette.Ultramarine),
            opacity = const(0.3f * (1f - r)),
        )
        CircleLayer(
            id = "pick-spot",
            source = rememberGeoJsonSource(GeoJsonData.JsonString(spot)),
            radius = const((5f + 3f * pop).dp),
            color = const(Palette.Ultramarine),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )
    }
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value.toDouble()
        LaunchedEffect(mapState) {
            // Open on the place, with the area about a third of the map's width across.
            val metersPerDp = radius * 2 / (widthDp / 3)
            val zoom = kotlin.math.log2(40_075_016.686 * cos(lat * PI / 180) / (512.0 * metersPerDp)).coerceIn(2.0, 18.0)
            mapState.setCameraPosition(CameraPosition(center = Position(lon, lat), zoom = zoom))
        }
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            cameraConstraints = worldFills(maxHeight),
            interactions = interactions,
            // Drawn with the rest of the screen, so the rounded corners clip it.
            uiOptions = remember { overlayMapUiOptions() },
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

/**
 * For each person sharing approximately (#157), the circle they could be anywhere in: their
 * update's accuracy, about 1 mi for a neighbourhood and 10 mi for a city, round the cell's centre.
 */
private fun approximateAreasToGeoJson(markers: List<MapMarker>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        markers.forEach { m ->
            val loc = m.location ?: return@forEach
            if (loc.precision == null || loc.precision == Precision.EXACT) return@forEach
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") {
                        addJsonArray { circlePoints(loc.lat, loc.lon, loc.accuracy.toDouble()).forEach { (lat, lon) -> addJsonArray { add(lon); add(lat) } } }
                    }
                }
                putJsonObject("properties") { put("color", if (m.kind == MapMarker.Kind.OFFLINE) "#9E9E9E" else m.color) }
            }
        }
    }
}.toString()

/** Alert areas as circles of 64 points, true to scale on the map. */
private fun placesToGeoJson(places: List<Place>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        places.filter { it.enabled }.forEach { p ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") {
                        addJsonArray { circlePoints(p.lat, p.lon, p.radius).forEach { (lat, lon) -> addJsonArray { add(lon); add(lat) } } }
                    }
                }
                putJsonObject("properties") { put("name", p.name) }
            }
        }
    }
}.toString()

/** A closed ring of points [radius] metres round a centre, as (lat, lon). */
internal fun circlePoints(lat: Double, lon: Double, radius: Double, steps: Int = 64): List<Pair<Double, Double>> {
    val dLat = radius / 111_320.0
    val dLon = radius / (111_320.0 * cos(lat * PI / 180).coerceAtLeast(0.01))
    return (0..steps).map { i ->
        val a = 2 * PI * i / steps
        (lat + dLat * kotlin.math.sin(a)) to (lon + dLon * cos(a))
    }
}

/** Identifies a person's picture: who, and how they look right now. */
internal fun faceKey(m: MapMarker): String = "${m.id}:${m.kind.ordinal}:${m.ring.ordinal}:${m.color}:${m.initial}:${m.photo?.hashCode() ?: 0}"



/**
 * A face as drawn: at its person's own spot, shifted by ([dx], [dy]) dp, its drawing order, its
 * stack's people, and whether it's the one the stack's pointer stands on ([anchor]).
 */
internal data class StackedFace(val marker: MapMarker, val dx: Double, val dy: Double, val order: Int, val ids: String?, val anchor: Boolean = false)

/**
 * Where a face belongs: [anchor]'s spot plus ([sx], [sy]) dp, in place [ring] round its stack.
 * Someone on their own is their own anchor.
 */
private data class Slot(val anchor: String, val sx: Double, val sy: Double, val order: Int, val ids: String?, val ring: Int = 0)

/** How long a face takes to glide into or out of a stack. */
private const val STACK_MILLIS = 400.0

private fun slots(groups: List<List<MapMarker>>, zoom: Double, previous: Map<String, Slot>): Map<String, Slot> = buildMap {
    val world = 512.0 * kotlin.math.exp(zoom * ln(2.0))
    groups.forEach { g ->
        val ids = if (g.size > 1) g.joinToString(",") { it.id } else null
        // Raised so the faces rest on their pointer, whose tip is the spot.
        val lift = stackLift(g.size)
        val places = ringPlaces(g, ids, world, previous)
        g.forEachIndexed { i, m ->
            val k = places[i]
            val (sx, sy) = stackSlot(k, g.size)
            put(m.id, Slot(g.first().id, sx, sy - lift, g.size - k, ids, k))
        }
    }
}

/**
 * Which place round the ring each person in stack [g] takes: the one on the side of the stack
 * nearest where they really are, so someone to the west sits on the left and pointers fanning out
 * to different places never cross. (The arrangement with the shortest total distance from faces
 * to spots never has two pointers crossing.) The arrangement already shown is kept unless a new
 * one is clearly better, so faces don't swap back and forth as people move a little.
 */
private fun ringPlaces(g: List<MapMarker>, ids: String?, world: Double, previous: Map<String, Slot>): IntArray {
    val n = g.size
    if (n < 2) return IntArray(n)
    val pts = g.map { mercatorOf(it.lat, it.lon) }
    val cx = pts.sumOf { it.first } / n
    val cy = pts.sumOf { it.second } / n
    val rel = pts.map { (x, y) -> (x - cx) * world to (y - cy) * world }
    val ring = (0 until n).map { stackSlot(it, n) }
    fun cost(a: IntArray) = (0 until n).sumOf { i -> kotlin.math.hypot(ring[a[i]].first - rel[i].first, ring[a[i]].second - rel[i].second) }
    var best = IntArray(n) { it }
    var bestCost = cost(best)
    if (n <= 6) {
        // Every arrangement: at most 720.
        val a = IntArray(n) { it }
        fun permute(k: Int) {
            if (k == n) { val c = cost(a); if (c < bestCost - 1e-6) { bestCost = c; best = a.copyOf() }; return }
            for (j in k until n) {
                a[k] = a[j].also { a[j] = a[k] }
                permute(k + 1)
                a[k] = a[j].also { a[j] = a[k] }
            }
        }
        permute(0)
    } else {
        // Larger stacks: people in order of direction from the middle, matched to the ring's places
        // in the same order, at whichever turn fits best.
        val byAngle = (0 until n).sortedBy { kotlin.math.atan2(rel[it].second, rel[it].first) }
        val placesByAngle = (0 until n).sortedBy { kotlin.math.atan2(ring[it].second, ring[it].first) }
        for (turn in 0 until n) {
            val a = IntArray(n)
            byAngle.forEachIndexed { k, person -> a[person] = placesByAngle[(k + turn) % n] }
            val c = cost(a)
            if (c < bestCost - 1e-6) { bestCost = c; best = a }
        }
    }
    val shown = g.map { previous[it.id] }
    if (shown.all { it != null && it.ids == ids } && shown.map { it!!.ring }.toSet().size == n) {
        val kept = IntArray(n) { shown[it]!!.ring }
        if (cost(kept) <= bestCost * 1.15 + 3.0) return kept
    }
    return best
}

/** Web Mercator, as a fraction of the world across and down. */
internal fun mercatorOf(lat: Double, lon: Double): Pair<Double, Double> {
    val phi = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
    return (lon + 180.0) / 360.0 to (1.0 - ln(kotlin.math.tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
}

/**
 * The faces as they should be drawn right now (#146).
 *
 * Every face stays at its own person's spot on the map, and a stack is made by shifting the
 * picture (`icon-offset`) to the stack's centre and round its circle. Moving the point itself by
 * more than a couple of pixels between redraws makes MapLibre treat it as a new symbol and fade it
 * in from transparent, which flashed. When someone joins or leaves a stack, the shift glides from
 * the old slot to the new one over [STACK_MILLIS]. Both slots are worked out at the current
 * [zoom], so a glide stays right while the map zooms.
 */
@Composable
private fun rememberStackedFaces(groups: List<List<MapMarker>>, zoom: Double): List<StackedFace> {
    val clock = remember { kotlin.time.TimeSource.Monotonic.markNow() }
    val current = remember { HashMap<String, Slot>() }
    val glides = remember { HashMap<String, Pair<Slot, Double>>() }
    var now by remember { mutableStateOf(0.0) }
    val targets = remember(groups) { slots(groups, zoom, HashMap(current)) }
    val byId = remember(groups) { groups.flatten().associateBy { it.id } }
    // A changed slot starts a glide from the old one.
    val start = clock.elapsedNow().inWholeMilliseconds.toDouble()
    targets.forEach { (id, slot) ->
        val was = current[id]
        if (was != null && (was.anchor != slot.anchor || was.sx != slot.sx || was.sy != slot.sy)) glides[id] = was to start
        current[id] = slot
    }
    current.keys.retainAll(targets.keys)
    glides.keys.retainAll(targets.keys)
    if (glides.isNotEmpty()) {
        LaunchedEffect(glides.keys.toSet()) {
            while (glides.isNotEmpty()) withFrameMillis { now = clock.elapsedNow().inWholeMilliseconds.toDouble() }
        }
    }
    val world = 512.0 * kotlin.math.exp(zoom * ln(2.0))
    fun offset(m: MapMarker, slot: Slot): Pair<Double, Double> {
        val anchor = byId[slot.anchor] ?: m
        val (ax, ay) = mercatorOf(anchor.lat, anchor.lon)
        val (ox, oy) = mercatorOf(m.lat, m.lon)
        return (ax - ox) * world + slot.sx to (ay - oy) * world + slot.sy
    }
    val t = maxOf(now, start)
    return groups.flatten().map { m ->
        val slot = targets.getValue(m.id)
        val (tx, ty) = offset(m, slot)
        val glide = glides[m.id]
        var f = glide?.let { ((t - it.second) / STACK_MILLIS).coerceIn(0.0, 1.0) } ?: 1.0
        // A glide across more than a stack's width (the camera jumped, or the circle changed) isn't
        // a glide: go straight there.
        if (glide != null && f < 1.0) {
            val (fx, fy) = offset(m, glide.first)
            if (!fx.isFinite() || !fy.isFinite() || kotlin.math.hypot(tx - fx, ty - fy) > 150.0) f = 1.0
        }
        if (f >= 1.0) {
            glides.remove(m.id)
            StackedFace(m, tx, ty, slot.order, slot.ids, slot.anchor == m.id)
        } else {
            val (fx, fy) = offset(m, glide!!.first)
            val e = 1 - (1 - f) * (1 - f) * (1 - f) // ease out
            StackedFace(m, fx + (tx - fx) * e, fy + (ty - fy) * e, slot.order, slot.ids, slot.anchor == m.id)
        }
    }
}

/**
 * A day of someone's history: the path coloured by activity (driving, walking) and a
 * dot where they stayed put. Frames the whole day, or [focus] (one tapped stretch) when set.
 *
 * The path is lined up with roads it runs close to ([RoadSnap]), using the roads in the map tiles
 * already on screen, so it's redone whenever the map stops moving. Nothing is sent anywhere.
 */
@Composable
fun HistoryMap(points: List<HistoryPoint>, segments: List<HistorySegment>, focus: List<HistoryPoint>?, modifier: Modifier = Modifier, darkMap: Boolean = false, animate: Boolean = true, showStops: Boolean = true) {
    var lines by remember(segments) { mutableStateOf(historyLines(segments, emptyList())) }
    val stops = remember(segments, showStops) { if (showStops) historyStops(segments) else historyStops(emptyList()) }
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
        val center = Position((minLon + maxLon) / 2, midLat)
        if (animate) mapState.animateCamera(CameraUpdate(center = center, zoom = zoom))
        else mapState.setCameraPosition(CameraPosition(center = center, zoom = zoom))
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
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = mapState, cameraConstraints = worldFills(maxHeight), interactions = Interactions, uiOptions = remember { overlayMapUiOptions() }, overlay = {})
    }
}

/**
 * Don't zoom out so far that the world is shorter than the map: below Antarctica (and above the
 * Arctic) there's only the style's plain background, a light band that shows even in dark mode.
 * At zoom z the world is 512·2^z dp tall, so the smallest zoom that fills [height] is log2(height / 512).
 */
@Composable
private fun worldFills(height: Dp): CameraConstraints = remember(height) {
    val fill = ln(height.value / 512.0) / ln(2.0) + 0.02
    CameraConstraints { minZoom = fill.coerceAtLeast(0.0) }
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
            // Start from where the previous stretch ended, so the path has no gaps, unless that was
            // a pause or long ago (#156): then there's no knowing the way taken, so draw none.
            val before = segments.getOrNull(i - 1)?.takeIf { !it.paused }?.points?.lastOrNull()
            val points = listOfNotNull(before) + seg.points
            // A gap in the recording (phone off, no signal) splits the line rather than drawing a
            // straight one across it, which could run through water or buildings.
            historyPieces(points).forEach { piece ->
                val recorded = piece.map { GeoPoint(it.lat, it.lon) }
                if (recorded.size < 2) return@forEach
                val snapped = RoadSnap.snap(recorded, roads, snapMeters(seg.activity))
                // Still leave from the dot of the stop before, which is drawn where it was recorded.
                val fromStop = piece.first() === before && segments.getOrNull(i - 1)?.let { !LocationHistory.moving(it.activity) } == true
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
    }
}.toString()

/**
 * Splits recorded points where there's a gap no line should bridge (#156): over 3 minutes with
 * nothing recorded while moving, or a jump faster than any car.
 */
internal fun historyPieces(points: List<HistoryPoint>): List<List<HistoryPoint>> {
    val pieces = mutableListOf<MutableList<HistoryPoint>>()
    points.forEach { p ->
        val last = pieces.lastOrNull()?.lastOrNull()
        val gap = last != null && run {
            val dt = p.t - last.t
            val meters = MotionPolicy.distanceMeters(last.lat, last.lon, p.lat, p.lon)
            dt > HISTORY_GAP_SECONDS || (dt > 0 && meters / dt > 70) || (dt <= 0 && meters > 500)
        }
        if (last == null || gap) pieces += mutableListOf(p) else pieces.last() += p
    }
    return pieces
}

private const val HISTORY_GAP_SECONDS = 180L

private fun historyStops(segments: List<HistorySegment>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        segments.filter { !LocationHistory.moving(it.activity) }.forEach { seg ->
            val p = seg.points.last()
            val color = if (seg.paused) "#9E9E9E" else activityColorHex(seg.activity)
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(p.lon); add(p.lat) }
                }
                putJsonObject("properties") { put("color", color) }
            }
        }
    }
}.toString()
