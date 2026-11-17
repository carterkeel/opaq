package app.quietmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

private fun dateOf(epochSeconds: Long) = NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())

actual fun localDayStart(epochSeconds: Long): Long =
    NSCalendar.currentCalendar.startOfDayForDate(dateOf(epochSeconds)).timeIntervalSince1970.toLong()

actual fun formatClock(epochSeconds: Long): String = NSDateFormatter().run {
    timeStyle = NSDateFormatterShortStyle
    dateStyle = NSDateFormatterNoStyle
    stringFromDate(dateOf(epochSeconds))
}

actual fun formatDay(epochSeconds: Long): String = NSDateFormatter().run {
    setLocalizedDateFormatFromTemplate("EEEMMMd")
    stringFromDate(dateOf(epochSeconds))
}

actual fun regionUsesMiles(): Boolean = NSLocale.currentLocale.countryCode in MILES_REGIONS

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Throwable) {
        null
    }

/** iOS has no system back button; back navigation is the on-screen back arrow (swipe-back may come later). */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
