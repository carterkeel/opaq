package app.opaq.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opaq.core.Engine
import app.opaq.core.protocol.InviteData
import app.opaq.core.protocol.InviteLinks
import app.opaq.core.protocol.InviteProblem
import kotlinx.coroutines.CoroutineScope

sealed interface Route {
    data object Home : Route
    data class Profile(val circleId: String, val pubkey: String) : Route
    data class Add(val circleId: String) : Route
    /** [scanned]: the QR code was scanned in the app, in person, which verifies the inviter. */
    data class Join(val invite: InviteData?, val scanned: Boolean = false, val problem: InviteProblem? = null) : Route
    data object Settings : Route
    data class SettingsPage(val section: SettingsSection) : Route
    data object EditProfile : Route
    data object JoinOptions : Route
    data object Backup : Route
    data object Restore : Route
    data object SecurityLog : Route
    data class CircleInfo(val circleId: String) : Route
    data class History(val circleId: String, val pubkey: String) : Route
}

/**
 * The whole app UI, shared by Android and iOS. The host app owns the engine, permissions and
 * incoming invite links, and passes their current state in.
 *
 * The home screen (map and people) is always composed. Other screens slide in over it, so the
 * map keeps its position and never reloads when you go back.
 *
 * @param scope long-lived scope for engine actions, so they finish even if the screen goes away.
 * @param sharingSettings platform rows shown under Settings → Sharing.
 * @param incomingLink an invite link the app was opened with; call [onLinkHandled] once shown.
 * @param sharingBlocked why the platform can't share right now even though sharing is on (for
 *   example its notification is turned off), shown with a button that calls [onFixSharing].
 */
