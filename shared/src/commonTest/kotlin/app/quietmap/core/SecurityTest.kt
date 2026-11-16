package app.quietmap.core

import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.model.SecurityEventType
import app.quietmap.core.protocol.Backups
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

    private fun engine(store: StateStore) = Engine(store, scope, { clock }, transport = { network.client(it) }, deviceName = "Test phone")

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
        val backup = engine(FlakyStore()).let { it.createCircle("Family"); it.setPin("2468"); it.exportBackup("correct horse battery") to it.myPubkey }
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

    // QM-01 / QM-17: the PIN --------------------------------------------------------------------

    @Test
    fun backupsNeedAPinSetAndEntered() = runTest {
        val e = engine(FlakyStore())
        e.createCircle("Family")
        assertFailsWith<IllegalStateException> { e.exportBackup("correct horse battery") }
        e.setPin("2468")
        e.exportBackup("correct horse battery")
        clock += Engine.AUTH_WINDOW_SECONDS + 1
        assertFailsWith<IllegalStateException>("an export without the PIN is refused") { e.exportBackup("correct horse battery") }
        assertEquals(PinResult.Wrong(4), e.unlock("1111"))
        assertEquals(PinResult.Ok, e.unlock("2468"))
        e.exportBackup("correct horse battery")
        assertEquals(2, e.state.value.securityLog.count { it.type == SecurityEventType.BACKUP_EXPORTED }, "every export is recorded")
        assertEquals("Test phone", e.state.value.securityLog.last().device)
    }

    @Test
    fun sensitiveActionsNeedThePinButViewingDoesNot() = runTest {
        val e = engine(FlakyStore())
        val c = e.createCircle("Family")
        e.setPin("2468")
        clock += Engine.AUTH_WINDOW_SECONDS + 1
        assertFailsWith<IllegalStateException> { e.createInvite(c.id) }
        assertFailsWith<IllegalStateException> { e.setRelays(listOf("wss://evil.example")) }
        assertFailsWith<IllegalStateException> { e.setManager(c.id, "f".repeat(64), true) }
        assertFailsWith<IllegalStateException> { e.deleteCircle(c.id) }
        assertFailsWith<IllegalStateException> { e.resetIdentity() }
        assertFailsWith<IllegalStateException> { e.removePin() }
        assertFailsWith<IllegalStateException> { e.setPin("1111") }
        assertFailsWith<IllegalStateException> { e.setHideInRecents(false) }
        // Opening the app, seeing locations and sharing never ask for it.
        assertEquals(1, e.state.value.circles.size)
        e.setSharing(true)
        e.publishLocation(app.quietmap.core.model.MemberLocation(1.0, 2.0, 5f, time = clock))
        assertEquals(1.0, e.state.value.locations[c.id]!![e.myPubkey]!!.lat)
        e.setHideInRecents(true)
        assertEquals(PinResult.Ok, e.unlock("2468"))
        e.createInvite(c.id)
    }

    @Test
    fun changingTheDateDoesntEndAPinLockout() = runTest {
        var uptime = 5_000L
        val e = Engine(FlakyStore(), scope, { clock }, transport = { network.client(it) }, elapsed = { uptime })
        e.setPin("2468")
        repeat(5) { e.unlock("0000") }
        clock += 86_400 // the date moved forward a day
        assertTrue(e.unlock("2468") is PinResult.Locked)
        uptime += 31
        assertEquals(PinResult.Ok, e.unlock("2468"))
        clock += 86_400
        assertTrue(e.isAuthorized(), "the PIN window runs on the same clock")
        uptime += Engine.AUTH_WINDOW_SECONDS + 1
        assertFalse(e.isAuthorized())
        // After a reboot the uptime starts again: the wait restarts rather than ending.
        repeat(5) { e.unlock("0000") }
        uptime = 10
        assertTrue(e.unlock("2468") is PinResult.Locked)
        uptime += 31
        assertEquals(PinResult.Ok, e.unlock("2468"))
    }

    @Test
    fun wrongPinsLockForLongerEachTime() = runTest {
        val e = engine(FlakyStore())
        e.setPin("2468")
        repeat(4) { assertTrue(e.unlock("0000") is PinResult.Wrong) }
        val locked = e.unlock("0000")
        assertTrue(locked is PinResult.Locked && locked.until == clock + 30)
        assertTrue(e.unlock("2468") is PinResult.Locked, "even the right PIN waits out the lock")
        clock += 31
        assertTrue(e.unlock("0000") is PinResult.Locked)
        assertEquals(clock + 60, (e.unlock("2468") as PinResult.Locked).until)
        clock += 61
        assertEquals(PinResult.Ok, e.unlock("2468"))
        assertEquals(0, e.state.value.pin!!.failedAttempts)
        assertTrue(e.state.value.securityLog.any { it.type == SecurityEventType.PIN_LOCKED })
    }

    @Test
    fun pinIsStoredOnlyAsASaltedHash() = runTest {
        val store = FlakyStore()
        val e = engine(store)
        e.setPin("246813")
        assertFalse(store.data!!.contains("246813"))
        assertFailsWith<IllegalArgumentException> { engine(FlakyStore()).setPin("12") }
        assertFailsWith<IllegalArgumentException> { engine(FlakyStore()).setPin("12ab") }
    }

    // QM-06: reminders --------------------------------------------------------------------------

    @Test
    fun sharingIsReconfirmedMonthly() = runTest {
        val e = engine(FlakyStore())
        e.createCircle("Family")
        e.housekeeping()
        assertFalse(e.consentDue())
        clock += Engine.CONSENT_EVERY_SECONDS
        assertTrue(e.consentDue())
        e.confirmConsent()
        assertFalse(e.consentDue())
        clock += Engine.CONSENT_EVERY_SECONDS
        e.setSharing(false)
        assertFalse(e.consentDue(), "no reminder while not sharing")
    }

    // QM-10: backups ----------------------------------------------------------------------------

    @Test
    fun newBackupsUseArgon2idAndOldOnesStillRestore() = runTest {
        val e = engine(FlakyStore())
        e.createCircle("Family")
        e.setPin("2468")
        val v2 = e.exportBackup("correct horse battery")
        assertTrue(v2.startsWith("quietmap-backup-v2\n{\"kdf\":\"argon2id\",\"m\":65536,\"t\":3,"), v2.take(80))
        val data = Backups.decrypt(v2, "correct horse battery")
        assertEquals(e.myPubkey, app.quietmap.core.crypto.KeyPair(data.identitySecretHex.hexToBytes()).publicHex)
        // A v1 (PBKDF2) file from an older version still restores.
        val v1 = Backups.encryptV1ForTests(data, "correct horse battery")
        assertEquals(data.identitySecretHex, Backups.decrypt(v1, "correct horse battery").identitySecretHex)
        assertFailsWith<IllegalArgumentException> { Backups.decrypt(v2, "wrong horse battery") }
    }

    @Test
    fun backupFilesCantDemandAbsurdWork() {
        val crafted = "quietmap-backup-v2\n{\"kdf\":\"argon2id\",\"m\":4194304,\"t\":3,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA\"}\nAA\n"
        assertFailsWith<IllegalArgumentException> { Backups.decrypt(crafted, "whatever123") }
        val sha = "quietmap-backup-v2\n{\"kdf\":\"sha1\",\"m\":65536,\"t\":3,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA\"}\nAA\n"
        assertFailsWith<IllegalArgumentException> { Backups.decrypt(sha, "whatever123") }
        val big = "quietmap-backup-v2\n{\"kdf\":\"argon2id\",\"m\":524288,\"t\":3,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA\"}\nAA\n"
        assertFailsWith<IllegalArgumentException>("512 MiB is more than a phone should be asked for") { Backups.decrypt(big, "whatever123") }
        val longSalt = "quietmap-backup-v2\n{\"kdf\":\"argon2id\",\"m\":65536,\"t\":3,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}\nAA\n"
        assertFailsWith<IllegalArgumentException>("salts are exactly 16 bytes on both platforms") { Backups.decrypt(longSalt, "whatever123") }
    }
}
