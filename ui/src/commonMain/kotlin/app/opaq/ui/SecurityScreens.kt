package app.opaq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opaq.core.PinResult
import app.opaq.core.Recovery
import app.opaq.core.model.AppState
import app.opaq.core.model.MembershipChange
import app.opaq.core.model.Precision
import app.opaq.core.model.SecurityEventType

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
        Text("Opaq can't open your saved data", style = MaterialTheme.typography.headlineSmall)
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
            text = "Opaq gets a new device code and forgets your circles. Your old data stays on this phone, unused. To rejoin a circle you'll need a new invite.",
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

    /**
     * Always asks, even if the PIN was entered a minute ago: for backups, which copy this phone's
     * identity. (With no PIN set it runs straight away; backups refuse that case themselves.)
     */
    fun requireNow(action: () -> Unit) {
        if (actions.hasPin) pending = action else action()
    }
}

val LocalPinGate = staticCompositionLocalOf<PinGate> { error("No PinGate provided") }

/**
 * Runs [action] (opening "Set a PIN") once the phone's own unlock confirms it's the owner, or
 * straight away on a phone with no screen lock. Without this, someone who picks up an unlocked phone
 * with no Opaq PIN could choose one and then make a backup. Not needed during first-run setup, before
 * there's anything to copy.
 */
fun PlatformBridge.confirmOwnerThen(action: () -> Unit) =
    confirmPhoneOwner("Confirm it's you to set a PIN for Opaq") { result ->
        if (result == BiometricResult.OK || result == BiometricResult.UNAVAILABLE) action()
    }

/** A field for 4 to 8 digits, hidden as they're typed. */
@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // The keyboard's ✓ key goes to the next field, or submits from the last one: on small
        // screens the button underneath is hidden by the keyboard.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun lockedMessage(until: Long) = "Too many wrong tries. Wait ${(until - epochSeconds()).coerceAtLeast(1)} seconds and try again."

/**
 * Asks for the PIN when [PinGate.require] is waiting, then runs the action. With face or fingerprint
 * unlock on, asks for that first; the PIN can always be typed instead.
 */
