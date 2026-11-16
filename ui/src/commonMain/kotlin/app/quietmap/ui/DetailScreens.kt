package app.quietmap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.quietmap.core.model.AppState
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.protocol.Backups
import app.quietmap.core.protocol.DeviceCode
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.core.protocol.InviteData
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

internal val Gutter = Modifier.padding(horizontal = 24.dp)

/**
 * Big centered avatar with name and a line under it, as at the top of a Signal profile. The
 * camera badge sits outside the avatar's circle so it isn't cropped.
 */
@Composable
private fun ProfileHeader(info: AvatarInfo, title: String, subtitle: String?, onAvatarClick: (() -> Unit)? = null, editBadge: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(124.dp)) {
            Avatar(
                info,
                size = 112.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(if (onAvatarClick != null) Modifier.clip(CircleShape).clickable(onClick = onAvatarClick) else Modifier),
            )
            if (editBadge) {
                Surface(
                    onClick = onAvatarClick ?: {},
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(40.dp)
                        .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
                ) { Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Camera, contentDescription = "Change photo", modifier = Modifier.size(20.dp)) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Gutter)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Gutter)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Someone's profile
// -------------------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(state: AppState, me: String, circleId: String, pubkey: String, actions: Actions, onBack: () -> Unit, onHistory: () -> Unit = {}) {
    val circle = state.circles.firstOrNull { it.id == circleId }
    val member = circle?.members?.firstOrNull { it.pubkey == pubkey }
    if (circle == null || member == null) {
        ScreenScaffold("", onBack) { Text("This person is no longer in the circle.", modifier = Gutter) }
        return
    }
    val now = rememberNow()
    val isMe = pubkey == me
    val amAdmin = circle.adminPubkey == me
    val isManager = pubkey in circle.managers
    val info = avatarFor(state, pubkey, me, member.name)
    val contact = state.contacts[pubkey]
    val published = state.profiles[pubkey]?.name?.ifBlank { null } ?: member.name
    val loc = state.locations[circleId]?.get(pubkey)
    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmAdmin by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current

    ScreenScaffold("", onBack) {
        val subtitle = when {
            isMe -> "This is you"
            contact?.label != null && published.isNotBlank() && published != contact.label -> "Calls themself “$published”"
            else -> null
        }
        ProfileHeader(info, info.name, subtitle)

        SettingsRow(
            title = if (isMe && !state.sharing) "Sharing paused" else Format.status(loc, now),
            subtitle = loc?.let { "Accurate to about ${it.accuracy.toInt()} m" },
            icon = AppIcons.LocationPin,
        )
        SettingsRow(
            "Location history",
            if (state.historyDays == 0) "Off · turn it on in Settings" else "Past ${Format.historyDays(state.historyDays).lowercase()}, kept on this phone",
            AppIcons.History,
            onClick = onHistory,
        )
        when {
            pubkey == circle.adminPubkey -> SettingsRow("Admin of ${circle.name}", "Can invite, approve and remove people", AppIcons.Star)
            isManager -> SettingsRow("Manager in ${circle.name}", "Can invite and approve people", AppIcons.Person)
        }
        val waiting = circle.pendingAcks.firstOrNull { it.pubkey == pubkey && now - it.firstSentAt > 600 }
        if (waiting != null) {
            SettingsRow(
                "Hasn't received the circle's new key",
                "Their phone may be off or offline. Quietmap keeps resending it for two weeks.",
                AppIcons.Lock,
            )
        }
        if (!isMe) SettingsRow("Rename", "Only changes the name on your phone", AppIcons.Edit, onClick = { renaming = true })

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionHeader(if (isMe) "Your device code" else "Device code")
        Column(Gutter, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(DeviceCode.full(pubkey), size = 22, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
            Caption(
                if (isMe) "Show this to people you add so they can check it matches."
                else "Compare this with the code on their phone, in person or on a call. Verified people get a green check on their photo.",
            )
        }
        if (!isMe) {
            val verified = contact?.verified == true
            SettingsRow(
                title = if (verified) "Verified" else "Mark as verified",
                subtitle = if (verified) "Tap to clear" else "Do this once the codes match",
                icon = AppIcons.Shield,
                titleColor = if (verified) verifiedGreen() else MaterialTheme.colorScheme.onSurface,
                onClick = { actions.setVerified(pubkey, !verified) },
            ) {
                AnimatedVisibility(verified, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                    Icon(AppIcons.Check, contentDescription = null, tint = verifiedGreen())
                }
            }
        }

        if (!isMe && amAdmin) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader("Admin")
            SettingsRow(
                if (isManager) "Remove as manager" else "Make a manager",
                if (isManager) "They'll no longer be able to invite people" else "They can invite and approve people",
                AppIcons.Person,
                onClick = { gate.require { actions.setManager(circleId, pubkey, !isManager) } },
            )
            SettingsRow("Make admin", "Hand over ${circle.name}. You'll become a regular member.", AppIcons.Star, onClick = { confirmAdmin = true })
            SettingsRow("Remove from ${circle.name}", icon = AppIcons.Delete, titleColor = MaterialTheme.colorScheme.error, onClick = { confirmRemove = true })
        }
    }

    if (renaming) {
        TextSheet("Rename", "Name shown on your phone", contact?.label ?: published, "Save", { renaming = false }) {
            actions.setLabel(pubkey, it); renaming = false
        }
    }
    if (confirmRemove) {
        ConfirmSheet(
            title = "Remove ${info.name}?",
            text = "They'll stop seeing ${circle.name}, and the circle gets a new key so they can't read anything new.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { confirmRemove = false; gate.require { actions.removeMember(circleId, pubkey); onBack() } },
            onDismiss = { confirmRemove = false },
        )
    }
    if (confirmAdmin) {
        ConfirmSheet(
            title = "Make ${info.name} the admin?",
            text = "They'll control ${circle.name}: inviting, removing people and choosing managers. You can't undo this yourself.",
            confirm = "Make admin",
            onConfirm = { confirmAdmin = false; gate.require { actions.transferAdmin(circleId, pubkey) } },
            onDismiss = { confirmAdmin = false },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Your profile: name, photo or default avatar color
// -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(state: AppState, me: String, actions: Actions, onBack: () -> Unit) {
    val platform = LocalPlatform.current
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var photoSheet by remember { mutableStateOf(false) }
    val info = avatarFor(state, me, me, name)
    val selectedColor = state.myColor ?: AvatarColors.defaultIndex(me)

    ScreenScaffold("Your profile", onBack) {
        ProfileHeader(info.copy(name = name.ifBlank { "?" }), name.ifBlank { "Add your name" }, "Only people in your circles can see this", onAvatarClick = { photoSheet = true }, editBadge = true)

        Column(Gutter, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Your name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    AnimatedVisibility(name != state.myName && name.isNotBlank(), enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { actions.setName(name) }) { Icon(AppIcons.Check, contentDescription = "Save name", tint = MaterialTheme.colorScheme.primary) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionHeader("Photo")
        SettingsRow(
            title = if (state.myPhoto == null) "Choose a photo" else "Change photo",
            subtitle = "Cropped to a square and kept small",
            icon = AppIcons.Camera,
            onClick = { platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) } },
        )
        AnimatedVisibility(state.myPhoto != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SettingsRow("Use the default avatar instead", icon = AppIcons.Close, onClick = { actions.setAvatar(state.myColor, null) })
        }

        SectionHeader("Default avatar color")
        AvatarColorGrid(name, me, selectedColor, photoSet = state.myPhoto != null) { index -> actions.setAvatar(index, null) }
        Caption("Picking a color uses your initial instead of a photo.", Gutter.padding(top = 8.dp))

        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Gutter, verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Caption("Your name and photo are end-to-end encrypted and sent only to people in your circles. Relays can't see them.")
        }
    }

    if (photoSheet) {
        ModalBottomSheet(onDismissRequest = { photoSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SettingsRow("Choose a photo", icon = AppIcons.Camera, onClick = {
                photoSheet = false
                platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) }
            })
            if (state.myPhoto != null) {
                SettingsRow("Use the default avatar", icon = AppIcons.Close, onClick = { photoSheet = false; actions.setAvatar(state.myColor, null) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The twelve default avatar colors, with your initial in each. */
@Composable
fun AvatarColorGrid(name: String, me: String, selected: Int, photoSet: Boolean, horizontalPadding: Dp = 24.dp, onPick: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = horizontalPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarColors.all.indices.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { index ->
                    val isSelected = !photoSet && index == selected
                    val ring by animateDpAsState(if (isSelected) 3.dp else 0.dp, label = "ring")
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(ring, MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(if (isSelected) 5.dp else 0.dp)
                            .clip(CircleShape)
                            .clickable { onPick(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(AvatarInfo(me, name.ifBlank { "?" }, index, null, false), size = 44.dp, showVerified = false)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Invite: QR code and link (only for the admin and managers)
// -------------------------------------------------------------------------------------------------

@Composable
fun AddScreen(state: AppState, me: String, circleId: String, actions: Actions, onBack: () -> Unit) {
    val platform = LocalPlatform.current
    val circle = state.circles.firstOrNull { it.id == circleId }
    var link by remember { mutableStateOf<String?>(null) }
    var longer by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current
    val hours = if (longer) 24 else 1

    ScreenScaffold("Invite to ${circle?.name ?: "circle"}", onBack) {
        if (circle != null && (circle.adminPubkey == me || me in circle.managers)) {
            AnimatedContent(
                targetState = link,
                transitionSpec = { (fadeIn() + slideInVertically { it / 6 }).togetherWith(fadeOut()) },
                label = "invite",
            ) { current ->
                Column(Gutter, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (current == null) {
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(AppIcons.QrCode, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
                        }
                        Text(
                            "Create a QR code and link for someone to join ${circle.name}. They work once, and you approve whoever uses them. Scanning in person is safest: someone who joins from a link will need to hear your device code from you.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        SwitchRow("Valid for 24 hours", "Off: the invite expires in 1 hour", longer, AppIcons.Info) { longer = it }
                        PrimaryButton("Create invite", { gate.require { actions.createInvite(circle.id, hours) { link = it } } })
                    } else {
                        Text("Have them scan this with Quietmap", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // QR codes need dark modules on a light background, in both themes.
                        Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
                            Image(
                                painter = rememberQrCodePainter(current),
                                contentDescription = "Invite QR code",
                                modifier = Modifier.padding(20.dp).size(232.dp),
                            )
                        }
                        Caption("On their phone: Join a circle → Scan QR code. Works once · expires in ${if (hours == 1) "1 hour" else "24 hours"}.")
                        InfoCard {
                            Caption("Your device code")
                            Mono(DeviceCode.full(me), size = 17, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
                            Caption("If you send the link instead, read this out to them on a call. Quietmap asks them to type it.")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TonalButton("Share link", { platform.shareText("Join my circle \"${circle.name}\" on Quietmap: ${InviteLinks.webLink(current)}", "Share invite link") }, Modifier.weight(1f), icon = AppIcons.Share)
                            TonalButton("Copy", { platform.copyToClipboard("Quietmap invite", InviteLinks.webLink(current)) }, Modifier.weight(1f))
                        }
                        Caption("Tip: send links in an end-to-end encrypted chat. The app that carries a link can read it.")
                    }
                }
            }
        } else if (circle != null) {
            val admin = circle.members.firstOrNull { it.pubkey == circle.adminPubkey }
            InfoCard(Gutter) {
                Text("Only ${admin?.let { avatarFor(state, it.pubkey, me, it.name).name } ?: "the circle admin"} and managers can invite people to ${circle.name}.")
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Join: scan or paste, then consent
// -------------------------------------------------------------------------------------------------

/** Where you go to join a circle: scan an invite QR code or paste a link. */
@Composable
fun JoinOptionsScreen(onBack: () -> Unit, onScan: () -> Unit, onJoinLink: (String) -> Unit) {
    var paste by remember { mutableStateOf("") }
    ScreenScaffold("Join a circle", onBack) {
        Column(Gutter, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(AppIcons.ScanQr, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
            }
            Text(
                "Ask someone in the circle to open it in Quietmap and tap Invite. Then scan their QR code, or paste the link they send you.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Scan QR code", onScan)
            OutlinedTextField(
                value = paste,
                onValueChange = { paste = it },
                label = { Text("Or paste an invite link") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(paste.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TonalButton("Open invite", { onJoinLink(paste) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun JoinScreen(state: AppState, invite: InviteData?, scanned: Boolean, actions: Actions, onDone: () -> Unit) {
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var code by remember { mutableStateOf("") }
    var keepRelays by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current
    ScreenScaffold("Join a circle", onDone) {
        val now = epochSeconds()
        when {
            invite == null -> Text("That isn't a valid Quietmap invite, or it was changed after it was made. Ask for a new link or QR code.", modifier = Gutter)
            invite.e < now -> Text("This invite has expired. Ask ${invite.n.ifBlank { "them" }} for a new one.", modifier = Gutter)
            invite.k == actions.me -> Text("This is your own invite. Show it to the person you want to add.", modifier = Gutter)
            state.circles.any { it.id == invite.c } -> Text("You're already in ${invite.cn}.", modifier = Gutter)
            else -> {
                val inviter = invite.n.ifBlank { "the person who invited you" }
                val alreadyVerified = state.contacts[invite.k]?.verified == true
                val verified = scanned || alreadyVerified || actions.deviceCodeMatches(invite.k, code)
                val newRelays = invite.r.mapNotNull { app.quietmap.core.nostr.RelayUrls.clean(it) }.filter { it !in state.relays }.take(4)
                ProfileHeader(
                    AvatarInfo(invite.k, invite.n.ifBlank { "?" }, null, null, false),
                    "Join ${invite.cn}?",
                    "${invite.n.ifBlank { "Someone" }} invited you. Once they approve, you'll see who's in ${invite.cn} and choose when to start sharing.",
                )
                Column(Gutter, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InfoCard {
                        Caption("${invite.n.ifBlank { "Inviter" }}'s device code")
                        Mono(DeviceCode.full(invite.k), size = 18, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
                        when {
                            scanned -> Caption("Verified: you scanned this from their screen.")
                            alreadyVerified -> Caption("Verified earlier.")
                            else -> Caption("A link can be swapped on the way. Call $inviter and ask them to read out the device code on their phone, then type it below.")
                        }
                    }
                    if (!scanned && !alreadyVerified) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.take(24) },
                            label = { Text("Device code they read out") },
                            singleLine = true,
                            isError = code.filter { it.isLetterOrDigit() }.length >= 16 && !verified,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (code.filter { it.isLetterOrDigit() }.length >= 16 && !verified) {
                            Text("That doesn't match this invite. Don't join: the link may have been changed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (newRelays.isNotEmpty()) {
                        InfoCard {
                            Caption("This invite also uses these relays while you join:")
                            newRelays.forEach { Mono(it.removePrefix("wss://"), size = 14, color = MaterialTheme.colorScheme.onSurface) }
                        }
                        SwitchRow("Keep using these relays", "Off: they're dropped once you've joined", keepRelays, AppIcons.Relay) { keepRelays = it }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Your name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton("Ask to join", {
                        actions.setName(name)
                        val join = { actions.acceptInvite(invite, scanned, code.takeIf { !scanned }, keepRelays, onDone) }
                        if (keepRelays) gate.require(join) else join()
                    }, enabled = name.isNotBlank() && verified)
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Settings
// -------------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    state: AppState,
    me: String,
    relayStatus: Map<String, RelayStatus>,
    sharingSettings: List<SettingItem>,
    actions: Actions,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onSecurityLog: () -> Unit,
    onCircle: (String) -> Unit,
) {
    var newRelay by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf<String?>(null) }
    var showHistoryDays by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var showRelays by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current
    val platform = LocalPlatform.current

    ScreenScaffold("Settings", onBack) {
        // Profile row, like the top of Signal's settings.
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEditProfile).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatarFor(state, me, me, state.myName), size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(state.myName.ifBlank { "Add your name" }, style = MaterialTheme.typography.titleMedium)
                Caption(DeviceCode.full(me))
            }
            Icon(AppIcons.Edit, contentDescription = "Edit profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionHeader("Sharing")
        SwitchRow("Share my location", "With everyone in your circles", state.sharing, AppIcons.LocationPin) { actions.setSharing(it) }
        SwitchRow("Share battery level", "Shown next to your status", state.shareBattery, AppIcons.Battery) { actions.setShareBattery(it) }
        sharingSettings.forEach { item ->
            SettingsRow(item.title, item.detail, AppIcons.Info, onClick = if (item.action != null) item.onAction else null) {
                if (item.action != null) TextButton(onClick = item.onAction) { Text(item.action) }
            }
        }

        SectionHeader("Display")
        SwitchRow("Show profile photos", "Off shows everyone's initial instead", state.showPhotos, AppIcons.Face) { actions.setShowPhotos(it) }
        SwitchRow("Dark map", "Darker map colours, easier on the eyes at night", state.darkMap, AppIcons.Moon) { actions.setDarkMap(it) }

        SectionHeader("Circles")
        state.circles.forEach { c ->
            val role = when {
                c.adminPubkey == me -> " · you're the admin"
                me in c.managers -> " · you're a manager"
                else -> ""
            }
            SettingsRow(c.name, "${c.members.size} ${if (c.members.size == 1) "person" else "people"} · key #${c.epoch}$role", AppIcons.Circles, onClick = { onCircle(c.id) }) {
                when {
                    c.adminPubkey != me -> TextButton(onClick = { confirmLeave = c.id }) { Text("Leave") }
                    c.members.size <= 1 -> TextButton(onClick = { confirmLeave = c.id }) { Text("Leave") }
                    else -> TextButton(onClick = { confirmDelete = c.id }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (state.circles.any { it.adminPubkey == me && it.members.size > 1 }) {
            Caption("To leave a circle you run, open someone's profile and make them admin first.", Gutter)
        }

        SectionHeader("Security")
        if (state.pin == null) {
            SettingsRow("Set a PIN", "Asked before backups, invites and other sensitive changes. Never to open the app.", AppIcons.Lock, onClick = { settingPin = true })
        } else {
            SettingsRow("Change PIN", "Asked before backups, invites and other sensitive changes", AppIcons.Lock, onClick = { gate.require { settingPin = true } })
            SettingsRow("Remove PIN", null, AppIcons.Close, onClick = { gate.require { actions.removePin() } })
        }
        SwitchRow("Hide in recent apps", "Blank Quietmap's screen in the app switcher and screenshots", state.hideInRecents, AppIcons.Shield) { on ->
            if (on) actions.setHideInRecents(true) else gate.require { actions.setHideInRecents(false) }
        }
        SettingsRow("Security log", "Backups made, PIN changes and resets on this phone", AppIcons.Info, onClick = onSecurityLog)
        SettingsRow(
            "Get a new device code",
            "If someone may have copied this phone's identity",
            AppIcons.Refresh,
            titleColor = MaterialTheme.colorScheme.error,
            onClick = { confirmReset = true },
        )

        SectionHeader("Backup")
        SettingsRow("Back up to a file", "For moving to a new phone. Protected by a passphrase.", AppIcons.Share, onClick = onBackup)
        SettingsRow("Restore from a file", "Replaces everything on this phone", AppIcons.Refresh, onClick = onRestore)

        SectionHeader("Privacy")
        SettingsRow(
            "Location history",
            "${Format.historyDays(state.historyDays)} · kept only on this phone",
            AppIcons.History,
            onClick = { showHistoryDays = true },
        )
        if (platform.supportsTor) {
            SwitchRow(
                "Connect through Tor",
                if (state.useTor) "Relays see a Tor address, not yours. Needs Orbot running; nothing connects without it. The map still loads directly."
                else "Hide your IP address from relays. Needs the Orbot app.",
                state.useTor,
                AppIcons.Shield,
            ) { on -> if (on) actions.setUseTor(true) else gate.require { actions.setUseTor(false) } }
        }
        val connected = state.relays.count { relayStatus[it] == RelayStatus.CONNECTED }
        SettingsRow("Relays", "$connected of ${state.relays.size} connected · they only see encrypted data", AppIcons.Relay, onClick = { showRelays = !showRelays })
        AnimatedVisibility(showRelays, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                state.relays.forEach { url ->
                    Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(
                                when (relayStatus[url]) {
                                    RelayStatus.CONNECTED -> verifiedGreen()
                                    RelayStatus.CONNECTING -> Palette.Amber
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                CircleShape,
                            ),
                        )
                        Text(url.removePrefix("wss://"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { gate.require { actions.setRelays(state.relays - url) } }, enabled = state.relays.size > 1) {
                            Icon(AppIcons.Close, contentDescription = "Remove $url", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(Modifier.padding(start = 72.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRelay,
                        onValueChange = { newRelay = it.trim() },
                        label = { Text("wss://relay.example") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { val add = newRelay; gate.require { actions.setRelays(state.relays + add); newRelay = "" } }, enabled = app.quietmap.core.nostr.RelayUrls.isAllowed(newRelay)) {
                        Icon(AppIcons.Add, contentDescription = "Add relay")
                    }
                }
            }
        }
        SettingsRow("Your key stays on this phone", "It's only copied if you make a backup.", AppIcons.Lock)

        AnimatedVisibility(state.sharing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("Stop sharing with everyone", icon = AppIcons.Leave, titleColor = MaterialTheme.colorScheme.error, onClick = { actions.setSharing(false) })
            }
        }
        Caption("Quietmap · open source", Gutter.padding(top = 16.dp))
    }

    confirmLeave?.let { id ->
        val c = state.circles.firstOrNull { it.id == id }
        ConfirmSheet(
            title = "Leave ${c?.name ?: "circle"}?",
            text = "You'll stop sharing with ${c?.name ?: "this circle"} and stop seeing it. The admin is told, and the circle gets a new key.",
            confirm = "Leave",
            destructive = true,
            onConfirm = { actions.leaveCircle(id); confirmLeave = null },
            onDismiss = { confirmLeave = null },
        )
    }
    confirmDelete?.let { id ->
        val c = state.circles.firstOrNull { it.id == id }
        ConfirmSheet(
            title = "Delete ${c?.name ?: "circle"}?",
            text = "Everyone is removed and stops seeing each other in this circle. This can't be undone.",
            confirm = "Delete circle",
            destructive = true,
            onConfirm = { confirmDelete = null; gate.require { actions.deleteCircle(id) } },
            onDismiss = { confirmDelete = null },
        )
    }
    if (settingPin) SetPinSheet(actions, if (state.pin == null) "Set a PIN" else "Change PIN") { settingPin = false }
    if (showHistoryDays) {
        HistoryDaysSheet(state.historyDays, onPick = { days ->
            showHistoryDays = false
            if (days > state.historyDays) gate.require { actions.setHistoryDays(days) } else actions.setHistoryDays(days)
        }, onDismiss = { showHistoryDays = false })
    }
    if (confirmReset) {
        ConfirmSheet(
            title = "Get a new device code?",
            text = "Do this if someone may have copied your identity, for example from a backup file. " +
                "Circles you run move to your new code with new keys, so the copy loses access. " +
                "You leave circles run by someone else, and need a new invite to rejoin them.",
            confirm = "Get a new device code",
            destructive = true,
            onConfirm = {
                confirmReset = false
                gate.require { actions.resetIdentity { platform.showMessage("You have a new device code") } }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Backup and restore
// -------------------------------------------------------------------------------------------------

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Exports an encrypted backup file. */
@Composable
fun BackupScreen(state: AppState, actions: Actions, onBack: () -> Unit) {
    val platform = LocalPlatform.current
    val gate = LocalPinGate.current
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    val ok = pass.length >= Backups.MIN_PASSPHRASE && pass == again
    if (settingPin) SetPinSheet(actions, "Set a PIN") { settingPin = false }
    if (state.pin == null) {
        ScreenScaffold("Back up", onBack) {
            Column(Gutter, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "A backup can recreate you on another phone, so Quietmap asks for a PIN before making one. That way someone who picks up your unlocked phone can't quietly copy you.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton("Set a PIN", { settingPin = true })
            }
        }
        return
    }
    ScreenScaffold("Back up", onBack) {
        Column(Gutter, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Saves your device code, profile and circles to a file, so you can move to a new phone without starting over. Locations aren't included.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoCard {
                Text("Keep it safe", style = MaterialTheme.typography.titleSmall)
                Caption("Anyone with this file and its passphrase can act as you in your circles. Pick a passphrase you don't use anywhere else.")
            }
            PassphraseField(pass, { pass = it }, "Passphrase (at least ${Backups.MIN_PASSPHRASE} characters)")
            PassphraseField(again, { again = it }, "Passphrase again")
            AnimatedVisibility(again.isNotEmpty() && pass != again) { Caption("The passphrases don't match.") }
            PrimaryButton(if (working) "Encrypting…" else "Save backup file", {
                gate.require {
                    working = true
                    actions.exportBackup(pass) { text ->
                        if (text == null) {
                            working = false
                            return@exportBackup
                        }
                        platform.saveTextFile("quietmap-backup.txt", text) { saved ->
                            working = false
                            if (saved) {
                                platform.showMessage("Backup saved. It's listed in Settings, Security log.")
                                onBack()
                            }
                        }
                    }
                }
            }, enabled = ok && !working)
        }
    }
}

/** Restores from a backup file, replacing this phone's identity and circles. */
@Composable
fun RestoreScreen(actions: Actions, onBack: () -> Unit, onRestored: () -> Unit) {
    val platform = LocalPlatform.current
    val gate = LocalPinGate.current
    var file by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ScreenScaffold("Restore", onBack) {
        Column(Gutter, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Use a backup from your old phone. Your device code, profile and circles come back. Everything currently on this phone is replaced.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalButton(
                if (file == null) "Choose backup file" else "Backup file chosen",
                { platform.openTextFile { text -> if (text != null) { file = text; error = null } } },
                Modifier.fillMaxWidth(),
                icon = if (file == null) AppIcons.Add else AppIcons.Check,
            )
            PassphraseField(pass, { pass = it; error = null }, "Passphrase")
            AnimatedVisibility(error != null) { Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            PrimaryButton(if (working) "Restoring…" else "Restore", {
                val text = file ?: return@PrimaryButton
                gate.require {
                working = true
                actions.importBackup(text, pass) { err ->
                    working = false
                    if (err == null) {
                        platform.showMessage("Restored. Sharing is paused until you turn it on.")
                        onRestored()
                    } else {
                        error = err
                    }
                }
                }
            }, enabled = file != null && pass.isNotEmpty() && !working)
            Caption("Sharing starts paused after a restore, so you can check everything first.")
        }
    }
}
