package app.opaq.ios

import app.opaq.ui.PlatformBridge
import app.opaq.ui.SettingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.alternateIconName
import platform.UIKit.popoverPresentationController
import platform.UIKit.setAlternateIconName
import platform.UIKit.supportsAlternateIcons

/** iOS side of the shared screens. */
class IosPlatform(private val location: IosLocationService) : PlatformBridge {
    private val _message = MutableStateFlow<String?>(null)
    /** The message to show at the bottom of the screen, if any; iOS has no toasts. */
    val message: StateFlow<String?> = _message.asStateFlow()

    override fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage(text: String) {
        if (_message.value == text) _message.value = null
    }

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
        showMessage("Copied")
    }

    override fun shareText(text: String, title: String) {
        val presenter = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        // iPad shows the share sheet as a popover, which needs an anchor.
        sheet.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(sheet, animated = true, completion = null)
    }

    override fun ensureSharingService() = location.update()

    override fun refreshMyLocation() = location.refreshNow()

    private val photoPicker = IosPhotoPicker()

    override fun pickProfilePhoto(onResult: (String?) -> Unit) {
        val presenter = topViewController() ?: return onResult(null)
        photoPicker.pick(presenter, onResult)
    }

    private val qrScanner = IosQrScanner(::showMessage)

    override fun scanQrCode(onResult: (String?) -> Unit) {
        val presenter = topViewController() ?: return onResult(null)
        qrScanner.scan(presenter, onResult)
    }

    // iOS has no persistent notification; the system location arrow is the sign that sharing is on.
    override val sharingIndicatorText = "The location arrow shows at the top of the screen while you're sharing"

    // iOS lets apps cover their app-switcher snapshot, but not block screenshots.
    override val hideInRecentsDetail = "Blank Opaq's screen in the app switcher"

    /** Alternate icon sets in Assets.xcassets, by app icon id. Dark A on white is the main AppIcon. */
    private val alternateIcons = mapOf("twotone" to "AppIcon-TwoTone", "oversized" to "AppIcon-Oversized")

    override val appIcon: String?
        get() {
            val app = UIApplication.sharedApplication
            if (!app.supportsAlternateIcons) return null
            val name = app.alternateIconName ?: return "white"
            return alternateIcons.entries.firstOrNull { it.value == name }?.key ?: "white"
        }

    override fun setAppIcon(id: String) {
        val app = UIApplication.sharedApplication
        if (!app.supportsAlternateIcons || id == appIcon) return
        // iOS shows its own "You have changed the icon" alert.
        app.setAlternateIconName(alternateIcons[id], completionHandler = null)
    }

    private val filePicker = IosFilePicker(::showMessage)

    override fun saveTextFile(fileName: String, text: String, onDone: (Boolean) -> Unit) {
        val presenter = topViewController() ?: return onDone(false)
        filePicker.save(presenter, fileName, text, onDone)
    }

    override fun openTextFile(onResult: (String?) -> Unit) {
        val presenter = topViewController() ?: return onResult(null)
        filePicker.open(presenter, onResult)
    }

    /**
     * Settings → Sharing rows: "Always" access keeps sharing going in the background, and the
     * blue location pill can be hidden (iOS only allows that with Always access).
     */
    fun sharingSettings(hasAlways: Boolean, showIndicator: Boolean): List<SettingItem> = listOf(
        SettingItem(
            "Allow all the time",
            if (hasAlways) "Granted. Sharing continues in the background and after a restart."
            else "Needed to keep sharing while Opaq is in the background. Choose Location → Always.",
            if (hasAlways) null else "Open",
        ) {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null)
            }
        },
        SettingItem(
            "Show blue location pill",
            when {
                showIndicator -> "Shows at the top of the screen while Opaq shares in the background"
                hasAlways -> "Hidden. Outside Opaq, nothing at the top of the screen shows that you're sharing."
                else -> "iOS keeps showing it until location access is set to Always"
            },
            action = null,
            checked = showIndicator,
            onCheckedChange = location::setShowIndicator,
        ),
    )

    private fun topViewController(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
