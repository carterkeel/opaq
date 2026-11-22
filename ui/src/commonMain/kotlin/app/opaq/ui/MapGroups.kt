package app.opaq.ui

import androidx.compose.ui.geometry.Offset
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

/** Room round a face's picture for its soft drop shadow. The picture is [FACE_IMAGE_DP] square. */
internal const val SHADOW_DP = 4f
internal const val FACE_IMAGE_DP = FACE_DP + 2 * SHADOW_DP

/**
 * The white pointer under a face, whose tip is the person's spot, like a map pin. One person has a
 * small one; a stack shares one larger one under the whole stack. The pointer's top tucks under the
 * face by [PIN_OVERLAP_DP].
 */
internal class Pin(val widthDp: Float, val heightDp: Float) {
    /** The picture: the pointer with room for its shadow at the sides and bottom. */
    val imageWidthDp get() = widthDp + 2 * SHADOW_DP
    val imageHeightDp get() = heightDp + SHADOW_DP
    /** Shifts the picture's centre so the tip lands on the point. */
    val offsetDp get() = -(heightDp - imageHeightDp / 2)
}

internal val SINGLE_PIN = Pin(18f, 16f)
internal val GROUP_PIN = Pin(30f, 22f)
private const val PIN_OVERLAP_DP = 6f

/**
 * How far above the point the centre of a stack of [count] faces sits, so the faces rest on their
 * pointer and its tip is the spot.
 */
internal fun stackLift(count: Int): Double {
    val pin = if (count > 1) GROUP_PIN else SINGLE_PIN
    val lowest = (0 until count).maxOf { stackSlot(it, count).second }
    return lowest + FACE_DP / 2 + pin.heightDp - PIN_OVERLAP_DP
}

/** Draws a [Pin]: a soft shadow, then a white triangle pointing down. */
internal class PinPainter(private val pin: Pin) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val unit = size.width / pin.imageWidthDp
        val left = SHADOW_DP * unit
        val w = pin.widthDp * unit
        val h = pin.heightDp * unit
        fun triangle(dx: Float, dy: Float, grow: Float) = Path().apply {
            moveTo(left - grow + dx, dy - grow)
            lineTo(left + w + grow + dx, dy - grow)
            lineTo(left + w / 2 + dx, h + grow + dy)
            close()
        }
        // A light shadow, a little below and spreading out.
        for (i in 3 downTo 1) drawPath(triangle(0f, unit * 1.2f, unit * i * 0.8f), Color.Black.copy(alpha = 0.05f))
        drawPath(triangle(0f, 0f, 0f), Color.White)
    }
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
 * approximate, grey offline) with a white edge and a soft drop shadow, dimmed (greyed, or a faded
 * photo) when they're offline. The picture is [FACE_IMAGE_DP] square, the face [FACE_DP] across in
 * the middle. People close together are these same faces stacked, so they can
 * glide in and out of a stack.
 */
internal class FacePainter(private val face: GroupFace, private val measurer: TextMeasurer) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / FACE_IMAGE_DP
        val d = FACE_DP * unit
        val center = Offset(size.width / 2, size.height / 2)
        val inner = d / 2 - RING_DP * unit
        // A light drop shadow, a little below the face.
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(
                0.75f to Color.Black.copy(alpha = 0.22f),
                1f to Color.Transparent,
                center = center + Offset(0f, 1.5f * unit),
                radius = d / 2 + SHADOW_DP * unit,
            ),
            d / 2 + SHADOW_DP * unit,
            center + Offset(0f, 1.5f * unit),
        )
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
