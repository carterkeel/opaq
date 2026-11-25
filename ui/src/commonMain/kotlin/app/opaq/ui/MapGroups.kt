package app.opaq.ui

import androidx.compose.ui.geometry.Offset
import org.maplibre.compose.overlay.MapOverlayScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** One face in a group on the map. */
internal data class GroupFace(val color: Color, val initial: String, val photo: ImageBitmap?, val dimmed: Boolean, val ring: Color = Color.White)

/** A face, ring included, is this wide on the map. */
internal const val FACE_DP = 40f

/**
 * The white pointer under a face, whose tip is the person's spot, like a map pin. One person has a
 * small one; people at one spot in a stack share a larger one. Its top tucks under the faces by
 * [PIN_OVERLAP_DP].
 */
internal class Pin(val widthDp: Float, val heightDp: Float)

// Height is from the top (inside the face) to the tip; about 16 and 22 dp show below the face.
internal val SINGLE_PIN = Pin(26f, 30f)
internal val GROUP_PIN = Pin(32f, 36f)

/** How far the pointer's top reaches up into the face, so they join as one shape. */
private const val PIN_OVERLAP_DP = 14f

/** The farthest a face is drawn from its stack's spot; anything beyond is a jump, not a glide. */
private const val MAX_REACH_DP = 300f

/** People in a stack within this many dp of each other on screen share one pointer. */
private const val SAME_SPOT_DP = 14.0

/**
 * How far above the point the centre of a stack of [count] faces sits, so the faces rest on their
 * pointer and its tip is the spot.
 */
internal fun stackLift(count: Int): Double {
    val pin = if (count > 1) GROUP_PIN else SINGLE_PIN
    val lowest = (0 until count).maxOf { stackSlot(it, count).second }
    return lowest + FACE_DP / 2 + pin.heightDp - PIN_OVERLAP_DP
}

/** A thin white edge (so the ring stands out on any map), the status ring, then a white gap. */
private const val EDGE_DP = 1.2f
private const val STATUS_DP = 2.8f
private const val RING_DP = 5.2f

/**
 * Where face [index] of a stack of [count] sits, in dp from the stack's centre (#146): round a
 * circle, so two sit side by side, three make a triangle (point up), four a square, and so on.
 * Neighbours are about 26 dp apart, so the 36 dp faces still overlap like a stack; past eight the
 * ring stops growing and they overlap more. Screen directions: y grows downwards.
 */
internal fun stackSlot(index: Int, count: Int): Pair<Double, Double> {
    if (count < 2) return 0.0 to 0.0
    val radius = minOf(30.0, 13.0 / kotlin.math.sin(kotlin.math.PI / count))
    val start = if (count % 2 == 1) -kotlin.math.PI / 2 else -kotlin.math.PI / 2 - kotlin.math.PI / count
    val a = start + 2 * kotlin.math.PI * index / count
    return radius * kotlin.math.cos(a) to radius * kotlin.math.sin(a)
}

/**
 * One person on the map: their photo or initial inside their status ring (blue exact, green
 * approximate, grey offline) with a white edge, dimmed (greyed, or a faded photo) when they're
 * offline. Its shadow is drawn with its stack's ([PeopleOverlay]). People close together are these same faces stacked, so they can
 * glide in and out of a stack.
 */
internal class FacePainter(private val face: GroupFace, private val measurer: TextMeasurer) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / FACE_DP
        val d = FACE_DP * unit
        val center = Offset(size.width / 2, size.height / 2)
        val inner = d / 2 - RING_DP * unit
        drawCircle(Color.White, d / 2, center)
        drawCircle(face.ring, d / 2 - EDGE_DP * unit, center)
        drawCircle(Color.White, d / 2 - (EDGE_DP + STATUS_DP) * unit, center)
        val image = face.photo
        if (image != null) {
            val side = minOf(image.width, image.height)
            val from = IntOffset((image.width - side) / 2, (image.height - side) / 2)
            clipPath(Path().apply { addOval(Rect(center, inner)) }) {
                drawImage(
                    image,
                    srcOffset = from,
                    srcSize = IntSize(side, side),
                    dstOffset = IntOffset((center.x - inner).toInt(), (center.y - inner).toInt()),
                    dstSize = IntSize((inner * 2).toInt(), (inner * 2).toInt()),
                    alpha = if (face.dimmed) 0.45f else 1f,
                )
            }
        } else {
            drawCircle(face.color, inner, center)
            label(face.initial, center, inner)
        }
    }

    private fun DrawScope.label(text: String, center: Offset, radius: Float) {
        if (text.isEmpty()) return
        val size = if (text.length > 2) radius * 0.62f else radius * 0.85f
        val layout = measurer.measure(
            text,
            style = TextStyle(color = Color.White, fontSize = size.toSp(), fontWeight = FontWeight.Bold),
            density = this,
        )
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
    }
}

