package app.quietmap.core.protocol

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.Keys
import app.quietmap.core.crypto.Nip44
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.fromBase64Url
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toBase64Url
import app.quietmap.core.crypto.toHex
import app.quietmap.core.model.Member
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Kinds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val ProtocolJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    encodeDefaults = false
}

// ---------------------------------------------------------------------------------------------
// Message bodies. Every body is signed by the sender's identity key inside the encryption, so
// recipients know who really sent it even though the outer Nostr event uses a throwaway key.
// ---------------------------------------------------------------------------------------------

@Serializable
sealed class Body {
    abstract val from: String
    abstract val ts: Long
}

@Serializable
@SerialName("loc")
data class LocationBody(
    override val from: String,
    override val ts: Long,
    val circle: String,
    val epoch: Int,
    val loc: MemberLocation,
    /**
     * v2: the outer event's public key. The sender derives it from its own secret, and signs it here,
     * so nobody else in the circle can publish (or replace an update) under it.
     */
    val pub: String? = null,
    /** v2: grows with every update from this sender; receivers reject anything not newer (replays). */
    val seq: Long = 0,
) : Body()

@Serializable
@SerialName("join_req")
data class JoinRequestBody(override val from: String, override val ts: Long, val circle: String, val secret: String, val name: String) : Body()

/**
 * The inviter's answer to a join request that can't go ahead, so the joiner isn't left waiting.
 * [to] names the joiner and [invite] the invite's inbox ([InviteLinks.inbox]), so a refusal can't
 * be passed on to cancel someone else's join, or a different join of the same person.
 */
@Serializable
@SerialName("join_refused")
data class JoinRefusedBody(
    override val from: String,
    override val ts: Long,
    val circle: String,
    val to: String,
    val invite: String,
    /** One of [USED], [EXPIRED], [DENIED], [CANCELLED]. */
    val reason: String,
) : Body() {
    companion object {
        const val USED = "used"
        const val EXPIRED = "expired"
        const val DENIED = "denied"
        const val CANCELLED = "cancelled"
    }
}

@Serializable
data class RosterCircle(
    val id: String,
    val name: String,
    /** v1: the circle key. v2: the location key for [day]; later days are ratcheted from it. */
    val key: String,
    val epoch: Int,
    val admin: String,
    val members: List<Member>,
    val managers: List<String> = emptyList(),
    /** Circle protocol version (see [CircleCrypto]). */
    val v: Int = 1,
    /** v2: UTC day number [key] belongs to. */
    val day: Long = 0,
    /** v2: this epoch's control key (heartbeats, profiles, inboxes). */
    val ctl: String? = null,
)

@Serializable
@SerialName("roster")
data class RosterBody(override val from: String, override val ts: Long, val circle: RosterCircle) : Body()

/**
 * A v2 roster. It has its own type so an older app ignores it instead of misreading a v2 key
 * as a v1 one.
 */
@Serializable
@SerialName("roster2")
data class Roster2Body(override val from: String, override val ts: Long, val circle: RosterCircle) : Body()

/** A member confirms it has the roster (and key) for [epoch], so the sender stops retrying. */
@Serializable
@SerialName("roster_ack")
data class RosterAckBody(override val from: String, override val ts: Long, val circle: String, val epoch: Int) : Body()

/** A member that can't confirm its key is current asks the admin to send the roster again. */
@Serializable
@SerialName("roster_req")
data class RosterRequestBody(override val from: String, override val ts: Long, val circle: String, val epoch: Int) : Body()

/**
 * The admin's signed statement about a key epoch, published under that epoch's control key.
 * `closed = false` is the heartbeat ("[epoch] is current"); `closed = true` says it has been
 * replaced, so members still on it stop publishing and ask for the new roster.
 */
@Serializable
@SerialName("epoch")
data class EpochBody(override val from: String, override val ts: Long, val circle: String, val epoch: Int, val closed: Boolean = false) : Body()

