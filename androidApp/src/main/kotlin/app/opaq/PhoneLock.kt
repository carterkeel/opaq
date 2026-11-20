package app.opaq

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import app.opaq.ui.BiometricResult

/**
 * The phone's own unlock: its PIN, pattern or password, or its fingerprint or face. Asked before the
 * first Opaq PIN is set (see [app.opaq.ui.PlatformBridge.confirmPhoneOwner]).
 *
 * Android 11 and later use the system prompt. 8 to 10 use the lock screen's confirmation screen,
 * started through [confirmScreen] (the activity's launcher, which answers whether it was confirmed):
 * Android 10's prompt loses the answer when you switch from a fingerprint to the phone's PIN.
 */
class PhoneLock(
    private val context: Context,
    private val confirmScreen: (Intent, (Boolean) -> Unit) -> Unit,
) {
    fun confirm(reason: String, onResult: (BiometricResult) -> Unit) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) return onResult(BiometricResult.UNAVAILABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) prompt(reason, onResult) else lockScreen(keyguard, reason, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun prompt(reason: String, onResult: (BiometricResult) -> Unit) {
        val executor = context.mainExecutor
        var answered = false
        val answer = { r: BiometricResult -> if (!answered) { answered = true; onResult(r) } }
        val builder = BiometricPrompt.Builder(context).setTitle(reason).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        val prompt = runCatching { builder.build() }.getOrElse { return answer(BiometricResult.CANCELLED) }
        prompt.authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = answer(BiometricResult.OK)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = answer(BiometricResult.CANCELLED)
        })
    }

    private fun lockScreen(keyguard: KeyguardManager, reason: String, onResult: (BiometricResult) -> Unit) {
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(reason, null) ?: return onResult(BiometricResult.UNAVAILABLE)
        confirmScreen(intent) { ok -> onResult(if (ok) BiometricResult.OK else BiometricResult.CANCELLED) }
    }
}
