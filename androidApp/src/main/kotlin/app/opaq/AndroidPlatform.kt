package app.opaq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
) : PlatformBridge {
    private val app = OpaqApp.from(context)
    /** Face or fingerprint unlock needs Android 11 or later. */
    private val biometrics = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) Biometrics(context) else null

    override val biometricName: String? get() = biometrics?.name
    override fun enrollBiometrics(reason: String, onResult: (BiometricResult) -> Unit) =
        biometrics?.enroll(reason, onResult) ?: onResult(BiometricResult.UNAVAILABLE)
    override fun checkBiometrics(reason: String, onResult: (BiometricResult) -> Unit) =
        biometrics?.check(reason, onResult) ?: onResult(BiometricResult.UNAVAILABLE)
    override fun forgetBiometrics() { biometrics?.forget() }

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
            ) {
                requestBackgroundLocation()
            },
            SettingItem(
                "Battery saver for Opaq",
                if (ignoringBattery) "Off. Updates stay reliable." else "On, so some phones pause updates. Tap Turn off, then choose “Allow”.",
                if (ignoringBattery) null else "Turn off",
            ) {
                try {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
    }
}
