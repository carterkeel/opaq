package app.opaq

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import app.opaq.ui.AppIcons
import app.opaq.ui.BiometricResult
import app.opaq.ui.PlatformBridge
import app.opaq.ui.SettingItem

/**
 * Android side of the shared screens. [context] should be the activity, so the share sheet opens
 * over it. Photo picking and QR scanning go through launchers the activity registers.
 */
class AndroidPlatform(
    private val context: Context,
    private val photoPicker: ((String?) -> Unit) -> Unit,
    private val qrScanner: ((String?) -> Unit) -> Unit,
    private val fileSaver: (name: String, text: String, onDone: (Boolean) -> Unit) -> Unit,
    private val fileOpener: ((String?) -> Unit) -> Unit,
    private val requestBackgroundLocation: () -> Unit,
    confirmScreen: (android.content.Intent, (Boolean) -> Unit) -> Unit,
) : PlatformBridge {
    private val app = OpaqApp.from(context)
    /** Face or fingerprint unlock needs Android 11 or later (each use checks, so lint can see it). */
    private val biometrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Biometrics(context) else null

    override val biometricName: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) biometrics?.name else null
    override fun enrollBiometrics(reason: String, onResult: (BiometricResult) -> Unit) {
        val b = biometrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && b != null) b.enroll(reason, onResult) else onResult(BiometricResult.UNAVAILABLE)
    }
    override fun checkBiometrics(reason: String, onResult: (BiometricResult) -> Unit) {
        val b = biometrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && b != null) b.check(reason, onResult) else onResult(BiometricResult.UNAVAILABLE)
    }
    override fun forgetBiometrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) biometrics?.forget()
    }

    private val phoneLock = PhoneLock(context, confirmScreen)
    override fun confirmPhoneOwner(reason: String, onResult: (BiometricResult) -> Unit) = phoneLock.confirm(reason, onResult)

    override fun showMessage(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    override fun copyToClipboard(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    override fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    override fun ensureSharingService() = app.ensureSharingService()

    override fun refreshMyLocation() = app.refreshMyLocation()

    override val directionsApps = listOf(GOOGLE_MAPS, OTHER_MAPS)

    override val diagnosticsHeader: String
        get() = "Opaq ${app.appVersion()} on Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    override fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            showMessage("No browser can open this")
        }
    }

    override fun directionsAppIcon(app: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (app != GOOGLE_MAPS) return null
        return try {
            val icon = context.packageManager.getApplicationIcon(GOOGLE_MAPS_PACKAGE)
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 96, 96)
            icon.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun openDirections(app: String, lat: Double, lon: Double, name: String) {
        val to = "$lat,$lon"
        val intent = when (app) {
            // Google Maps' directions link: the app if it's installed, otherwise the browser.
            GOOGLE_MAPS -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$to"))
                .setPackage(GOOGLE_MAPS_PACKAGE)
            // Any map app on the phone, with the spot marked and named.
            else -> Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$to?q=$to(${Uri.encode(name)})")), "Directions to $name")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$to")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                showMessage("No map app can open this")
            }
        }
    }

    override val supportsTor: Boolean get() = true

    /** The manifest's launcher entries (activity-alias), by app icon id. "white" is enabled by default. */
    private val iconAliases = mapOf(
        "white" to "IconWhite",
        "twotone" to "IconTwoTone",
        "oversized" to "IconOversized",
    )

    // Aliases are named in the code's package (app.opaq), but belong to this build's application id.
    private fun alias(name: String) =
        ComponentName(context.packageName, MainActivity::class.java.name.substringBeforeLast('.') + "." + name)

    override val appIcon: String
        get() {
            val pm = context.packageManager
            return iconAliases.entries.firstOrNull { (id, name) ->
                when (pm.getComponentEnabledSetting(alias(name))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> id == "white"
                    else -> false
                }
            }?.key ?: "white"
        }

    override fun setAppIcon(id: String) {
        val chosen = iconAliases[id] ?: return
        if (id == appIcon) return
        val pm = context.packageManager
        // Turn the new entry on before the others off, so there's always one icon to open the app with.
        pm.setComponentEnabledSetting(alias(chosen), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        iconAliases.filterKeys { it != id }.values.forEach {
            pm.setComponentEnabledSetting(alias(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    override fun pickProfilePhoto(onResult: (String?) -> Unit) = photoPicker(onResult)

    override fun scanQrCode(onResult: (String?) -> Unit) = qrScanner(onResult)

    override fun saveTextFile(fileName: String, text: String, onDone: (Boolean) -> Unit) = fileSaver(fileName, text, onDone)

    override fun openTextFile(onResult: (String?) -> Unit) = fileOpener(onResult)

    /** Settings → Sharing rows: background location and battery optimisation. */
    fun sharingSettings(hasBackgroundPermission: Boolean): List<SettingItem> {
        val ignoringBattery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return listOf(
            SettingItem(
                "Background location",
                if (hasBackgroundPermission) "On. Sharing keeps going in the background and after a restart."
                else "Off. Tap Turn on, then choose “Allow all the time” on the next screen.",
                if (hasBackgroundPermission) null else "Turn on",
                icon = AppIcons.Clock,
            ) {
                requestBackgroundLocation()
            },
            SettingItem(
                "Battery saver for Opaq",
                if (ignoringBattery) "Off. Updates stay reliable." else "On, so some phones pause updates. Tap Turn off, then choose “Allow”.",
                if (ignoringBattery) null else "Turn off",
                icon = AppIcons.Leaf,
            ) {
                try {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
    }

    private companion object {
        const val GOOGLE_MAPS = "Google Maps"
        const val OTHER_MAPS = "Another map app"
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
