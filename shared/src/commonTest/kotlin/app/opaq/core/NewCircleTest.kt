package app.opaq.core

import app.opaq.core.model.MemberLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/** A circle made while already sharing gets an update straight away (#148). */
class NewCircleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_002_500L
    private val net = FakeRelayNetwork { clock }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class MemoryStore(var data: String? = null) : StateStore {
        override fun read(): StoredState = data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) { this.data = data }
    }

    @Test
    fun secondCircleGetsTheNextUpdate() = runTest {
        lateinit var client: FakeRelayNetwork.Client
        val engine = Engine(MemoryStore(), scope, { clock }, transport = { onEvent -> net.client(onEvent).also { client = it } })
        engine.setMyName("Ana")
        client.setRelays(listOf("wss://fake.relay"))
        val first = engine.createCircle("Family")
        net.flush()
        engine.publishLocation(MemberLocation(lat = 41.0, lon = -87.6, accuracy = 5f, time = clock))
        assertNotNull(engine.state.value.locations[first.id]?.get(engine.myPubkey), "first circle")

        for (gap in listOf(0L, 1L, 30L, 150L)) {
            clock += gap
            val next = engine.createCircle("Friends $gap")
            engine.publishLocation(MemberLocation(lat = 41.0, lon = -87.6, accuracy = 5f, time = clock))
            assertNotNull(engine.state.value.locations[next.id]?.get(engine.myPubkey), "circle made $gap s after the last update")
        }
    }
}
