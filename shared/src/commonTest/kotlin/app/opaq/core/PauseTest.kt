package app.opaq.core

import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import app.opaq.core.protocol.InviteLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pausing sharing with one circle or all of them, by hand or for a while. */
class PauseTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_002_500L
    private val net = FakeRelayNetwork { clock }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class MemoryStore(var data: String? = null) : StateStore {
        override fun read(): StoredState = data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) { this.data = data }
    }

    private inner class Phone(name: String) {
        lateinit var client: FakeRelayNetwork.Client
        val engine = Engine(MemoryStore(), scope, { clock }, transport = { onEvent -> net.client(onEvent).also { client = it } })
        val me get() = engine.myPubkey

        init {
            kotlinx.coroutines.runBlocking {
                engine.setMyName(name)
                client.setRelays(listOf("wss://fake.relay"))
            }
        }

        fun locationOf(circleId: String, pubkey: String) = engine.state.value.locations[circleId]?.get(pubkey)
        fun circle(id: String) = engine.state.value.circles.single { it.id == id }
    }

    private suspend fun sync(vararg phones: Phone) {
        repeat(3) {
            phones.forEach { it.engine.housekeeping() }
            net.flush()
        }
    }

    private fun loc(lat: Double) = MemberLocation(lat = lat, lon = -87.6, accuracy = 5f, speed = 12f, activity = Activity.DRIVING, time = clock)

    private suspend fun circleWith(admin: Phone, joiner: Phone, name: String): String {
        val c = admin.engine.createCircle(name)
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
    fun pausingOneCircleKeepsSharingWithTheOthers() = runTest {
        val me = Phone("Me")
        val family = Phone("Family member")
        val work = Phone("Coworker")
        val home = circleWith(me, family, "Family")
        val office = circleWith(me, work, "Work")

        assertTrue(me.engine.publishLocation(loc(41.0)))
        sync(me, family, work)
        assertEquals(41.0, family.locationOf(home, me.me)?.lat)

        clock += 30
        me.engine.setCirclePaused(office, true)
        sync(me, family, work)
        assertEquals(ShareStatus.PAUSED, me.engine.shareStatus(me.circle(office)))
        val notice = work.locationOf(office, me.me)!!
        assertEquals(MemberLocation.PAUSED, notice.status, "the circle is told sharing paused")
        assertEquals(Activity.STILL, notice.activity, "and nobody's dot is carried on as if still driving")
        assertNull(notice.speed)
        assertTrue(notice.points.isEmpty())
        assertNull(family.locationOf(home, me.me)?.status, "the other circle hears nothing about it")

        clock += 30
        assertTrue(me.engine.publishLocation(loc(41.01)))
        sync(me, family, work)
        assertEquals(41.01, family.locationOf(home, me.me)?.lat, "still sharing with Family")
        assertEquals(MemberLocation.PAUSED, work.locationOf(office, me.me)?.status, "nothing new for Work")
        assertEquals(41.0, work.locationOf(office, me.me)?.lat)

        clock += 30
        me.engine.setCirclePaused(office, false)
        assertTrue(me.engine.publishLocation(loc(41.02)))
        sync(me, family, work)
        assertEquals(41.02, work.locationOf(office, me.me)?.lat, "resumed")
        assertNull(work.locationOf(office, me.me)?.status)
    }

    @Test
    fun timedPausesEndOnTheirOwn() = runTest {
        val me = Phone("Me")
        val other = Phone("Other")
        val id = circleWith(me, other, "Family")
        assertTrue(me.engine.publishLocation(loc(41.0)))
        sync(me, other)

        // All sharing, for ten minutes.
        clock += 30
        me.engine.setSharing(false, resumeAt = clock + 600)
        sync(me, other)
        assertEquals(MemberLocation.PAUSED, other.locationOf(id, me.me)?.status)
        clock += 30
        assertFalse(me.engine.publishLocation(loc(41.01)), "nothing goes out while paused")
        clock += 600
        me.engine.housekeeping()
        assertTrue(me.engine.state.value.sharing, "the pause ended")
        assertEquals(0L, me.engine.state.value.sharingResumeAt)
        assertTrue(me.engine.publishLocation(loc(41.02)))

        // One circle, for an hour.
        clock += 30
        me.engine.setCirclePaused(id, true, until = clock + 3600)
        me.engine.housekeeping()
        assertTrue(me.circle(id).paused)
        clock += 3600
        me.engine.housekeeping()
        assertFalse(me.circle(id).paused)
        assertEquals(0L, me.circle(id).pausedUntil)
    }

    @Test
    fun pausingByHandStaysPaused() = runTest {
        val me = Phone("Me")
        val other = Phone("Other")
        val id = circleWith(me, other, "Family")
        me.engine.setCirclePaused(id, true)
        me.engine.setSharing(false)
        clock += 30 * 24 * 3600
        me.engine.housekeeping()
        assertTrue(me.circle(id).paused)
        assertFalse(me.engine.state.value.sharing)
    }
}
