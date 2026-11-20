package app.opaq.core.protocol

import app.opaq.core.crypto.Nip44
import app.opaq.core.crypto.PlatformCrypto
import app.opaq.core.crypto.fromBase64Url
import app.opaq.core.crypto.toBase64Url
import app.opaq.core.crypto.toHex
import app.opaq.core.model.AppState
import app.opaq.core.model.Circle
import app.opaq.core.model.Contact
import app.opaq.core.model.PendingJoin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a backup carries: your identity key, profile, settings, circles (with their keys) and the
 * names and verifications you set for people. Other people's locations and photos aren't included;
 * they arrive again from the relays.
 */
@Serializable
data class BackupData(
    val v: Int = 1,
    val identitySecretHex: String,
    val myName: String,
    val myColor: Int? = null,
    val myPhoto: String? = null,
    val relays: List<String>,
    /** [AppState.relayDefaultsVersion]; missing in older backups, whose relay lists then get the newer defaults. */
    val relayDefaultsVersion: Int = 0,
    val shareBattery: Boolean = false,
    val showPhotos: Boolean = true,
    val circles: List<Circle>,
    val contacts: Map<String, Contact> = emptyMap(),
    val pendingJoins: List<PendingJoin> = emptyList(),
    val lastCircleId: String? = null,
)

/** Key-derivation settings stored in a v2 backup's second line. */
@Serializable
private data class BackupKdf(val kdf: String, val m: Int, val t: Int, val salt: String)

/**
 * Passphrase-protected backups for moving to a new phone.
 *
 * v2 (written now): the passphrase goes through Argon2id (64 MiB, 3 passes, one lane, random
 * 16-byte salt), a memory-hard function that makes guessing passphrases offline with GPUs slow and
 * expensive. The 32-byte result encrypts the backup with NIP-44 v2. File format:
 *
 * ```
 * opaq-backup-v2
 * {"kdf":"argon2id","m":65536,"t":3,"salt":"<base64url>"}
 * <NIP-44 payload>
 * ```
 *
 * Files from before the app was renamed Opaq (`quietmap-backup-v2`, the same format) and v1 files
 * (PBKDF2-HMAC-SHA256, 600,000 iterations) can still be restored.
 */
object Backups {
    const val HEADER = "opaq-backup-v2"
    /** The same format, made before the app was renamed Opaq. */
    const val HEADER_V2_OLD = "quietmap-backup-v2"
    const val HEADER_V1 = "quietmap-backup-v1"
    const val ITERATIONS_V1 = 600_000
    const val ARGON2_MEMORY_KIB = 65_536
    const val ARGON2_ITERATIONS = 3
    const val MIN_PASSPHRASE = 8
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun fromState(s: AppState): BackupData = BackupData(
        identitySecretHex = s.identitySecretHex,
        myName = s.myName,
        myColor = s.myColor,
        myPhoto = s.myPhoto,
        relays = s.relays,
        relayDefaultsVersion = s.relayDefaultsVersion,
        shareBattery = s.shareBattery,
        showPhotos = s.showPhotos,
        // Only current keys: replaced keys and control keys of earlier epochs never go into a backup.
        circles = s.circles.map {
            it.copy(previousKeyHex = null, previousEpoch = null, previousValidUntil = null, previousKeyDay = null, oldControls = emptyList(), pendingAcks = emptyList(), handoverSecretHex = null, handoverUntil = 0)
        },
        contacts = s.contacts,
        pendingJoins = s.pendingJoins,
        lastCircleId = s.lastCircleId,
    )

    /** Fresh app state from a backup. Sharing starts paused so nothing goes out before you check. */
    fun toState(b: BackupData): AppState = AppState(
        identitySecretHex = b.identitySecretHex,
        myName = b.myName,
        myColor = b.myColor,
        myPhoto = b.myPhoto,
        relays = b.relays,
        relayDefaultsVersion = b.relayDefaultsVersion,
        sharing = false,
        shareBattery = b.shareBattery,
        showPhotos = b.showPhotos,
        circles = b.circles,
        contacts = b.contacts,
        pendingJoins = b.pendingJoins,
        lastCircleId = b.lastCircleId,
        setupDone = true,
    )

    /** Argon2id needs the same bytes on every platform: hash the passphrase's UTF-8, hex-encoded. */
    private fun argonKey(passphrase: String, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray =
        PlatformCrypto.argon2id(passphrase.encodeToByteArray().toHex(), salt, memoryKiB, iterations, 32)

    fun encrypt(data: BackupData, passphrase: String): String {
        require(passphrase.length >= MIN_PASSPHRASE) { "Use a passphrase of at least $MIN_PASSPHRASE characters" }
        var plain = json.encodeToString(BackupData.serializer(), data)
        // NIP-44 carries up to 64 KB; a large photo is the only thing that could push past it.
        if (plain.encodeToByteArray().size > 65_000) plain = json.encodeToString(BackupData.serializer(), data.copy(myPhoto = null))
        val salt = PlatformCrypto.randomBytes(16)
        val key = argonKey(passphrase, salt, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS)
        val kdf = json.encodeToString(BackupKdf.serializer(), BackupKdf("argon2id", ARGON2_MEMORY_KIB, ARGON2_ITERATIONS, salt.toBase64Url()))
        return "$HEADER\n$kdf\n${Nip44.encrypt(plain, key)}\n"
    }

    /** Throws [IllegalArgumentException] with a readable message if the file or passphrase is wrong. */
    fun decrypt(text: String, passphrase: String): BackupData {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size == 3 && (lines[0] == HEADER || lines[0] == HEADER_V2_OLD || lines[0] == HEADER_V1)) { "That isn't an Opaq backup file" }
        val key = if (lines[0] == HEADER_V1) {
            val salt = try { lines[1].fromBase64Url() } catch (_: Throwable) { throw IllegalArgumentException("The backup file is damaged") }
            PlatformCrypto.pbkdf2Sha256(passphrase, salt, ITERATIONS_V1, 32)
        } else {
            val kdf = try { json.decodeFromString(BackupKdf.serializer(), lines[1]) } catch (_: Throwable) { throw IllegalArgumentException("The backup file is damaged") }
            // Bounds stop a crafted file from asking for absurd memory or time.
            require(kdf.kdf == "argon2id" && kdf.m in 8_192..262_144 && kdf.t in 1..10) { "This backup uses settings Opaq doesn't support" }
            val salt = try { kdf.salt.fromBase64Url() } catch (_: Throwable) { throw IllegalArgumentException("The backup file is damaged") }
            // Exactly 16 bytes: libsodium (iOS) only reads 16, so a longer salt would give a different key.
            require(salt.size == 16) { "The backup file is damaged" }
            argonKey(passphrase, salt, kdf.m, kdf.t)
        }
        val plain = try { Nip44.decrypt(lines[2], key) } catch (_: Throwable) { throw IllegalArgumentException("Wrong passphrase, or the backup file is damaged") }
        return json.decodeFromString(BackupData.serializer(), plain)
    }

    /** Writes a v1 file. Only for tests that check old backups still restore. */
    internal fun encryptV1ForTests(data: BackupData, passphrase: String): String {
        val salt = PlatformCrypto.randomBytes(16)
        val key = PlatformCrypto.pbkdf2Sha256(passphrase, salt, ITERATIONS_V1, 32)
        return "$HEADER_V1\n${salt.toBase64Url()}\n${Nip44.encrypt(json.encodeToString(BackupData.serializer(), data), key)}\n"
    }
}