/** A member's name, avatar color and optional photo, published to each of their circles. */
@Serializable
@SerialName("profile")
data class ProfileBody(
    override val from: String,
    override val ts: Long,
    val name: String,
    val color: Int? = null,
    val photo: String? = null,
    /** v2: the outer event's public key, which only the sender can derive (see [LocationBody.pub]). */
    val pub: String? = null,
) : Body()

@Serializable
@SerialName("removed")
data class RemovedBody(
    override val from: String,
    override val ts: Long,
    val circle: String,
    /** The admin deleted the whole circle, rather than removing just you. */
    val deleted: Boolean = false,
    /**
     * v2: who this notice is for, and the epoch it ends (newer than the recipient's), so nobody can
     * pass a notice meant for someone else, or an old one, on to a member to make them leave.
     */
    val to: String? = null,
    val epoch: Int? = null,
) : Body()

/** A member tells the admin they left, so the admin can rotate the key without them. */
@Serializable
@SerialName("left")
data class LeftBody(override val from: String, override val ts: Long, val circle: String) : Body()

@Serializable
private data class Envelope(val b: String, val s: String, val p: String? = null)

object Envelopes {
    /** Location envelopes are padded to this many characters so every update looks the same size. */
    const val LOCATION_SIZE = 1400

    fun seal(identity: KeyPair, body: Body, padTo: Int? = null): String {
        val bodyJson = ProtocolJson.encodeToString(Body.serializer(), body)
        val sig = identity.sign(PlatformCrypto.sha256(bodyJson.encodeToByteArray())).toHex()
        val bare = ProtocolJson.encodeToString(Envelope.serializer(), Envelope(bodyJson, sig))
        if (padTo == null) return bare
        val overhead = ProtocolJson.encodeToString(Envelope.serializer(), Envelope(bodyJson, sig, "")).length
        val padLen = (padTo - overhead).coerceAtLeast(0)
        return ProtocolJson.encodeToString(Envelope.serializer(), Envelope(bodyJson, sig, "0".repeat(padLen)))
    }

    /** Returns the body only if its signature matches the identity it claims to come from. */
    fun open(text: String): Body? = try {
        val env = ProtocolJson.decodeFromString(Envelope.serializer(), text)
        val body = ProtocolJson.decodeFromString(Body.serializer(), env.b)
        val ok = Keys.verify(env.s.hexToBytes(), PlatformCrypto.sha256(env.b.encodeToByteArray()), body.from.hexToBytes())
        if (ok) body else null
    } catch (_: Throwable) {
        null
    }
}

// ---------------------------------------------------------------------------------------------
// Sealed direct messages: invites, approvals, roster updates.
// ---------------------------------------------------------------------------------------------

object DirectMessages {
    private const val EXPIRY_SECONDS = 7 * 24 * 3600L

    /**
     * Encrypts [body] to [recipientPubkey]. [inbox] is the `p` tag relays see: the recipient's public
     * key for v1 circles, or a rotating inbox tag ([CircleCrypto.inbox]) that hides who receives it.
     */
    fun seal(identity: KeyPair, recipientPubkey: String, body: Body, now: Long, inbox: String = recipientPubkey): Event {
        val throwaway = KeyPair.generate()
        val conv = Nip44.conversationKey(throwaway.secret, recipientPubkey.hexToBytes())
        val content = Nip44.encrypt(Envelopes.seal(identity, body), conv)
        val tags = listOf(listOf("p", inbox), listOf("expiration", (now + EXPIRY_SECONDS).toString()))
        // Blur the timestamp slightly so relays can't line it up exactly with other activity.
        val jitter = (PlatformCrypto.randomBytes(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) } % 900)
        return Event.create(throwaway, Kinds.SEALED_DIRECT, tags, content, now - jitter)
    }

    /** Opens a sealed message addressed to [identity]. The caller decides which inbox tags it listens on. */
    fun open(identity: KeyPair, event: Event): Body? = try {
        if (event.kind != Kinds.SEALED_DIRECT) null
        else {
            val conv = Nip44.conversationKey(identity.secret, event.pubkey.hexToBytes())
            Envelopes.open(Nip44.decrypt(event.content, conv))
        }
    } catch (_: Throwable) {
        null
    }
}

