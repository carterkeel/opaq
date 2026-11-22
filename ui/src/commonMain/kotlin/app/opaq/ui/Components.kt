package app.opaq.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -------------------------------------------------------------------------------------------------
// Screen chrome
// -------------------------------------------------------------------------------------------------

/** A plain top bar: back arrow, title, optional actions. No divider, like Signal. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(4.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** A full screen with a top bar and a scrolling column of content. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack, actions)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            content = content,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Settings-style rows
// -------------------------------------------------------------------------------------------------

/** Small bold section title above a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** A Signal-style list row: optional leading icon, title, optional subtitle, optional trailing content. */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (titleColor == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else titleColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * Someone's status with their battery beside it, as a small battery shape (like the one in a
 * phone's status bar) so the number reads as battery at a glance.
 */
@Composable
fun StatusLine(text: String, battery: Int?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (battery != null) {
            Spacer(Modifier.width(8.dp))
            BatteryBadge(battery)
        }
    }
}

/**
 * A battery with the percentage inside it: dark on a light screen, white on a dark one, and red
 * at 20% or below.
 */
@Composable
fun BatteryBadge(percent: Int, modifier: Modifier = Modifier) {
    val low = percent <= 20
    val body = if (low) Palette.Red else MaterialTheme.colorScheme.onSurface
    val digits = if (low) Color.White else MaterialTheme.colorScheme.surface
    Row(modifier.clearAndSetSemantics { contentDescription = "Battery $percent%" }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.heightIn(min = 15.dp).widthIn(min = 25.dp).clip(RoundedCornerShape(4.5.dp)).background(body).padding(horizontal = 3.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                percent.coerceIn(0, 100).toString(),
                color = digits,
                style = TextStyle(
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(1.dp))
        Box(Modifier.width(2.dp).height(5.dp).clip(RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)).background(body))
    }
}

/** How choices in a list move between rows: a spring that settles without bouncing. */
fun <T> choiceSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * One row in a list where a single choice is picked. The picked row's icon and title turn the
 * accent colour and a check grows in at the end, while the row you left fades back and its check
 * shrinks away, so you see the choice move.
 */
@Composable
fun ChoiceRow(title: String, subtitle: String? = null, icon: ImageVector? = null, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        choiceSpring(),
        label = "choice colour",
    )
    SettingsRow(title, subtitle, icon, titleColor = tint, onClick = onClick) { SelectedCheck(selected) }
}

