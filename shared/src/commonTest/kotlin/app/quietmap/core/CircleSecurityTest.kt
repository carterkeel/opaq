package app.quietmap.core

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toHex
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.Member
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.MembershipChange
import app.quietmap.core.model.Precision
import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Filter
import app.quietmap.core.nostr.Kinds
import app.quietmap.core.protocol.Backups
import app.quietmap.core.protocol.CircleCrypto
import app.quietmap.core.protocol.DeviceCode
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.core.protocol.LocationBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whole-circle attacks against circle protocol v2, with several phones on an in-memory relay:
 * missed rotations (QM-03), forward secrecy (QM-04), members overwriting or replaying each other's
 * updates (QM-05), and inboxes that hide permanent keys from relays (QM-02).
 */
class CircleSecurityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_000_000L
    private val net = FakeRelayNetwork { clock }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class MemoryStore(var data: String? = null) : StateStore {
        override fun read(): StoredState = data?.let { StoredState.Found(it) } ?: StoredState.Missing
        override fun save(data: String) { this.data = data }
    }

    private inner class Phone(name: String, store: StateStore = MemoryStore()) {
        lateinit var client: FakeRelayNetwork.Client
        val engine = Engine(store, scope, { clock }, transport = { onEvent -> net.client(onEvent).also { client = it } })
        val me get() = engine.myPubkey

        init {
            kotlinx.coroutines.runBlocking { engine.setMyName(name) }
        }

        fun circle(id: String) = engine.state.value.circles.firstOrNull { it.id == id }
        fun locationOf(circleId: String, pubkey: String) = engine.state.value.locations[circleId]?.get(pubkey)
    }

    private suspend fun sync(vararg phones: Phone) {
        repeat(3) {
            phones.forEach { it.engine.housekeeping() }
            net.flush()
        }
    }

    private fun loc(lat: Double) = MemberLocation(lat = lat, lon = -87.6, accuracy = 5f, time = clock)

    /** Admin creates a circle and lets each joiner in through an invite link. */
    private suspend fun circleWith(admin: Phone, vararg joiners: Phone): String {
        val c = admin.engine.createCircle("Family")
        sync(admin, *joiners)
        joiners.forEach { j ->
            val link = admin.engine.createInvite(c.id)
            j.engine.acceptInvite(InviteLinks.decode(link)!!, scannedInPerson = true)
            sync(admin, *joiners)
            val req = admin.engine.state.value.joinRequests.single { it.pubkey == j.me }
            admin.engine.approve(req.id)
            sync(admin, *joiners)
            j.engine.confirmCircle(c.id)
            sync(admin, *joiners)
        }
        return c.id
    }

    @Test
    fun membersShareLocationsOverV2() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        assertEquals(2, a.circle(id)!!.version)
        assertEquals(2, b.circle(id)!!.members.size)
        assertEquals(ShareStatus.OK, b.engine.shareStatus(b.circle(id)!!))
        b.engine.publishLocation(loc(41.1))
        a.engine.publishLocation(loc(42.2))
        net.flush()
        assertEquals(41.1, a.locationOf(id, b.me)!!.lat)
        assertEquals(42.2, b.locationOf(id, a.me)!!.lat)
        assertTrue(a.circle(id)!!.pendingAcks.isEmpty(), "every roster was acknowledged")
    }

    @Test
    fun memberWhoMissesTheRotationNeverPublishesUnderTheOldKey() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val id = circleWith(a, b, c)
        // Ben is about to be removed; he keeps a copy of the circle key as an attacker would.
        val stolen = b.circle(id)!!
        c.client.offline()
        a.engine.removeMember(id, b.me)
        net.flush()
        assertNull(b.circle(id), "Ben's own app drops the circle")
        assertEquals(stolen.epoch, c.circle(id)!!.epoch, "Cal hasn't heard yet")

        // Cal is offline: his update is dropped, not queued to go out later under the old key.
        val before = net.published.size
        c.engine.publishLocation(loc(43.3))
        assertEquals(before, net.published.size)

        // Cal's relay loses every message for him, but he still sees the admin's "closed" notice.
        c.client.drop = { it.kind == Kinds.SEALED_DIRECT }
        c.client.reconnect()
        net.flush()
        val cc = c.circle(id)!!
        assertTrue(cc.stale)
        assertEquals(ShareStatus.KEY_REPLACED, c.engine.shareStatus(cc))
        val published = net.published.size
        c.engine.publishLocation(loc(43.3))
        assertEquals(published, net.published.size, "a phone on a replaced key publishes nothing")

        // Messages flow again: the roster arrives (resent on request) and Cal is back on the new key.
        c.client.drop = { false }
        clock += 3600
        sync(a, c)
        assertEquals(a.circle(id)!!.epoch, c.circle(id)!!.epoch)
        assertFalse(c.circle(id)!!.stale)
        c.engine.publishLocation(loc(44.4))
        net.flush()
        assertEquals(44.4, a.locationOf(id, c.me)!!.lat)

        // Nothing published after the removal opens with any key Ben kept.
        assertNothingOpensWith(stolen, net.published.drop(before))
    }

    @Test
    fun relaysWithholdingEverythingStopsPublishingAfterTheHeartbeatExpires() = runTest {
        val a = Phone("Ana"); val c = Phone("Cal")
        val id = circleWith(a, c)
        // Every relay withholds all of the admin's control events and messages from Cal.
        c.client.drop = { it.kind == Kinds.SEALED_DIRECT || it.pubkey != c.me && it.tags.any { t -> t[0] == "d" } && it.content.length < 1000 }
        a.client.offline()
        clock += Engine.HEARTBEAT_MAX_AGE_SECONDS + 60
        c.client.offline(); c.client.reconnect()
        sync(c)
        assertEquals(ShareStatus.ADMIN_AWAY, c.engine.shareStatus(c.circle(id)!!))
        val n = net.published.size
        c.engine.publishLocation(loc(1.0))
        assertEquals(n, net.published.size)
    }

    @Test
    fun aDeviceThatWasOffForDaysChecksBeforePublishing() = runTest {
        val a = Phone("Ana"); val c = Phone("Cal")
        val id = circleWith(a, c)
        c.client.offline()
        clock += 4 * 24 * 3600
        sync(a)
        // Back online, but the relays haven't finished sending what they hold yet.
        c.client.reconnect()
        assertNotEquals(ShareStatus.OK, c.engine.shareStatus(c.circle(id)!!))
        val n = net.published.size
        c.engine.publishLocation(loc(1.0))
        assertEquals(n, net.published.size)
        // Once synced, the admin's fresh heartbeat confirms the key, and the weekly rotation hasn't happened.
        net.flush()
        sync(a, c)
        assertEquals(ShareStatus.OK, c.engine.shareStatus(c.circle(id)!!))
    }

    @Test
    fun unacknowledgedRostersAreResentUntilConfirmed() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val id = circleWith(a, b, c)
        c.client.drop = { it.kind == Kinds.SEALED_DIRECT }
        a.engine.removeMember(id, b.me)
        net.flush()
        assertEquals(listOf(c.me), a.circle(id)!!.pendingAcks.map { it.pubkey }, "the admin can see Cal hasn't got the new key")
        val sentBefore = net.published.count { it.kind == Kinds.SEALED_DIRECT }
        clock += 300
        sync(a)
        assertTrue(net.published.count { it.kind == Kinds.SEALED_DIRECT } > sentBefore, "resent")
        c.client.drop = { false }
        c.client.offline(); c.client.reconnect()
        sync(a, c)
        assertTrue(a.circle(id)!!.pendingAcks.isEmpty())
        assertEquals(a.circle(id)!!.epoch, c.circle(id)!!.epoch)
    }

    @Test
    fun aMemberCannotOverwriteOrReplayAnotherMembersUpdates() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val id = circleWith(a, b, c)
        c.engine.publishLocation(loc(10.0))
        net.flush()
        assertEquals(10.0, a.locationOf(id, c.me)!!.lat)
        val calsEvent = net.published.last { it.pubkey != a.me && it.pubkey != b.me && it.kind == Kinds.CIRCLE_UPDATE && it.content.length > 2000 }

        // Ben, a member, knows the circle key. The v1 rule let him derive Cal's publisher key; in v2
        // he can't, so his forgery lands under a different key and Cal's update stays on the relay.
        val circle = b.circle(id)!!
        val key = CircleCrypto.keyForDay(circle.keyHex.hexToBytes(), circle.keyDay, id, circle.epoch, CircleCrypto.day(clock))!!
        val slot = CircleCrypto.slot(clock)
        val forger = CircleCrypto.publisher(key, c.me, slot)
        assertNotEquals(calsEvent.pubkey, forger.publicHex)
        clock += 5
        val forged = Event.create(forger, Kinds.CIRCLE_UPDATE, calsEvent.tags, calsEvent.content, clock)
        net.client { _, _ -> }.publish(forged)
        // He also re-signs Cal's real (old) update with his own key to replay it.
        val replay = Event.create(KeyPair.generate(), Kinds.CIRCLE_UPDATE, calsEvent.tags, calsEvent.content, clock + 1)
        net.client { _, _ -> }.publish(replay)
        net.flush()
        assertTrue(net.query(Filter(listOf(Kinds.CIRCLE_UPDATE), tagD = listOf(calsEvent.tag("d")!!))).any { it.id == calsEvent.id }, "Cal's update wasn't replaced")

        // Cal moves on; an exact rebroadcast of his old event changes nothing either.
        clock += 60
        c.engine.publishLocation(loc(11.0))
        net.flush()
        net.client { _, _ -> }.publish(calsEvent)
        net.flush()
        assertEquals(11.0, a.locationOf(id, c.me)!!.lat)

        // A body opened from the real event names Cal's own publisher key and a sequence number.
        val body = CircleCrypto.open(key, circle.epoch, calsEvent) as LocationBody
        assertEquals(calsEvent.pubkey, body.pub)
        assertTrue(body.seq > 0)
    }

    @Test
    fun yesterdaysKeyIsGoneAndTodaysCantReachBack() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        b.engine.publishLocation(loc(5.0))
        net.flush()
        val old = net.published.last { it.kind == Kinds.CIRCLE_UPDATE && it.content.length > 2000 }
        val day0 = CircleCrypto.day(clock)
        clock += 3 * 24 * 3600
        sync(a, b)
        val c = a.circle(id)!!
        assertTrue(c.keyDay >= day0 + 2, "the stored key moved forward")
        assertNull(CircleCrypto.keyForDay(c.keyHex.hexToBytes(), c.keyDay, id, c.epoch, day0), "earlier days can't be derived")
        // No key the phone still holds opens the old update.
        assertNothingOpensWith(c, listOf(old))
    }

    @Test
    fun backupsCarryOnlyCurrentKeys() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val id = circleWith(a, b, c)
        a.engine.removeMember(id, b.me)
        net.flush()
        assertNotNull(a.circle(id)!!.previousKeyHex)
        assertTrue(a.circle(id)!!.oldControls.isNotEmpty())
        a.engine.setPin("2468")
        val restored = Backups.decrypt(a.engine.exportBackup("correct horse battery"), "correct horse battery").circles.single()
        assertNull(restored.previousKeyHex)
        assertTrue(restored.oldControls.isEmpty())
        assertEquals(a.circle(id)!!.keyHex, restored.keyHex)
    }

    @Test
    fun weeklyRotationReplacesTheKeyWithFreshRandomness() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        val before = a.circle(id)!!
        clock += Engine.ROTATE_EVERY_SECONDS + 60
        sync(a, b)
        val after = a.circle(id)!!
        assertEquals(before.epoch + 1, after.epoch)
        assertNotEquals(before.ctlKeyHex, after.ctlKeyHex)
        // Not derivable from the old key by ratcheting.
        val ratcheted = CircleCrypto.keyForDay(before.keyHex.hexToBytes(), before.keyDay, id, before.epoch, after.keyDay)!!.toHex()
        assertNotEquals(ratcheted, after.keyHex)
        assertEquals(after.epoch, b.circle(id)!!.epoch)
        assertTrue(after.pendingAcks.isEmpty())
    }

    @Test
    fun relaysDontSeePermanentKeysOnceJoined() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        sync(a, b)
        listOf(a, b).forEach { p ->
            val filters = p.client.subs.values.flatten()
            assertFalse(filters.any { p.me in it.tagP }, "no subscription names the permanent key")
        }
        // Messages after the join (a role change and its acknowledgement) go to inbox tags, never to a public key.
        val before = net.published.size
        a.engine.setManager(id, b.me, true)
        sync(a, b)
        val later = net.published.drop(before).filter { it.kind == Kinds.SEALED_DIRECT }
        assertTrue(later.size >= 2)
        assertTrue(later.none { it.tag("p") == a.me || it.tag("p") == b.me })
        assertTrue(b.me in a.circle(id)!!.managers && b.me in b.circle(id)!!.managers)
    }

    @Test
    fun removedMemberWhoIgnoresTheNoticeIsToldAgainWhenAsking() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        b.client.drop = { it.kind == Kinds.SEALED_DIRECT }
        a.engine.removeMember(id, b.me)
        net.flush()
        assertNotNull(b.circle(id), "the removal notice was lost")
        assertTrue(b.circle(id)!!.stale, "but the closed notice stops Ben from publishing")
        b.client.drop = { false }
        clock += 3600
        sync(a, b)
        assertNull(b.circle(id), "asking for the roster gets the removal notice instead")
    }

    @Test
    fun v1CirclesUpgradeToV2() = runTest {
        // Two phones with a v1 circle from an older version of the app.
        val adminKeys = KeyPair.generate(); val memberKeys = KeyPair.generate()
        val v1 = Circle(
            id = CircleCrypto.newId(), name = "Family", keyHex = CircleCrypto.newKey(), epoch = 3, adminPubkey = adminKeys.publicHex,
            members = listOf(Member(adminKeys.publicHex, "Ana"), Member(memberKeys.publicHex, "Ben")),
        )
        val json = Json { encodeDefaults = true }
        fun stateFor(k: KeyPair) = MemoryStore(json.encodeToString(AppState.serializer(), AppState(identitySecretHex = k.secret.toHex(), circles = listOf(v1), setupDone = true)))
        val a = Phone("Ana", stateFor(adminKeys)); val b = Phone("Ben", stateFor(memberKeys))
        assertEquals(1, b.circle(v1.id)!!.version)
        sync(a, b)
        assertEquals(2, a.circle(v1.id)!!.version)
        assertEquals(2, b.circle(v1.id)!!.version)
        assertEquals(4, b.circle(v1.id)!!.epoch)
        b.engine.publishLocation(loc(7.0))
        net.flush()
        assertEquals(7.0, a.locationOf(v1.id, b.me)!!.lat)
    }

    // QM-01 ------------------------------------------------------------------------------------

    @Test
    fun aClonedIdentityLosesAccessWhenTheOwnerResetsIt() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        // Someone with Ben's unlocked phone (and his PIN) makes a backup and restores it elsewhere.
        b.engine.setPin("1357")
        val backup = b.engine.exportBackup("stolen passphrase")
        val clone = Phone("Clone")
        clone.engine.importBackup(backup, "stolen passphrase")
        assertEquals(b.me, clone.me)
        sync(a, b, clone)
        a.engine.publishLocation(loc(30.0))
        net.flush()
        assertEquals(30.0, clone.locationOf(id, a.me)!!.lat, "the clone can read the circle, which is the threat")

        // Ben notices and resets his identity. He leaves the circle, the admin rotates the key.
        clock += 30
        assertTrue(b.engine.unlock("1357") is PinResult.Ok)
        b.engine.resetIdentity()
        sync(a, b, clone)
        assertNotEquals(clone.me, b.me)
        assertFalse(a.circle(id)!!.members.any { it.pubkey == clone.me }, "the old identity is out")
        val stolen = clone.circle(id)
        clock += 60
        a.engine.publishLocation(loc(31.0))
        net.flush()
        assertTrue(clone.circle(id) == null || clone.locationOf(id, a.me)?.lat != 31.0, "the clone sees nothing new")
        if (stolen != null) assertNothingOpensWith(stolen, net.published.takeLast(5))
    }

    @Test
    fun anAdminResetMovesTheirCirclesToTheNewIdentity() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        a.engine.setPin("1357")
        val clone = Phone("Clone")
        clone.engine.importBackup(a.engine.exportBackup("stolen passphrase"), "stolen passphrase")
        val old = a.me
        a.engine.resetIdentity()
        sync(a, b)
        assertNotEquals(old, a.me)
        assertEquals(a.me, b.circle(id)!!.adminPubkey, "members follow the admin to the new identity")
        assertFalse(b.circle(id)!!.members.any { it.pubkey == old })
        assertEquals(a.circle(id)!!.epoch, b.circle(id)!!.epoch)
        b.engine.publishLocation(loc(12.0))
        net.flush()
        assertEquals(12.0, a.locationOf(id, b.me)!!.lat)
        // The clone of the old identity was never sent the new key.
        sync(clone)
        assertTrue(clone.circle(id)!!.epoch < a.circle(id)!!.epoch)
    }

    // QM-14 ------------------------------------------------------------------------------------

    @Test
    fun everyoneHearsWhenSomeoneJoinsOrLeaves() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val heard = mutableListOf<Notice>()
        val id = circleWith(a, b)
        val job = scope.launch { b.engine.notices.collect { heard += it } }
        kotlinx.coroutines.yield()
        val link = a.engine.createInvite(id)
        c.engine.acceptInvite(InviteLinks.decode(link)!!, scannedInPerson = true)
        sync(a, b, c)
        a.engine.approve(a.engine.state.value.joinRequests.single().id)
        sync(a, b, c)
        a.engine.removeMember(id, c.me)
        sync(a, b, c)
        kotlinx.coroutines.delay(100)
        job.cancel()
        assertTrue(heard.any { it.title == "Cal joined Family" && it.text == "Approved by Ana" }, heard.toString())
        assertTrue(heard.any { it.title == "Cal is no longer in Family" })
        val history = b.circle(id)!!.history.map { it.change to it.name }
        assertTrue(MembershipChange.JOINED to "Cal" in history && MembershipChange.LEFT to "Cal" in history)
        assertEquals(a.me, b.circle(id)!!.history.last { it.name == "Cal" && it.change == MembershipChange.JOINED }.by)
    }

    // QM-08 ------------------------------------------------------------------------------------

    @Test
    fun aTamperedInviteIsRejected() = runTest {
        val a = Phone("Ana")
        val c = a.engine.createCircle("Family")
        val link = a.engine.createInvite(c.id)
        val invite = InviteLinks.decode(link)!!
        assertEquals(2, invite.v)
        val evil = KeyPair.generate()
        // Swap the inviter's key, or the relays, and keep the signature: the link no longer decodes.
        assertNull(InviteLinks.decode(InviteLinks.encode(invite.copy(k = evil.publicHex))))
        assertNull(InviteLinks.decode(InviteLinks.encode(invite.copy(r = listOf("wss://evil.example")))))
        assertNull(InviteLinks.decode(InviteLinks.encode(invite.copy(e = invite.e + 86_400))))
        // Re-signed by the attacker, it decodes, but names the attacker's key: the device code the
        // real inviter reads out won't match it, so joining through it is refused.
        val resigned = InviteLinks.decode(InviteLinks.encode(InviteLinks.signed(evil, invite)))!!
        val b = Phone("Ben")
        assertFailsWith<IllegalArgumentException> { b.engine.acceptInvite(resigned, typedCode = DeviceCode.full(a.me)) }
        assertFailsWith<IllegalArgumentException> { b.engine.acceptInvite(invite) }
        assertTrue(b.engine.state.value.pendingJoins.isEmpty())
        // The right code (as read out by Ana) works.
        b.engine.acceptInvite(invite, typedCode = DeviceCode.full(a.me).lowercase().replace(" ", ""))
        assertEquals(1, b.engine.state.value.pendingJoins.size)
        assertTrue(b.engine.state.value.contacts[a.me]!!.verified)
    }

    @Test
    fun joiningDoesntShareUntilTheUserSeesTheCircleAndConfirms() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val c = a.engine.createCircle("Family")
        sync(a, b)
        b.engine.acceptInvite(InviteLinks.decode(a.engine.createInvite(c.id))!!, scannedInPerson = true)
        sync(a, b)
        a.engine.approve(a.engine.state.value.joinRequests.single().id)
        sync(a, b)
        val joined = b.circle(c.id)!!
        assertFalse(joined.confirmed)
        assertEquals(ShareStatus.NOT_CONFIRMED, b.engine.shareStatus(joined))
        val n = net.published.size
        b.engine.publishLocation(loc(1.0))
        net.flush()
        assertEquals(n, net.published.size, "no location and no profile before confirming")
        assertNull(a.locationOf(c.id, b.me))
        b.engine.confirmCircle(c.id)
        sync(a, b)
        b.engine.publishLocation(loc(2.0))
        net.flush()
        assertEquals(2.0, a.locationOf(c.id, b.me)!!.lat)
    }

    @Test
    fun anInviteWorksOnceAndJoinTrafficNeverNamesPublicKeys() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val x = Phone("Xavier")
        val c = a.engine.createCircle("Family")
        val invite = InviteLinks.decode(a.engine.createInvite(c.id))!!
        val before = net.published.size
        b.engine.acceptInvite(invite, scannedInPerson = true)
        sync(a, b, x)
        x.engine.acceptInvite(invite, scannedInPerson = true)
        sync(a, b, x)
        assertEquals(listOf(b.me), a.engine.state.value.joinRequests.map { it.pubkey }, "the second request is ignored")
        a.engine.approve(a.engine.state.value.joinRequests.single().id)
        sync(a, b, x)
        assertNotNull(b.circle(c.id))
        assertNull(x.circle(c.id))
        val joinTraffic = net.published.drop(before).filter { it.kind == Kinds.SEALED_DIRECT }
        assertTrue(joinTraffic.isNotEmpty())
        assertTrue(joinTraffic.none { it.tag("p") in setOf(a.me, b.me, x.me) }, "invite inboxes, not public keys")
        listOf(a, b).forEach { p -> assertFalse(p.client.subs.values.flatten().any { p.me in it.tagP }) }
    }

    @Test
    fun inviteRelaysAreUsedOnlyWhileJoining() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        a.engine.setRelays(listOf("wss://ana.example"))
        val c = a.engine.createCircle("Family")
        b.engine.setRelays(listOf("wss://ben.example"))
        val invite = InviteLinks.decode(a.engine.createInvite(c.id))!!
        b.engine.acceptInvite(invite, scannedInPerson = true)
        assertEquals(setOf("wss://ben.example", "wss://ana.example"), b.client.status.value.keys)
        assertEquals(listOf("wss://ben.example"), b.engine.state.value.relays, "not saved")
        sync(a, b)
        a.engine.approve(a.engine.state.value.joinRequests.single().id)
        sync(a, b)
        assertNotNull(b.circle(c.id))
        assertEquals(setOf("wss://ben.example"), b.client.status.value.keys, "dropped once joined")
    }

    // QM-12 ------------------------------------------------------------------------------------

    @Test
    fun eachCircleGetsOnlyThePrecisionChosenForIt() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        b.engine.setCirclePrecision(id, Precision.NEIGHBORHOOD)
        val exact = MemberLocation(lat = 41.881901, lon = -87.627801, accuracy = 4f, speed = 12f, heading = 90f, battery = 80, time = clock,
            points = listOf(app.quietmap.core.model.Point(41.8818, -87.6277, clock - 30)))
        b.engine.publishLocation(exact)
        net.flush()
        val seen = a.locationOf(id, b.me)!!
        assertEquals(Precision.NEIGHBORHOOD, seen.precision)
        assertNull(seen.speed); assertNull(seen.heading); assertTrue(seen.points.isEmpty())
        assertNull(seen.battery, "battery is off by default")
        assertTrue(kotlin.math.abs(seen.lat - exact.lat) < 0.02 && seen.lat != exact.lat)
        // More precise again needs the PIN once one is set.
        b.engine.setPin("1357")
        clock += Engine.AUTH_WINDOW_SECONDS + 1
        assertFailsWith<IllegalStateException> { b.engine.setCirclePrecision(id, Precision.EXACT) }
        b.engine.setCirclePrecision(id, Precision.CITY)
        assertFailsWith<IllegalStateException> { b.engine.setCircleTrail(id, true) }
    }

    @Test
    fun torRoutesRelaysThroughOrbotAndTurningItOffNeedsThePin() = runTest {
        val a = Phone("Ann")
        a.engine.housekeeping()
        assertNull(a.client.proxy)
        a.engine.setUseTor(true)
        assertEquals(app.quietmap.core.nostr.SocksProxy("127.0.0.1", Engine.TOR_SOCKS_PORT), a.client.proxy)
        a.engine.setPin("1357")
        clock += Engine.AUTH_WINDOW_SECONDS + 1
        assertFailsWith<IllegalStateException> { a.engine.setUseTor(false) }
        assertNotNull(a.client.proxy, "a failed attempt must leave Tor on")
        assertEquals(PinResult.Ok, a.engine.unlock("1357"))
        a.engine.setUseTor(false)
        assertNull(a.client.proxy)
    }

    @Test
    fun relaysSeeBlurredTimestampsThatNeverGoBackwards() = runTest {
        val a = Phone("Ann"); val b = Phone("Ben")
        val id = circleWith(a, b)
        var last = 0L
        val offsets = mutableSetOf<Long>()
        repeat(40) {
            clock += 61
            b.engine.publishLocation(loc(1.0 + it / 1000.0))
            net.flush()
            val e = b.client.published.last()
            assertEquals(Kinds.CIRCLE_UPDATE, e.kind)
            assertTrue(e.createdAt in (clock - 15)..clock, "timestamp ${clock - e.createdAt}s off")
            assertTrue(e.createdAt > last, "timestamps went backwards")
            last = e.createdAt
            offsets += clock - e.createdAt
            assertEquals(clock, a.locationOf(id, b.me)!!.time, "the exact time travels inside the encryption")
        }
        assertTrue(offsets.size > 3, "timestamps aren't blurred: $offsets")
    }

    /** Sends [body] to [to]'s inbox in [circleId], signed by [from]'s identity, as any phone holding that key could. */
    private suspend fun deliver(from: Phone, to: Phone, circleId: String, body: app.quietmap.core.protocol.Body) {
        val signer = KeyPair(from.engine.state.value.identitySecretHex.hexToBytes())
        val ctl = to.circle(circleId)!!.ctlKeyHex!!.hexToBytes()
        val inbox = CircleCrypto.inbox(ctl, to.me, CircleCrypto.day(clock))
        from.client.publish(app.quietmap.core.protocol.DirectMessages.seal(signer, to.me, body, clock, inbox), true)
        net.flush()
        to.engine.housekeeping()
    }

    private fun rosterOf(c: Circle, managers: List<String> = c.managers, day: Long = CircleCrypto.day(clock)) =
        app.quietmap.core.protocol.RosterCircle(
            c.id, c.name, CircleCrypto.keyForDay(c.keyHex.hexToBytes(), c.keyDay, c.id, c.epoch, day)!!.toHex(), c.epoch,
            c.adminPubkey, c.members, managers, v = 2, day = day, ctl = c.ctlKeyHex,
        )

    @Test
    fun removalNoticesOnlyWorkForTheirRecipientAndANewerEpoch() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val id = circleWith(a, b, c)
        val epoch = c.circle(id)!!.epoch
        // Admin-signed notices, passed on to Cal by someone who got hold of them.
        deliver(a, c, id, app.quietmap.core.protocol.RemovedBody(a.me, clock, id, to = b.me, epoch = epoch + 1))
        deliver(a, c, id, app.quietmap.core.protocol.RemovedBody(a.me, clock + 1, id))
        deliver(a, c, id, app.quietmap.core.protocol.RemovedBody(a.me, clock + 2, id, to = c.me, epoch = epoch))
        assertNotNull(c.circle(id), "a notice for someone else, or an old one, is ignored")
        deliver(a, c, id, app.quietmap.core.protocol.RemovedBody(a.me, clock + 3, id, to = c.me, epoch = epoch + 1))
        assertNull(c.circle(id))
    }

    @Test
    fun anOldRosterCantBePassedOnToUndoALaterChange() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben"); val c = Phone("Cal")
        val id = circleWith(a, b, c)
        a.engine.setManager(id, b.me, true)
        sync(a, b, c)
        assertTrue(b.me in c.circle(id)!!.managers)
        val asManager = a.circle(id)!!
        val then = clock
        clock += 60
        a.engine.setManager(id, b.me, false)
        sync(a, b, c)
        assertFalse(b.me in c.circle(id)!!.managers)
        deliver(a, c, id, app.quietmap.core.protocol.Roster2Body(a.me, then, rosterOf(asManager)))
        assertFalse(b.me in c.circle(id)!!.managers, "the roster that made Ben a manager can't be replayed")
    }

    @Test
    fun malformedRostersAreIgnored() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        val before = b.circle(id)!!
        val rc = rosterOf(a.circle(id)!!)
        clock += 5
        deliver(a, b, id, app.quietmap.core.protocol.Roster2Body(a.me, clock, rc.copy(day = 0)))
        deliver(a, b, id, app.quietmap.core.protocol.Roster2Body(a.me, clock + 1, rc.copy(ctl = "zz")))
        deliver(a, b, id, app.quietmap.core.protocol.Roster2Body(a.me, clock + 2, rc.copy(members = rc.members + Member("not a key", "X"))))
        sync(a, b)
        assertEquals(before.ctlKeyHex, b.circle(id)!!.ctlKeyHex)
        assertEquals(before.members, b.circle(id)!!.members)
    }

    @Test
    fun anOldLeftMessageCantRemoveSomeoneWhoCameBack() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        clock += 60
        deliver(b, a, id, app.quietmap.core.protocol.LeftBody(b.me, clock - 3 * 3600, id))
        assertTrue(a.circle(id)!!.members.any { it.pubkey == b.me }, "from before Ben joined: a replay")
        deliver(b, a, id, app.quietmap.core.protocol.LeftBody(b.me, clock, id))
        assertFalse(a.circle(id)!!.members.any { it.pubkey == b.me })
    }

    @Test
    fun membersWhoMissAResetStillFollowItAndACopyCantPullThemBack() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val id = circleWith(a, b)
        a.engine.setPin("1357")
        val copy = Phone("Copy")
        copy.engine.importBackup(a.engine.exportBackup("stolen passphrase"), "stolen passphrase")
        sync(copy)
        b.client.drop = { true } // Ben's phone misses the reset entirely
        a.engine.resetIdentity()
        sync(copy, a, b)
        b.client.drop = { false }
        assertNotEquals(a.me, b.circle(id)!!.adminPubkey)
        assertEquals(ShareStatus.KEY_REPLACED, copy.engine.shareStatus(copy.circle(id)!!), "the old copy sees it was replaced and stands down")
        // A week on, the copy doesn't rotate; Ana's phone resends the hand-over, signed by the old key.
        clock += Engine.ROTATE_EVERY_SECONDS + 1
        repeat(3) { sync(copy, a, b) }
        assertEquals(a.me, b.circle(id)!!.adminPubkey, "Ben follows Ana to her new identity")
        assertEquals(a.circle(id)!!.epoch, b.circle(id)!!.epoch)
        clock += Engine.ROTATE_EVERY_SECONDS + 1
        repeat(3) { sync(copy, a, b) }
        assertEquals(a.me, b.circle(id)!!.adminPubkey, "and the copy can't take him back")
        b.engine.publishLocation(loc(14.0))
        net.flush()
        assertEquals(14.0, a.locationOf(id, b.me)!!.lat)
        assertNull(a.circle(id)!!.handoverSecretHex, "the old secret is dropped once everyone has the new key")
    }

    @Test
    fun startingToShareWithANewCircleNeedsThePin() = runTest {
        val a = Phone("Ana"); val b = Phone("Ben")
        val c = a.engine.createCircle("Family")
        sync(a, b)
        b.engine.setPin("2468")
        b.engine.acceptInvite(InviteLinks.decode(a.engine.createInvite(c.id))!!, scannedInPerson = true)
        sync(a, b)
        a.engine.approve(a.engine.state.value.joinRequests.single().id)
        sync(a, b)
        clock += Engine.AUTH_WINDOW_SECONDS + 1
        assertFailsWith<IllegalStateException> { b.engine.confirmCircle(c.id) }
        assertEquals(PinResult.Ok, b.engine.unlock("2468"))
        b.engine.confirmCircle(c.id)
        assertTrue(b.circle(c.id)!!.confirmed)
    }

    /** Fails if any event opens with [c]'s keys for any day from its key day up to 400 days on. */
    private fun assertNothingOpensWith(c: Circle, events: List<Event>) {
        val keys = buildList {
            add(c.keyHex.hexToBytes())
            c.ctlKeyHex?.let { add(it.hexToBytes()) }
            (0L..10L).forEach { ahead -> CircleCrypto.keyForDay(c.keyHex.hexToBytes(), c.keyDay, c.id, c.epoch, c.keyDay + ahead)?.let { add(it) } }
        }
        events.filter { it.kind == Kinds.CIRCLE_UPDATE }.forEach { e ->
            keys.forEach { k -> assertNull(CircleCrypto.open(k, c.epoch, e) as? LocationBody, "an update opened with a removed member's key") }
        }
    }
}