// ---------------------------------------------------------------------------------------------
// Circle updates. One event per member per hour slot, replaced in place on the relays.
// The signing key and the d-tag both change every hour, so relays can't link a member's
// updates across hours.
// ---------------------------------------------------------------------------------------------

object CircleCrypto {
    private const val SLOT_SECONDS = 3600L
    private const val EXPIRY_SECONDS = 2 * 3600L

    fun slot(now: Long): Long = now / SLOT_SECONDS

    fun tag(circleKey: ByteArray, epoch: Int, slot: Long): String =
        PlatformCrypto.hmacSha256(circleKey, "qm-tag|$epoch|$slot".encodeToByteArray()).toHex().take(32)

    fun publisher(circleKey: ByteArray, memberPubkey: String, slot: Long): KeyPair =
        KeyPair.fromSeed(PlatformCrypto.hmacSha256(circleKey, "qm-pub|$memberPubkey|$slot".encodeToByteArray()))

    private fun conversationKey(circleKey: ByteArray, epoch: Int): ByteArray =
        Nip44.conversationKeyFromSecret(circleKey, "quietmap-circle|$epoch")

    fun seal(identity: KeyPair, circleKey: ByteArray, epoch: Int, body: Body, now: Long): Event {
        val s = slot(now)
        val tags = listOf(listOf("d", tag(circleKey, epoch, s)), listOf("expiration", (now + EXPIRY_SECONDS).toString()))
        val content = Nip44.encrypt(Envelopes.seal(identity, body, Envelopes.LOCATION_SIZE), conversationKey(circleKey, epoch))
        return Event.create(publisher(circleKey, identity.publicHex, s), Kinds.CIRCLE_UPDATE, tags, content, now)
    }

    fun open(circleKey: ByteArray, epoch: Int, event: Event): Body? = try {
        Envelopes.open(Nip44.decrypt(event.content, conversationKey(circleKey, epoch)))
    } catch (_: Throwable) {
        null
    }

    // Profiles change rarely, so they use one tag and one publisher key per circle key (epoch)
    // rather than per hour. Relays keep only the newest profile per member.
    private const val PROFILE_EXPIRY_SECONDS = 30 * 24 * 3600L

    /** Largest accepted photo, in base64 characters (about 24 KB of JPEG). */
    const val MAX_PHOTO_CHARS = 32_768

    fun profileTag(circleKey: ByteArray, epoch: Int): String =
        PlatformCrypto.hmacSha256(circleKey, "qm-profile|$epoch".encodeToByteArray()).toHex().take(32)

    fun profilePublisher(circleKey: ByteArray, memberPubkey: String, epoch: Int): KeyPair =
        KeyPair.fromSeed(PlatformCrypto.hmacSha256(circleKey, "qm-profile-pub|$memberPubkey|$epoch".encodeToByteArray()))

    fun sealProfile(identity: KeyPair, circleKey: ByteArray, epoch: Int, body: ProfileBody, now: Long): Event {
        val tags = listOf(listOf("d", profileTag(circleKey, epoch)), listOf("expiration", (now + PROFILE_EXPIRY_SECONDS).toString()))
        val content = Nip44.encrypt(Envelopes.seal(identity, body), conversationKey(circleKey, epoch))
        return Event.create(profilePublisher(circleKey, identity.publicHex, epoch), Kinds.CIRCLE_UPDATE, tags, content, now)
    }

