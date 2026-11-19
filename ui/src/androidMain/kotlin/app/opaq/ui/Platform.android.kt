package app.opaq.ui

import android.graphics.BitmapFactory
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Locale

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

actual fun epochMillis(): Long = System.currentTimeMillis()

private fun zoned(epochSeconds: Long) = java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault())

actual fun localDayStart(epochSeconds: Long): Long =
    zoned(epochSeconds).toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()

actual fun formatClock(epochSeconds: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(zoned(epochSeconds))

actual fun formatDay(epochSeconds: Long): String =
    java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")).format(zoned(epochSeconds))

actual fun regionUsesMiles(): Boolean = Locale.getDefault().country in MILES_REGIONS

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Light or dark bar icons for the app's own theme, with the same scrims Android uses by default. */
@Composable
actual fun SystemBarsTheme(dark: Boolean, followSystem: Boolean) {
    val view = LocalView.current
    LaunchedEffect(dark) {
        val activity = view.context.activity() as? ComponentActivity ?: return@LaunchedEffect
        val lightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
        )
    }
}