/** "#1251D3" as a colour; grey if it can't be read. */
internal fun hexColor(hex: String): Color =
    hex.removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color(0xFF9E9E9E)

/**
 * The people on the map, drawn over it in Compose rather than as map symbols (#146), so faces never
 * fade or swap order while the map zooms.
 *
 * Each stack (or person on their own) is one piece placed at its anchor's spot: the faces, a white
 * pointer under them for each spot its people are at (one for people together, several fanning out
 * to each area when a zoomed-out stack covers more than one), and one soft shadow under the whole
 * shape. A pointer's tip is the spot. While faces glide into or out of a stack, their pointers
 * stretch along with them.
 *
 * [faces] gives each face's centre relative to its own person's spot, in dp, at [zoom]. The faces
 * take no touches, so a pinch or drag that starts on one still moves the map; [hits] keeps where
 * each face is drawn so the map's own tap can be matched to it.
 */
@Composable
internal fun MapOverlayScope.PeopleOverlay(
    groups: List<List<MapMarker>>,
    faces: List<StackedFace>,
    painters: Map<String, Painter>,
    keyOf: (MapMarker) -> String,
    zoom: Double,
    hits: FaceHits,
) {
    val byId = faces.associateBy { it.marker.id }
    val world = 512.0 * kotlin.math.exp(zoom * kotlin.math.ln(2.0))
    // Where anyone's spot and face are, in dp from a given spot, so a pointer can hand over
    // smoothly from a face in another stack.
    val spotOf = faces.associate { it.marker.id to mercatorOf(it.marker.lat, it.marker.lon) }
    fun spotFrom(id: String, ax: Double, ay: Double): Offset? = spotOf[id]?.let { (x, y) -> Offset(((x - ax) * world).toFloat(), ((y - ay) * world).toFloat()) }
    fun faceFrom(id: String, ax: Double, ay: Double): Offset? = byId[id]?.let { f -> spotFrom(id, ax, ay)?.plus(Offset(f.dx.toFloat(), f.dy.toFloat())) }
    // Which face carries each person's pointer, and the handover under way when that changes.
    val clock = androidx.compose.runtime.remember { kotlin.time.TimeSource.Monotonic.markNow() }
    val handoffs = androidx.compose.runtime.remember { HashMap<String, Handoff>() }
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val nowMs = maxOf(clock.elapsedNow().inWholeMilliseconds, tick)
    handoffs.keys.retainAll(spotOf.keys)
    // A jump rather than a zoom (another circle or Everyone, or the camera flying somewhere): any
    // handover under way finishes at once, rather than racing across the screen to catch up.
    val lastZoom = androidx.compose.runtime.remember { doubleArrayOf(zoom) }
    if (kotlin.math.abs(zoom - lastZoom[0]) > 0.75) handoffs.replaceAll { _, h -> h.copy(start = Long.MIN_VALUE / 2) }
    lastZoom[0] = zoom
    // People who weren't on the map a moment ago (switching to another circle or Everyone, or
    // someone's first update) pop in one after another, west to east, rather than all at once.
    val onMap = androidx.compose.runtime.remember { HashSet<String>() }
    val popping = androidx.compose.runtime.remember { HashMap<String, Long>() }
    spotOf.keys.filter { it !in onMap }.sortedBy { spotOf.getValue(it).first }
        .forEachIndexed { k, id -> popping[id] = nowMs + k * POP_STAGGER_MILLIS }
    onMap.clear(); onMap.addAll(spotOf.keys)
    popping.keys.retainAll(spotOf.keys)
    popping.entries.removeAll { nowMs - it.value > POP_MILLIS }
    fun pop(id: String): Float = popping[id]?.let { popScale(((nowMs - it).toFloat() / POP_MILLIS).coerceIn(0f, 1f)) } ?: 1f
    // Your stack on top, then the rest in a steady order.
    val ordered = groups.sortedBy { g -> if (g.any { it.kind == MapMarker.Kind.ME }) 1 else 0 }
    val drawn = mutableListOf<FaceHit>()
    ordered.forEach { g ->
        val anchor = g.first()
        androidx.compose.runtime.key(anchor.id) {
            val (ax, ay) = mercatorOf(anchor.lat, anchor.lon)
            // Everyone's spot, and their face's centre, in dp from the anchor's spot.
            val spots = g.map { m -> mercatorOf(m.lat, m.lon).let { (x, y) -> Offset(((x - ax) * world).toFloat(), ((y - ay) * world).toFloat()) } }
            val centres = g.mapIndexed { i, m -> byId[m.id]?.let { f -> spots[i] + Offset(f.dx.toFloat(), f.dy.toFloat()) } ?: spots[i] }
            // People at (nearly) the same spot share one pointer.
            val clusters = mutableListOf<MutableList<Int>>()
            g.indices.forEach { i ->
                val near = clusters.firstOrNull { c -> (spots[c.first()] - spots[i]).getDistance() < SAME_SPOT_DP }
                if (near != null) near += i else clusters += mutableListOf(i)
            }
            // Each pointer starts inside one face, the one of its people nearest the spot, so its
            // top is always hidden by that face and never shows in the gaps between faces. When the
            // face carrying it changes (people join or leave as you zoom), it slides across from the
            // old face to the new one, and its tip and width move with it, rather than jumping.
            // How far each face has popped in (0 to 1, a touch over on the way), and each pointer
            // with the first of its faces to appear.
            val pops = g.map { pop(it.id) }
            val tailPops = clusters.map { c -> c.maxOf { pops[it] }.coerceIn(0f, 1f) }
            val tails = clusters.map { c ->
                val pin = if (c.size > 1) GROUP_PIN else SINGLE_PIN
                val members = c.map { g[it].id }
                val tip = c.map { spots[it] }.reduce { acc, o -> acc + o } / c.size.toFloat()
                val carrier = g[c.minBy { (centres[it] - tip).getDistance() }].id
                members.forEach { id ->
                    val h = handoffs[id]
                    if (h == null) handoffs[id] = Handoff(carrier, carrier, members, members, pin, pin, Long.MIN_VALUE / 2)
                    else if (h.to != carrier || h.toPin != pin || h.toPeople != members) {
                        // Only a short slide between faces close together; anything farther (a
                        // different circle, a face that was far away) moves at once.
                        val was = faceFrom(h.to, ax, ay)
                        val near = was != null && (was - (faceFrom(carrier, ax, ay) ?: was)).getDistance() < HANDOFF_REACH_DP
                        handoffs[id] = Handoff(h.to, carrier, h.toPeople, members, h.toPin, pin, if (near) nowMs else Long.MIN_VALUE / 2)
                    }
                }
                val h = handoffs.getValue(members.first())
                val e = h.progress(nowMs)
                val face = mix(faceFrom(h.from, ax, ay) ?: centres[c.first()], faceFrom(h.to, ax, ay) ?: centres[c.first()], e)
                val oldTip = h.fromPeople.mapNotNull { spotFrom(it, ax, ay) }.takeIf { it.isNotEmpty() }?.let { it.reduce { a, o -> a + o } / it.size.toFloat() } ?: tip
                val point = mix(oldTip, tip, e)
                val width = h.fromPin.widthDp + (h.toPin.widthDp - h.fromPin.widthDp) * e
                val baseY = face.y + FACE_DP / 2 - PIN_OVERLAP_DP
                Triple(Offset(face.x - width / 2, baseY), Offset(face.x + width / 2, baseY), point)
            }
            // A box from the faces down to the spot, so a tap on the stack lands here and a drag
            // anywhere else still moves the map. Everything is drawn from its bottom centre.
            // Kept to a sane size: a box thousands of dp across (a stale glide, or a camera that
            // jumped from far out to street level) can't be measured and would crash the app.
            val half = (centres.maxOf { kotlin.math.abs(it.x) }.takeIf { it.isFinite() } ?: 0f).coerceAtMost(MAX_REACH_DP) + FACE_DP / 2 + 2
            val top = (-(centres.minOf { it.y }.takeIf { it.isFinite() } ?: 0f)).coerceIn(0f, MAX_REACH_DP) + FACE_DP / 2 + 2
            val faceOrder = g.indices.sortedBy { byId[g[it].id]?.order ?: 0 }
            val ids = g.map { it.id }
            faceOrder.forEach { i -> drawn += FaceHit(anchor.lat, anchor.lon, centres[i], g[i].id, if (g.size > 1) ids else null) }
            Box(
                Modifier
                    .placedAt(org.maplibre.spatialk.geojson.Position(anchor.lon, anchor.lat), Alignment.BottomCenter)
                    .size((half * 2).dp, top.coerceAtLeast(1f).dp)
                    .drawBehind {
                        translate(size.width / 2f, size.height) {
                            val u = density
                            // Each piece drawn on its own: in one path, a pointer and a circle wound in
                            // opposite directions cancel out where they overlap, leaving a gap.
                            fun shape(grow: Float) {
                                tails.forEachIndexed { k, (l, r, tip) ->
                                    if (tailPops[k] < 0.5f) return@forEachIndexed
                                    drawPath(Path().apply { moveTo(l.x * u - grow, l.y * u - grow); lineTo(r.x * u + grow, r.y * u - grow); lineTo(tip.x * u, tip.y * u + grow); close() }, Color.Black)
                                }
                                centres.forEachIndexed { i, c -> if (pops[i] > 0f) drawCircle(Color.Black, ((FACE_DP / 2) * u + grow) * pops[i], c * u) }
                            }
                            // One soft shadow under the whole shape, faces and pointers together:
                            // each layer is drawn solid, then faded as a whole, so overlaps don't darken.
                            val area = Rect(-size.width * 4, -size.height * 4, size.width * 4, size.height * 4)
                            for (spread in listOf(3.5f, 2f, 0.8f)) {
                                drawContext.canvas.saveLayer(area, Paint().apply { alpha = 0.07f })
                                translate(0f, 1.5f * u) { shape(spread * u) }
                                drawContext.canvas.restore()
                            }
                            tails.forEachIndexed { k, (l, r, tip) ->
                                if (tailPops[k] <= 0f) return@forEachIndexed
                                drawPath(Path().apply { moveTo(l.x * u, l.y * u); lineTo(r.x * u, r.y * u); lineTo(tip.x * u, tip.y * u); close() }, Color.White.copy(alpha = tailPops[k]))
                            }
                            faceOrder.forEach { i ->
                                val painter = painters[keyOf(g[i])] ?: return@forEach
                                if (pops[i] <= 0f) return@forEach
                                val side = FACE_DP * u * pops[i]
                                val c = centres[i] * u
                                translate(c.x - side / 2, c.y - side / 2) { with(painter) { draw(Size(side, side), alpha = pops[i].coerceAtMost(1f)) } }
                            }
                        }
                    },
            )
        }
    }
    hits.targets = drawn
    // Keep redrawing while a pointer is handing over from one face to another, or faces pop in.
    fun busy(now: Long) = handoffs.values.any { it.progress(now) < 1f } || popping.values.any { now - it <= POP_MILLIS }
    if (busy(nowMs)) {
        androidx.compose.runtime.LaunchedEffect(maxOf(handoffs.values.maxOfOrNull { it.start } ?: 0L, popping.values.maxOrNull() ?: 0L)) {
            while (busy(clock.elapsedNow().inWholeMilliseconds)) {
                androidx.compose.runtime.withFrameMillis { tick = clock.elapsedNow().inWholeMilliseconds }
            }
            tick = clock.elapsedNow().inWholeMilliseconds
        }
    }
}

