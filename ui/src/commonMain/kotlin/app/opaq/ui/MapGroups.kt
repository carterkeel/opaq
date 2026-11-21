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
internal data class GroupFace(val color: Color, val initial: String, val photo: ImageBitmap?, val dimmed: Boolean)

/** A face, ring included, is this wide on the map. */
internal const val FACE_DP = 36f

private const val RING_DP = 3f

/** How far apart faces sit in a stack of [count] (#146): 22 dp for a few, closer as it grows, so a stack stays under 100 dp wide. */
internal fun stackStep(count: Int): Double = if (count < 2) 0.0 else minOf(22.0, 60.0 / (count - 1))

/**
 * One person on the map: their photo or initial inside a white ring, dimmed (greyed, or a faded
 * photo) when they're offline. People close together are these same faces stacked, so they can
 * glide in and out of a stack.
 */
internal class FacePainter(private val face: GroupFace, private val measurer: TextMeasurer) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val d = size.minDimension
        val unit = d / FACE_DP
        val center = Offset(size.width / 2, size.height / 2)
        val inner = d / 2 - RING_DP * unit
        drawCircle(Color.White, d / 2, center)
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
