package app.quietmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.quietmap.core.PinResult
import app.quietmap.core.model.AppState
import app.quietmap.core.model.MembershipChange
import app.quietmap.core.model.Precision
import app.quietmap.core.model.SecurityEventType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.quietmap.core.Recovery

/**
 * Shown instead of everything else when saved data exists but can't be read. Nothing is shared
 * and nothing is overwritten until one of these choices succeeds.
 */
@Composable
fun RecoveryScreen(recovery: Recovery, actions: Actions, onRestore: () -> Unit) {
    var confirmFresh by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Quietmap can't open your saved data", style = MaterialTheme.typography.headlineSmall)
        Text(recovery.reason + ".", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Your data is still on this phone and hasn't been changed. Location sharing is off until this is fixed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (recovery.retryable) {
            PrimaryButton(if (working) "Trying…" else "Try again", {
                working = true
                actions.retryLoad { working = false }
            }, Modifier.fillMaxWidth(), enabled = !working)
        }
        TonalButton("Restore from a backup", onRestore, Modifier.fillMaxWidth())
        TextButton(onClick = { confirmFresh = true }, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
        Caption(
            if (recovery.retryable) "If the phone was just restarted, unlock it and try again."
            else "If you have a backup file, restoring it brings back your circles.",
        )
    }
    if (confirmFresh) {
        ConfirmSheet(
            title = "Start over?",
            text = "Quietmap gets a new device code and forgets your circles. Your old data stays on this phone, unused. To rejoin a circle you'll need a new invite.",
            confirm = "Start over",
            destructive = true,
            onConfirm = { confirmFresh = false; actions.startFresh() },
            onDismiss = { confirmFresh = false },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// PIN
// -------------------------------------------------------------------------------------------------

/**
 * Runs sensitive actions only after the PIN has been entered, when one is set. Opening the app and
 * seeing locations never go through here.
 */
class PinGate(private val actions: Actions) {
    var pending by mutableStateOf<(() -> Unit)?>(null)
        internal set

    fun require(action: () -> Unit) {
        if (actions.isAuthorized()) action() else pending = action
    }
}

val LocalPinGate = staticCompositionLocalOf<PinGate> { error("No PinGate provided") }

/** A field for 4 to 8 digits, hidden as they're typed. */
@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Asks for the PIN when [PinGate.require] is waiting, then runs the action. */
@Composable
fun PinPrompt(gate: PinGate, actions: Actions) {
    val action = gate.pending ?: return
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ConfirmSheet(
        title = "Enter your PIN",
        text = null,
        confirm = "Continue",
        onConfirm = {
            if (pin.length < 4) return@ConfirmSheet
            actions.unlock(pin) { result ->
                pin = ""
                when (result) {
                    PinResult.Ok -> {
                        gate.pending = null
                        action()
                    }
                    is PinResult.Wrong -> error = if (result.attemptsLeft > 0) "That's not your PIN. ${result.attemptsLeft} more tries before a short wait." else "That's not your PIN."
                    is PinResult.Locked -> error = "Too many wrong tries. Wait ${(result.until - epochSeconds()).coerceAtLeast(1)} seconds and try again."
                }
            }
        },
        onDismiss = { gate.pending = null },
    ) {
        PinField(pin, { pin = it; error = null }, "PIN")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Two fields for choosing a PIN. [onValid] gets the PIN once both match and it's 4 to 8 digits, else null. */
@Composable
fun NewPinFields(onValid: (String?) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PinField(pin, { pin = it; onValid(if (it.length >= 4 && it == again) it else null) }, "New PIN (4 to 8 digits)")
        PinField(again, { again = it; onValid(if (pin.length >= 4 && pin == it) pin else null) }, "New PIN again")
        if (again.isNotEmpty() && again != pin) Caption("The PINs don't match.")
    }
}

/** Chooses (or changes) the PIN in a bottom sheet. */
@Composable
fun SetPinSheet(actions: Actions, title: String, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    ConfirmSheet(
        title = title,
        text = "Quietmap asks for it before backups, invites, approving or removing people, relay changes and security settings. Never to open the app or see where people are.",
        confirm = "Save PIN",
        onConfirm = { chosen?.let { actions.setPin(it) { onDismiss() } } },
        onDismiss = onDismiss,
    ) {
        NewPinFields { chosen = it }
    }
}

// -------------------------------------------------------------------------------------------------
// Security log
// -------------------------------------------------------------------------------------------------

@Composable
fun SecurityLogScreen(state: AppState, onBack: () -> Unit) {
    val now = rememberNow()
    ScreenScaffold("Security log", onBack) {
        Caption(
            "Things that matter for your security, recorded on this phone. Someone who resets or wipes this phone's data can also erase this list.",
            Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (state.securityLog.isEmpty()) {
            SettingsRow("Nothing yet", "Backups, PIN changes and identity resets show up here", AppIcons.Shield)
        }
        state.securityLog.asReversed().forEach { e ->
            val title = when (e.type) {
                SecurityEventType.BACKUP_EXPORTED -> "Backup file made"
                SecurityEventType.BACKUP_RESTORED -> "Restored from a backup"
                SecurityEventType.PIN_SET -> "PIN set"
                SecurityEventType.PIN_CHANGED -> "PIN changed"
                SecurityEventType.PIN_REMOVED -> "PIN removed"
                SecurityEventType.PIN_LOCKED -> "Wrong PIN entered too many times"
                SecurityEventType.IDENTITY_RESET -> "New device code"
            }
            SettingsRow(title, listOfNotNull(Format.ago(now - e.at), e.device?.let { "on $it" }).joinToString(" "), AppIcons.Shield)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Circle details: membership history
// -------------------------------------------------------------------------------------------------

@Composable
fun CircleInfoScreen(state: AppState, me: String, circleId: String, onBack: () -> Unit) {
    val circle = state.circles.firstOrNull { it.id == circleId }
    if (circle == null) {
        ScreenScaffold("", onBack) { Text("You're no longer in this circle.", modifier = Modifier.padding(horizontal = 24.dp)) }
        return
    }
    val now = rememberNow()
    fun nameOf(pubkey: String?, fallback: String = "Someone"): String =
        pubkey?.let { p -> circle.members.firstOrNull { it.pubkey == p }?.let { avatarFor(state, p, me, it.name).name } } ?: fallback
    ScreenScaffold(circle.name, onBack) {
        SectionHeader("Who can see you")
        circle.members.filter { it.pubkey != me }.forEach { m ->
            SettingsRow(avatarFor(state, m.pubkey, me, m.name).name, if (m.pubkey == circle.adminPubkey) "Admin" else null, AppIcons.Person)
        }
        if (circle.members.size <= 1) SettingsRow("Nobody else yet", null, AppIcons.Person)

        SectionHeader("History")
        if (circle.history.isEmpty()) SettingsRow("No changes recorded yet", "Joins and departures show up here", AppIcons.Info)
        circle.history.asReversed().forEach { e ->
            val who = if (e.pubkey == me) "You" else e.name.ifBlank { "Someone" }
            val title = when (e.change) {
                MembershipChange.CREATED -> "$who created ${circle.name}"
                MembershipChange.JOINED -> "$who joined"
                MembershipChange.LEFT -> "$who left or was removed"
                MembershipChange.NEW_ADMIN -> "$who became the admin"
            }
            val by = e.by?.takeIf { it != e.pubkey && e.change != MembershipChange.CREATED }?.let { if (it == me) "by you" else "by ${nameOf(it)}" }
            SettingsRow(title, listOfNotNull(Format.ago(now - e.at), by).joinToString(" · "), AppIcons.Circles)
        }
        Caption("Kept on this phone only.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

// -------------------------------------------------------------------------------------------------
// Monthly sharing reminder
// -------------------------------------------------------------------------------------------------

/** "You're still sharing your location": who sees it, how precisely, keep or stop. Swiping it away decides nothing. */
@Composable
fun ConsentSheet(state: AppState, me: String, actions: Actions, onDismiss: () -> Unit) {
    val people = state.circles.flatMap { c -> c.members.map { it.pubkey } }.filter { it != me }.toSet().size
    ConfirmSheet(
        title = "You're sharing your location",
        text = "With $people ${if (people == 1) "person" else "people"}. Check this is still what you want.",
        confirm = "Keep sharing",
        onConfirm = { actions.confirmConsent(); onDismiss() },
        dismiss = "Decide later",
        onDismiss = onDismiss,
    ) {
        state.circles.forEach { c ->
            val others = c.members.count { it.pubkey != me }
            Text(
                "${c.name}: $others ${if (others == 1) "person" else "people"} · ${precisionLabel(c.precision)}",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(4.dp))
        DangerButton("Stop sharing with everyone", { actions.setSharing(false); onDismiss() })
    }
}

fun precisionLabel(p: Precision): String = when (p) {
    Precision.EXACT -> "exact location"
    Precision.NEIGHBORHOOD -> "neighbourhood only"
    Precision.CITY -> "city only"
}
