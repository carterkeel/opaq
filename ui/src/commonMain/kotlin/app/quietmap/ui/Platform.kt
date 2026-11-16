package app.quietmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The few things the shared screens need from the phone they run on. Android implements this in
 * `androidApp`; iOS implements it in the iOS app.
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
     * Lets the user pick a photo, then crops it square and shrinks it to a small JPEG
     * (under [app.quietmap.core.protocol.CircleCrypto.MAX_PHOTO_CHARS] once base64-encoded).
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

    /** Whether relays can be routed through Tor here (Android with Orbot). */
    val supportsTor: Boolean get() = false

    /** Lets the user pick a text file (a backup) and calls [onResult] with its contents, or null. */
    fun openTextFile(onResult: (String?) -> Unit) {
        showMessage("Opening files isn't available on this device yet")
        onResult(null)
    }
}

/** A platform-specific row under Settings → Sharing, such as background access or battery optimisation. */
data class SettingItem(
    val title: String,
    val detail: String,
    /** Button label, or null when nothing needs fixing. */
    val action: String?,
    val onAction: () -> Unit = {},
)

val LocalPlatform = staticCompositionLocalOf<PlatformBridge> { error("No PlatformBridge provided") }

/** Seconds since the Unix epoch. */
expect fun epochSeconds(): Long

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

/** Regions that show road speeds in mph. */
internal val MILES_REGIONS = setOf("US", "GB", "LR", "MM")
