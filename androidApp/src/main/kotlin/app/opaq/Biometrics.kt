package app.opaq

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import app.opaq.ui.BiometricResult
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Face or fingerprint unlock with the system prompt (Android 11 and later, strong biometrics only).
 *
 * A check only passes if the prompt unlocks a Keystore key that needs a biometric to use and that
 * Android deletes when a fingerprint or face is added. So a check can't be faked from outside the
 * prompt, and someone who adds their own fingerprint (knowing the phone's passcode) doesn't get in:
 * the check reports [BiometricResult.CHANGED] and the PIN is asked for instead.
 *
 * [context] must be the activity the prompt shows over.
 */
@RequiresApi(Build.VERSION_CODES.R)
class Biometrics(private val context: Context) {
    val name: String?
        get() {
            val manager = context.getSystemService(BiometricManager::class.java) ?: return null
            if (manager.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return null
            val pm = context.packageManager
            val face = pm.hasSystemFeature(PackageManager.FEATURE_FACE)
            val finger = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            return when {
                face && !finger -> "Face unlock"
                finger && !face -> "Fingerprint"
                else -> "Fingerprint or face"
            }
        }

    fun enroll(reason: String, onResult: (BiometricResult) -> Unit) {
        if (name == null) return onResult(BiometricResult.UNAVAILABLE)
        forget()
        val created = runCatching { createKey() }.isSuccess
        if (!created) return onResult(BiometricResult.UNAVAILABLE)
        check(reason, onResult)
    }

    fun check(reason: String, onResult: (BiometricResult) -> Unit) {
        if (name == null) return onResult(BiometricResult.UNAVAILABLE)
        val cipher = try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return onResult(BiometricResult.CHANGED)
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            forget()
            return onResult(BiometricResult.CHANGED)
        } catch (_: Exception) {
            return onResult(BiometricResult.CANCELLED)
        }
        val executor = context.mainExecutor
        var answered = false
        val answer = { r: BiometricResult -> if (!answered) { answered = true; onResult(r) } }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(reason)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton("Use PIN", executor) { _, _ -> answer(BiometricResult.CANCELLED) }
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // The key only works after a real biometric match.
                val ok = runCatching { result.cryptoObject?.cipher?.doFinal(ByteArray(16)) }.getOrNull() != null
                answer(if (ok) BiometricResult.OK else BiometricResult.CANCELLED)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = answer(BiometricResult.CANCELLED)
        })
    }

    fun forget() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun createKey() {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opaq-biometric-unlock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