/** The check at the end of a picked row. It keeps its space either way, so the text never shifts. */
@Composable
fun SelectedCheck(selected: Boolean) {
    val shown by animateFloatAsState(if (selected) 1f else 0f, choiceSpring(), label = "check")
    Box(Modifier.size(24.dp)) {
        if (shown > 0.01f) {
            Icon(
                AppIcons.Check,
                contentDescription = if (selected) "Selected" else null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = shown; scaleY = shown; alpha = shown },
            )
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val change: (Boolean) -> Unit = { haptics.toggle(it); onChange(it) }
    SettingsRow(title, subtitle, icon, onClick = { change(!checked) }) {
        Switch(checked = checked, onCheckedChange = change)
    }
}

/**
 * Light haptic feedback for the moments worth confirming: a toggle, a scanned invite, matching
 * codes, approving or removing someone, being let into a circle. No sounds, and nothing for routine
 * taps or location updates. It follows the phone's own haptics setting.
 */
class Haptics(private val feedback: HapticFeedback) {
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

// -------------------------------------------------------------------------------------------------
// Buttons, cards, text
// -------------------------------------------------------------------------------------------------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** A large icon in a tinted circle, at the top of a screen or step. */
@Composable
fun BigIcon(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
fun Mono(text: String, size: Int = 13, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, weight: FontWeight = FontWeight.Normal) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color, fontWeight = weight)
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

// -------------------------------------------------------------------------------------------------
// Icons not in the core material set (drawn as 24dp strokes)
// -------------------------------------------------------------------------------------------------

object AppIcons {
    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()

    val QrCode: ImageVector = stroke("QrCode") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(16f, 14f); moveTo(19f, 14f); lineTo(20f, 14f); lineTo(20f, 16f)
        moveTo(14f, 17f); lineTo(14f, 20f); lineTo(17f, 20f); moveTo(17f, 17f); lineTo(20f, 17f); lineTo(20f, 20f)
    }

    val ScanQr: ImageVector = stroke("ScanQr") {
        moveTo(3f, 8f); lineTo(3f, 4f); lineTo(7f, 4f)
        moveTo(17f, 4f); lineTo(21f, 4f); lineTo(21f, 8f)
        moveTo(21f, 16f); lineTo(21f, 20f); lineTo(17f, 20f)
        moveTo(7f, 20f); lineTo(3f, 20f); lineTo(3f, 16f)
        moveTo(7f, 12f); lineTo(17f, 12f)
    }

    /** A turn arrow in a road sign: directions. */
    val Directions: ImageVector = stroke("Directions") {
        moveTo(12f, 2.5f); lineTo(21.5f, 12f); lineTo(12f, 21.5f); lineTo(2.5f, 12f); close()
        moveTo(9f, 15f); lineTo(9f, 12f); quadTo(9f, 10f, 11f, 10f); lineTo(15f, 10f)
        moveTo(13f, 8f); lineTo(15f, 10f); lineTo(13f, 12f)
    }

    /** A bullseye: an exact location. */
    val Target: ImageVector = stroke("Target") {
        moveTo(20f, 12f); arcTo(8f, 8f, 0f, true, true, 4f, 12f); arcTo(8f, 8f, 0f, true, true, 20f, 12f); close()
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f); close()
        moveTo(12f, 12f); lineTo(12.01f, 12f)
    }

    /** Two houses: roughly which part of town. */
    val Houses: ImageVector = stroke("Houses") {
        moveTo(2.5f, 20f); lineTo(21.5f, 20f)
        moveTo(3.5f, 20f); lineTo(3.5f, 12f); lineTo(8f, 8f); lineTo(12.5f, 12f); lineTo(12.5f, 20f)
        moveTo(14.5f, 10f); lineTo(16.5f, 8.2f); lineTo(20.5f, 11.8f); lineTo(20.5f, 20f)
        moveTo(8f, 20f); lineTo(8f, 16f)
    }

    /** Tall buildings: roughly which city. */
    val City: ImageVector = stroke("City") {
        moveTo(2.5f, 21f); lineTo(21.5f, 21f)
        moveTo(4f, 21f); lineTo(4f, 11f); lineTo(9f, 11f)
        moveTo(9f, 21f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 21f)
        moveTo(15f, 13f); lineTo(20f, 13f); lineTo(20f, 21f)
        moveTo(12f, 8f); lineTo(12.01f, 8f); moveTo(12f, 12f); lineTo(12.01f, 12f); moveTo(12f, 16f); lineTo(12.01f, 16f)
    }

    /** A winding path between two spots: your recent path. */
    val Route: ImageVector = stroke("Route") {
        moveTo(8f, 18f); arcTo(2f, 2f, 0f, true, true, 4f, 18f); arcTo(2f, 2f, 0f, true, true, 8f, 18f); close()
        moveTo(20f, 6f); arcTo(2f, 2f, 0f, true, true, 16f, 6f); arcTo(2f, 2f, 0f, true, true, 20f, 6f); close()
        moveTo(8f, 18f); lineTo(13f, 18f); quadTo(16f, 18f, 16f, 15f); quadTo(16f, 12f, 12f, 12f); quadTo(8f, 12f, 8f, 9f); quadTo(8f, 6f, 11f, 6f); lineTo(16f, 6f)
    }

    /** An arrow coming down into an area: someone arrives. */
    val Arrive: ImageVector = stroke("Arrive") {
        moveTo(19f, 18.5f); arcTo(7f, 2.5f, 0f, true, true, 5f, 18.5f); arcTo(7f, 2.5f, 0f, true, true, 19f, 18.5f); close()
        moveTo(12f, 3f); lineTo(12f, 15f)
        moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f)
    }

    /** An arrow going up out of an area: someone leaves. */
    val Depart: ImageVector = stroke("Depart") {
        moveTo(19f, 18.5f); arcTo(7f, 2.5f, 0f, true, true, 5f, 18.5f); arcTo(7f, 2.5f, 0f, true, true, 19f, 18.5f); close()
        moveTo(12f, 15f); lineTo(12f, 3f)
        moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
    }

    /** A folded paper map: any map app. */
    val Map: ImageVector = stroke("Map") {
        moveTo(3f, 6f); lineTo(9f, 4f); lineTo(15f, 6f); lineTo(21f, 4f); lineTo(21f, 18f); lineTo(15f, 20f); lineTo(9f, 18f); lineTo(3f, 20f); close()
        moveTo(9f, 4f); lineTo(9f, 18f); moveTo(15f, 6f); lineTo(15f, 20f)
    }

    /** A heartbeat line: diagnostic logs. */
    val Pulse: ImageVector = stroke("Pulse") {
        moveTo(2f, 12f); lineTo(6f, 12f); lineTo(9f, 5f); lineTo(14f, 19f); lineTo(17f, 12f); lineTo(22f, 12f)
    }

    /** A page with a folded corner: terms of use. */
    val Document: ImageVector = stroke("Document") {
        moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close()
        moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
        moveTo(9f, 13f); lineTo(16f, 13f); moveTo(9f, 17f); lineTo(16f, 17f)
    }

    /** An eye, crossed out: privacy policy. */
    val EyeOff: ImageVector = stroke("EyeOff") {
        moveTo(2f, 12f); quadTo(12f, 2.5f, 22f, 12f); quadTo(12f, 21.5f, 2f, 12f); close()
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f); close()
        moveTo(4f, 4f); lineTo(20f, 20f)
    }

    /** A key: your identity key, or a circle's key. */
    val Key: ImageVector = stroke("Key") {
        moveTo(11f, 12f); arcTo(3.5f, 3.5f, 0f, true, true, 4f, 12f); arcTo(3.5f, 3.5f, 0f, true, true, 11f, 12f); close()
        moveTo(11f, 12f); lineTo(21f, 12f)
        moveTo(17f, 12f); lineTo(17f, 15f); moveTo(20f, 12f); lineTo(20f, 14.5f)
    }

    /** Lines of a list: a log. */
    val Log: ImageVector = stroke("Log") {
        moveTo(4f, 6f); lineTo(4.01f, 6f); moveTo(8f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(4.01f, 12f); moveTo(8f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(4.01f, 18f); moveTo(8f, 18f); lineTo(20f, 18f)
    }

    /** An arrow up out of a tray: making a backup. */
    val Upload: ImageVector = stroke("Upload") {
        moveTo(4f, 15f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
        moveTo(12f, 16f); lineTo(12f, 4f)
        moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
    }

    /** An arrow down into a tray: restoring a backup. */
    val Download: ImageVector = stroke("Download") {
        moveTo(4f, 15f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
        moveTo(12f, 4f); lineTo(12f, 16f)
        moveTo(7.5f, 11.5f); lineTo(12f, 16f); lineTo(16.5f, 11.5f)
    }

    /** A storage box: backups. */
    val Archive: ImageVector = stroke("Archive") {
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 8f); lineTo(3f, 8f); close()
        moveTo(5f, 8f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 8f)
        moveTo(10f, 12f); lineTo(14f, 12f)
    }

    /** A leaf: the phone's battery saver. */
    val Leaf: ImageVector = stroke("Leaf") {
        moveTo(5f, 19f); curveTo(4f, 10f, 10f, 4.5f, 20f, 4f); curveTo(20.5f, 14f, 14f, 20f, 5f, 19f); close()
        moveTo(5f, 19f); lineTo(13f, 11f)
    }

    /** A clock: all the time, in the background. */
    val Clock: ImageVector = stroke("Clock") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }

    /** A pill shape with a dot: the location indicator at the top of the screen. */
    val Pill: ImageVector = stroke("Pill") {
        moveTo(8f, 8f); lineTo(16f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 16f); lineTo(8f, 16f); arcTo(4f, 4f, 0f, false, true, 8f, 8f); close()
        moveTo(9f, 12f); lineTo(9.01f, 12f)
    }

    /** A person with a plus: someone joined, or make them a manager. */
    val PersonAdd: ImageVector = stroke("PersonAdd") {
        moveTo(12.5f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 5.5f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 12.5f, 8f); close()
        moveTo(2f, 20f); curveTo(2f, 16f, 5f, 14f, 9f, 14f); curveTo(13f, 14f, 16f, 16f, 16f, 20f)
        moveTo(19f, 8f); lineTo(19f, 14f); moveTo(16f, 11f); lineTo(22f, 11f)
    }

    /** A person with a minus: someone left, or no longer a manager. */
    val PersonRemove: ImageVector = stroke("PersonRemove") {
        moveTo(12.5f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 5.5f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 12.5f, 8f); close()
        moveTo(2f, 20f); curveTo(2f, 16f, 5f, 14f, 9f, 14f); curveTo(13f, 14f, 16f, 16f, 16f, 20f)
        moveTo(16f, 11f); lineTo(22f, 11f)
    }

    /** A flag on a pole: report a problem. */
    val Flag: ImageVector = stroke("Flag") {
        moveTo(5f, 21f); lineTo(5f, 3.5f)
        moveTo(5f, 4f); quadTo(8.5f, 2.5f, 12f, 4f); quadTo(15.5f, 5.5f, 19f, 4f); lineTo(19f, 13f); quadTo(15.5f, 14.5f, 12f, 13f); quadTo(8.5f, 11.5f, 5f, 13f)
    }

    /** A globe: everyone from every circle (#147). */
    val Globe: ImageVector = stroke("Globe") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f); quadTo(16f, 7.5f, 16f, 12f); quadTo(16f, 16.5f, 12f, 21f)
        moveTo(12f, 3f); quadTo(8f, 7.5f, 8f, 12f); quadTo(8f, 16.5f, 12f, 21f)
    }

    /** A bell: notifications. */
    val Bell: ImageVector = stroke("Bell") {
        moveTo(6f, 16f); lineTo(6f, 11f); quadTo(6f, 5f, 12f, 5f); quadTo(18f, 5f, 18f, 11f); lineTo(18f, 16f); lineTo(19.5f, 17.5f); lineTo(4.5f, 17.5f); close()
        moveTo(10f, 20.5f); quadTo(12f, 22f, 14f, 20.5f)
        moveTo(12f, 3f); lineTo(12f, 5f)
    }

    /** A spot with a ring round it: an area on the map. */
    val Radius: ImageVector = stroke("Radius") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(13.5f, 12f); arcTo(1.5f, 1.5f, 0f, true, true, 10.5f, 12f); arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, 12f); close()
        moveTo(13.5f, 12f); lineTo(21f, 12f)
    }

    val Camera: ImageVector = stroke("Camera") {
        moveTo(4f, 8f); lineTo(7.5f, 8f); lineTo(9f, 5.5f); lineTo(15f, 5.5f); lineTo(16.5f, 8f); lineTo(20f, 8f)
        lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(15.5f, 13f); arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 13f); arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 13f); close()
    }

    val MyLocation: ImageVector = stroke("MyLocation") {
        moveTo(18f, 12f); arcTo(6f, 6f, 0f, true, true, 6f, 12f); arcTo(6f, 6f, 0f, true, true, 18f, 12f); close()
        moveTo(12f, 2f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(22f, 12f)
    }

    val Battery: ImageVector = stroke("Battery") {
        moveTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 21f); lineTo(8f, 21f); close()
        moveTo(10.5f, 2f); lineTo(13.5f, 2f)
    }

    val Relay: ImageVector = stroke("Relay") {
        moveTo(5f, 12f); arcTo(7f, 7f, 0f, false, true, 19f, 12f)
        moveTo(8.5f, 12f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 12f)
        moveTo(12f, 12f); lineTo(12f, 20f)
    }

    val Circles: ImageVector = stroke("Circles") {
        moveTo(13f, 9f); arcTo(4f, 4f, 0f, true, true, 5f, 9f); arcTo(4f, 4f, 0f, true, true, 13f, 9f); close()
        moveTo(2f, 20f); curveTo(2f, 16f, 5f, 15f, 9f, 15f); curveTo(13f, 15f, 16f, 16f, 16f, 20f)
        moveTo(15f, 5.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15f, 12.5f)
        moveTo(18f, 15.5f); curveTo(20.5f, 16.3f, 22f, 17.5f, 22f, 20f)
    }

    // Standard UI icons, drawn here so the app doesn't depend on the frozen material-icons library.

    val ArrowBack: ImageVector = stroke("ArrowBack") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }

    val Add: ImageVector = stroke("Add") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Check: ImageVector = stroke("Check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }

    val Close: ImageVector = stroke("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val ChevronDown: ImageVector = stroke("ChevronDown") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }

    val ChevronLeft: ImageVector = stroke("ChevronLeft") {
        moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f)
    }

    val ChevronRight: ImageVector = stroke("ChevronRight") {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }

    /** A clock with an arrow going back round it. */
    val History: ImageVector = stroke("History") {
        moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, true, 6.3f, 17.7f)
        moveTo(4f, 12f); lineTo(2f, 10f); moveTo(4f, 12f); lineTo(6f, 10f)
        moveTo(12f, 8f); lineTo(12f, 12f); lineTo(15f, 14f)
    }

    val Delete: ImageVector = stroke("Delete") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
        moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
    }

    val Edit: ImageVector = stroke("Edit") {
        moveTo(4f, 20f); lineTo(8f, 19f); lineTo(19f, 8f); lineTo(16f, 5f); lineTo(5f, 16f); close()
        moveTo(14f, 7f); lineTo(17f, 10f)
    }

    val Face: ImageVector = stroke("Face") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(8.5f, 14.5f); curveTo(10f, 16.5f, 14f, 16.5f, 15.5f, 14.5f)
        moveTo(9f, 9.5f); lineTo(9f, 10f); moveTo(15f, 9.5f); lineTo(15f, 10f)
    }

    /** A crescent moon. */
    val Moon: ImageVector = stroke("Moon") {
        moveTo(20f, 14.5f); arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f); arcTo(7f, 7f, 0f, false, false, 20f, 14.5f); close()
    }

    val Info: ImageVector = stroke("Info") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.5f); lineTo(12f, 8f)
    }

    val LocationPin: ImageVector = stroke("LocationPin") {
        moveTo(12f, 21f); curveTo(12f, 21f, 5f, 14.5f, 5f, 9.5f); arcTo(7f, 7f, 0f, false, true, 19f, 9.5f); curveTo(19f, 14.5f, 12f, 21f, 12f, 21f); close()
        moveTo(14.5f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 9.5f); close()
    }

    /** An app tile with the A's arch: Settings → Appearance → App icon. */
    val AppTile: ImageVector = stroke("AppTile") {
        moveTo(8f, 3f); lineTo(16f, 3f); arcTo(5f, 5f, 0f, false, true, 21f, 8f); lineTo(21f, 16f)
        arcTo(5f, 5f, 0f, false, true, 16f, 21f); lineTo(8f, 21f); arcTo(5f, 5f, 0f, false, true, 3f, 16f)
        lineTo(3f, 8f); arcTo(5f, 5f, 0f, false, true, 8f, 3f); close()
        moveTo(9f, 17f); lineTo(9f, 11.5f); arcTo(3f, 3f, 0f, false, true, 15f, 11.5f); lineTo(15f, 17f)
    }

    val Lock: ImageVector = stroke("Lock") {
        moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        moveTo(8f, 11f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); lineTo(16f, 11f)
    }

    val Person: ImageVector = stroke("Person") {
        moveTo(16f, 8f); arcTo(4f, 4f, 0f, true, true, 8f, 8f); arcTo(4f, 4f, 0f, true, true, 16f, 8f); close()
        moveTo(4f, 20f); curveTo(4f, 16f, 7.5f, 14f, 12f, 14f); curveTo(16.5f, 14f, 20f, 16f, 20f, 20f)
    }

    /** Two people: the button that brings back the people panel in landscape. */
    val People: ImageVector = stroke("People") {
        moveTo(13f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 6f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 13f, 8f); close()
        moveTo(3f, 20f); curveTo(3f, 16.5f, 5.8f, 14.5f, 9.5f, 14.5f); curveTo(13.2f, 14.5f, 16f, 16.5f, 16f, 20f)
        moveTo(15.5f, 4.8f); arcTo(3.3f, 3.3f, 0f, false, true, 15.5f, 11.2f)
        moveTo(18f, 14.8f); curveTo(19.9f, 15.5f, 21f, 17.2f, 21f, 20f)
    }

    val Refresh: ImageVector = stroke("Refresh") {
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 16.9f, 7f)
        moveTo(17f, 3f); lineTo(17f, 7f); lineTo(13f, 7f)
    }

    val Settings: ImageVector = stroke("Settings") {
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f); close()
        moveTo(12f, 2.5f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(18.7f, 18.7f)
        moveTo(5.3f, 18.7f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(18.7f, 5.3f)
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 5f, 12f); arcTo(7f, 7f, 0f, true, true, 19f, 12f); close()
    }

    val Share: ImageVector = stroke("Share") {
        moveTo(12f, 15f); lineTo(12f, 3f)
        moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
        moveTo(5f, 12f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 12f)
    }

    val Star: ImageVector = stroke("Star") {
        moveTo(12f, 3f); lineTo(14.8f, 8.8f); lineTo(21f, 9.6f); lineTo(16.5f, 14f); lineTo(17.6f, 20.2f)
        lineTo(12f, 17.2f); lineTo(6.4f, 20.2f); lineTo(7.5f, 14f); lineTo(3f, 9.6f); lineTo(9.2f, 8.8f); close()
    }

    val Leave: ImageVector = stroke("Leave") {
        moveTo(10f, 4f); lineTo(5f, 4f); lineTo(5f, 20f); lineTo(10f, 20f)
        moveTo(10f, 12f); lineTo(20f, 12f)
        moveTo(16f, 8f); lineTo(20f, 12f); lineTo(16f, 16f)
    }

    /** A circle, half filled with a smaller half circle: Signal's appearance icon, in outline. */
    val Appearance: ImageVector = stroke("Appearance") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(12f, 3f); lineTo(12f, 21f)
        moveTo(12f, 7f); arcTo(5f, 5f, 0f, false, true, 12f, 17f)
    }

    val Shield: ImageVector = stroke("Shield") {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); curveTo(19f, 16f, 16f, 19.5f, 12f, 21f)
        curveTo(8f, 19.5f, 5f, 16f, 5f, 11f); lineTo(5f, 6f); close()
    }
}

