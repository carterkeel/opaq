package app.quietmap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quietmap.core.Engine
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import kotlinx.coroutines.CoroutineScope

sealed interface Route {
    data object Home : Route
    data class Profile(val circleId: String, val pubkey: String) : Route
    data class Add(val circleId: String) : Route
    /** [scanned]: the QR code was scanned in the app, in person, which verifies the inviter. */
    data class Join(val invite: InviteData?, val scanned: Boolean = false) : Route
    data object Settings : Route
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
fun QuietmapScreens(
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
    val openLink: (String) -> Unit = { push(Route.Join(InviteLinks.decode(it))) }
    val scan: () -> Unit = { platform.scanQrCode { text -> if (text != null) push(Route.Join(InviteLinks.decode(text), scanned = true)) } }

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
        QuietmapTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // Backgrounds, the map and the people sheet reach the screen edges; each screen keeps
                // its own content clear of the status bar, notch, home indicator and keyboard.
                Box(Modifier.fillMaxSize()) {
                    val unreadable = recovery
                    if (unreadable != null) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            RecoveryScreen(unreadable, actions, onRestore = { push(Route.Restore) })
                        }
                    } else if (showSetup) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
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
                            )
                        }
                    } else {
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

                    AnimatedContent(
                        targetState = stack.toList(),
                        transitionSpec = { slideTransition() },
                        contentKey = { it.last() },
                        label = "screens",
                    ) { routes ->
                        when (val route = routes.last()) {
                            Route.Home -> Box(Modifier) // the always-composed home shows through
                            else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                    when (route) {
                                        is Route.Profile -> ProfileScreen(state, me, route.circleId, route.pubkey, actions, ::pop, onHistory = { push(Route.History(route.circleId, route.pubkey)) })
                                        is Route.History -> HistoryScreen(state, me, route.circleId, route.pubkey, actions, ::pop)
                                        is Route.Add -> AddScreen(state, me, route.circleId, actions, ::pop)
                                        is Route.Join -> JoinScreen(state, route.invite, route.scanned, actions, ::home)
                                        Route.JoinOptions -> JoinOptionsScreen(::pop, scan, openLink)
                                        Route.Settings -> SettingsScreen(
                                            state, me, relayStatus, sharingSettings, actions, ::pop,
                                            onEditProfile = { push(Route.EditProfile) },
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
                    PinPrompt(pinGate, actions)
                    if (consentDue && !consentShown) ConsentSheet(state, me, actions) { consentShown = true }
                }
            }
        }
    }
}

private val slideSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
private val fadeSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/** Push: the new screen slides in from the right over the old one. Pop: it slides back out. */
private fun AnimatedContentTransitionScope<List<Route>>.slideTransition(): ContentTransform {
    val forward = targetState.size >= initialState.size
    return if (forward) {
        (slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec, initialAlpha = 0.7f))
            .togetherWith(slideOutHorizontally(slideSpec) { -it / 5 } + fadeOut(fadeSpec, targetAlpha = 0.6f))
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(slideSpec) { -it / 5 } + fadeIn(fadeSpec, initialAlpha = 0.6f))
            .togetherWith(slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec, targetAlpha = 0.7f))
            .apply { targetContentZIndex = -1f }
    }
}
