package app.opaq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.opaq.core.model.AppState

/**
 * A person's row: [onClick] on a tap, and [onLongPress] on a long press (only when [enabled]), with
 * where the finger was in the row, after a long-press buzz.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.personPress(enabled: Boolean, onClick: () -> Unit, onLongPress: (at: Offset) -> Unit): Modifier {
    var downAt by remember { mutableStateOf(Offset.Zero) }
    val haptics = rememberHaptics()
    return this
        // Notes where the finger went down, without taking the touch, so the menu opens there.
        .pointerInput(Unit) { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false).position } }
        .combinedClickable(
            onClickLabel = "Show on map",
            onLongClickLabel = if (enabled) "More options" else null,
            onClick = onClick,
            onLongClick = if (enabled) ({ haptics.longPress(); onLongPress(downAt) }) else null,
        )
}

/**
 * What a long press on someone in a people list offers: ask their phone for their location, rename
 * them on this phone, directions to them, and (for the circle's admin) remove them, after asking.
 *
 * It pops in where the finger was ([at], within the row it belongs to) and closes when you tap
 * anywhere else. [circleId] is a real circle they're in: in Everyone, the one their newest update
 * came through.
 */
@Composable
fun PersonMenu(state: AppState, me: String, circleId: String, pubkey: String, actions: Actions, at: Offset?, onDismiss: () -> Unit) {
    val circle = state.circles.firstOrNull { it.id == circleId } ?: return
    val member = circle.members.firstOrNull { it.pubkey == pubkey } ?: return
    val info = avatarFor(state, pubkey, me, member.name)
    val loc = state.locations[circleId]?.get(pubkey)
    val now = rememberNow()
    val haptics = rememberHaptics()
    val gate = LocalPinGate.current
    val contact = state.contacts[pubkey]
    val published = state.profiles[pubkey]?.name?.ifBlank { null } ?: member.name
    var renaming by remember { mutableStateOf(false) }
    var directions by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    // Shown while opening, and kept while it shrinks away after closing.
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(at != null) { shown.targetState = at != null }
    if (shown.currentState || shown.targetState) {
        // Below the row (above it when there's no room), so the name and picture stay in view,
        // with an arrow in the menu's edge pointing at their picture.
        var below by remember { mutableStateOf(true) }
        var arrowX by remember { mutableStateOf(0f) }
        var width by remember { mutableStateOf(1f) }
        val origin = TransformOrigin(arrowX / width, if (below) 0f else 1f)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val position = remember(density) {
            BesideRow(with(density) { PICTURE_CENTRE.roundToPx() }, with(density) { ARROW_INSET.roundToPx() }) { b, x, w -> below = b; arrowX = x; width = w }
        }
        Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(
                visibleState = shown,
                enter = scaleIn(spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f, transformOrigin = origin) +
                    fadeIn(tween(110)),
                exit = scaleOut(tween(130), targetScale = 0.85f, transformOrigin = origin) + fadeOut(tween(130)),
            ) {
                Surface(
                    shape = remember(below, arrowX) { Bubble(below, arrowX) },
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 10.dp,
                    modifier = Modifier.widthIn(min = 232.dp, max = 300.dp),
                ) {
                    Column(Modifier.padding(top = if (below) ARROW_HEIGHT + 6.dp else 6.dp, bottom = if (below) 6.dp else ARROW_HEIGHT + 6.dp)) {
                        val why = cantAskBecause(info.name, loc, now)
                        MenuItem(
                            "Ask for their location",
                            AppIcons.Refresh,
                            note = why,
                            enabled = why == null && circle.confirmed,
                        ) { onDismiss(); haptics.confirm(); actions.requestLocation(circleId, pubkey, info.name) }
                        MenuItem("Rename", AppIcons.Edit) { onDismiss(); renaming = true }
                        if (loc != null) MenuItem("Directions", AppIcons.Directions) { onDismiss(); directions = true }
                        if (circle.adminPubkey == me && pubkey != me) {
                            MenuItem("Remove from ${circle.name}", AppIcons.Delete, color = MaterialTheme.colorScheme.error) { onDismiss(); confirmRemove = true }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        TextSheet("Rename", "Name shown on your phone", contact?.label ?: published, "Save", { renaming = false }) {
            actions.setLabel(pubkey, it); renaming = false
        }
    }
    if (directions && loc != null) DirectionsSheet(info.name, loc) { directions = false }
    if (confirmRemove) {
        ConfirmSheet(
            title = "Remove ${info.name}?",
            text = "They'll stop seeing ${circle.name}, and the circle gets a new key so they can't read anything new.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { confirmRemove = false; gate.require { haptics.confirm(); actions.removeMember(circleId, pubkey) } },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun MenuItem(
    title: String,
    icon: ImageVector,
    note: String? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        Modifier.clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Where a person's picture is across their row, and the arrow's size. The popup's anchor is the
 * row's content, inside its 24 dp gutter, so a 48 dp picture's middle is 24 dp in.
 */
private val PICTURE_CENTRE = 24.dp
private val ARROW_INSET = 30.dp
private val ARROW_HEIGHT = 9.dp
private val ARROW_WIDTH = 20.dp

/**
 * Puts the menu just below the row it belongs to, or just above it when there's no room below,
 * with its arrow over the person's picture, kept on screen. Tells [onPlaced] which way it went,
 * where the arrow is across the menu and how wide the menu is (pixels), so the arrow is drawn there
 * and the menu grows from it.
 */
private class BesideRow(
    private val pictureX: Int,
    private val arrowInset: Int,
    private val onPlaced: (below: Boolean, arrowX: Float, width: Float) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = (windowSize.width * 0.03f).toInt().coerceAtLeast(16)
        val pointAt = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left + pictureX else anchorBounds.right - pictureX
        val below = anchorBounds.bottom + popupContentSize.height + margin <= windowSize.height
        val x = (pointAt - arrowInset).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = if (below) anchorBounds.bottom else anchorBounds.top - popupContentSize.height
        onPlaced(below, (pointAt - x).toFloat().coerceIn(0f, popupContentSize.width.toFloat()), popupContentSize.width.toFloat().coerceAtLeast(1f))
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/**
 * The menu's outline: a rounded card with an arrow in its top edge ([below]) or bottom edge,
 * [arrowX] pixels across, as one shape so the shadow goes round both.
 */
private class Bubble(private val below: Boolean, private val arrowX: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): androidx.compose.ui.graphics.Outline {
        val h = with(density) { ARROW_HEIGHT.toPx() }
        val w = with(density) { ARROW_WIDTH.toPx() }
        val r = with(density) { 18.dp.toPx() }
        val top = if (below) h else 0f
        val bottom = if (below) size.height else size.height - h
        val card = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, top, size.width, bottom, androidx.compose.ui.geometry.CornerRadius(r)))
        }
        val x = arrowX.coerceIn(r + w / 2, (size.width - r - w / 2).coerceAtLeast(r + w / 2))
        val arrow = androidx.compose.ui.graphics.Path().apply {
            if (below) { moveTo(x - w / 2, top + 1); lineTo(x, 0f); lineTo(x + w / 2, top + 1) } else { moveTo(x - w / 2, bottom - 1); lineTo(x, size.height); lineTo(x + w / 2, bottom - 1) }
            close()
        }
        val both = androidx.compose.ui.graphics.Path()
        both.op(card, arrow, androidx.compose.ui.graphics.PathOperation.Union)
        return androidx.compose.ui.graphics.Outline.Generic(both)
    }
}

/** Room the list of a stack needs above its faces: its title and padding, and each person's row (dp). */
internal const val GROUP_LIST_BASE_DP = 64f
internal const val GROUP_LIST_ROW_DP = 60f

/** Rows shown at once; more scroll, with the last one fading out to show there are more. */
internal const val GROUP_LIST_MAX_ROWS = 4

/** Space between the arrow's tip and the top face, so the arrow never covers a face. */
internal val GROUP_ARROW_GAP = 6.dp

/**
 * The people in a stack of faces on the map (#146), popping up from the stack like the long-press
 * menu: above the faces with its arrow just over the top one (below the faces, past their pointer,
 * when there's no room above). It grows out of the arrow's tip. Tap someone to zoom to them, their
 * info button to open their profile, or hold them for the quick menu. [faces] is the area the
 * stack's faces cover in this popup's parent (the map). Tapping anywhere else closes it.
 */
@Composable
internal fun GroupBubble(
    state: AppState,
    me: String,
    ids: List<String>,
    faces: androidx.compose.ui.unit.DpRect?,
    now: Long,
    location: (String) -> app.opaq.core.model.MemberLocation?,
    name: (String) -> String,
    ring: (String) -> Format.Ring,
    onPerson: (String) -> Unit,
    onProfile: (String) -> Unit,
    personMenu: @Composable (String, Offset?, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val open = faces != null
    // Kept while it shrinks away after closing.
    var last by remember { mutableStateOf(faces) }
    var lastIds by remember { mutableStateOf(ids) }
    if (faces != null) { last = faces; lastIds = ids }
    val area = last ?: return
    val people = lastIds
    // Grows from the arrow's tip once the popup knows its size (so the tip is known), and shrinks back.
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(open, placed) {
        if (open && placed) grow.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
        else if (!open) grow.animateTo(0f, tween(140))
    }
    if (!open && grow.value == 0f && !grow.isRunning) {
        if (placed) placed = false
        return
    }
    var below by remember { mutableStateOf(false) }
    var arrowX by remember { mutableStateOf(0f) }
    var width by remember { mutableStateOf(1f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val position = remember(density, area) {
        with(density) {
            AtFaces(
                IntOffset(((area.left + area.right) / 2).roundToPx(), (area.top - GROUP_ARROW_GAP).roundToPx()),
                // Below: past the pointer under the faces.
                IntOffset(((area.left + area.right) / 2).roundToPx(), (area.bottom + 14.dp).roundToPx()),
            ) { b, x, w -> below = b; arrowX = x; width = w; if (w > 1f) placed = true }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            shape = remember(below, arrowX) { Bubble(below, arrowX) },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .graphicsLayer {
                    val g = grow.value
                    transformOrigin = TransformOrigin((arrowX / width).coerceIn(0f, 1f), if (below) 0f else 1f)
                    scaleX = 0.55f + 0.45f * g
                    scaleY = 0.55f + 0.45f * g
                    alpha = (g * 2.5f).coerceIn(0f, 1f)
                }
                .widthIn(min = 264.dp, max = 320.dp),
        ) {
            Column(Modifier.padding(top = if (below) ARROW_HEIGHT + 8.dp else 8.dp, bottom = if (below) 8.dp else ARROW_HEIGHT + 8.dp)) {
                Text(
                    "${people.size} people here",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
                )
                // Four rows at once; more scroll, and the fourth fades out while there's more below.
                val scroll = rememberScrollState()
                androidx.compose.foundation.layout.Box {
                    Column(Modifier.heightIn(max = (GROUP_LIST_ROW_DP * GROUP_LIST_MAX_ROWS).dp).verticalScroll(scroll)) {
                        people.forEach { id ->
                            val info = avatarFor(state, id, me, name(id))
                            val loc = location(id)
                            val offline = id != me && Format.isOffline(loc, now)
                            var menuAt by remember { mutableStateOf<Offset?>(null) }
                            Row(
                                Modifier.personPress(enabled = id != me, onClick = { onPerson(id) }, onLongPress = { menuAt = it })
                                    .heightIn(min = GROUP_LIST_ROW_DP.dp)
                                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (id != me) personMenu(id, menuAt) { menuAt = null }
                                Avatar(info, size = 40.dp, dimmed = offline, ring = ring(id))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (id == me) "You" else info.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    StatusLine(Format.status(loc, now), Format.battery(loc, now))
                                }
                                androidx.compose.material3.IconButton(onClick = { onProfile(id) }) {
                                    Icon(AppIcons.Info, contentDescription = "Profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (scroll.canScrollForward) {
                        val ground = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                        androidx.compose.foundation.layout.Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
                                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground.copy(alpha = 0.85f), ground))),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Puts a stack's list with its arrow's tip at [above] (just over the top face), or at [below]
 * (past the pointer under the faces) when there's no room above, kept on screen. Points are in
 * the parent's pixels.
 */
private class AtFaces(
    private val above: IntOffset,
    private val below: IntOffset,
    private val onPlaced: (below: Boolean, arrowX: Float, width: Float) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = (windowSize.width * 0.03f).toInt().coerceAtLeast(16)
        val tipAbove = anchorBounds.top + above.y
        val goBelow = tipAbove - popupContentSize.height < margin * 3
        val tip = if (goBelow) IntOffset(anchorBounds.left + below.x, anchorBounds.top + below.y) else IntOffset(anchorBounds.left + above.x, tipAbove)
        val x = (tip.x - popupContentSize.width / 2).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = if (goBelow) tip.y else tip.y - popupContentSize.height
        onPlaced(goBelow, (tip.x - x).toFloat().coerceIn(0f, popupContentSize.width.toFloat()), popupContentSize.width.toFloat().coerceAtLeast(1f))
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}