@Composable
fun OpaqScreens(
    engine: Engine,
    scope: CoroutineScope,
    platform: PlatformBridge,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    sharingSettings: List<SettingItem>,
    incomingLink: String?,
    onLinkHandled: () -> Unit,
    sharingBlocked: String? = null,
    onFixSharing: () -> Unit = {},
) {
    val actions = remember(engine, scope, platform) { Actions(engine, scope, platform) }
    val pinGate = remember(actions) { PinGate(actions) }
    val state by engine.state.collectAsStateWithLifecycle()
    val recovery by engine.recovery.collectAsStateWithLifecycle()
    val relayStatus by engine.relayStatus.collectAsStateWithLifecycle()
    val me = engine.myPubkey

    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    // Reopen on the circle you last looked at.
    var selectedCircle by remember { mutableStateOf(state.lastCircleId) }
    val selectCircle: (String) -> Unit = { selectedCircle = it; actions.setLastCircle(it) }
    // First-run setup shows for new installs. Existing installs that already have circles skip it.
    // Recomputed when unreadable saved state is resolved (a fresh start goes through setup).
    val needsSetup = remember(recovery) { !state.setupDone && state.circles.isEmpty() }
    LaunchedEffect(recovery) { if (recovery == null && !needsSetup && !state.setupDone) actions.setSetupDone() }
    val showSetup = needsSetup && !state.setupDone
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val openLink: (String) -> Unit = { push(Route.Join(InviteLinks.decode(it), problem = InviteLinks.problem(it))) }
    val haptics = rememberHaptics()
    val scan: () -> Unit = {
        platform.scanQrCode { text ->
            if (text != null) {
                val invite = InviteLinks.decode(text)
                if (invite != null) haptics.confirm() else haptics.reject()
                push(Route.Join(invite, scanned = true, problem = InviteLinks.problem(text)))
            }
        }
    }

    // A tap you can feel when you're let into a circle, or when a join request is turned down,
    // even if you've moved on to another screen while waiting. Not for what was already there
    // when the app opened.
    val newCircles = state.circles.filter { !it.confirmed }.map { it.id }.toSet()
    val refusals = state.joinRefusals.map { it.circleId to it.at }.toSet()
    var seenCircles by remember { mutableStateOf<Set<String>?>(null) }
    var seenRefusals by remember { mutableStateOf<Set<Pair<String, Long>>?>(null) }
    LaunchedEffect(newCircles, refusals, recovery) {
        if (recovery != null) return@LaunchedEffect
        seenCircles?.let { if ((newCircles - it).isNotEmpty()) haptics.confirm() }
        seenRefusals?.let { if ((refusals - it).isNotEmpty()) haptics.reject() }
        seenCircles = newCircles
        seenRefusals = refusals
    }

    LaunchedEffect(incomingLink) {
        val l = incomingLink ?: return@LaunchedEffect
        onLinkHandled()
        openLink(l)
    }

    // Ask once as soon as there's a circle to share with (setup asks on its own last step).
    LaunchedEffect(state.circles.isNotEmpty(), showSetup) {
        if (!showSetup && state.circles.isNotEmpty() && state.sharing && !hasLocationPermission) onRequestLocation()
    }

    PlatformBackHandler(enabled = stack.size > 1) { pop() }

    // Once a month while sharing: who sees you, keep or stop.
    var consentShown by remember { mutableStateOf(false) }
    val consentDue = recovery == null && !showSetup && state.sharing && actions.consentDue()

    CompositionLocalProvider(LocalPlatform provides platform, LocalPinGate provides pinGate) {
        OpaqTheme(state.themeMode) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // Backgrounds, the map and the people sheet reach the screen edges; each screen keeps
                // its own content clear of the status bar, notch, home indicator and keyboard.
                Box(Modifier.fillMaxSize()) {
                    val unreadable = recovery
                    if (unreadable != null) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH)) {
                                RecoveryScreen(unreadable, actions, onRestore = { push(Route.Restore) })
                            }
                        }
                    } else if (showSetup) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                          Box(Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH)) {
                            SetupScreen(
                                state = state,
                                me = me,
                                actions = actions,
                                hasLocationPermission = hasLocationPermission,
                                onRequestLocation = onRequestLocation,
                                onJoinLink = openLink,
                                onScan = scan,
                                onRestore = { push(Route.Restore) },
                                onCircleCreated = selectCircle,
                                backgroundSettings = sharingSettings,
                            )
                          }
                        }
                    } else {
                      // While another screen is open over the map, screen readers (and the UI test)
                      // shouldn't reach the map's buttons through it.
                      Box(Modifier.fillMaxSize().then(if (stack.size > 1) Modifier.clearAndSetSemantics {} else Modifier)) {
                        HomeScreen(
                            state = state,
                            me = me,
                            selectedCircleId = selectedCircle,
                            hasLocationPermission = hasLocationPermission,
                            actions = actions,
                            onSelectCircle = selectCircle,
                            onOpenProfile = { c, p -> push(Route.Profile(c, p)) },
                            onAdd = { push(Route.Add(it)) },
                            onJoin = { push(Route.JoinOptions) },
                            onSettings = { push(Route.Settings) },
                            onRequestLocation = onRequestLocation,
                            sharingBlocked = sharingBlocked,
                            onFixSharing = onFixSharing,
                        )
                      }
                    }

                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    AnimatedContent(
                        targetState = stack.toList(),
                        transitionSpec = { slideTransition(rtl) },
                        contentKey = { it.last() },
                        // Every route, Home included, fills the screen. Otherwise the container
                        // would grow out of nothing and screens would seem to open from the middle.
                        modifier = Modifier.fillMaxSize(),
                        label = "screens",
                    ) { routes ->
                        when (val route = routes.last()) {
                            Route.Home -> Box(Modifier.fillMaxSize()) // empty: the always-composed home shows through
                            else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                                  // On a wide screen (a phone turned sideways) rows and pickers keep a readable
                                  // width, centred, like Signal. The history map uses the whole width.
                                  Box(if (route is Route.History) Modifier.fillMaxSize() else Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH)) {
                                    when (route) {
                                        is Route.Profile -> ProfileScreen(state, me, route.circleId, route.pubkey, actions, ::pop, onHistory = { push(Route.History(route.circleId, route.pubkey)) })
                                        is Route.History -> HistoryScreen(state, me, route.circleId, route.pubkey, actions, ::pop)
                                        is Route.Add -> AddScreen(state, me, route.circleId, actions, ::pop)
                                        is Route.Join -> JoinScreen(state, route.invite, route.scanned, actions, ::home, route.problem)
                                        Route.JoinOptions -> JoinOptionsScreen(::pop, scan, openLink)
                                        Route.Settings -> SettingsScreen(
                                            state, me, ::pop,
                                            onEditProfile = { push(Route.EditProfile) },
                                            onSection = { push(Route.SettingsPage(it)) },
                                        )
                                        is Route.SettingsPage -> SettingsSectionScreen(
                                            route.section, state, me, relayStatus, sharingSettings, actions, ::pop,
                                            onBackup = { push(Route.Backup) },
                                            onRestore = { push(Route.Restore) },
                                            onSecurityLog = { push(Route.SecurityLog) },
                                            onCircle = { push(Route.CircleInfo(it)) },
                                        )
                                        Route.EditProfile -> EditProfileScreen(state, me, actions, ::pop)
                                        Route.Backup -> BackupScreen(state, actions, ::pop)
                                        Route.SecurityLog -> SecurityLogScreen(state, ::pop)
                                        is Route.CircleInfo -> CircleInfoScreen(state, me, route.circleId, actions, ::pop)
                                        Route.Restore -> RestoreScreen(actions, ::pop) { selectedCircle = null; home() }
                                        Route.Home -> Unit
                                    }
                                  }
                                }
                            }
                        }
                    }
                    PinPrompt(pinGate, actions)
                    if (consentDue && !consentShown) ConsentSheet(state, me, actions) { consentShown = true }
                }
            }
        }
    }
}

