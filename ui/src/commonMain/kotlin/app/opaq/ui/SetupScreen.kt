package app.opaq.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opaq.core.model.AppState

private enum class SetupStep { Name, Photo, Pin, Circle, Location }

/**
 * First run, one thing per screen: your name, a profile photo (optional), a PIN (optional), a
 * circle to create or join, and location access. Then the app opens on the map.
 */
@Composable
fun SetupScreen(
    state: AppState,
    me: String,
    actions: Actions,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    onJoinLink: (String) -> Unit,
    onScan: () -> Unit,
    onRestore: () -> Unit,
    onCircleCreated: (String) -> Unit,
    backgroundSettings: List<SettingItem> = emptyList(),
) {
    val platform = LocalPlatform.current
    var step by remember { mutableStateOf(if (state.circles.isNotEmpty()) SetupStep.Location else SetupStep.Name) }
    var forward by remember { mutableStateOf(true) }
    fun go(to: SetupStep) { forward = to.ordinal > step.ordinal; step = to }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Back arrow and progress dots.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                androidx.compose.animation.AnimatedVisibility(step.ordinal in 1..3, enter = fadeIn(), exit = fadeOut()) {
                    IconButton(onClick = { go(SetupStep.entries[step.ordinal - 1]) }) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SetupStep.entries.forEach { s ->
                    val width by animateDpAsState(if (s == step) 22.dp else 8.dp, label = "dot")
                    val color by animateColorAsState(if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor")
                    Box(Modifier.height(8.dp).width(width).clip(RoundedCornerShape(50)).background(color))
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                // Same as moving between screens: both steps slide the full width together, no fade.
                val dir = if (forward) 1 else -1
                slideInHorizontally(screenSlideSpec) { it * dir }.togetherWith(slideOutHorizontally(screenSlideSpec) { -it * dir })
            },
            label = "setup",
            modifier = Modifier.weight(1f),
        ) { current ->
            // The location step lays itself out, with its buttons pinned to the bottom.
            if (current == SetupStep.Location) {
                LocationStep(
                    hasLocationPermission, onRequestLocation, backgroundSettings,
                    sharing = state.sharing,
                    onShare = { actions.setSharing(true) },
                    onDone = { actions.setSetupDone() },
                )
                return@AnimatedContent
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (current) {
                    SetupStep.Name -> NameStep(state, actions, onNext = { go(SetupStep.Photo) }, onRestore = onRestore)
                    SetupStep.Photo -> PhotoStep(state, me, actions, platform, onNext = { go(SetupStep.Pin) })
                    SetupStep.Pin -> PinStep(state, actions, onNext = { go(SetupStep.Circle) })
                    SetupStep.Circle -> CircleStep(
                        actions = actions,
                        onCreated = { id -> onCircleCreated(id); go(SetupStep.Location) },
                        onJoinLink = onJoinLink,
                        onScan = onScan,
                    )
                    SetupStep.Location -> Unit
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, text: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun ColumnScope.NameStep(state: AppState, actions: Actions, onNext: () -> Unit, onRestore: () -> Unit) {
    var name by remember { mutableStateOf(state.myName) }
    Spacer(Modifier.height(8.dp))
    BigIcon(AppIcons.Add)
    StepTitle("What should people call you?", "Your name is shown only to people in your circles. There's no account and no phone number.")
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        label = { Text("Your name") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton("Continue", { actions.setName(name); onNext() }, enabled = name.isNotBlank())
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onRestore) { Text("Restore from a backup") }
}

@Composable
private fun ColumnScope.PhotoStep(state: AppState, me: String, actions: Actions, platform: PlatformBridge, onNext: () -> Unit) {
    val info = avatarFor(state, me, me, state.myName)
    StepTitle("Add a profile photo", "Optional. Use a photo, or your initial on a color you pick.")
    Box(Modifier.clip(CircleShape).clickable { platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) } }) {
        Avatar(info, size = 120.dp)
    }
    TonalButton(if (state.myPhoto == null) "Choose a photo" else "Change photo", { platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) } }, icon = AppIcons.Camera)
    AvatarColorGrid(state.myName, me, state.myColor ?: AvatarColors.defaultIndex(me), photoSet = state.myPhoto != null, horizontalPadding = 0.dp) { actions.setAvatar(it, null) }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(if (state.myPhoto == null && state.myColor == null) "Skip" else "Continue", onNext)
}

@Composable
private fun ColumnScope.PinStep(state: AppState, actions: Actions, onNext: () -> Unit) {
    val platform = LocalPlatform.current
    var chosen by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    BigIcon(AppIcons.Lock)
    StepTitle(
        "Add a PIN?",
        "Optional. Opaq asks for it before backups, invites, approving or removing people, and security changes. Never to open the app or see where people are.",
    )
    if (state.pin != null) {
        Text("PIN set", style = MaterialTheme.typography.titleMedium)
        BiometricSwitch(state, actions)
        PrimaryButton("Continue", onNext)
        return
    }
    // Ignore further taps (and the keyboard's Done) while the PIN is being saved.
    val save: () -> Unit = save@{
        val pin = chosen ?: return@save
        if (saving) return@save
        saving = true
        // With face or fingerprint unlock on this phone, stay to offer it (the "PIN set" view above).
        actions.setPin(pin, onFailed = { saving = false }, onDone = { saving = false; if (platform.biometricName == null) onNext() })
    }
    NewPinFields(onDone = save) { chosen = it }
    PrimaryButton("Set PIN", save, enabled = chosen != null && !saving)
    TextButton(onClick = onNext) { Text("Not now") }
}

@Composable
private fun ColumnScope.CircleStep(actions: Actions, onCreated: (String) -> Unit, onJoinLink: (String) -> Unit, onScan: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) } // 0 = choose, 1 = create, 2 = join
    var circleName by remember { mutableStateOf("Family") }
    var link by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    StepTitle("Your circle", "A circle is a group that sees each other on the map, like your family.")
    ChoiceCard("Create a circle", "Start one and invite people", AppIcons.Circles, selected = mode == 1) { mode = 1 }
    AnimatedVisibility(mode == 1, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = circleName,
                onValueChange = { circleName = it.take(40) },
                label = { Text("Circle name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            // One tap makes one circle: ignore taps until this one has finished or failed.
            PrimaryButton("Create", {
                if (!creating) {
                    creating = true
                    actions.createCircle(circleName, onFailed = { creating = false }) { id -> creating = false; onCreated(id) }
                }
            }, enabled = circleName.isNotBlank() && !creating)
        }
    }
    ChoiceCard("Join a circle", "Use an invite QR code or link", AppIcons.ScanQr, selected = mode == 2) { mode = 2 }
    AnimatedVisibility(mode == 2, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalButton("Scan QR code", onScan, Modifier.fillMaxWidth(), icon = AppIcons.ScanQr)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Or paste an invite link") },
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(link.isNotBlank()) { PrimaryButton("Open invite", { onJoinLink(link) }) }
        }
    }
}