/** How long a face takes to pop in, and the gap between one face and the next. */
private const val POP_MILLIS = 280L
private const val POP_STAGGER_MILLIS = 45L

/** A face's size as it pops in, [f] from 0 to 1: grows quickly, a little past full size, and settles. */
private fun popScale(f: Float): Float {
    if (f <= 0f) return 0f
    val c = 1.2f
    val t = f - 1
    return 1 + (c + 1) * t * t * t + c * t * t
}

/** How long a pointer takes to move from one face to another. */
private const val HANDOFF_MILLIS = 350L

/** A pointer slides between faces at most this far apart, in dp; farther, it moves at once. */
private const val HANDOFF_REACH_DP = 120f

/**
 * A pointer moving from face [from] (pointing at [fromPeople]'s spot, [fromPin] wide) to face [to]
 * (pointing at [toPeople]'s spot, [toPin] wide), starting at [start] ms.
 */
private data class Handoff(
    val from: String,
    val to: String,
    val fromPeople: List<String>,
    val toPeople: List<String>,
    val fromPin: Pin,
    val toPin: Pin,
    val start: Long,
) {
    /** 0 at the start, 1 once it's there, eased out. */
    fun progress(now: Long): Float {
        val f = ((now - start).toFloat() / HANDOFF_MILLIS).coerceIn(0f, 1f)
        return 1 - (1 - f) * (1 - f) * (1 - f)
    }
}

