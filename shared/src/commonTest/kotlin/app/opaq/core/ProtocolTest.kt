package app.opaq.core

import app.opaq.core.crypto.KeyPair
import app.opaq.core.crypto.hexToBytes
import app.opaq.core.model.Activity
import app.opaq.core.model.LocationSample
import app.opaq.core.model.Member
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.LocationPrecision
import app.opaq.core.model.Point
import app.opaq.core.model.Precision
import app.opaq.core.nostr.Kinds
import app.opaq.core.protocol.CircleCrypto
import app.opaq.core.protocol.DeviceCode
import app.opaq.core.protocol.DirectMessages
import app.opaq.core.protocol.Envelopes
import app.opaq.core.protocol.InviteData
import app.opaq.core.protocol.InviteLinks
import app.opaq.core.protocol.InviteProblem
import app.opaq.core.protocol.JoinRefusedBody
import app.opaq.core.protocol.JoinRequestBody
import app.opaq.core.protocol.LocationBody
import app.opaq.core.protocol.ProfileBody
import app.opaq.core.protocol.EpochBody
import app.opaq.core.protocol.Roster2Body
import app.opaq.core.protocol.RosterAckBody
import app.opaq.core.protocol.RosterBody
import app.opaq.core.protocol.RosterCircle
import app.opaq.core.protocol.RosterRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private val now = 1_763_000_000L

    private fun sampleLocation(points: Int = 6) = MemberLocation(
        lat = 41.881900, lon = -87.627800, accuracy = 8f, speed = 18.8f, heading = 270f,
        activity = Activity.DRIVING, battery = 64, time = now,
        points = List(points) { Point(41.8819 + it * 0.0001, -87.6278 - it * 0.0001, now - 50 + it * 10) },
    )

    @Test
    fun envelopeRejectsForgedSender() {
        val mom = KeyPair.generate()
        val mallory = KeyPair.generate()
        val body = JoinRequestBody(from = mom.publicHex, ts = now, circle = "c", secret = "s".repeat(32), name = "Mom")
        // Mallory signs a body that claims to come from Mom.
        val forged = Envelopes.seal(mallory, body)
        assertNull(Envelopes.open(forged))
        assertIs<JoinRequestBody>(Envelopes.open(Envelopes.seal(mom, body)))
    }

    @Test
    fun directMessageOnlyOpensForRecipient() {
        val jordan = KeyPair.generate()
        val mom = KeyPair.generate()
        val other = KeyPair.generate()
        val roster = RosterBody(jordan.publicHex, now, RosterCircle("cid", "Family", CircleCrypto.newKey(), 1, jordan.publicHex, listOf(Member(jordan.publicHex, "Jordan"))))
        val event = DirectMessages.seal(jordan, mom.publicHex, roster, now)
        assertEquals(Kinds.SEALED_DIRECT, event.kind)
        assertNotEquals(jordan.publicHex, event.pubkey, "outer event must not reveal the sender")
        assertTrue(event.isValid())
        assertEquals(roster, DirectMessages.open(mom, event))
        assertNull(DirectMessages.open(other, event))
    }

    @Test
    fun circleUpdatesRoundTripAndHideTheSender() {
        val mom = KeyPair.generate()
        val key = CircleCrypto.newKey().hexToBytes()
        val body = LocationBody(mom.publicHex, now, "cid", 3, sampleLocation())
        val event = CircleCrypto.seal(mom, key, 3, body, now)
        assertEquals(Kinds.CIRCLE_UPDATE, event.kind)
        assertNotEquals(mom.publicHex, event.pubkey)
        assertEquals(CircleCrypto.tag(key, 3, CircleCrypto.slot(now)), event.tag("d"))
        assertEquals(body, CircleCrypto.open(key, 3, event))
        assertNull(CircleCrypto.open(key, 4, event), "a different epoch must not decrypt")
        assertNull(CircleCrypto.open(CircleCrypto.newKey().hexToBytes(), 3, event), "a removed member's old key must not decrypt")
    }

    @Test
    fun locationUpdatesAreAllTheSameSize() {
        val mom = KeyPair.generate()
        val key = CircleCrypto.newKey().hexToBytes()
        val sizes = listOf(0, 1, 6).map { n ->
            CircleCrypto.seal(mom, key, 1, LocationBody(mom.publicHex, now, "cid", 1, sampleLocation(n)), now).content.length
        }
        assertEquals(1, sizes.toSet().size, "sizes were $sizes")
        assertEquals(Engine.MAX_LOCATION_CONTENT, sizes.first())
    }

    @Test
    fun publisherKeyAndTagRotateHourly() {
        val key = CircleCrypto.newKey().hexToBytes()
        val member = KeyPair.generate().publicHex
        val s = CircleCrypto.slot(now)
        assertEquals(CircleCrypto.publisher(key, member, s).publicHex, CircleCrypto.publisher(key, member, s).publicHex)
        assertNotEquals(CircleCrypto.publisher(key, member, s).publicHex, CircleCrypto.publisher(key, member, s + 1).publicHex)
        assertNotEquals(CircleCrypto.tag(key, 1, s), CircleCrypto.tag(key, 1, s + 1))
        assertNotEquals(CircleCrypto.tag(key, 1, s), CircleCrypto.tag(key, 2, s))
    }

    @Test
    fun inviteLinksRoundTrip() {
        val jordan = KeyPair.generate()
        val unsigned = InviteData(k = jordan.publicHex, n = "Jordan", r = listOf("wss://nos.lol"), c = "cid", cn = "Family", s = "a".repeat(32), e = now + 3600)
        assertNull(InviteLinks.decode(InviteLinks.encode(unsigned)), "unsigned v1 links are refused")
        assertNull(InviteLinks.decode(InviteLinks.encode(InviteLinks.signed(jordan, unsigned).copy(v = 1, x = null))), "no downgrading a v2 link")
        val data = InviteLinks.signed(jordan, unsigned)
        val link = InviteLinks.encode(data)
        assertTrue(link.startsWith("opaq://join#"))
        assertEquals(data, InviteLinks.decode(link))
        assertEquals(data, InviteLinks.decode("Join my circle: $link"))
        assertNull(InviteLinks.decode("opaq://join#garbage"))
        val web = InviteLinks.webLink(link)
        assertTrue(web.startsWith("https://carterkeel.github.io/opaq/join#"))
        assertEquals(link.substringAfter('#'), web.substringAfter('#'), "the invite stays after #, so the web host never gets it")
        assertEquals(data, InviteLinks.decode("Join my circle \"Family\" on Opaq: $web"))
    }

    @Test
    fun invitesAreFoundWhereverTheyAreInAPastedMessage() {
        val jordan = KeyPair.generate()
        val data = InviteLinks.signed(jordan, InviteData(k = jordan.publicHex, n = "Jordan", r = listOf("wss://nos.lol", "wss://relay.damus.io"), c = "c".repeat(32), cn = "Family", s = "a".repeat(32), e = now + 3600))
        val link = InviteLinks.encode(data)
        val payload = link.substringAfter('#')
        val web = InviteLinks.webLink(link)
        listOf(
            web,
            link,
            payload,
            "  $web\n",
            "Join my circle \"Family\" on Opaq:\n$web\nSee you there!",
            InviteLinks.shareText("Family", link),
            "Open this: $web.",
            "<$web>",
            "($link)",
            // The page hands Android an intent with the invite in the path, not after #.
            "opaq://join/$payload",
            // Links made before the rename to Opaq, pasted in.
            "quietmap://join/$payload",
            "https://carterkeel.github.io/quietmap/join#$payload",
            // A # some app percent-encoded.
            web.replace("#", "%23"),
            // Long words broken by a line break or an invisible character.
            web.substring(0, web.length - 20) + "\n" + web.takeLast(20),
            web.substring(0, web.length - 20) + "\u200B" + web.takeLast(20),
        ).forEach { text -> assertEquals(data, InviteLinks.decode(text), text) }
        assertNull(InviteLinks.problem(web))
        assertTrue(InviteLinks.shareText("Family", link).endsWith("\n$web"), "the link ends the message")
    }

    @Test
    fun brokenInviteLinksSayWhatWentWrong() {
        val jordan = KeyPair.generate()
        val data = InviteLinks.signed(jordan, InviteData(k = jordan.publicHex, n = "Jordan", r = listOf("wss://nos.lol"), c = "c".repeat(32), cn = "Family", s = "a".repeat(32), e = now + 3600))
        val web = InviteLinks.webLink(InviteLinks.encode(data))
        // Cut off at the #, as some apps do.
        assertEquals(InviteProblem.MISSING, InviteLinks.problem(web.substringBefore('#')))
        assertEquals(InviteProblem.MISSING, InviteLinks.problem(web.substringBefore('#') + "#"))
        assertEquals(InviteProblem.MISSING, InviteLinks.problem("opaq://join"))
        assertEquals(InviteProblem.MISSING, InviteLinks.problem("quietmap://join"))
        // Cut short or changed.
        assertEquals(InviteProblem.DAMAGED, InviteLinks.problem(web.dropLast(30)))
        assertEquals(InviteProblem.DAMAGED, InviteLinks.problem(InviteLinks.encode(data.copy(cn = "Other"))))
        assertEquals(InviteProblem.NOT_AN_INVITE, InviteLinks.problem("hello"))
        assertNull(InviteLinks.decode(""))
    }

    @Test
    fun deviceCodeFormat() {
        val code = DeviceCode.full(KeyPair.generate().publicHex)
        assertTrue(Regex("^[0-9A-F]{4}( [0-9A-F]{4}){3}$").matches(code), code)
    }

    @Test
    fun profilesRoundTripWithAStablePublisherPerEpoch() {
        val mom = KeyPair.generate()
        val key = CircleCrypto.newKey().hexToBytes()
        val body = ProfileBody(mom.publicHex, now, "Mom", color = 4, photo = "AAAA")
        val event = CircleCrypto.sealProfile(mom, key, 2, body, now)
        assertEquals(CircleCrypto.profileTag(key, 2), event.tag("d"))
        assertEquals(CircleCrypto.profilePublisher(key, mom.publicHex, 2).publicHex, event.pubkey)
        assertEquals(event.pubkey, CircleCrypto.sealProfile(mom, key, 2, body, now + 7200).pubkey, "same key all epoch, so relays replace it")
        assertNotEquals(event.pubkey, CircleCrypto.sealProfile(mom, key, 3, body, now).pubkey)
        assertEquals(body, CircleCrypto.open(key, 2, event))
        assertNotEquals(CircleCrypto.profileTag(key, 2), CircleCrypto.tag(key, 2, CircleCrypto.slot(now)))
    }

    private fun sample(t: Long, lat: Double, speed: Float?, acc: Float = 5f) = LocationSample(lat, -87.0, acc, speed, null, t)

    @Test
    fun motionPolicyClassifiesWithHysteresis() {
        assertEquals(Activity.STILL, MotionPolicy.classify(0.2f, Activity.UNKNOWN))
        assertEquals(Activity.WALKING, MotionPolicy.classify(1.4f, Activity.STILL))
        assertEquals(Activity.WALKING, MotionPolicy.classify(2.6f, Activity.WALKING), "just over the boundary keeps walking")
        assertEquals(Activity.CYCLING, MotionPolicy.classify(4f, Activity.WALKING))
        assertEquals(Activity.DRIVING, MotionPolicy.classify(6.5f, Activity.DRIVING), "slowing in traffic stays driving")
        assertEquals(Activity.DRIVING, MotionPolicy.classify(18f, Activity.STILL))
    }

    @Test
    fun motionPolicyPublishesPromptlyWhenMovementStartsAndStops() {
        val p = MotionPolicy()
        var t = now
        p.add(sample(t, 41.0, 0f))
        assertTrue(p.shouldPublish(t), "first fix publishes right away")
        p.markPublished(t)
        assertFalse(p.isMoving(t))

        // 30 s later the phone starts driving: publish without waiting out the 5-minute still interval.
        t += 30
        p.add(sample(t, 41.001, 15f))
        p.add(sample(t + 5, 41.002, 16f))
        t += 5
        assertEquals(Activity.DRIVING, p.activity())
        assertTrue(p.shouldPublish(t))
        p.markPublished(t)
        assertFalse(p.shouldPublish(t + 10), "no flood right after publishing")
        assertFalse(p.shouldPublish(t + 20), "never faster than the moving rate")
        assertTrue(p.shouldPublish(t + 35), "about every half minute while moving")

        // Parks: the stop is reported promptly instead of showing "Driving" for minutes.
        p.markPublished(t + 70)
        t += 100
        p.add(sample(t, 41.003, 0f))
        p.add(sample(t + 5, 41.003, 0f))
        t += 5
        assertEquals(Activity.STILL, p.activity())
        assertTrue(p.shouldPublish(t))
    }

    @Test
    fun motionHintSwitchesToGpsBeforeAnyFixMoves() {
        val p = MotionPolicy()
        p.add(sample(now, 41.0, 0f))
        p.markPublished(now)
        assertFalse(p.wantsHighAccuracy(now + 10))
        p.motionHint(now + 10)
        assertTrue(p.wantsHighAccuracy(now + 11))
    }

    @Test
    fun stopIsReportedWhenFixesStopComing() {
        // Driving, then parked: the phone sends no more fixes (iOS distance filter, idle GPS).
        val p = MotionPolicy()
        var t = now
        p.add(sample(t, 41.000, 15f))
        p.add(sample(t + 5, 41.001, 15f))
        t += 5
        assertEquals(Activity.DRIVING, p.activity())
        p.markPublished(t)

        p.refresh(t + 30)
        assertEquals(Activity.DRIVING, p.activity(), "a short gap between fixes isn't a stop")
        p.refresh(t + MotionPolicy.QUIET_SECONDS)
        assertEquals(Activity.STILL, p.activity(), "no fix for a minute means stopped")
        assertTrue(p.shouldPublish(t + MotionPolicy.QUIET_SECONDS), "the stop goes out straight away")
        val loc = p.snapshot(null, now = t + MotionPolicy.QUIET_SECONDS)!!
        assertEquals(Activity.STILL, loc.activity)
        assertEquals(0f, loc.speed)
        assertEquals(t + MotionPolicy.QUIET_SECONDS, loc.time)
    }

    @Test
    fun stillUpdatesCarryTheSendTimeNotTheLastFix() {
        // Standing still for an hour with no new fixes: each update must look fresh, not "1 h ago"
        // (which would show the person as offline after 45 minutes).
        val p = MotionPolicy()
        p.add(sample(now, 41.0, 0f))
        p.markPublished(now)
        p.refresh(now + 3600)
        val loc = p.snapshot(80, now = now + 3600)!!
        assertEquals(now + 3600, loc.time)
        assertEquals(41.0, loc.lat)

        // A moving update keeps the fix's own time, since the position is only true for that moment.
        val m = MotionPolicy()
        m.add(sample(now, 41.000, 15f))
        m.add(sample(now + 5, 41.001, 15f))
        assertEquals(now + 5, m.snapshot(null, now = now + 8)!!.time)
    }

    @Test
    fun cachedFixFromBeforeSharingIsNotShared() {
        // The OS hands over its last known position from an hour ago, maybe somewhere else.
        val p = MotionPolicy()
        p.add(sample(now - 3600, 41.0, 0f), receivedAt = now, ageSeconds = 3600)
        assertEquals(null, p.latest)
        assertFalse(p.shouldPublish(now))
        // A fresh fix is used as normal.
        p.add(sample(now - 2, 42.0, 0f), receivedAt = now, ageSeconds = 2)
        assertEquals(42.0, p.latest?.lat)
        assertTrue(p.shouldPublish(now))
    }

    @Test
    fun phoneClockSetWrongDoesNotBreakMotion() {
        // Fixes carry GPS time; this phone's clock is 10 minutes fast. Fresh fixes must still be
        // used, driving must stay driving, and still updates stay on the fixes' clock.
        val skew = 600L
        val p = MotionPolicy()
        p.add(sample(now, 41.000, 15f), receivedAt = now + skew, ageSeconds = 0)
        p.add(sample(now + 5, 41.001, 15f), receivedAt = now + 5 + skew, ageSeconds = 0)
        p.refresh(now + 10 + skew)
        assertEquals(Activity.DRIVING, p.activity(), "a 10-minute clock error isn't a 10-minute gap")

        // Parked: zero-speed fixes once the driving ones have left the 30 s speed window.
        p.add(sample(now + 40, 41.001, 0f), receivedAt = now + 40 + skew, ageSeconds = 0)
        p.add(sample(now + 45, 41.001, 0f), receivedAt = now + 45 + skew, ageSeconds = 0)
        assertEquals(Activity.STILL, p.activity())
        assertEquals(now + 45 + 300, p.snapshot(null, now = now + 45 + skew + 300)!!.time)
    }

    @Test
    fun oneBadSpeedReadingIsNotMovement() {
        // Standing still; one GPS fix reports 20 m/s by mistake.
        val p = MotionPolicy()
        p.add(sample(now, 41.0, 0f))
        assertEquals(Activity.STILL, p.activity())
        p.add(sample(now + 40, 41.0, 20f))
        assertEquals(Activity.STILL, p.activity(), "a lone reading doesn't start a drive")
        p.add(sample(now + 45, 41.0, 0f))
        assertEquals(Activity.STILL, p.activity(), "one spike next to a still reading isn't a drive")

        // A first fix that says moving waits for the next one instead of guessing.
        val q = MotionPolicy()
        q.add(sample(now, 41.0, 15f))
        assertEquals(Activity.UNKNOWN, q.activity())
        q.add(sample(now + 5, 41.001, 15f))
        assertEquals(Activity.DRIVING, q.activity())
    }

    @Test
    fun coarseFixJitterIsNotMovement() {
        val p = MotionPolicy()
        // Network fixes wobbling 40 m with 60 m accuracy: still, not walking.
        p.add(sample(now, 41.0, null, acc = 60f))
        p.add(sample(now + 60, 41.00036, null, acc = 60f))
        assertEquals(0f, p.estimateSpeed())
        assertFalse(p.isMoving(now + 60))
    }

    @Test
    fun v2BodiesRoundTripAndOldOnesStillDecode() {
        val me = KeyPair.generate()
        val rc = RosterCircle("c1", "Family", "aa".repeat(32), 2, me.publicHex, listOf(Member(me.publicHex, "Ana")), v = 2, day = 20_400, ctl = "bb".repeat(32))
        val bodies = listOf(
            Roster2Body(me.publicHex, now, rc),
            RosterAckBody(me.publicHex, now, "c1", 2),
            RosterRequestBody(me.publicHex, now, "c1", 2),
            EpochBody(me.publicHex, now, "c1", 2, closed = true),
            LocationBody(me.publicHex, now, "c1", 2, sampleLocation(), pub = "cc".repeat(32), seq = 42),
            ProfileBody(me.publicHex, now, "Ana", pub = "dd".repeat(32)),
            RosterBody(me.publicHex, now, rc.copy(v = 1, day = 0, ctl = null)),
            JoinRefusedBody(me.publicHex, now, "c1", to = "ee".repeat(32), invite = "ff".repeat(32), reason = JoinRefusedBody.USED),
        )
        bodies.forEach { assertEquals(it, Envelopes.open(Envelopes.seal(me, it))) }
    }

    @Test
    fun ratchetIsDeterministicOneWayAndCircleSpecific() {
        val k = KeyPair.generate().secret
        val d5 = CircleCrypto.keyForDay(k, 1, "c", 1, 5)!!
        assertTrue(d5.contentEquals(CircleCrypto.keyForDay(CircleCrypto.keyForDay(k, 1, "c", 1, 3)!!, 3, "c", 1, 5)!!))
        assertNull(CircleCrypto.keyForDay(d5, 5, "c", 1, 4), "no way back to an earlier day")
        assertFalse(d5.contentEquals(CircleCrypto.keyForDay(k, 1, "other", 1, 5)!!))
        assertFalse(d5.contentEquals(CircleCrypto.keyForDay(k, 1, "c", 2, 5)!!))
    }

    @Test
    fun memberPublisherKeysDependOnTheMembersOwnSecret() {
        val a = KeyPair.generate(); val b = KeyPair.generate()
        val pa = CircleCrypto.memberPublisher(a.secret, "c", 1, 100)
        assertNotEquals(pa.publicHex, CircleCrypto.memberPublisher(b.secret, "c", 1, 100).publicHex)
        assertNotEquals(pa.publicHex, CircleCrypto.memberPublisher(a.secret, "c", 1, 101).publicHex, "changes every hour")
        assertNotEquals(CircleCrypto.closedTag(a.secret, 1), CircleCrypto.controlTag(a.secret, 1, 0))
    }

    @Test
    fun approximateLocationsSnapToAFixedGridAndCantBeAveragedBack() {
        val rnd = kotlin.random.Random(7)
        val truth = 41.8819 to -87.6278
        val snapped = (1..200).map {
            val fix = MemberLocation(truth.first + rnd.nextDouble(-0.0005, 0.0005), truth.second + rnd.nextDouble(-0.0005, 0.0005), 5f, speed = 3f, heading = 10f, time = now)
            LocationPrecision.apply(fix, Precision.NEIGHBORHOOD)
        }.map { it.lat to it.lon }.toSet()
        // Every fix in the cell gives the same answer, so averaging many updates learns nothing more.
        assertTrue(snapped.size <= 4, "at most the cells the noise straddles: ${snapped.size}")
        val city = LocationPrecision.apply(sampleLocation(), Precision.CITY)
        assertTrue(kotlin.math.abs(city.lat - 41.8819) < 0.2 && kotlin.math.abs(city.lon + 87.6278) < 0.3)
        assertNull(city.speed); assertNull(city.heading); assertTrue(city.points.isEmpty())
        assertEquals(Precision.CITY, city.precision)
        assertEquals(sampleLocation(), LocationPrecision.apply(sampleLocation(), Precision.EXACT))
        assertTrue(LocationPrecision.apply(sampleLocation(), Precision.EXACT, trail = false).points.isEmpty())
    }
}