    fun newKey(): String = PlatformCrypto.randomBytes(32).toHex()
    fun newId(): String = PlatformCrypto.randomBytes(16).toHex()

    // -----------------------------------------------------------------------------------------
    // Circle protocol v2
    //
    // - Location keys ratchet daily: key(d+1) = HMAC-SHA256(key(d), label), which can't be run
    //   backwards, so once an old day's key is deleted, a stolen current key can't read that day.
    //   A removal, and a weekly rotation, replace the key with fresh randomness (new epoch).
    // - Each epoch also has a control key that doesn't ratchet. It encrypts the admin's signed
    //   heartbeats and "epoch closed" notices, profiles, and derives rotating inbox tags for
    //   messages, so relays never see a member's permanent public key.
    // - Outer event keys come from the publishing member's own secret, not the shared key, so no
    //   other member can publish under (or replace) them.
    // -----------------------------------------------------------------------------------------

    private const val DAY_SECONDS = 86_400L
    private const val CONTROL_EXPIRY_SECONDS = 8 * DAY_SECONDS

    fun day(now: Long): Long = now / DAY_SECONDS

    /** One ratchet step: the key for [toDay] from the key for the day before. One-way. */
    fun ratchetStep(key: ByteArray, circleId: String, epoch: Int, toDay: Long): ByteArray =
        PlatformCrypto.hmacSha256(key, "qm-ratchet|$circleId|$epoch|$toDay".encodeToByteArray())

    /** The location key for [day], from the key held for [heldDay]. Null for an earlier day: those keys are gone. */
    fun keyForDay(held: ByteArray, heldDay: Long, circleId: String, epoch: Int, day: Long): ByteArray? {
        if (day < heldDay) return null
        require(day - heldDay <= 4000) { "key is too old" }
        var k = held
        for (d in heldDay + 1..day) k = ratchetStep(k, circleId, epoch, d)
        return k
    }

    fun controlTag(ctl: ByteArray, epoch: Int, day: Long): String =
        PlatformCrypto.hmacSha256(ctl, "qm-ctl|$epoch|$day".encodeToByteArray()).toHex().take(32)

    /**
     * Where an epoch's "closed" notice lives. It has its own tag so a heartbeat can never replace it
     * on a relay (addressable events with the same key and tag replace each other).
     */
    fun closedTag(ctl: ByteArray, epoch: Int): String =
        PlatformCrypto.hmacSha256(ctl, "qm-closed|$epoch".encodeToByteArray()).toHex().take(32)

    /** A member's inbox tag for one day, under one control key: what relays see instead of their public key. */
    fun inbox(ctl: ByteArray, memberPubkey: String, day: Long): String =
        PlatformCrypto.hmacSha256(ctl, "qm-inbox|$memberPubkey|$day".encodeToByteArray()).toHex()

    /** Hourly location publisher, derived from the member's own secret: only they can sign with it. */
    fun memberPublisher(identitySecret: ByteArray, circleId: String, epoch: Int, slot: Long): KeyPair =
        KeyPair.fromSeed(PlatformCrypto.hmacSha256(identitySecret, "qm-pub2|$circleId|$epoch|$slot".encodeToByteArray()))

    fun memberProfilePublisher(identitySecret: ByteArray, circleId: String, epoch: Int): KeyPair =
        KeyPair.fromSeed(PlatformCrypto.hmacSha256(identitySecret, "qm-profile-pub2|$circleId|$epoch".encodeToByteArray()))

    fun controlPublisher(identitySecret: ByteArray, circleId: String, epoch: Int, day: Long): KeyPair =
        KeyPair.fromSeed(PlatformCrypto.hmacSha256(identitySecret, "qm-ctl-pub|$circleId|$epoch|$day".encodeToByteArray()))

