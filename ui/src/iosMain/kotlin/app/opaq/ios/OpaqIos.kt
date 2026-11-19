package app.opaq.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.opaq.core.Engine
import app.opaq.core.protocol.InviteLinks
import app.opaq.ui.OpaqScreens
import app.opaq.ui.epochSeconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.systemBackgroundColor
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS app's Kotlin side. Swift calls three things:
 * - `OpaqIos.shared.start()` from `application(_:didFinishLaunchingWithOptions:)`, including
 *   background relaunches by significant-change monitoring;
 * - `OpaqIos.shared.handleUrl(url:)` for `opaq://join` links;
 * - `OpaqIosKt.MainViewController()` for the screen.
 */
@OptIn(ExperimentalForeignApi::class)
object OpaqIos {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: Engine by lazy {
        Engine(
            store = IosStateStore(),
            scope = scope,
            now = ::epochSeconds,
            // A Kotlin String passed through C varargs isn't bridged to NSString and crashes; cast it.
            log = { NSLog("Opaq: %@", it as NSString) },
            deviceName = UIDevice.currentDevice.model,
            // Time since boot: changing the date can't shorten a PIN lockout.
            elapsed = { NSProcessInfo.processInfo.systemUptime.toLong() },
            historyFiles = IosHistoryFiles(),
        ).also { it.start() }
    }

    /** Created on first use, on the main thread, as CoreLocation requires. */
    val location: IosLocationService by lazy { IosLocationService(engine, scope, notifier) }

    /** Join/leave notifications and the monthly sharing reminder. */
    val notifier: IosNotifier by lazy { IosNotifier(engine, scope) }

    internal val platform: IosPlatform by lazy { IosPlatform(location) }

    internal val incomingLink = MutableStateFlow<String?>(null)
    private var unlockObserver: NSObjectProtocol? = null
    private var started = false
    private var networkUp: Boolean? = null
    private var cover: UIView? = null

    /**
     * Starts relays and, if sharing is on, location updates. After a reboot iOS can relaunch the
     * app before the phone's first unlock, when saved state can't be read yet; then this waits
     * for the unlock instead of starting with an empty state. Call on the main thread.
     */
    fun start() {
        if (!UIApplication.sharedApplication.isProtectedDataAvailable()) {
            if (unlockObserver == null) {
                unlockObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    UIApplicationProtectedDataDidBecomeAvailable,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                ) { _ ->
                    unlockObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
                    unlockObserver = null
                    start()
                }
            }
            return
        }
        location.update()
        if (started) return
        started = true
        watchNetwork()
        coverInAppSwitcher()
        notifier // follow notices from now on, even if the screen never opens (background relaunch)
    }

    /** The network came back: reconnect to relays now instead of after the retry wait, as Android does. */
    private fun watchNetwork() {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            val up = nw_path_get_status(path) == nw_path_status_satisfied
            if (up && networkUp == false) engine.networkAvailable()
            networkUp = up
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    /**
     * "Hide in recent apps": covers the screen whenever Opaq stops being the active app, so the
     * app switcher's snapshot shows a blank page instead of the map. (iOS gives apps no way to block
     * screenshots, unlike Android's FLAG_SECURE.)
     */
    private fun coverInAppSwitcher() {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationWillResignActiveNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
            if (engine.state.value.hideInRecents && cover == null) {
                val window = keyWindow() ?: return@addObserverForName
                cover = UIView(frame = window.bounds).apply {
                    backgroundColor = UIColor.systemBackgroundColor
                    autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                    window.addSubview(this)
                }
            }
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
            cover?.removeFromSuperview()
            cover = null
        }
    }

    private fun keyWindow(): UIWindow? = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }

    /** Handles an `opaq://join…` or shared web invite link. Returns false for links that aren't Opaq's. */
    fun handleUrl(url: String): Boolean {
        val ours = InviteLinks.SCHEMES.any { url.startsWith("$it://") } ||
            url.startsWith(InviteLinks.WEB_PREFIX)
        if (!ours) return false
        incomingLink.value = url
        return true
    }
}

/** The app's only screen: the shared Compose UI. */
@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController(): UIViewController = ComposeUIViewController { IosRoot() }

@Composable
private fun IosRoot() {
    val app = OpaqIos
    val platform = app.platform
    val location = app.location
    val link by app.incomingLink.collectAsState()
    val authorization by location.authorization.collectAsState()
    val message by platform.message.collectAsState()
    val showIndicator by location.showIndicator.collectAsState()
    val sharingSettings = remember(authorization, showIndicator) { platform.sharingSettings(location.hasAlways, showIndicator) }
    val state by app.engine.state.collectAsState()
    // Ask about notifications once someone can join or leave, after the location prompt (iOS shows
    // one system prompt at a time).
    val askNotifications = state.circles.isNotEmpty() && authorization != kCLAuthorizationStatusNotDetermined
    LaunchedEffect(askNotifications) { if (askNotifications) app.notifier.requestPermission() }

    Box(Modifier.fillMaxSize()) {
        OpaqScreens(
            engine = app.engine,
            scope = app.scope,
            platform = platform,
            hasLocationPermission = location.hasPermission,
            onRequestLocation = location::requestPermission,
            sharingSettings = sharingSettings,
            incomingLink = link,
            onLinkHandled = { app.incomingLink.value = null },
        )
        message?.let { text ->
            LaunchedEffect(text) {
                delay(3_000)
                platform.clearMessage(text)
            }
            Surface(
                color = Color(0xEE1F1F1F),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            ) {
                Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}