/**
 * The last step: everything sharing depends on, in one short checklist (#158) that fits without
 * scrolling: location access, the phone settings that keep it going in the background (#131; on
 * Android "Allow all the time" and no battery saver) and sharing itself, each ticked once done.
 * Optional switches (like iOS's blue pill) stay in Settings. The buttons stay at the bottom.
 */
@Composable
private fun LocationStep(
    hasPermission: Boolean,
    onRequest: () -> Unit,
    background: List<SettingItem>,
    sharing: Boolean,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    val needed = background.filter { it.checked == null }
    val done = hasPermission && sharing && needed.all { it.action == null }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.LocationPin, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                }
            }
            Text("Share your location", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Your circle sees where you are. It's encrypted on this phone before it goes anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    NeedRow(AppIcons.LocationPin, "Location access", if (hasPermission) "Allowed" else "Lets Opaq see where you are", hasPermission, "Allow", onRequest)
                    needed.forEach { item ->
                        NeedDivider()
                        NeedRow(
                            item.icon,
                            item.title,
                            when {
                                !hasPermission -> "After location access"
                                else -> item.brief ?: item.detail
                            },
                            ok = hasPermission && item.action == null,
                            action = item.action,
                            onAction = item.onAction,
                            enabled = hasPermission,
                        )
                    }
                    NeedDivider()
                    NeedRow(AppIcons.Share, "Sharing", if (sharing) "On. Pause any time from the map" else "Off, so nobody sees you", sharing, "Turn on", onShare)
                }
            }
            Caption(
                "${LocalPlatform.current.sharingIndicatorText}.",
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hasPermission) {
                PrimaryButton("Open the map", onDone)
                if (!done) {
                    Spacer(Modifier.height(10.dp))
                    Caption("You can finish these later in Settings → Sharing.", textAlign = TextAlign.Center)
                }
            } else {
                PrimaryButton("Allow location", onRequest)
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun NeedDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/** One thing sharing needs, as a row in the checklist: a tick once it's done, otherwise a small button. */
@Composable
private fun NeedRow(icon: ImageVector, title: String, detail: String, ok: Boolean, action: String?, onAction: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled || ok) 1f else 0.6f))
            Caption(detail)
        }
        AnimatedContent(ok, label = "need") { isOk ->
            if (isOk) {
                Box(Modifier.size(width = 40.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            } else if (action != null) {
                FilledTonalButton(
                    onClick = onAction,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) { Text(action, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "choice")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(2.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Caption(subtitle)
            }
        }
    }
}
