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
    var lastAt by remember { mutableStateOf(Offset.Zero) }
    if (at != null) lastAt = at
    LaunchedEffect(at != null) { shown.targetState = at != null }
    if (shown.currentState || shown.targetState) {
        var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
        val position = remember(lastAt) { NearPress(lastAt) { origin = it } }
        Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(
                visibleState = shown,
                enter = scaleIn(spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f, transformOrigin = origin) +
                    fadeIn(tween(110)),
                exit = scaleOut(tween(130), targetScale = 0.85f, transformOrigin = origin) + fadeOut(tween(130)),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 10.dp,
                    modifier = Modifier.widthIn(min = 232.dp, max = 300.dp),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
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
 * Puts the menu's corner at the finger ([at], inside the row), below it, or above it when there's
 * no room below, and keeps it on screen. Tells [onOrigin] which corner that is, so it grows from
 * the finger.
 */
private class NearPress(private val at: Offset, private val onOrigin: (TransformOrigin) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = (windowSize.width * 0.03f).toInt().coerceAtLeast(16)
        val fx = anchorBounds.left + at.x.toInt()
        val fy = anchorBounds.top + at.y.toInt()
        val below = fy + popupContentSize.height + margin <= windowSize.height
        val left = fx + popupContentSize.width + margin <= windowSize.width
        val x = (if (left) fx else fx - popupContentSize.width).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = (if (below) fy else fy - popupContentSize.height).coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        onOrigin(TransformOrigin(if (left) 0f else 1f, if (below) 0f else 1f))
        return IntOffset(x, y)
    }
}
