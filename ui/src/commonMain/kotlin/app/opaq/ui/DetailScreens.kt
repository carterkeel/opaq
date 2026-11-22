package app.opaq.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opaq.core.model.Precision
import app.opaq.core.model.MemberLocation
import app.opaq.core.Engine
import app.opaq.core.model.AppState
import app.opaq.core.model.Invite
import app.opaq.core.model.THEME_DARK
import app.opaq.core.model.THEME_LIGHT
import app.opaq.core.model.THEME_MIDNIGHT
import app.opaq.core.model.THEME_SYSTEM
import app.opaq.core.nostr.RelayStatus
import app.opaq.core.nostr.RelayUrls
import app.opaq.core.protocol.Backups
import app.opaq.core.protocol.DeviceCode
import app.opaq.core.protocol.InviteLinks
import app.opaq.core.protocol.InviteData
import app.opaq.core.protocol.InviteProblem
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var askInfo by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmAdmin by remember { mutableStateOf(false) }
    var directions by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current
    val haptics = rememberHaptics()
    val platform = LocalPlatform.current

    ScreenScaffold("", onBack) {
        val subtitle = when {
            isMe -> "This is you"
            contact?.label != null && published.isNotBlank() && published != contact.label -> "Calls themself “$published”"
            else -> null
        }
        ProfileHeader(info, info.name, subtitle)

        val myPaused = isMe && (!state.sharing || circle.paused)
        SettingsRow(
            title = if (myPaused) "Sharing paused" else Format.status(loc, now),
            // Shared roughly (#157): spell out that the dot is only the middle of an area.
            subtitle = loc?.let { l ->
                when (l.precision) {
                    Precision.NEIGHBORHOOD -> "Shares only roughly which part of town: somewhere within about ${Format.radius(l.accuracy.toDouble())} of the dot"
                    Precision.CITY -> "Shares only roughly which city: somewhere within about ${Format.radius(l.accuracy.toDouble())} of the dot"
                    else -> "Accurate to about ${Format.radius(l.accuracy.toDouble())}"
                }
            },
            icon = AppIcons.LocationPin,
            trailing = Format.battery(loc, now)?.takeIf { !myPaused }?.let { b -> { BatteryBadge(b) } },
        )
        // Nudge a phone that's gone quiet to send its location (#149). Their phone answers on its own,
        // and they see nothing but their location going out, so it only works while they're online,
        // sharing with this circle and sharing exactly; and only once their update is 10 minutes old.
        if (!isMe && circle.confirmed) {
            val age = loc?.let { now - it.time }
            val why = when {
                loc?.status == MemberLocation.PAUSED -> "${info.name} paused sharing, so their phone won't answer"
                loc?.precision == Precision.NEIGHBORHOOD || loc?.precision == Precision.CITY -> "${info.name} shares only roughly where they are, so their phone won't answer"
                age != null && age < Engine.LOCATION_REQUEST_AFTER_SECONDS -> "Updated ${Format.ago(age)}. You can ask once it's 10 minutes old"
                else -> null
            }
            SettingsRow(
                "Ask for their location",
                why ?: "Their phone sends a fresh update if it's online",
                AppIcons.Refresh,
                titleColor = if (why == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = if (why == null) ({ haptics.confirm(); actions.requestLocation(circleId, pubkey, info.name) }) else null,
            ) {
                IconButton(onClick = { askInfo = true }) {
                    Icon(AppIcons.Info, contentDescription = "About asking for a location", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Directions to where they are (#150), from their last shared position.
        if (!isMe && loc != null && platform.directionsApps.isNotEmpty()) {
            SettingsRow("Directions", "Open a map app with directions to ${info.name}", AppIcons.Directions, onClick = { directions = true })
        }
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
                "Their phone may be off or offline. Opaq keeps resending it for two weeks.",
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

    if (directions && loc != null) {
        ModalBottomSheet(onDismissRequest = { directions = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text("Directions to ${info.name}", style = MaterialTheme.typography.titleMedium, modifier = Gutter)
            Caption(
                "Directions go to where they were at their last update. The map app you pick will see the destination.",
                Gutter.padding(top = 4.dp, bottom = 8.dp),
            )
            platform.directionsApps.forEach { app ->
                SettingsRow(app, icon = AppIcons.Directions, onClick = {
                    directions = false
                    platform.openDirections(app, loc.lat, loc.lon, info.name)
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (renaming) {
        TextSheet("Rename", "Name shown on your phone", contact?.label ?: published, "Save", { renaming = false }) {
            actions.setLabel(pubkey, it); renaming = false
        }
    }
    if (askInfo) {
        InfoSheet(
            title = "Asking for a location",
            text = "If ${info.name}'s location hasn't updated in a while, this reminds their phone to send it now. " +
                "All they see is their location being shared, as usual.",
            points = listOf(
                "It works only if their phone is online.",
                "It doesn't work if they've paused sharing, or share only roughly which part of town or city they're in.",
                "You can ask once their last update is 10 minutes old.",
            ),
            onDismiss = { askInfo = false },
        )
    }
    if (confirmRemove) {
        ConfirmSheet(
            title = "Remove ${info.name}?",
            text = "They'll stop seeing ${circle.name}, and the circle gets a new key so they can't read anything new.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { confirmRemove = false; gate.require { haptics.confirm(); actions.removeMember(circleId, pubkey); onBack() } },
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
    // Capped so the swatches stay about avatar-sized on a tablet rather than stretching across the screen.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.padding(horizontal = horizontalPadding).widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarColors.all.indices.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { index ->
                    val isSelected = !photoSet && index == selected
                    val ring by animateDpAsState(if (isSelected) 3.dp else 0.dp, label = "ring")
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            // A zero-width border still draws a hairline, so leave it off unless selected.
                            .then(if (ring > 0.dp) Modifier.border(ring, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
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
    var multiUse by remember { mutableStateOf(false) }
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
                        BigIcon(AppIcons.QrCode)
                        Text(
                            "Create a QR code and link for someone to join ${circle.name}. You approve everyone who uses them. Scanning in person is safest: someone who joins from a link should check your device code with you.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        SwitchRow("Valid for 24 hours", "Off: the invite expires in 1 hour", longer, AppIcons.Info) { longer = it }
                        SwitchRow("Let more than one person use this link", "Up to ${Invite.MAX_USES} people. Off: it works once.", multiUse, AppIcons.Person) { multiUse = it }
                        PrimaryButton("Create invite", { gate.require { actions.createInvite(circle.id, hours, multiUse) { link = it } } })
                    } else {
                        val secret = remember(current) { InviteLinks.decode(current)?.s }
                        val invite = state.invites.firstOrNull { it.secret == secret }
                        val uses = invite?.usedBy?.size ?: 0
                        val max = invite?.maxUses ?: 1
                        Text("Have them scan this with Opaq", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // QR codes need dark modules on a light background, in both themes.
                        Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
                            Image(
                                // The web form, so a phone's own camera app opens the join page (which hands the
                                // invite to Opaq) instead of finding a link no app handles. Opaq's scanner reads both.
                                painter = rememberQrCodePainter(remember(current) { InviteLinks.webLink(current) }),
                                contentDescription = "Invite QR code",
                                modifier = Modifier.padding(20.dp).size(232.dp),
                            )
                        }
                        val works = if (max > 1) "Works for up to $max people" else "Works once"
                        Caption("On their phone: Join a circle → Scan QR code. $works · expires in ${if (hours == 1) "1 hour" else "24 hours"}.")
                        Caption(
                            when {
                                max > 1 -> "Used by $uses of $max people"
                                uses > 0 || invite?.used == true -> "Used. Create a new invite for anyone else."
                                else -> "Not used yet"
                            },
                        )
                        InfoCard {
                            Caption("Your device code")
                            Mono(DeviceCode.full(me), size = 17, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
                            Caption("If you send the link instead, read this out to them on a call. Opaq shows them the code from the link, so they can check it's the same.")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TonalButton("Share link", { platform.shareText(InviteLinks.shareText(circle.name, current), "Share invite link") }, Modifier.weight(1f), icon = AppIcons.Share)
                            TonalButton("Copy", { platform.copyToClipboard("Opaq invite", InviteLinks.webLink(current)) }, Modifier.weight(1f))
                        }
                        Caption("Tip: send links in an end-to-end encrypted chat. The app that carries a link can read it.")
                        if (secret != null) {
                            TextButton(
                                onClick = { actions.cancelInvite(secret) { link = null; platform.showMessage("Invite cancelled") } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Cancel this invite", color = MaterialTheme.colorScheme.error) }
                        }
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
            BigIcon(AppIcons.ScanQr)
            Text(
                "Ask someone in the circle to open it in Opaq and tap Invite. Then scan their QR code, or paste the link they send you.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Scan QR code", onScan)
            OutlinedTextField(
                value = paste,
                onValueChange = { paste = it },
                label = { Text("Or paste an invite link") },
                // Multi-line, so a whole pasted message (with the link inside) fits.
                maxLines = 4,
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
fun JoinScreen(state: AppState, invite: InviteData?, scanned: Boolean, actions: Actions, onDone: () -> Unit, problem: InviteProblem? = null) {
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var keepRelays by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current
    val haptics = rememberHaptics()
    ScreenScaffold("Join a circle", onDone) {
        val now = epochSeconds()
        when {
            invite == null -> Text(
                when (problem) {
                    InviteProblem.MISSING -> "This invite link is incomplete. The invite is the long part after the #, and it's missing here: some apps cut links off there. Ask for the link again and paste their whole message here, or scan their QR code."
                    InviteProblem.DAMAGED -> "This invite link is incomplete or was changed after it was made. Ask for the link again and paste their whole message here, or scan their QR code."
                    else -> "That isn't an Opaq invite. Ask for a new link or QR code."
                },
                modifier = Gutter,
            )
            invite.e < now -> Text("This invite has expired. Ask ${invite.n.ifBlank { "them" }} for a new one.", modifier = Gutter)
            invite.k == actions.me -> Text("This is your own invite. Show it to the person you want to add.", modifier = Gutter)
            state.circles.any { it.id == invite.c } -> Text("You're already in ${invite.cn}.", modifier = Gutter)
            else -> {
                val inviter = invite.n.ifBlank { "the person who invited you" }
                val alreadyVerified = state.contacts[invite.k]?.verified == true
                val checked = scanned || alreadyVerified
                val newRelays = invite.r.mapNotNull { RelayUrls.clean(it) }.filter { it !in state.relays }.take(4)
                val join: (Boolean) -> Unit = { codesMatch ->
                    if (codesMatch) haptics.confirm()
                    actions.setName(name)
                    val send = { actions.acceptInvite(invite, scanned, codesMatch, keepRelays, onDone) }
                    if (keepRelays) gate.require(send) else send()
                }
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
                            else -> Caption("A link can be swapped on the way. Ask $inviter to read out the device code on their phone, on a call or in person. If it's the same as this one, the invite really came from them.")
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
                    if (checked) {
                        PrimaryButton("Ask to join", { join(false) }, enabled = name.isNotBlank())
                    } else {
                        PrimaryButton("The codes match", { join(true) }, enabled = name.isNotBlank())
                        TonalButton("Join without checking", { join(false) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
                        Caption("If you join without checking, ${invite.n.ifBlank { "they" }} won't be marked as verified. You can compare codes later from their profile.")
                    }
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Settings
// -------------------------------------------------------------------------------------------------

/** The parts of Settings (#108). Like Signal: one row each on the first screen, each opening its own. */
enum class SettingsSection(val title: String) {
    Sharing("Sharing"),
    Circles("Circles"),
    Appearance("Appearance"),
    Privacy("Privacy"),
    Security("Security"),
    Backups("Backups"),
    Connection("Connection");

    val icon: ImageVector
        get() = when (this) {
            Sharing -> AppIcons.LocationPin
            Circles -> AppIcons.Circles
            Appearance -> AppIcons.Appearance
            Privacy -> AppIcons.Lock
            Security -> AppIcons.Shield
            Backups -> AppIcons.History
            Connection -> AppIcons.Relay
        }
}

/** Settings: your profile, then one row per section, split into two groups like Signal's. */
@Composable
fun SettingsScreen(
    state: AppState,
    me: String,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onSection: (SettingsSection) -> Unit,
    onPlaces: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
) {
    ScreenScaffold("Settings", onBack) {
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
        Spacer(Modifier.height(8.dp))
        SettingsSection.entries.forEach { section ->
            if (section == SettingsSection.Appearance) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            SettingsRow(section.title, icon = section.icon, onClick = { onSection(section) })
            // Alert areas (#151) sit with sharing and circles.
            if (section == SettingsSection.Circles) SettingsRow("Place alerts", icon = AppIcons.Radius, onClick = onPlaces)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        // Diagnostics (#154), and the legal pages on the website (#159).
        val platform = LocalPlatform.current
        // The website's report form (opaq.me/report); the app itself never contacts the form service.
        SettingsRow("Report a problem", "Tell the developer what went wrong", AppIcons.Flag, onClick = { platform.openUrl(REPORT_URL) })
        SettingsRow("Diagnostic logs", "Find out why sharing stopped", AppIcons.Info, onClick = onDiagnostics)
        SettingsRow("Privacy policy", icon = AppIcons.Shield, onClick = { platform.openUrl(PRIVACY_URL) })
        SettingsRow("Terms of use", icon = AppIcons.Lock, onClick = { platform.openUrl(TERMS_URL) })
        Caption("Opaq · open source", Gutter.padding(top = 24.dp))
    }
}

/** One section of Settings, on its own screen. */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    state: AppState,
    me: String,
    relayStatus: Map<String, RelayStatus>,
    sharingSettings: List<SettingItem>,
    actions: Actions,
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onSecurityLog: () -> Unit,
    onCircle: (String) -> Unit,
) {
    var newRelay by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf<String?>(null) }
    var showHistoryDays by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showAppIcon by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var showRelays by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val gate = LocalPinGate.current
    val platform = LocalPlatform.current
    var appIcon by remember { mutableStateOf(platform.appIcon) }

    ScreenScaffold(section.title, onBack) {
        when (section) {
            SettingsSection.Sharing -> {
                SwitchRow("Share my location", "With everyone in your circles", state.sharing, AppIcons.LocationPin) { actions.setSharing(it) }
                SwitchRow("Share battery level", "Shown next to your status", state.shareBattery, AppIcons.Battery) { actions.setShareBattery(it) }
                sharingSettings.forEach { item ->
                    if (item.checked != null) {
                        SwitchRow(item.title, item.detail, item.checked, AppIcons.Info, item.onCheckedChange)
                    } else {
                        SettingsRow(item.title, item.detail, AppIcons.Info, onClick = if (item.action != null) item.onAction else null) {
                            if (item.action != null) TextButton(onClick = item.onAction) { Text(item.action) }
                        }
                    }
                }
                AnimatedVisibility(state.sharing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow("Stop sharing with everyone", icon = AppIcons.Leave, titleColor = MaterialTheme.colorScheme.error, onClick = { actions.setSharing(false) })
                    }
                }
            }

            SettingsSection.Circles -> {
                if (state.circles.isEmpty()) Caption("You're not in any circles yet.", Gutter.padding(top = 8.dp))
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
                    Caption("To leave a circle you run, open someone's profile and make them admin first.", Gutter.padding(top = 8.dp))
                }
            }

            SettingsSection.Appearance -> {
                SettingsRow("Theme", themeLabel(state.themeMode), AppIcons.Appearance, onClick = { showTheme = true })
                appIcon?.let { id ->
                    val current = AppIconChoice.of(id)
                    SettingsRow("App icon", current.label, AppIcons.AppTile, onClick = { showAppIcon = true }) {
                        AppIconPreview(current, 32.dp)
                    }
                }
                SwitchRow("Show profile photos", "Off shows everyone's initial instead", state.showPhotos, AppIcons.Face) { actions.setShowPhotos(it) }
                SwitchRow("Dark map", "Darker map colors, easier on the eyes at night", state.darkMap, AppIcons.Moon) { actions.setDarkMap(it) }
            }

            SettingsSection.Privacy -> {
                SettingsRow(
                    "Location history",
                    "${Format.historyDays(state.historyDays)} · kept only on this phone",
                    AppIcons.History,
                    onClick = { showHistoryDays = true },
                )
                SwitchRow("Hide in recent apps", platform.hideInRecentsDetail, state.hideInRecents, AppIcons.Shield) { on ->
                    if (on) actions.setHideInRecents(true) else gate.require { actions.setHideInRecents(false) }
                }
            }

            SettingsSection.Security -> {
                if (state.pin == null) {
                    SettingsRow("Set a PIN", "Asked before backups, invites and other sensitive changes. Never to open the app.", AppIcons.Lock, onClick = { platform.confirmOwnerThen { settingPin = true } })
                } else {
                    SettingsRow("Change PIN", "Asked before backups, invites and other sensitive changes", AppIcons.Lock, onClick = { gate.require { settingPin = true } })
                    SettingsRow("Remove PIN", null, AppIcons.Close, onClick = { gate.require { platform.forgetBiometrics(); actions.removePin() } })
                    BiometricSwitch(state, actions)
                }
                SettingsRow("Security log", "Backups made, PIN changes and resets on this phone", AppIcons.Info, onClick = onSecurityLog)
                SettingsRow("Your key stays on this phone", "It's only copied if you make a backup.", AppIcons.Lock)
                SettingsRow(
                    "Get a new device code",
                    "If someone may have copied this phone's identity",
                    AppIcons.Refresh,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmReset = true },
                )
            }

            SettingsSection.Backups -> {
                SettingsRow("Back up to a file", "For moving to a new phone. Protected by a passphrase.", AppIcons.Share, onClick = onBackup)
                SettingsRow("Restore from a file", "Replaces everything on this phone", AppIcons.Refresh, onClick = onRestore)
            }

            SettingsSection.Connection -> {
                actions.testRelays?.let { test ->
                    val up = test.any { relayStatus[it] == RelayStatus.CONNECTED }
                    SettingsRow(
                        "Test relay",
                        "${test.joinToString()} · ${if (up) "connected" else "not connected"}. This test build uses only this relay; the ones below are ignored.",
                        AppIcons.Relay,
                    )
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
                            IconButton(onClick = { val add = newRelay; gate.require { actions.setRelays(state.relays + add); newRelay = "" } }, enabled = RelayUrls.isAllowed(newRelay)) {
                                Icon(AppIcons.Add, contentDescription = "Add relay")
                            }
                        }
                    }
                }
                if (platform.supportsTor) {
                    SwitchRow(
                        "Connect through Tor",
                        if (state.useTor) "Relays see a Tor address, not yours. Needs Orbot running; nothing connects without it. The map still loads directly."
                        else "Hide your IP address from relays. Needs the Orbot app.",
                        state.useTor,
                        AppIcons.Shield,
                    ) { on -> if (on) actions.setUseTor(true) else gate.require { actions.setUseTor(false) } }
                }
            }
        }
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
    if (showAppIcon) {
        AppIconSheet(appIcon ?: AppIconChoice.Default.id, onPick = { id ->
            showAppIcon = false
            platform.setAppIcon(id)
            appIcon = platform.appIcon
        }, onDismiss = { showAppIcon = false })
    }
    if (showTheme) {
        ThemeSheet(state.themeMode, onPick = { mode -> showTheme = false; actions.setThemeMode(mode) }, onDismiss = { showTheme = false })
    }
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

private fun themeLabel(mode: String) = when (mode) {
    THEME_LIGHT -> "Light"
    THEME_DARK -> "Dark"
    THEME_MIDNIGHT -> "Midnight"
    else -> "System default"
}

/** Settings → Appearance → Theme: follow the phone (the default), or always light, dark or midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleLarge, modifier = Gutter)
            Caption("System default follows your phone's light or dark setting. Midnight is dark with a black background.", Gutter.then(Modifier.padding(top = 4.dp, bottom = 8.dp)))
            val modes = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK, THEME_MIDNIGHT)
            val selected = if (current in modes) current else THEME_SYSTEM
            modes.forEach { mode ->
                SettingsRow(themeLabel(mode), onClick = { onPick(mode) }) {
                    if (mode == selected) Icon(AppIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** An app icon as it looks on a home screen, for Settings. */
@Composable
fun AppIconPreview(choice: AppIconChoice, size: Dp) {
    val shape = RoundedCornerShape(size * 0.225f)
    val dark = LocalDarkTheme.current
    Image(
        appIconArt(choice),
        contentDescription = null,
        modifier = Modifier.size(size).clip(shape)
            // A tile the same shade as the screen needs an edge: the white icon on light screens,
            // the Ink two-tone icon on dark ones (Dark's background is Ink itself).
            .then(
                when {
                    choice == AppIconChoice.White && !dark -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    choice == AppIconChoice.TwoTone && dark -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    else -> Modifier
                },
            ),
    )
}

/** Settings → Appearance → App icon: the approved icons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppIconSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("App icon", style = MaterialTheme.typography.titleLarge, modifier = Gutter)
            Caption("The icon on your home screen. It can take a moment to change.", Gutter.then(Modifier.padding(top = 4.dp, bottom = 8.dp)))
            AppIconChoice.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(choice.id) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconPreview(choice, 48.dp)
                    Text(choice.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    if (choice.id == current) Icon(AppIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
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
                    "A backup can recreate you on another phone, so Opaq asks for a PIN before making one. That way someone who picks up your unlocked phone can't quietly copy you.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton("Set a PIN", { platform.confirmOwnerThen { settingPin = true } })
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
                gate.requireNow {
                    working = true
                    actions.exportBackup(pass) { text ->
                        if (text == null) {
                            working = false
                            return@exportBackup
                        }
                        platform.saveTextFile("opaq-backup.txt", text) { saved ->
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

/** The website's legal pages (#159), also linked from the store listings. */
const val PRIVACY_URL = "https://opaq.me/privacy"
const val TERMS_URL = "https://opaq.me/terms"
const val REPORT_URL = "https://opaq.me/report"