    /** A v2 location update under the day key, signed by the member's own hourly publisher key. */
    fun sealLocation(identity: KeyPair, dayKey: ByteArray, epoch: Int, body: LocationBody, publisher: KeyPair, now: Long): Event {
        val tags = listOf(listOf("d", tag(dayKey, epoch, slot(now))), listOf("expiration", (now + EXPIRY_SECONDS).toString()))
        val content = Nip44.encrypt(Envelopes.seal(identity, body, Envelopes.LOCATION_SIZE), conversationKey(dayKey, epoch))
        return Event.create(publisher, Kinds.CIRCLE_UPDATE, tags, content, now)
    }

    /** A v2 profile under the epoch's control key. */
    fun sealProfileV2(identity: KeyPair, ctl: ByteArray, epoch: Int, body: ProfileBody, publisher: KeyPair, now: Long): Event {
        val tags = listOf(listOf("d", profileTag(ctl, epoch)), listOf("expiration", (now + PROFILE_EXPIRY_SECONDS).toString()))
        val content = Nip44.encrypt(Envelopes.seal(identity, body), conversationKey(ctl, epoch))
        return Event.create(publisher, Kinds.CIRCLE_UPDATE, tags, content, now)
    }

    /** The admin's heartbeat or closed notice for [epoch], under that epoch's control key. */
    fun sealControl(admin: KeyPair, ctl: ByteArray, epoch: Int, body: EpochBody, publisher: KeyPair, now: Long): Event {
        val d = if (body.closed) closedTag(ctl, epoch) else controlTag(ctl, epoch, day(now))
        val tags = listOf(listOf("d", d), listOf("expiration", (now + CONTROL_EXPIRY_SECONDS).toString()))
        val content = Nip44.encrypt(Envelopes.seal(admin, body), conversationKey(ctl, epoch))
        return Event.create(publisher, Kinds.CIRCLE_UPDATE, tags, content, now)
    }
}

// ---------------------------------------------------------------------------------------------
// Device codes and invite links.
// ---------------------------------------------------------------------------------------------

object DeviceCode {
    /** "7F3A 91C2 D04E 6B1A": a short fingerprint of a public key, for comparing in person. */
    fun full(pubkeyHex: String): String =
        PlatformCrypto.sha256(pubkeyHex.hexToBytes()).copyOfRange(0, 8).toHex().uppercase().chunked(4).joinToString(" ")
}

@Serializable
data class InviteData(
    /** 1: unsigned, join request goes to the inviter's public key. 2: signed, rotating invite inbox. */
    val v: Int = 1,
    /** inviter public key */
    val k: String,
    /** inviter name */
    val n: String,
    /** relays */
    val r: List<String>,
    /** circle id and name */
    val c: String,
    val cn: String,
    /** one-time secret */
    val s: String,
    /** expiry, unix seconds */
    val e: Long,
    /** v2: the inviter's signature over all the fields above, so none can be changed on the way. */
    val x: String? = null,
)

object InviteLinks {
    const val SCHEME_PREFIX = "quietmap://join#"

    /**
     * Shared links use a web address, since chat apps only make http(s) links tappable (#56). The
     * static page there hands the invite to the app. Everything after `#` stays on the phone:
     * browsers never send it to the web host.
     */
    const val WEB_PREFIX = "https://carterkeel.github.io/quietmap/join#"

    /** The tappable web form of an invite link. Both forms decode the same way. */
    fun webLink(link: String): String = WEB_PREFIX + link.trim().substringAfter('#')

    /**
     * The message the Share button sends. The link is alone on the last line with nothing after
     * it, so chat apps that make links tappable take all of it, and if one doesn't, the whole
     * message can still be pasted into Quietmap, which finds the invite in it.
     */
    fun shareText(circleName: String, link: String): String =
        "Join my circle \"$circleName\" on Quietmap. Tap the link, or copy this whole message into Quietmap under Join a circle:\n\n${webLink(link)}"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(data: InviteData): String =
        SCHEME_PREFIX + json.encodeToString(InviteData.serializer(), data).encodeToByteArray().toBase64Url()