/** The widest a screen's content gets, so rows don't stretch across a phone turned sideways. */
internal val MAX_CONTENT_WIDTH = 640.dp

/**
 * A people panel beside the map rather than a sheet under it whenever the screen is wider than it
 * is tall: a phone turned sideways, an unfolded foldable held flat, a tablet in landscape. Held
 * upright, a foldable or tablet gets the same sheet as a phone.
 */
internal fun usesSidePanel(width: Dp, height: Dp): Boolean = width > height

/**
 * How wide that panel is: about a phone's width (the list as it is in portrait), up to 400 dp,
 * leaving the map at least half the screen. Never taller than the screen is high, so a phone
 * sideways keeps the width its list has upright.
 */
internal fun sidePanelWidth(width: Dp, height: Dp): Dp = minOf(400.dp, height, maxOf(300.dp, width * 0.45f))

/** Moving between screens (and setup steps), like Signal: short, eased, no bounce. */
internal val screenSlideSpec: FiniteAnimationSpec<IntOffset> = tween(durationMillis = 220, easing = FastOutSlowInEasing)

/** The edge a screen comes in from when it opens, and goes back to when it closes. */
private enum class Edge { End, Bottom }

/**
 * Screens open from the side their button is on. Settings (top corner button) and screens opened
 * from another screen come in from the end edge, like Signal. Screens opened from the people sheet
 * at the bottom of the map (a profile, inviting, joining) come up from the bottom.
 */
private fun Route.edge(): Edge = when (this) {
    is Route.Profile, is Route.Add, Route.JoinOptions, is Route.Join -> Edge.Bottom
    else -> Edge.End
}

/**
 * Push: the new screen slides in from its [Edge] with no fade; pop is the exact reverse. From the
 * end edge the old screen slides out the other way at the same speed. From the bottom the new
 * screen rises over the old one, which stays put.
 */
private fun AnimatedContentTransitionScope<List<Route>>.slideTransition(rtl: Boolean): ContentTransform {
    val forward = targetState.size >= initialState.size
    val end = if (rtl) -1 else 1
    // The screen on top (the one opening or closing) decides the direction.
    val edge = (if (forward) targetState else initialState).last().edge()
    return when {
        edge == Edge.Bottom && forward ->
            slideInVertically(screenSlideSpec) { it }
                .togetherWith(slideOutVertically(screenSlideSpec) { 0 })
                .apply { targetContentZIndex = 1f }
        edge == Edge.Bottom ->
            slideInVertically(screenSlideSpec) { 0 }
                .togetherWith(slideOutVertically(screenSlideSpec) { it })
                .apply { targetContentZIndex = -1f }
        forward ->
            slideInHorizontally(screenSlideSpec) { it * end }
                .togetherWith(slideOutHorizontally(screenSlideSpec) { -it * end })
                .apply { targetContentZIndex = 1f }
        else ->
            slideInHorizontally(screenSlideSpec) { -it * end }
                .togetherWith(slideOutHorizontally(screenSlideSpec) { it * end })
                .apply { targetContentZIndex = -1f }
    }
}
