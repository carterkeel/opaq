package app.quietmap.core.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object PlatformCrypto {
    private val random = SecureRandom()

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    actual fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    actual fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    actual fun argon2id(password: String, salt: ByteArray, memoryKiB: Int, iterations: Int, length: Int): ByteArray {
        require(password.all { it.code < 128 }) { "argon2id input must be ASCII" }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(length)
        generator.generateBytes(password.encodeToByteArray(), out)
        return out
    }
}
