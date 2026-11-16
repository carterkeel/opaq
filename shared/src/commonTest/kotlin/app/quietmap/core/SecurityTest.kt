package app.quietmap.core

import app.quietmap.core.nostr.RelayUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Security regression tests: each one is an attack or failure from the audit (QM-xx) and checks
 * that it fails closed. See SECURITY_IMPLEMENTATION_LOG.md.
 */
class SecurityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_000_000L

    @AfterTest
    fun tearDown() = scope.cancel()

    /** A store whose reads can be made to fail the ways real Keystore/Keychain storage fails. */
    private class FlakyStore : StateStore {
        var data: String? = null
        var failure: StoredState.Unreadable? = null
        var saves = 0
        var setAsideCount = 0
        override fun read(): StoredState = failure ?: data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) {
            saves++
            this.data = data
        }
        override fun setAside() {
            setAsideCount++
        }
    }

    private val network = FakeRelayNetwork { clock }

    private fun engine(store: StateStore) = Engine(store, scope, { clock }, transport = { network.client(it) })

    // QM-11 ------------------------------------------------------------------------------------

    @Test
    fun keystoreFailureNeverReplacesTheIdentity() = runTest {
        val store = FlakyStore()
        val original = engine(store)
        original.createCircle("Family")
        val savedBefore = store.data
        val savesBefore = store.saves

        store.failure = StoredState.Unreadable("Keystore busy", retryable = true)
        val reopened = engine(store)
        assertNotNull(reopened.recovery.value)
        assertEquals(savesBefore, store.saves, "nothing may be written while saved state is unreadable")
        assertEquals(savedBefore, store.data)
        // Changes are refused rather than saved over the old state.
        assertFailsWith<IllegalStateException> { reopened.createCircle("Other") }
        reopened.setSharing(true)
        assertEquals(savesBefore, store.saves)

        // The storage comes back: the original identity and circles load.
        store.failure = null
        assertTrue(reopened.retryLoad())
        assertNull(reopened.recovery.value)
        assertEquals(original.myPubkey, reopened.myPubkey)
        assertEquals(1, reopened.state.value.circles.size)
    }

    @Test
    fun throwingStoreIsTreatedAsUnreadable() = runTest {
        val store = object : StateStore {
            var saves = 0
            override fun read(): StoredState = throw IllegalStateException("Keychain locked")
            override fun save(data: String) { saves++ }
        }
        val e = engine(store)
        assertTrue(e.recovery.value!!.retryable)
        assertEquals(0, store.saves)
    }

    @Test
    fun damagedStateIsKeptNotOverwritten() = runTest {
        val store = FlakyStore()
        store.data = "{not json"
        val e = engine(store)
        assertNotNull(e.recovery.value)
        assertEquals(0, store.saves)
        assertEquals("{not json", store.data)
    }

    @Test
    fun startingOverSetsTheOldFileAsideFirst() = runTest {
        val store = FlakyStore()
        engine(store).createCircle("Family")
        store.failure = StoredState.Unreadable("Key missing", retryable = false)
        val e = engine(store)
        store.failure = null
        e.startFresh()
        assertEquals(1, store.setAsideCount)
        assertNull(e.recovery.value)
        assertTrue(e.state.value.circles.isEmpty())
    }

    @Test
    fun restoringABackupWhileRecoveringSetsTheOldFileAside() = runTest {
        val backup = engine(FlakyStore()).let { it.createCircle("Family"); it.exportBackup("correct horse battery") to it.myPubkey }
        val store = FlakyStore()
        store.data = "{damaged"
        val e = engine(store)
        assertFailsWith<IllegalArgumentException> { e.importBackup(backup.first, "wrong passphrase") }
        assertEquals(0, store.setAsideCount, "a wrong passphrase changes nothing")
        assertEquals("{damaged", store.data)
        e.importBackup(backup.first, "correct horse battery")
        assertEquals(1, store.setAsideCount)
        assertEquals(backup.second, e.myPubkey)
        assertNull(e.recovery.value)
        assertFalse(e.state.value.sharing, "a restore starts with sharing paused")
    }

    @Test
    fun onlyAFirstLaunchMakesANewIdentity() = runTest {
        val store = FlakyStore()
        val e = engine(store)
        assertNull(e.recovery.value)
        assertEquals(1, store.saves)
    }

    // QM-09 ------------------------------------------------------------------------------------

    @Test
    fun remoteRelaysMustUseTls() {
        RelayUrls.allowLocalPlaintext = false
        assertTrue(RelayUrls.isAllowed("wss://relay.example.com"))
        assertTrue(RelayUrls.isAllowed("wss://relay.example.com:443/path"))
        assertFalse(RelayUrls.isAllowed("ws://relay.example.com"))
        assertFalse(RelayUrls.isAllowed("ws://localhost:7777"), "release builds refuse even local plaintext")
        assertFalse(RelayUrls.isAllowed("https://relay.example.com"))
        assertFalse(RelayUrls.isAllowed("wss://"))
        assertFalse(RelayUrls.isAllowed("wss://user@evil.example"))
        assertFalse(RelayUrls.isAllowed("wss://relay.example.com #x"))
        RelayUrls.allowLocalPlaintext = true
        try {
            assertTrue(RelayUrls.isAllowed("ws://localhost:7777"))
            assertTrue(RelayUrls.isAllowed("ws://10.0.2.2:7777"))
            assertFalse(RelayUrls.isAllowed("ws://relay.example.com"), "debug builds still refuse remote plaintext")
            assertFalse(RelayUrls.isAllowed("ws://localhost.evil.com"))
        } finally {
            RelayUrls.allowLocalPlaintext = false
        }
    }

    @Test
    fun plaintextRelaysAreDroppedFromSettings() = runTest {
        val e = engine(FlakyStore())
        e.setRelays(listOf("wss://a.example", "ws://b.example", "wss://a.example/", " wss://c.example "))
        assertEquals(listOf("wss://a.example", "wss://c.example"), e.state.value.relays)
    }
}