// -------------------------------------------------------------------------------------------------
// Prompts that slide up from the bottom instead of popping in from the centre
// -------------------------------------------------------------------------------------------------

/**
 * A yes/no question as a bottom sheet. [destructive] colours the confirm button red.
 * [onDismiss] runs when the sheet is swiped away, closed with back or tapped outside; the
 * [dismiss] button runs [onSecondary] if given (a real answer, like "Deny"), else [onDismiss].
 */
/** A short explanation in a sheet, with one button to close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, text: String, onDismiss: () -> Unit, points: List<String> = emptyList()) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            points.forEach { point ->
                Row {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
                    Text(point, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.heightIn(min = 4.dp))
            PrimaryButton("OK", onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismiss: String = "Cancel",
    header: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
    onSecondary: (() -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Scrolls, so the buttons can be reached above the keyboard on small screens. Tapping the
        // title or text closes the keyboard: iOS number pads have no key for that.
        val focus = LocalFocusManager.current
        val closeKeyboard = Modifier.pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (header != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { header() }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = closeKeyboard)
            if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = closeKeyboard)
            body?.invoke(this)
            Spacer(Modifier.heightIn(min = 4.dp))
            if (destructive) DangerButton(confirm, onConfirm) else PrimaryButton(confirm, onConfirm, enabled = confirmEnabled)
            TextButton(onClick = onSecondary ?: onDismiss, modifier = Modifier.fillMaxWidth()) { Text(dismiss) }
        }
    }
}

/** Asks for one line of text in a bottom sheet. */
@Composable
fun TextSheet(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    ConfirmSheet(
        title = title,
        text = null,
        confirm = confirm,
        onConfirm = { if (text.isNotBlank()) onConfirm(text.trim()) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
