package app.opaq.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opaq.core.HistoryPoint
import app.opaq.core.HistorySegment
import app.opaq.core.HistorySegments
import app.opaq.core.LocationHistory
import app.opaq.core.model.Activity
import app.opaq.core.model.AppState

/** Colour of an activity on the history map and in the list. */
internal fun activityColorHex(a: Activity): String = when (a) {
    Activity.DRIVING, Activity.CYCLING -> "#2C6BED"
    Activity.WALKING -> "#0B8A3E"
    Activity.STILL, Activity.UNKNOWN -> "#8A8D96"
}

private fun activityColor(a: Activity): Color = Color(("FF" + activityColorHex(a).removePrefix("#")).toLong(16))

/**
 * Where someone has been, one day at a time (#58): a map of the path coloured by what they were
 * doing, and a list of stretches ("Driving · 8:02 – 8:25 AM · 6.1 mi"). Read from this phone only.
 */
@Composable
fun HistoryScreen(state: AppState, me: String, circleId: String, pubkey: String, actions: Actions, onBack: () -> Unit) {
    val member = state.circles.firstOrNull { it.id == circleId }?.members?.firstOrNull { it.pubkey == pubkey }
    val name = avatarFor(state, pubkey, me, member?.name ?: "").name
    val today = remember { localDayStart(epochSeconds()) }
    var day by remember { mutableLongStateOf(today) }
    val nextDay = localDayStart(day + 25 * 3600)
    val oldest = localDayStart(epochSeconds() - (state.historyDays - 1).coerceAtLeast(0) * 86_400L)
    var points by remember { mutableStateOf<List<HistoryPoint>?>(null) }
    // Reload when the day changes, history settings change, or a new update arrives today.
    val latest = state.locations[circleId]?.get(pubkey)?.time
    LaunchedEffect(day, state.historyDays, if (day == today) latest else null) {
        val requested = day
        actions.history(pubkey, requested, nextDay) { loaded ->
            // Loads run on the app scope and can finish out of order: drop one for a day no longer shown.
            if (requested == day) points = loaded
        }
    }
    val segments = remember(points) { HistorySegments.build(points.orEmpty()) }
    // The selected stretch, by index. Cleared on another day, and when a reload changes how many
    // stretches there are (they can split or merge, so the index may mean a different one).
    var selected by remember(day, segments.size) { mutableStateOf<Int?>(null) }

    val dayRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { day = localDayStart(day - 3600) }, enabled = day > oldest) {
                Icon(AppIcons.ChevronLeft, contentDescription = "Previous day")
            }
            Text(
                when (day) {
                    today -> "Today"
                    localDayStart(today - 3600) -> "Yesterday"
                    else -> formatDay(day)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { day = nextDay }, enabled = day < today) {
                Icon(AppIcons.ChevronRight, contentDescription = "Next day")
            }
        }
    }
    val shown = points.orEmpty()
    val focus = selected?.let { segments.getOrNull(it)?.points }
    // Full screen (#184): the map fills the whole screen, with the day and a button back.
    var fullMap by rememberSaveable { mutableStateOf(false) }
    PlatformBackHandler(fullMap) { fullMap = false }
    val mapBox: @Composable (Modifier, Boolean) -> Unit = { modifier, full ->
        Box(modifier.clip(RoundedCornerShape(if (full) 0.dp else 20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (shown.isNotEmpty()) {
                key(state.darkMap) { HistoryMap(shown, segments, focus, Modifier.fillMaxSize(), darkMap = state.darkMap) }
                Text(
                    MAP_ATTRIBUTION,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 6.dp),
                )
                MapCornerButton(
                    if (full) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                    if (full) "Exit full screen" else "Full screen",
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                ) { fullMap = !full }
            }
            else Text(
                if (points == null) "" else "No history for this day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
    val list: @Composable () -> Unit = {
        segments.asReversed().forEachIndexed { reversedIndex, seg ->
            val index = segments.size - 1 - reversedIndex
            SegmentRow(seg, selected == index) { selected = if (selected == index) null else index }
        }
        Caption(
            "Kept on this phone for ${Format.historyDays(state.historyDays).lowercase()}, from the updates it receives. " +
                "Gaps are times this phone or theirs was offline. Change how long in Settings.",
            Gutter.then(Modifier.padding(top = 12.dp)),
        )
    }
    val off = state.historyDays == 0
    val offCard: @Composable () -> Unit = {
        InfoCard(Gutter) {
            Text("History is off", style = MaterialTheme.typography.titleSmall)
            Caption("Turn it on in Settings, under Privacy. It's kept only on this phone.")
        }
    }

    if (fullMap && !off && shown.isNotEmpty()) {
        // Full screen: the map edge to edge, with the day on a pill at the top so you can still
        // step between days, and the corner button (or Back) to come out again.
        Box(Modifier.fillMaxSize()) {
            mapBox(Modifier.fillMaxSize(), true)
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 2.dp) {
                    IconButton(onClick = { fullMap = false }) { Icon(AppIcons.ArrowBack, contentDescription = "Exit full screen") }
                }
                Spacer(Modifier.width(12.dp))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 2.dp, modifier = Modifier.weight(1f)) {
                    dayRow()
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (pubkey == me) "Your history" else "$name's history", onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val listWidth = sidePanelWidth(maxWidth, maxHeight)
            if (usesSidePanel(maxWidth, maxHeight) && !off) {
                // Sideways: the map fills the left, the day and its stretches scroll on the right,
                // like the people panel on the home screen.
                Row(Modifier.fillMaxSize()) {
                    mapBox(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 8.dp, bottom = 16.dp), false)
                    Column(Modifier.width(listWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        dayRow()
                        list()
                    }
                }
            } else if (off) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    dayRow()
                    offCard()
                }
            } else {
                // The day and the map stay put; the stretches scroll under the map and fade out
                // as they go beneath it.
                val mapHeight = (maxHeight * 0.42f).coerceIn(200.dp, 320.dp)
                Column(Modifier.fillMaxSize()) {
                    dayRow()
                    mapBox(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp).fillMaxWidth().height(mapHeight), false)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp)) {
                            list()
                        }
                        val ground = MaterialTheme.colorScheme.background
                        Box(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
                                .background(Brush.verticalGradient(listOf(ground, ground.copy(alpha = 0.7f), ground.copy(alpha = 0f)))),
                        )
                    }
                }
            }
        }
    }
}

