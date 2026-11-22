package app.opaq.core

import app.opaq.core.model.MemberLocation
import app.opaq.core.protocol.InviteLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How quickly a published location reaches the other phones (issue #94). */
class LiveUpdatesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Five minutes before an hour starts.
    private var clock = 1_763_002_500L
    private val net = FakeRelayNetwork { clock }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class MemoryStore(var data: String? = null, var saves: Int = 0) : StateStore {
        override fun read(): StoredState = data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) {
            this.data = data
            saves++
        }
    }

    private inner class Phone(name: String) {
        lateinit var client: FakeRelayNetwork.Client
        val store = MemoryStore()
        val engine = Engine(store, scope, { clock }, transport = { onEvent -> net.client(onEvent).also { client = it } })
        val me get() = engine.myPubkey

        init {
            kotlinx.coroutines.runBlocking {
                engine.setMyName(name)
                client.setRelays(listOf("wss://fake.relay"))
            }
        }

        fun locationOf(circleId: String, pubkey: String) = engine.state.value.locations[circleId]?.get(pubkey)
    }

    private suspend fun sync(vararg phones: Phone) {
        repeat(3) {
            phones.forEach { it.engine.housekeeping() }
            net.flush()
        }
    }

    private fun loc(lat: Double) = MemberLocation(lat = lat, lon = -87.6, accuracy = 5f, time = clock)

    private suspend fun circleWith(admin: Phone, joiner: Phone): String {
        val c = admin.engine.createCircle("Family")
        sync(admin, joiner)
        val link = admin.engine.createInvite(c.id)
        joiner.engine.acceptInvite(InviteLinks.decode(link)!!, scannedInPerson = true)
        sync(admin, joiner)
        admin.engine.approve(admin.engine.state.value.joinRequests.single { it.pubkey == joiner.me }.id)
        sync(admin, joiner)
        joiner.engine.confirmCircle(c.id)
        sync(admin, joiner)
        return c.id
    }

    @Test
    fun askingForSomeonesLocationWakesTheirPhoneOnlyWhileTheyShare() = runTest {
        // #149: Ana asks Ben where he is. His phone is told once; asking again straight away sends
        // nothing, a paused or approximate circle never answers, and nobody is asked for an update
        // they sent a moment ago.
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        val asked = mutableListOf<String>()
        val watch = scope.launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { b.engine.locationRequests.collect { asked += it } }

        assertTrue(a.engine.requestLocation(id, b.me))
        net.flush()
        kotlinx.coroutines.yield()
        assertEquals(listOf(id), asked)
        assertFalse(a.engine.requestLocation(id, b.me), "asked a moment ago")

        clock += Engine.LOCATION_REQUEST_GAP_SECONDS + 1
        b.engine.setCirclePaused(id, true)
        assertTrue(a.engine.requestLocation(id, b.me))
        net.flush()
        kotlinx.coroutines.yield()
        assertEquals(listOf(id), asked, "paused: no answer")

        // Someone who sent an update a moment ago isn't asked: there's nothing to nudge.
        b.engine.setCirclePaused(id, false)
        clock += Engine.LOCATION_REQUEST_GAP_SECONDS + 1
        b.engine.publishLocation(loc(41.0))
        net.flush()
        assertFalse(a.engine.requestLocation(id, b.me), "updated just now")

        // Ten minutes on, Ben's phone answers again, but not once he shares only roughly.
        clock += Engine.LOCATION_REQUEST_AFTER_SECONDS + 1
        assertTrue(a.engine.requestLocation(id, b.me))
        net.flush()
        kotlinx.coroutines.yield()
        assertEquals(listOf(id, id), asked)
        b.engine.setCirclePrecision(id, app.opaq.core.model.Precision.NEIGHBORHOOD)
        clock += Engine.LOCATION_REQUEST_AFTER_SECONDS + 1
        assertTrue(a.engine.requestLocation(id, b.me))
        net.flush()
        kotlinx.coroutines.yield()
        assertEquals(listOf(id, id), asked, "approximate: no answer")
        watch.cancel()
    }

    @Test
    fun alertAreasNotifyOnlyTheirMakerWhenSomeoneArrivesOrLeaves() = runTest {
        // #151: Ana draws a 100 ft area round her house. Ben's updates tell her phone when he
        // arrives and leaves; Ben's phone hears nothing about it.
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        // Only place alerts: other notices (someone joining) can still arrive from setting up the circle.
        val seen = mutableListOf<Notice>()
        val heard = mutableListOf<Notice>()
        val watchA = scope.launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { a.engine.notices.collect { if (it.kind == Notice.PLACE) seen += it } }
        val watchB = scope.launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { b.engine.notices.collect { heard += it } }

        b.engine.publishLocation(loc(41.01))
        net.flush()
        a.engine.savePlace(app.opaq.core.model.Place("", "Home", 41.0, -87.6, radius = 30.0, onLeave = true))
        assertTrue(seen.isEmpty(), seen.toString())

        // A later update than the first, so it's never taken for the same one.
        clock += 10
        b.engine.publishLocation(loc(41.0001))
        net.flush()
        assertEquals(listOf("Ben arrived at Home"), seen.map { it.title })
        assertEquals(Notice.PLACE, seen.single().kind)

        clock += 60
        b.engine.publishLocation(loc(41.001))
        net.flush()
        assertEquals(listOf("Ben arrived at Home", "Ben left Home"), seen.map { it.title })
        assertTrue(heard.none { it.kind == Notice.PLACE })
        watchA.cancel(); watchB.cancel()
    }

    @Test
    fun aNewPlaceDoesNotAnnounceWhoIsAlreadyThere() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        b.engine.publishLocation(loc(41.0))
        net.flush()
        val seen = mutableListOf<Notice>()
        val watch = scope.launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { a.engine.notices.collect { seen += it } }
        val place = a.engine.savePlace(app.opaq.core.model.Place("", "Home", 41.0, -87.6, radius = 30.0, circleId = id))
        assertEquals(listOf(b.me), a.engine.state.value.placeInside[place.id])
        clock += 30
        b.engine.publishLocation(loc(41.00005))
        net.flush()
        assertTrue(seen.isEmpty())
        watch.cancel()
    }

    @Test
    fun housekeepingRunsJustAfterTheHourStarts() {
        val hour = 1_763_002_800L // a multiple of 3600
        assertEquals(60_000L, Engine.housekeepingDelayMillis(hour - 3600 + 10))
        assertEquals(60_000L, Engine.housekeepingDelayMillis(hour - 60))
        assertEquals(31_000L, Engine.housekeepingDelayMillis(hour - 30))
        assertEquals(2_000L, Engine.housekeepingDelayMillis(hour - 1))
        assertEquals(60_000L, Engine.housekeepingDelayMillis(hour))
        // Never longer than a minute, and when an hour starts within it, the next pass is 1 s into that hour.
        (0L until 7200L).forEach { off ->
            val t = hour - 3600 + off
            val wait = Engine.housekeepingDelayMillis(t)
            assertTrue(wait in 1_000L..60_000L)
            val nextHour = (t / 3600 + 1) * 3600
            if (nextHour - t < 60) assertEquals(nextHour + 1, t + wait / 1000)
        }
    }

    @Test
    fun newHoursUpdatesArriveOnceSubscriptionsRefresh() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        b.engine.publishLocation(loc(41.0))
        net.flush()
        assertEquals(41.0, a.locationOf(id, b.me)!!.lat)

        // The hour turns. Ben publishes under the new hour's tag before Ana's phone has refreshed.
        clock = (clock / 3600 + 1) * 3600 + 20
        b.engine.publishLocation(loc(42.0))
        net.flush()
        assertEquals(41.0, a.locationOf(id, b.me)!!.lat, "not subscribed to the new hour yet")

        // The refresh (now timed to run just after the hour starts) fetches it from the relay.
        a.engine.housekeeping()
        net.flush()
        assertEquals(42.0, a.locationOf(id, b.me)!!.lat)
    }

    @Test
    fun missedUpdateIsReportedAndNothingIsSavedForIt() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        assertTrue(b.engine.canPublishNow())
        assertTrue(b.engine.publishLocation(loc(41.0)))
        net.flush()
        assertEquals(41.0, a.locationOf(id, b.me)!!.lat)

        // No signal: the update reaches no relay, and the location service hears so.
        b.client.offline()
        assertFalse(b.engine.canPublishNow())
        val saves = b.store.saves
        val before = net.published.size
        assertFalse(b.engine.publishLocation(loc(42.0)))
        assertEquals(before, net.published.size, "dropped, not queued")
        assertEquals(saves, b.store.saves, "a dropped update doesn't rewrite the saved state")

        // Signal is back, but the relay hasn't confirmed the circle key yet (v2 member).
        clock += 30 // the next update, as the location service would send it
        b.client.reconnect()
        assertFalse(b.engine.canPublishNow(), "waits for the relay to finish replaying")
        net.flush()
        assertTrue(b.engine.canPublishNow(), "ready the moment the relay has caught up")
        assertTrue(b.engine.publishLocation(loc(43.0)))
        net.flush()
        assertEquals(43.0, a.locationOf(id, b.me)!!.lat)
    }

    @Test
    fun adminOfflineReportsMissedUpdate() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        a.client.offline()
        assertFalse(a.engine.canPublishNow())
        assertFalse(a.engine.publishLocation(loc(30.0)), "the admin's circle is fine, but no relay is connected")
        net.flush()
        assertNull(b.locationOf(id, a.me))
        // The clock moves on, as between real updates: each one needs a created_at newer than the last
        // (the offline one used a second up to 15 s back), so a second update in the same second can
        // be skipped.
        clock += 30
        a.client.reconnect()
        net.flush()
        assertTrue(a.engine.canPublishNow())
        assertTrue(a.engine.publishLocation(loc(31.0)))
        net.flush()
        assertEquals(31.0, b.locationOf(id, a.me)!!.lat)
    }

    @Test
    fun nothingToPublishToMeansNotReady() = runTest {
        val a = Phone("Ana")
        assertFalse(a.engine.canPublishNow(), "no circles")
        assertFalse(a.engine.publishLocation(loc(1.0)))
    }
}
