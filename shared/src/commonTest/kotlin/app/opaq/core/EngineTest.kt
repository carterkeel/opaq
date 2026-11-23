package app.opaq.core

import app.opaq.core.model.Member
import app.opaq.core.model.THEME_DARK
import app.opaq.core.model.THEME_MIDNIGHT
import app.opaq.core.model.THEME_SYSTEM
import app.opaq.core.protocol.Backups
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine behaviour that doesn't need relays (the engine is never started, so nothing connects). */
class EngineTest {
    private class MemoryStore : StateStore {
        var data: String? = null
        override fun read(): StoredState = data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) { this.data = data }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_000_000L

    private fun engine(store: StateStore = MemoryStore()) = Engine(store, scope, { clock })

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun newInstallsShareBatteryAndFollowThePhonesTheme() = runTest {
        val e = engine()
        e.setMyName("Me")
        assertTrue(e.state.value.shareBattery)
        assertEquals(THEME_SYSTEM, e.state.value.themeMode)
        e.setThemeMode(THEME_DARK)
        assertEquals(THEME_DARK, e.state.value.themeMode)
        e.setThemeMode(THEME_MIDNIGHT)
        assertEquals(THEME_MIDNIGHT, e.state.value.themeMode)
        assertFailsWith<IllegalArgumentException> { e.setThemeMode("purple") }
        assertEquals(THEME_MIDNIGHT, e.state.value.themeMode)

        // Turned off and backed up: the restore keeps it off.
        e.setShareBattery(false)
        e.setPin("2468")
        val restored = engine()
        restored.importBackup(e.exportBackup("correct horse battery"), "correct horse battery")
        assertFalse(restored.state.value.shareBattery)
    }

    @Test
    fun backupRestoresIdentityCirclesAndSettings() = runTest {
        val a = engine()
        a.setMyName("Mom")
        a.setShowPhotos(false)
        val circle = a.createCircle("Family")
        val home = a.savePlace(app.opaq.core.model.Place("", "Home", 41.0, -87.6, radius = 60.0, circleId = circle.id, onLeave = true))
        a.setPin("2468")
        val backup = a.exportBackup("correct horse battery")
        assertTrue(backup.startsWith(Backups.HEADER))

        val b = engine()
        b.importBackup(backup, "correct horse battery")
        assertEquals(a.myPubkey, b.myPubkey)
        assertEquals("Mom", b.state.value.myName)
        assertEquals(listOf(circle.id), b.state.value.circles.map { it.id })
        assertEquals(circle.keyHex, b.state.value.circles.single().keyHex)
        assertFalse(b.state.value.showPhotos)
        assertFalse(b.state.value.sharing, "sharing starts paused after a restore")
        assertTrue(b.state.value.setupDone)
        assertEquals(listOf(home), b.state.value.places, "place alerts come back too (#151)")
    }

    @Test
    fun backupRejectsWrongPassphraseAndShortOnes() = runTest {
        val a = engine()
        a.setPin("2468")
        val backup = a.exportBackup("correct horse battery")
        val b = engine()
        val before = b.myPubkey
        assertFailsWith<IllegalArgumentException> { b.importBackup(backup, "wrong passphrase") }
        assertEquals(before, b.myPubkey, "a failed import changes nothing")
        a.unlock("2468") // each backup needs the PIN entered just now
        assertFailsWith<IllegalArgumentException> { a.exportBackup("short") }
        assertFailsWith<IllegalArgumentException> { b.importBackup("not a backup", "whatever123") }
    }

    @Test
    fun savedStatesGetDefaultRelayChangesOnce() = runTest {
        val store = MemoryStore()
        val a = engine(store)
        val now = listOf("wss://nos.lol", "wss://relay.primal.net", "wss://relay.snort.social", "wss://relay.coinos.io", "wss://nostr.bitcoiner.social")
        suspend fun savedAt(version: Int, relays: List<String>): Engine {
            a.setRelays(relays)
            store.data = store.data!!.replace(Regex("\"relayDefaultsVersion\":\\d+"), "\"relayDefaultsVersion\":$version")
            return engine(store)
        }
        // Saved before offchain.pub was a default: gets it, then loses it with damus and nostr.mom (#165).
        assertEquals(now, savedAt(0, listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom")).state.value.relays)
        // Saved with the previous defaults, plus a relay the user added themselves, which stays.
        val b = savedAt(1, listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://offchain.pub", "wss://my.relay"))
        assertEquals(listOf("wss://nos.lol", "wss://relay.primal.net", "wss://my.relay", "wss://relay.snort.social", "wss://relay.coinos.io", "wss://nostr.bitcoiner.social"), b.state.value.relays)
        // Changing the list afterwards sticks: nothing is added or removed on the next launch.
        b.setRelays(listOf("wss://relay.damus.io", "wss://nos.lol"))
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), engine(store).state.value.relays)
        // Someone who kept only relays that were removed gets the new defaults in their place.
        assertEquals(listOf("wss://relay.snort.social", "wss://relay.coinos.io", "wss://nostr.bitcoiner.social"), savedAt(1, listOf("wss://relay.damus.io")).state.value.relays)
        // New installs start with the current defaults, and removing one there sticks too.
        val freshStore = MemoryStore()
        val c = engine(freshStore)
        assertEquals(now, c.state.value.relays)
        c.setRelays(now.drop(1))
        assertEquals(now.drop(1), engine(freshStore).state.value.relays)
    }

    @Test
    fun stateSurvivesRestartWithNewFields() = runTest {
        val store = MemoryStore()
        val a = engine(store)
        val c = a.createCircle("Friends")
        a.setLastCircle(c.id)
        val reopened = engine(store)
        assertEquals(c.id, reopened.state.value.lastCircleId)
        assertEquals(a.myPubkey, reopened.myPubkey)
    }

    @Test
    fun adminMustHandOverBeforeLeaving() = runTest {
        val a = engine()
        val c = a.createCircle("Family")
        // Alone in the circle: leaving is fine.
        a.leaveCircle(c.id)
        assertTrue(a.state.value.circles.isEmpty())
        assertNull(a.state.value.lastCircleId)
    }

    @Test
    fun managersCanInviteButOnlyTheAdminChoosesThem() = runTest {
        val a = engine()
        val c = a.createCircle("Family")
        assertTrue(a.canInvite(c))
        assertTrue(a.createInvite(c.id).startsWith("opaq://join#"))
        val stranger = Member("f".repeat(64), "Nobody")
        assertFailsWith<IllegalArgumentException> { a.setManager(c.id, stranger.pubkey, true) }
    }
}
