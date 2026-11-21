package app.opaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.opaq.core.PlaceAlerts
import app.opaq.core.model.AppState
import app.opaq.core.model.Place
import kotlin.math.exp
import kotlin.math.ln

/** What every alert area screen says about where alerts are worked out (#151). */
private const val PLACES_PRIVATE = "Only you can see your places. Alerts are worked out on this phone from updates it already gets, and aren't sent to anyone."

/** Settings → Place alerts: the alert areas on this phone (#151). */
@Composable
fun PlacesScreen(state: AppState, onBack: () -> Unit, onOpen: (placeId: String?) -> Unit) {
    ScreenScaffold("Place alerts", onBack) {
        Caption("Get a notification when someone in your circles arrives at or leaves a place, like home or school.", Gutter.padding(bottom = 8.dp))
        state.places.forEach { p ->
            SettingsRow(p.name, placeSummary(state, p), AppIcons.Radius, onClick = { onOpen(p.id) })
        }
        if (state.places.size < PlaceAlerts.MAX_PLACES) {
            SettingsRow("Add a place", icon = AppIcons.Add, titleColor = MaterialTheme.colorScheme.primary, onClick = { onOpen(null) })
        }
        Caption(
            "$PLACES_PRIVATE They arrive while Opaq is running and connected, usually within a minute.",
            Gutter.padding(top = 16.dp),
        )
    }
}

private fun placeSummary(state: AppState, p: Place): String {
    val who = p.circleId?.let { id -> state.circles.firstOrNull { it.id == id }?.name } ?: "All circles"
    val alerts = when {
        !p.enabled -> "Off"
        p.onArrive && p.onLeave -> "Arrivals and departures"
        p.onArrive -> "Arrivals"
        p.onLeave -> "Departures"
        else -> "No alerts"
    }
    return "${Format.radius(p.radius)} · $who · $alerts"
}

/** Slider position (0..1) for a radius, on a log scale from 100 ft to 3 miles. */
private fun sliderOf(radius: Double): Float =
    ((ln(radius) - ln(PlaceAlerts.MIN_RADIUS)) / (ln(PlaceAlerts.MAX_RADIUS) - ln(PlaceAlerts.MIN_RADIUS))).toFloat().coerceIn(0f, 1f)

private fun radiusOf(slider: Float): Double {
    val r = exp(ln(PlaceAlerts.MIN_RADIUS) + slider * (ln(PlaceAlerts.MAX_RADIUS) - ln(PlaceAlerts.MIN_RADIUS)))
    // Round to steps that read well: 5 m under 200 m, 25 m under 1 km, then 100 m.
    val step = when { r < 200 -> 5.0; r < 1000 -> 25.0; else -> 100.0 }
    return (kotlin.math.round(r / step) * step).coerceIn(PlaceAlerts.MIN_RADIUS, PlaceAlerts.MAX_RADIUS)
}

/**
 * Adding or changing one alert area (#151). The map stays put at the top while the settings
 * scroll under it, so moving the map never scrolls the screen.
 */
@Composable
fun PlaceScreen(state: AppState, me: String, placeId: String?, actions: Actions, onBack: () -> Unit) {
    val existing = state.places.firstOrNull { it.id == placeId }
    // A new place starts where you are, or where anyone in your circles is.
    val start = remember {
        existing?.let { it.lat to it.lon }
            ?: state.locations.values.firstNotNullOfOrNull { it[me] }?.let { it.lat to it.lon }
            ?: state.locations.values.flatMap { it.values }.firstOrNull()?.let { it.lat to it.lon }
            ?: (0.0 to 0.0)
    }
    var at by remember { mutableStateOf(start) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var radius by remember { mutableDoubleStateOf(existing?.radius ?: PlaceAlerts.DEFAULT_RADIUS) }
    var circleId by remember { mutableStateOf(existing?.circleId) }
    var onArrive by remember { mutableStateOf(existing?.onArrive ?: true) }
    var onLeave by remember { mutableStateOf(existing?.onLeave ?: false) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (existing == null) "New place" else "Edit place", onBack)
        Box(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))) {
            PlacePickerMap(at.first, at.second, radius, state.darkMap, onPick = { lat, lon -> at = lat to lon }, modifier = Modifier.fillMaxSize())
        }
        val scroll = rememberScrollState()
        Column(
            // Clipped, so fields scrolling up go under the map's edge rather than over the caption.
            Modifier.fillMaxWidth().weight(1f).clipToBounds().verticalScroll(scroll).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The hint scrolls away with the rest, fading as it goes.
            Caption(
                "Move the map and tap where the place should be.",
                Gutter.padding(bottom = 8.dp).graphicsLayer { alpha = (1f - scroll.value / 120f).coerceIn(0f, 1f) },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name") },
                placeholder = { Text("Home") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(14.dp),
                modifier = Gutter.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("Radius: ${Format.radius(radius)}", style = MaterialTheme.typography.bodyLarge, modifier = Gutter)
            Slider(value = sliderOf(radius), onValueChange = { radius = radiusOf(it) }, modifier = Gutter.fillMaxWidth())

            SectionHeader("Watch")
            SettingsRow("Everyone in all your circles", icon = AppIcons.People, onClick = { circleId = null }) {
                if (circleId == null) Icon(AppIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
            state.circles.forEach { c ->
                SettingsRow("Only ${c.name}", icon = AppIcons.Circles, onClick = { circleId = c.id }) {
                    if (circleId == c.id) Icon(AppIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }

            SectionHeader("Tell me when someone")
            SwitchRow("Arrives", null, onArrive, AppIcons.Bell) { onArrive = it }
            SwitchRow("Leaves", null, onLeave, AppIcons.Bell) { onLeave = it }
            if (existing != null) SwitchRow("Alerts for this place", if (enabled) "On" else "Off, and hidden from the map", enabled) { enabled = it }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                if (existing == null) "Add place" else "Save",
                onClick = {
                    saving = true
                    actions.savePlace(
                        Place(existing?.id.orEmpty(), name, at.first, at.second, radius, circleId, onArrive, onLeave, enabled),
                        done = onBack,
                        failed = { saving = false },
                    )
                },
                modifier = Gutter.fillMaxWidth(),
                enabled = !saving && name.isNotBlank() && (onArrive || onLeave),
            )
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Gutter) {
                    Text("Delete place", color = MaterialTheme.colorScheme.error)
                }
            }
            Caption(PLACES_PRIVATE, Gutter.padding(top = 8.dp))
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmSheet(
            title = "Delete ${existing.name}?",
            text = "You'll stop getting alerts for it.",
            confirm = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; actions.deletePlace(existing.id, done = onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}
