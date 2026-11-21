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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** One face in a group on the map. */
internal data class GroupFace(val color: Color, val initial: String, val photo: ImageBitmap?, val dimmed: Boolean)

/** Most faces drawn in a group; past this, the last slot shows "+N". */
private const val MAX_FACES = 3

/** A face, ring included, is the size of a single person's dot. */
private const val FACE_DP = 36f

/** How far each face sits to the right of the one in front. */
private const val STEP_DP = 22f

private const val RING_DP = 3f

/**
 * The faces to draw for a group of [count] people (#146): all of them up to three, otherwise the
 * first two and a "+N" for the rest. Returns the faces and how many are left over.
 */
internal fun <T> groupFaces(people: List<T>): Pair<List<T>, Int> =
    if (people.size <= MAX_FACES) people to 0 else people.take(MAX_FACES - 1) to people.size - (MAX_FACES - 1)

/** The icon size for [faces] faces and an optional "+N". */
internal fun groupIconSize(faces: Int, more: Int): DpSize {
    val slots = faces + if (more > 0) 1 else 0
    return DpSize((FACE_DP + STEP_DP * (slots - 1)).dp, FACE_DP.dp)
}

/**
 * People close together on the map, drawn as their overlapping faces with the first in front, like
 * a hand of cards, instead of a bubble with a count (#146). Each face looks like that person's own
 * dot: their photo or initial inside a white ring, dimmed when they're offline.
 */
internal class GroupPainter(
    private val faces: List<GroupFace>,
    private val more: Int,
    private val measurer: TextMeasurer,
    private val accent: Color,
) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val d = size.height
        val unit = d / FACE_DP
        val slots = faces.size + if (more > 0) 1 else 0
        // Back to front, so the first face is drawn last and sits on top.
        for (slot in slots - 1 downTo 0) {
            val center = Offset(d / 2 + STEP_DP * unit * slot, d / 2)
            val inner = d / 2 - RING_DP * unit
            drawCircle(Color.White, d / 2, center)
            val face = faces.getOrNull(slot)
            when {
                face == null -> {
                    drawCircle(accent, inner, center)
                    label("+$more", center, inner)
                }
                face.photo != null -> {
                    val image = face.photo
                    val side = minOf(image.width, image.height)
                    val from = IntOffset((image.width - side) / 2, (image.height - side) / 2)
                    val circle = Path().apply { addOval(Rect(center, inner)) }
                    clipPath(circle) {
                        drawImage(
                            image,
                            srcOffset = from,
                            srcSize = IntSize(side, side),
                            dstOffset = IntOffset((center.x - inner).toInt(), (center.y - inner).toInt()),
                            dstSize = IntSize((inner * 2).toInt(), (inner * 2).toInt()),
                            alpha = if (face.dimmed) 0.45f else 1f,
                        )
                    }
                }
                else -> {
                    drawCircle(face.color, inner, center)
                    label(face.initial, center, inner)
                }
            }
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
