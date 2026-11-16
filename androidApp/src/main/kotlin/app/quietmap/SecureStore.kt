package app.quietmap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.quietmap.core.StateStore
import app.quietmap.core.StoredState
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the app state (including the identity key) encrypted with AES-256-GCM.
 * The AES key lives in the Android Keystore, in secure hardware where the phone has it,
 * and can't be exported. The file is excluded from backups in the manifest.
 *
 * Reading never creates a key and never treats a failure as "nothing saved": the engine only makes
 * a new identity for [StoredState.Missing], so a Keystore hiccup can't replace someone's identity.
 */
class SecureStore(context: Context) : StateStore {
    private val dir = context.noBackupFilesDir
    private val file = File(dir, "state.bin")
    private val tmp = File(dir, "state.bin.tmp")

    override fun read(): StoredState {
        // A first save interrupted before its rename leaves only the temporary file.
        val source = when {
            file.exists() -> file
            tmp.exists() -> tmp
            else -> return StoredState.Missing
        }
        val bytes = try {
            source.readBytes()
        } catch (e: IOException) {
            return StoredState.Unreadable("Couldn't read the saved data file", retryable = true)
        }
        if (bytes.size <= IV_BYTES) return StoredState.Unreadable("The saved data file is damaged", retryable = false)
        val key = try {
            existingKey()
        } catch (t: Throwable) {
            return StoredState.Unreadable("Android's secure key storage isn't available right now", retryable = true)
        } ?: return StoredState.Unreadable("The key that protects your saved data is missing from this phone", retryable = false)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
            StoredState.Found(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString())
        } catch (e: AEADBadTagException) {
            StoredState.Unreadable("The saved data is damaged or was encrypted with a different key", retryable = false)
        } catch (e: KeyPermanentlyInvalidatedException) {
            StoredState.Unreadable("The key that protects your saved data no longer works", retryable = false)
        } catch (t: Throwable) {
            StoredState.Unreadable("Android's secure key storage isn't available right now", retryable = true)
        }
    }

    @Synchronized
    override fun save(data: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(data.encodeToByteArray())
        tmp.writeBytes(out)
        // rename(2) replaces the old file atomically: a crash leaves either the old or the new state.
        if (!tmp.renameTo(file)) throw IOException("Couldn't save the app state")
    }

    /** Keeps an unreadable state file under a new name instead of overwriting it. */
    @Synchronized
    override fun setAside() {
        val stamp = System.currentTimeMillis()
        listOf(file, tmp).filter { it.exists() }.forEach { it.renameTo(File(dir, "${it.name}.unreadable-$stamp")) }
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "quietmap-state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
