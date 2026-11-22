package app.opaq.core

import app.opaq.core.model.MemberLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The diagnostic log (#154): useful events, and never locations, keys, names or contents. */
class DiagnosticsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_700_000L
    private val net = FakeRelayNetwork { clock }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class MemoryStore(var data: String? = null) : StateStore {
        override fun read(): StoredState = data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) { this.data = data }
    }

    @Test
    fun cleanBlanksCoordinatesKeysAndData() {
        val key = "a3".repeat(32)
        val dirty = "at 41.881832,-87.623177 key $key invite eyJ2IjoyLCJjIjoiYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTYifQ"
        val clean = DiagnosticLog.clean(dirty)
        assertFalse(DiagnosticLog.looksSensitive(clean), clean)
        assertFalse("41.88" in clean); assertFalse(key in clean)
        // Ordinary events are left alone.
        assertEquals("relay relay.damus.io: connected", DiagnosticLog.clean("relay relay.damus.io: connected"))
        assertEquals("Battery 40–60%, power saver on", DiagnosticLog.clean("Battery 40–60%, power saver on"))
    }

    @Test
    fun theLogExplainsWhatHappenedAndHoldsNothingPrivate() = runBlocking {
        val files = MemoryHistoryFiles()
        val engine = Engine(MemoryStore(), scope, { clock }, transport = { net.client(it) }, historyFiles = files)
        engine.setMyName("Ana Example")
        val circle = engine.createCircle("Family secrets")
        engine.diagnostic("Sharing service started (GPS)")
        engine.publishLocation(MemberLocation(lat = 41.881832, lon = -87.623177, accuracy = 5f, time = clock))
        engine.setCirclePaused(circle.id, true)
        engine.setSharing(false)
        engine.publishLocation(MemberLocation(lat = 41.881900, lon = -87.623100, accuracy = 5f, time = clock + 10))

        val text = withTimeout(5_000) {
            var t: String
            do { delay(20); t = engine.diagnosticsText("Opaq test") } while ("Update not sent: sharing is paused" !in t)
            t
        }
        assertTrue("Sharing service started (GPS)" in text, text)
        assertTrue("circle 1 paused" in text, text)
        assertTrue("Sharing paused" in text, text)
        // Never the location, names, the circle id, keys or this phone's identity.
        for (secret in listOf("41.88", "87.62", "Ana Example", "Family secrets", circle.id, circle.keyHex, engine.myPubkey)) {
            assertFalse(secret in text, "log contains $secret")
        }
        text.lines().forEach { assertFalse(DiagnosticLog.looksSensitive(it), it) }
        // Stored encrypted.
        files.list().filter { it.startsWith(DiagnosticLog.PREFIX) }.flatMap { files.read(it) }.forEach { assertFalse("paused" in it) }

        engine.clearDiagnostics()
        assertFalse("circle 1 paused" in engine.diagnosticsText("Opaq test"), "Clear logs deletes them")
    }

    @Test
    fun utcTimesAreRight() {
        assertEquals("1970-01-01 00:00:00Z", utcTime(0))
        assertEquals("2025-11-21 00:00:00Z", utcTime(1_763_683_200))
        assertEquals("2024-02-29 12:34:56Z", utcTime(1_709_210_096))
    }
}