/** A small round button over a map's corner, like full screen on the history map. */
@Composable
private fun MapCornerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun SegmentRow(seg: HistorySegment, selected: Boolean, onClick: () -> Unit) {
    val moving = LocationHistory.moving(seg.activity)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A pause (#156) is a hollow grey ring: no location was shared for that stretch.
        if (seg.paused) {
            Box(Modifier.size(12.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
        } else {
            Box(Modifier.size(12.dp).background(activityColor(seg.activity), CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    seg.paused -> "Sharing paused"
                    moving && seg.meters >= 50 -> "${Format.activity(seg.activity)} · ${Format.distance(seg.meters)}"
                    else -> Format.activity(seg.activity)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            // A drive also says how fast it went: average, and top speed when known.
            val speeds = listOfNotNull(
                seg.averageSpeed?.let { "avg ${Format.speed(it)}" },
                seg.topSpeed?.let { "top ${Format.speed(it)}" },
            )
            Text(
                (listOf("${formatClock(seg.start)} – ${formatClock(seg.end)}", Format.duration(seg.end - seg.start)) + speeds).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Settings: how long this phone keeps location history. Keeping more needs the PIN. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDaysSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls when the phone is sideways and the list is taller than the screen.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Keep location history for", style = MaterialTheme.typography.titleLarge, modifier = Gutter)
            Caption(
                "Where people in your circles have been, kept only on this phone, so you can look back from their profile. " +
                    "Shorter deletes older days now.",
                Gutter.then(Modifier.padding(top = 4.dp, bottom = 8.dp)),
            )
            listOf(0, 1, 7, 30, LocationHistory.MAX_DAYS).forEach { days ->
                SettingsRow(
                    Format.historyDays(days),
                    if (days == LocationHistory.DEFAULT_DAYS) "Default" else null,
                    if (days == current) AppIcons.Check else null,
                    onClick = { onPick(days) },
                )
            }
        }
    }
}
