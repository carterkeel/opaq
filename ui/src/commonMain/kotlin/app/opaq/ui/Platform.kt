package app.opaq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The few things the shared screens need from the phone they run on. Android implements this in
 * `androidApp`, iOS in `IosPlatform` (ui/src/iosMain).
 */
interface PlatformBridge {
    /** A short, non-blocking message (a toast on Android). */
    fun showMessage(text: String)

    /** Copies [text] and lets the user know it was copied. */
    fun copyToClipboard(label: String, text: String)

    /** Opens the system share sheet for [text]. */
    fun shareText(text: String, title: String)

    /** Starts background sharing if sharing is on and location access was granted. */
    fun ensureSharingService()

    /**
     * The locate button: take a fresh GPS fix and, if it shows the shared position was off, send it
     * now (MotionPolicy.requestRefresh). Does nothing while sharing is off.
     */
    fun refreshMyLocation() {}

    /** Map apps that can give directions (#150), by the name shown to people. Empty if none can be opened. */
    val directionsApps: List<String> get() = emptyList()

    /** Opens [app] (one of [directionsApps]) with directions to [lat], [lon], labelled [name] where the app allows. */
    fun openDirections(app: String, lat: Double, lon: Double, name: String) {}

    /** [app]'s own icon as installed on this phone, if the platform can read it; null draws a plain one. */
    fun directionsAppIcon(app: String): androidx.compose.ui.graphics.ImageBitmap? = null

    /** First line of an exported diagnostic log (#154): app version and phone model, nothing personal. */
    val diagnosticsHeader: String get() = "Opaq"

    /** Opens a web page in the browser, such as the privacy policy (#159). */
    fun openUrl(url: String) {}

    /**
     * Lets the user pick a photo, then crops it square and shrinks it to a small JPEG
     * (under [app.opaq.core.protocol.CircleCrypto.MAX_PHOTO_CHARS] once base64-encoded).
     * Calls [onResult] with the base64 JPEG, or null if cancelled or unsupported.
     */
    fun pickProfilePhoto(onResult: (String?) -> Unit)

    /** Opens the camera to scan a QR code. Calls [onResult] with its text, or null if cancelled or unsupported. */
    fun scanQrCode(onResult: (String?) -> Unit)

    /**
     * Lets the user save [text] as a file (for backups). Calls [onDone] with true once written.
     * Platforms without a file picker yet fall back to the share sheet.
     */
    fun saveTextFile(fileName: String, text: String, onDone: (Boolean) -> Unit) {
        shareText(text, fileName)
        onDone(true)
    }

    /** What "Hide in recent apps" does on this phone. */
    val hideInRecentsDetail: String
        get() = "Blank Opaq's screen in the app switcher and screenshots"

    /** Whether relays can be routed through Tor here (Android with Orbot). */
    val supportsTor: Boolean get() = false

    /** The launcher icon in use (an [AppIconChoice] id), or null where the app can't change it. */
    val appIcon: String? get() = null

    /** Switches the launcher icon to [AppIconChoice] [id]. */
    fun setAppIcon(id: String) {}

    /** How the phone shows that sharing is on, for the setup screen ("A notification shows…"). */
    val sharingIndicatorText: String
        get() = "A notification shows while you're sharing"

    /**
     * The phone's face or fingerprint unlock by name ("Face ID", "Touch ID", "Fingerprint"), or null
     * when the phone has none set up (or it's too old: Android before 11).
     */
    val biometricName: String? get() = null

    /**
     * Turns face or fingerprint unlock on for Opaq: remembers which faces or fingers the phone has
     * now, then asks for one. Calls [onResult] on the main thread.
     */
    fun enrollBiometrics(reason: String, onResult: (BiometricResult) -> Unit) = onResult(BiometricResult.UNAVAILABLE)

    /**
     * Asks for the owner's face or fingerprint. [BiometricResult.CHANGED] means a face or finger was
     * added or removed since [enrollBiometrics]: someone else's may be on the phone, so the caller
     * turns biometric unlock off and asks for the PIN.
     */
    fun checkBiometrics(reason: String, onResult: (BiometricResult) -> Unit) = onResult(BiometricResult.UNAVAILABLE)

    /** Forgets what [enrollBiometrics] remembered. */
    fun forgetBiometrics() {}

    /**
     * Asks for the phone's own unlock (its passcode, or its face or fingerprint): before the first
     * Opaq PIN is set, so someone who picks up an unlocked phone can't choose a PIN and make a backup.
     * [BiometricResult.UNAVAILABLE] means the phone has no screen lock, so there's nothing to check.
     */
    fun confirmPhoneOwner(reason: String, onResult: (BiometricResult) -> Unit) = onResult(BiometricResult.UNAVAILABLE)

    /** Lets the user pick a text file (a backup) and calls [onResult] with its contents, or null. */
    fun openTextFile(onResult: (String?) -> Unit) {
        showMessage("Opening files isn't available on this device yet")
        onResult(null)
    }
}

/** How a face or fingerprint check went. */
enum class BiometricResult {
    /** It was the owner. */
    OK,
    /** Cancelled, failed, or the user chose to type the PIN. */
    CANCELLED,
    /** The phone's enrolled faces or fingers changed since biometric unlock was turned on. */
    CHANGED,
    /** No face or fingerprint unlock on this phone. */
    UNAVAILABLE,
}

/** A platform-specific row under Settings → Sharing, such as background access, battery optimisation or a switch. */
data class SettingItem(
    val title: String,
    val detail: String,
    /** Button label, or null when nothing needs fixing. */
    val action: String?,
    /** When set, the row shows a switch in this position instead of a button. */
    val checked: Boolean? = null,
    /** The row's icon; each row in a list gets its own. Before the lambdas, so a trailing lambda is still [onAction]. */
    val icon: androidx.compose.ui.graphics.vector.ImageVector = AppIcons.Info,
    val onCheckedChange: (Boolean) -> Unit = {},
    val onAction: () -> Unit = {},
)

val LocalPlatform = staticCompositionLocalOf<PlatformBridge> { error("No PlatformBridge provided") }

/** Seconds since the Unix epoch. */
expect fun epochSeconds(): Long

/**
 * Map options for every map in the app: on Android the map draws through a TextureView, shown in
 * the same frame as the rest of the screen. Faces drawn over it keep up while it pans, rounded
 * corners clip it, and it slides and scrolls with the screen it's on (a SurfaceView does none of these).
 */
expect fun overlayMapUiOptions(): org.maplibre.compose.map.MapUiOptions

/** Milliseconds since the Unix epoch, for smooth animation. */
expect fun epochMillis(): Long

/** Start of the phone's local calendar day that contains [epochSeconds]. */
expect fun localDayStart(epochSeconds: Long): Long

/** A time of day in the phone's style, such as "8:05 AM" or "08:05". */
expect fun formatClock(epochSeconds: Long): String

/** A short date in the phone's language, such as "Mon, Nov 9". */
expect fun formatDay(epochSeconds: Long): String

/** Whether to show speeds in mph rather than km/h, from the phone's region. */
expect fun regionUsesMiles(): Boolean

/** Decodes a JPEG or PNG into an image Compose can draw, or null if it isn't a valid image. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Handles the system back gesture or button while [enabled]. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * Makes the status and navigation bar icons readable on the app's own colours ([dark]), which can
 * differ from the phone's setting. [followSystem]: the app is following the phone.
 */
@Composable
expect fun SystemBarsTheme(dark: Boolean, followSystem: Boolean)

/** Regions that show road speeds in mph. */
internal val MILES_REGIONS = setOf("US", "GB", "LR", "MM")