@Composable
fun PinPrompt(gate: PinGate, state: AppState, actions: Actions) {
    val action = gate.pending ?: return
    val platform = LocalPlatform.current
    val biometricName = platform.biometricName.takeIf { state.pin?.biometrics == true }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // One check at a time: taps and the keyboard's Done are ignored until the answer is back.
    var checking by remember { mutableStateOf(false) }
    val tryBiometrics: () -> Unit = tryBiometrics@{
        val name = biometricName ?: return@tryBiometrics
        if (checking) return@tryBiometrics
        checking = true
        platform.checkBiometrics("Confirm it's you") { result ->
            when (result) {
                BiometricResult.OK -> actions.unlockWithBiometrics { unlocked ->
                    checking = false
                    when (unlocked) {
                        PinResult.Ok -> {
                            gate.pending = null
                            action()
                        }
                        is PinResult.Locked -> error = lockedMessage(unlocked.until)
                        is PinResult.Wrong -> Unit
                    }
                }
                BiometricResult.CHANGED -> {
                    checking = false
                    actions.setBiometrics(false)
                    error = "A face or fingerprint was added to this phone or removed, so $name is off for Opaq until you turn it on again. Enter your PIN."
                }
                else -> checking = false
            }
        }
    }
    LaunchedEffect(action) { tryBiometrics() }
    val submit: () -> Unit = submit@{
        if (pin.length < 4 || checking) return@submit
        checking = true
        actions.unlock(pin, onFailed = { checking = false }) { result ->
            checking = false
            pin = ""
            when (result) {
                PinResult.Ok -> {
                    gate.pending = null
                    action()
                }
                is PinResult.Wrong -> error = if (result.attemptsLeft > 0) "That's not your PIN. ${result.attemptsLeft} more tries before a short wait." else "That's not your PIN."
                is PinResult.Locked -> error = lockedMessage(result.until)
            }
        }
    }
    ConfirmSheet(
        title = "Enter your PIN",
        text = null,
        confirm = "Continue",
        onConfirm = submit,
        onDismiss = { gate.pending = null },
        confirmEnabled = !checking,
    ) {
        PinField(pin, { pin = it; error = null }, "PIN", onDone = submit)
        if (biometricName != null) TextButton(onClick = tryBiometrics, enabled = !checking) { Text("Use $biometricName") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Two fields for choosing a PIN. [onValid] gets the PIN once both match and it's 4 to 8 digits, else null. */
@Composable
fun NewPinFields(onDone: (() -> Unit)? = null, onValid: (String?) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PinField(pin, { pin = it; onValid(if (it.length >= 4 && it == again) it else null) }, "New PIN (4 to 8 digits)")
        PinField(again, {
            again = it
            val valid = pin.length >= 4 && pin == it
            onValid(if (valid) pin else null)
            // Both match: close the keyboard so the button underneath shows. iOS number pads have
            // no key to submit or close them.
            if (valid) focus.clearFocus()
        }, "New PIN again", onDone = onDone ?: {})
        if (again.isNotEmpty() && again != pin) Caption("The PINs don't match.")
    }
}

/**
 * "Use Face ID" (or Touch ID, or fingerprint): shown once a PIN is set, on phones with face or
 * fingerprint unlock. Turning it on needs the PIN, then a face or finger, so it's known to work.
 */
@Composable
fun BiometricSwitch(state: AppState, actions: Actions) {
    val platform = LocalPlatform.current
    val gate = LocalPinGate.current
    val lock = state.pin ?: return
    val name = platform.biometricName ?: return
    SwitchRow("Use $name", "Instead of typing your PIN. The PIN always works too.", lock.biometrics, AppIcons.Lock) { on ->
        if (on) {
            gate.require {
                platform.enrollBiometrics("Turn on $name for Opaq") { result ->
                    when (result) {
                        BiometricResult.OK -> actions.setBiometrics(true) { platform.showMessage("$name turned on") }
                        BiometricResult.UNAVAILABLE -> platform.showMessage("$name isn't set up on this phone")
                        else -> Unit
                    }
                }
            }
        } else {
            platform.forgetBiometrics()
            actions.setBiometrics(false)
        }
    }
}

/** Chooses (or changes) the PIN in a bottom sheet. */
@Composable
fun SetPinSheet(actions: Actions, title: String, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // One save at a time: further taps and the keyboard's Done are ignored until it's done.
    val save: () -> Unit = save@{
        val pin = chosen ?: return@save
        if (saving) return@save
        saving = true
        actions.setPin(pin, onFailed = { saving = false }, onDone = { saving = false; onDismiss() })
    }
    ConfirmSheet(
        title = title,
        text = "Opaq asks for it before backups, invites, approving or removing people, relay changes and security settings. Never to open the app or see where people are.",
        confirm = "Save PIN",
        onConfirm = save,
        onDismiss = onDismiss,
        confirmEnabled = chosen != null && !saving,
    ) {
        NewPinFields(onDone = save) { chosen = it }
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
                SecurityEventType.BIOMETRICS_ON -> "Face or fingerprint unlock turned on"
                SecurityEventType.BIOMETRICS_OFF -> "Face or fingerprint unlock turned off"
            }
            SettingsRow(title, listOfNotNull(Format.ago(now - e.at), e.device?.let { "on $it" }).joinToString(" "), AppIcons.Shield)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Circle details: membership history
// -------------------------------------------------------------------------------------------------

@Composable
fun CircleInfoScreen(state: AppState, me: String, circleId: String, actions: Actions, onBack: () -> Unit) {
    val circle = state.circles.firstOrNull { it.id == circleId }
    if (circle == null) {
        ScreenScaffold("", onBack) { Text("You're no longer in this circle.", modifier = Modifier.padding(horizontal = 24.dp)) }
        return
    }
    val now = rememberNow()
    fun nameOf(pubkey: String?, fallback: String = "Someone"): String =
        pubkey?.let { p -> circle.members.firstOrNull { it.pubkey == p }?.let { avatarFor(state, p, me, it.name).name } } ?: fallback
    val gate = LocalPinGate.current
    ScreenScaffold(circle.name, onBack) {
        if (circle.confirmed) {
            SwitchRow(
                "Share my location",
                when {
                    !circle.paused -> "${circle.name} sees where you are"
                    circle.pausedUntil > 0 -> "Paused until ${formatClock(circle.pausedUntil)}. You still see everyone here"
                    else -> "Paused. You still see everyone here"
                },
                !circle.paused,
                AppIcons.LocationPin,
            ) { on -> actions.setCirclePaused(circle.id, !on) }
        }
        SectionHeader("What ${circle.name} sees")
        listOf(
            Precision.EXACT to "Where you are, to within a few metres",
            Precision.NEIGHBORHOOD to "Roughly which part of town (about 2 km)",
            Precision.CITY to "Only roughly which city (about 20 km)",
        ).forEach { (p, detail) ->
            val selected = circle.precision == p
            ChoiceRow(
                title = when (p) {
                    Precision.EXACT -> "Exact location"
                    Precision.NEIGHBORHOOD -> "Neighbourhood"
                    Precision.CITY -> "City"
                },
                subtitle = detail,
                icon = when (p) {
                    Precision.EXACT -> AppIcons.Target
                    Precision.NEIGHBORHOOD -> AppIcons.Houses
                    Precision.CITY -> AppIcons.City
                },
                selected = selected,
                onClick = {
                    // Sharing less needs nothing; sharing more precisely needs the PIN.
                    if (p.ordinal < circle.precision.ordinal) gate.require { actions.setCirclePrecision(circle.id, p) }
                    else if (!selected) actions.setCirclePrecision(circle.id, p)
                },
            )
        }
        // Approximate locations never carry a path, so the switch folds away (and comes back) as
        // the choice above changes, rather than vanishing in one frame.
        AnimatedVisibility(
            visible = circle.precision == Precision.EXACT,
            enter = expandVertically(choiceSpring(), expandFrom = Alignment.Top) + fadeIn(choiceSpring()),
            exit = shrinkVertically(choiceSpring(), shrinkTowards = Alignment.Top) + fadeOut(choiceSpring()),
        ) {
            SwitchRow("Recent path", "Send your last few points with each update", circle.shareTrail, AppIcons.Route) { on ->
                if (on) gate.require { actions.setCircleTrail(circle.id, true) } else actions.setCircleTrail(circle.id, false)
            }
        }
        Caption(
            "Approximate locations snap to a fixed grid, so many updates can't be averaged to find you. Speed and your path aren't sent with them.",
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        SectionHeader("Who can see you")
        circle.members.filter { it.pubkey != me }.forEach { m ->
            SettingsRow(avatarFor(state, m.pubkey, me, m.name).name, if (m.pubkey == circle.adminPubkey) "Admin" else null, if (m.pubkey == circle.adminPubkey) AppIcons.Star else AppIcons.Person)
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
            SettingsRow(
                title,
                listOfNotNull(Format.ago(now - e.at), by).joinToString(" · "),
                when (e.change) {
                    MembershipChange.CREATED -> AppIcons.Circles
                    MembershipChange.JOINED -> AppIcons.PersonAdd
                    MembershipChange.LEFT -> AppIcons.PersonRemove
                    MembershipChange.NEW_ADMIN -> AppIcons.Star
                },
            )
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
    // Only circles you've started sharing with: a circle joined but not yet confirmed sees nothing.
    val sharingWith = state.circles.filter { it.confirmed }
    val people = sharingWith.flatMap { c -> c.members.map { it.pubkey } }.filter { it != me }.toSet().size
    ConfirmSheet(
        title = "You're sharing your location",
        text = "With $people ${if (people == 1) "person" else "people"}. Check this is still what you want.",
        confirm = "Keep sharing",
        onConfirm = { actions.confirmConsent(); onDismiss() },
        dismiss = "Decide later",
        onDismiss = onDismiss,
    ) {
        sharingWith.forEach { c ->
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
