package app.quietmap.ios

import app.quietmap.core.StoredState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosStateStoreTest {
    private val stateFile: NSURL = (NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL)
        .URLByAppendingPathComponent("Quietmap/state.bin")!!

    /** Test binaries get no Keychain (errSecNotAvailable), so keys live in memory here. */
    private class MemoryKeys : KeyStore {
        val items = mutableMapOf<String, ByteArray>()
        var locked = false
        override fun read(account: String) = when {
            locked -> KeyStore.Result.Error(-25308) // errSecInteractionNotAllowed: before first unlock
            else -> items[account]?.let { KeyStore.Result.Found(it) } ?: KeyStore.Result.Missing
        }
        override fun add(account: String, data: ByteArray) { items[account] = data }
    }

    private val keys = MemoryKeys()

    @Test
    fun savesAndLoadsAcrossInstances() {
        val secret = """{"identitySecretHex":"00ff","myName":"Ana ✓"}"""
        IosStateStore(keys).save(secret)
        assertEquals(secret, assertIs<StoredState.Found>(IosStateStore(keys).read()).data)

        val newer = """{"identitySecretHex":"00ff","myName":"Ana 2"}"""
        IosStateStore(keys).save(newer)
        assertEquals(newer, assertIs<StoredState.Found>(IosStateStore(keys).read()).data)
    }

    @Test
    fun fileOnDiskIsEncrypted() {
        IosStateStore(keys).save("""{"identitySecretHex":"cafebabe"}""")
        val bytes = assertNotNull(NSData.dataWithContentsOfURL(stateFile)).toByteArray()
        assertFalse(bytes.decodeToString(throwOnInvalidSequence = false).contains("cafebabe"))
    }

    @Test
    fun lockedKeychainIsUnreadableNotEmpty() {
        IosStateStore(keys).save("{}")
        keys.locked = true
        // Reporting "missing" here would make the engine create a new identity and overwrite the old one.
        assertTrue(assertIs<StoredState.Unreadable>(IosStateStore(keys).read()).retryable)
    }

    @Test
    fun lostKeyIsUnreadableNotEmpty() {
        IosStateStore(keys).save("{}")
        keys.items.clear()
        assertFalse(assertIs<StoredState.Unreadable>(IosStateStore(keys).read()).retryable)
    }

    @Test
    fun setAsideKeepsTheOldFile() {
        IosStateStore(keys).save("{}")
        IosStateStore(keys).setAside()
        assertEquals(StoredState.Missing, IosStateStore(keys).read())
    }
}