    /** What a v2 invite's signature covers: every field, in a fixed order. */
    private fun signedBytes(d: InviteData): ByteArray =
        PlatformCrypto.sha256(json.encodeToString(InviteData.serializer(), d.copy(x = null)).let { "quietmap-invite|$it" }.encodeToByteArray())

    /** A v2 invite signed by [inviter], whose public key it carries. */
    fun signed(inviter: KeyPair, data: InviteData): InviteData {
        val d = data.copy(v = 2, k = inviter.publicHex, x = null)
        return d.copy(x = inviter.sign(signedBytes(d)).toHex())
    }

    /**
     * Where a v2 join request (and the answer to it) is delivered: derived from the one-time secret,
     * so relays never see the inviter's or the joiner's public key.
     */
    fun inbox(secretHex: String): String =
        PlatformCrypto.hmacSha256(secretHex.hexToBytes(), "qm-invite-inbox".encodeToByteArray()).toHex()

    /** A real invite payload is several hundred characters; anything shorter can't be one. */
    private const val MIN_PAYLOAD = 40
    private const val MAX_TEXT = 20_000

    private fun isPayloadChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    /** Spaces, line breaks and invisible characters some apps put into long words. */
    private fun isFiller(c: Char) = c.isWhitespace() || c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿' || c == '­'

    /**
     * Possible payloads in [text], most likely first: what follows the `#` (with line breaks and
     * invisible characters some apps add to long words taken out), then every other long run of
     * base64url characters, longest first. That covers the https and `quietmap://` links, the
     * `quietmap://join/<payload>` form the web page hands to Android, a bare payload, and any of
     * these inside a longer message.
     */
    private fun candidates(text: String): List<String> {
        val t = text.take(MAX_TEXT).replace("%23", "#").replace("%2523", "#")
        val out = mutableListOf<String>()
        val hash = t.indexOf('#')
        if (hash >= 0) out += t.substring(hash + 1).filterNot(::isFiller).takeWhile(::isPayloadChar)
        Regex("[A-Za-z0-9_-]+").findAll(t).map { it.value }.sortedByDescending { it.length }.forEach { out += it }
        return out.filter { it.length >= MIN_PAYLOAD }.distinct().take(6)
    }

    /**
     * Finds the invite in a link or a pasted message. Returns null if there isn't a valid one,
     * including a v2 invite whose fields don't match the inviter's signature.
     */
    fun decode(link: String): InviteData? = candidates(link).firstNotNullOfOrNull { decodePayload(it) }

    /** Why [text] didn't decode, for a message that helps. Null if it's a valid invite. */
    fun problem(text: String): InviteProblem? = when {
        decode(text) != null -> null
        candidates(text).isNotEmpty() -> InviteProblem.DAMAGED
        text.contains("quietmap", ignoreCase = true) -> InviteProblem.MISSING
        else -> InviteProblem.NOT_AN_INVITE
    }

    private fun decodePayload(payload: String): InviteData? = try {
        val data = json.decodeFromString(InviteData.serializer(), payload.fromBase64Url().decodeToString())
        val valid = Keys.isValidPublicKey(data.k) && data.s.length >= 32 && data.s.all { it in "0123456789abcdef" } && when (data.v) {
            // Unsigned v1 links are refused: anyone could change the relays or names in them.
            2 -> data.x != null && Keys.verify(data.x.hexToBytes(), signedBytes(data), data.k.hexToBytes())
            else -> false
        }
        if (valid) data else null
    } catch (_: Throwable) {
        null
    }
}

/** Why a text isn't a usable invite. */
enum class InviteProblem {
    /** No invite in it at all, e.g. a link cut off at the `#`. */
    MISSING,

    /** Something that looks like an invite, but cut short or changed after it was made. */
    DAMAGED,

    /** Not a Quietmap link at all. */
    NOT_AN_INVITE,
}
