package app.opaq.ios

import app.opaq.ui.BiometricResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeOpticID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Face ID, Touch ID or Optic ID through LocalAuthentication (biometrics only, never the phone's
 * passcode: someone who knows the passcode shouldn't get past Opaq's PIN with it).
 *
 * Turning it on records which faces or fingers the phone has (`evaluatedPolicyDomainState`). If they
 * change later, someone may have added their own, so checks report [BiometricResult.CHANGED] and the
 * PIN is asked for instead.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBiometrics {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    val name: String?
        get() {
            val context = LAContext()
            if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) return null
            return when (context.biometryType) {
                LABiometryTypeFaceID -> "Face ID"
                LABiometryTypeTouchID -> "Touch ID"
                LABiometryTypeOpticID -> "Optic ID"
                else -> null
            }
        }

    fun enroll(reason: String, onResult: (BiometricResult) -> Unit) {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) return onResult(BiometricResult.UNAVAILABLE)
        evaluate(context, reason) { ok ->
            if (ok) context.evaluatedPolicyDomainState?.let { defaults.setObject(it.base64(), STATE_KEY) }
            onResult(if (ok) BiometricResult.OK else BiometricResult.CANCELLED)
        }
    }

    fun check(reason: String, onResult: (BiometricResult) -> Unit) {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) return onResult(BiometricResult.UNAVAILABLE)
        val saved = defaults.stringForKey(STATE_KEY)
        val now = context.evaluatedPolicyDomainState?.base64()
        if (saved == null || saved != now) {
            forget()
            return onResult(BiometricResult.CHANGED)
        }
        evaluate(context, reason) { ok -> onResult(if (ok) BiometricResult.OK else BiometricResult.CANCELLED) }
    }

    fun forget() = defaults.removeObjectForKey(STATE_KEY)

    /**
     * The phone's own unlock: Face ID or Touch ID, or the passcode. Only for setting the first Opaq
     * PIN; Opaq's own biometric unlock above never accepts the passcode.
     */
    fun confirmOwner(reason: String, onResult: (BiometricResult) -> Unit) {
        val context = LAContext()
        // No passcode set: nothing to check.
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) return onResult(BiometricResult.UNAVAILABLE)
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { ok, _ ->
            dispatch_async(dispatch_get_main_queue()) { onResult(if (ok) BiometricResult.OK else BiometricResult.CANCELLED) }
        }
    }

    private fun evaluate(context: LAContext, reason: String, done: (Boolean) -> Unit) {
        context.localizedFallbackTitle = "Use PIN"
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, reason) { ok, _ ->
            dispatch_async(dispatch_get_main_queue()) { done(ok) }
        }
    }

    private fun NSData.base64() = base64EncodedStringWithOptions(0u)

    private companion object {
        const val STATE_KEY = "opaqBiometricState"
    }
}