private fun mix(a: Offset, b: Offset, e: Float) = Offset(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)

/** A face as drawn: its stack's spot ([lat], [lon]), its centre in dp from there, who it is, and its stack. */
internal data class FaceHit(val lat: Double, val lon: Double, val centre: Offset, val id: String, val group: List<String>?)

/** Where the faces are drawn, front last, so a tap on the map can be matched to one. */
internal class FaceHits {
    var targets: List<FaceHit> = emptyList()

    /** The front face under [at] (screen dp), using [screen] to find a spot on screen. */
    fun at(at: androidx.compose.ui.unit.DpOffset, screen: (lat: Double, lon: Double) -> androidx.compose.ui.unit.DpOffset?): FaceHit? =
        targets.asReversed().firstOrNull { t ->
            val spot = screen(t.lat, t.lon) ?: return@firstOrNull false
            val dx = spot.x.value + t.centre.x - at.x.value
            val dy = spot.y.value + t.centre.y - at.y.value
            dx * dx + dy * dy <= (FACE_DP / 2 + 4) * (FACE_DP / 2 + 4)
        }
}

/**
 * Beyond the top and bottom of the world the map draws nothing, which showed as a white band when
 * the map was dragged right up or down with the people sheet out of the way. These fill it with the
 * colour the map has at that edge: the Arctic Ocean above, Antarctic ice below (as the OpenFreeMap
 * styles colour them). They're placed at the camera's longitude and twice the screen wide, so they
 * always cover it.
 */
@Composable
internal fun MapOverlayScope.WorldEdges(dark: Boolean, lon: Double, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val north = if (dark) Color(0xFF1B1B1D) else Color(0xFF9EBDFF)
    val south = if (dark) Color(0xFF0C0C0C) else Color(0xFFE0ECEC)
    val at = lon.takeIf { it.isFinite() } ?: 0.0
    Box(
        Modifier.placedAt(org.maplibre.spatialk.geojson.Position(at, -WORLD_EDGE_LAT), Alignment.TopCenter)
            .size(width * 2, height)
            .drawBehind { drawRect(south) },
    )
    Box(
        Modifier.placedAt(org.maplibre.spatialk.geojson.Position(at, WORLD_EDGE_LAT), Alignment.BottomCenter)
            .size(width * 2, height)
            .drawBehind { drawRect(north) },
    )
}

/** Just inside Web Mercator's edge (85.0511°), so the fill overlaps the map by a hair and no seam shows. */
private const val WORLD_EDGE_LAT = 85.04
