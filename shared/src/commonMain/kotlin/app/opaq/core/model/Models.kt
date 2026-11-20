package app.opaq.core.model

import kotlinx.serialization.Serializable

val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://nostr.mom",
    "wss://offchain.pub",
)

/**
 * Relays added to [DEFAULT_RELAYS] after release, oldest first. Saved states get each one once
 * ([AppState.relayDefaultsVersion] counts how many they've had), so someone who removes it keeps it removed.
 * Read sealed DMs (joins, rosters) need a relay that serves them without NIP-42 sign-in, which Opaq doesn't do.
 */
val ADDED_DEFAULT_RELAYS = listOf("wss://offchain.pub")

/** [this] with any default relays added since it was saved. */
fun AppState.withNewDefaultRelays(): AppState {
    if (relayDefaultsVersion >= ADDED_DEFAULT_RELAYS.size) return this
    val add = ADDED_DEFAULT_RELAYS.drop(relayDefaultsVersion).filter { it !in relays }
    return copy(relays = relays + add, relayDefaultsVersion = ADDED_DEFAULT_RELAYS.size)
}

/** Values of [AppState.themeMode]. */
const val THEME_SYSTEM = "system"
const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"
/** Dark, with a pure black background instead of Ink (OLED screens, night). */
const val THEME_MIDNIGHT = "midnight"

/** Everything the app persists. Stored encrypted on the device. */
@Serializable
data class AppState(
    val identitySecretHex: String,
    val myName: String = "",
    val relays: List<String> = DEFAULT_RELAYS,
    /** How many of [ADDED_DEFAULT_RELAYS] this state has already been given. */
    val relayDefaultsVersion: Int = 0,
    val sharing: Boolean = true,
    /** Include battery level in location updates. On for new installs; saved states keep their choice. */
    val shareBattery: Boolean = true,
    /** Avatar color index for the default initial avatar; null means derived from the public key. */
    val myColor: Int? = null,
    /** Profile photo as a small JPEG, base64. Shared only with circle members, encrypted. */
    val myPhoto: String? = null,
    /** When the profile (name, color, photo) last changed or was last published, unix seconds. */
    val profileChangedAt: Long = 0,
    val profilePublishedAt: Long = 0,
    /** Profiles other members published to their circles. */
    val profiles: Map<String, MemberProfile> = emptyMap(),
    /** Show other people's profile photos; off shows everyone's initial avatar instead. */
    val showPhotos: Boolean = true,
    /** Draw the map with the dark style. Off (the light map) unless turned on in Settings. */
    val darkMap: Boolean = false,
    /** The app's colours: [THEME_SYSTEM] (follow the phone), [THEME_LIGHT], [THEME_DARK] or [THEME_MIDNIGHT]. A string, so
     *  a value from a newer version can't make saved state unreadable; anything unknown follows the phone. */
    val themeMode: String = THEME_SYSTEM,
    /** Landscape only: the people panel at the side of the map is slid away. */
    val peoplePanelHidden: Boolean = false,
    /** The circle shown when the app opens. */
    val lastCircleId: String? = null,
    /** First-run setup finished (or skipped). */
    val setupDone: Boolean = false,
    val circles: List<Circle> = emptyList(),
    val contacts: Map<String, Contact> = emptyMap(),
    val invites: List<Invite> = emptyList(),
    val joinRequests: List<JoinRequest> = emptyList(),
    val pendingJoins: List<PendingJoin> = emptyList(),
    /** Joins the inviter turned away (link used up, expired, cancelled or denied), until dismissed. */
    val joinRefusals: List<JoinRefusal> = emptyList(),
    /** circleId -> member pubkey -> latest location */
    val locations: Map<String, Map<String, MemberLocation>> = emptyMap(),
    val seenEventIds: List<String> = emptyList(),
    /** Optional PIN that guards sensitive actions (never opening the app or seeing locations). */
    val pin: PinLock? = null,
    /** Security-relevant things that happened on this phone (backups made, PIN changes...). */
    val securityLog: List<SecurityEvent> = emptyList(),
    /** Hide the app's screen in recent apps and screenshots (Android). */
    val hideInRecents: Boolean = false,
    /** Connect to relays through Tor (Orbot's SOCKS proxy on this phone). Never falls back to direct. */
    val useTor: Boolean = false,
    /** When the user last confirmed they want to keep sharing (monthly reminder). 0 = not yet set. */
    val consentAt: Long = 0,
    /** The sequence number of our last location update (v2 replay protection). */
    val lastSeq: Long = 0,
    /** Newest sequence number accepted from each member, keyed "circleId|pubkey". */
    val memberSeqs: Map<String, Long> = emptyMap(),
    /** How many days of location history this phone keeps (0 = none). See LocationHistory. */
    val historyDays: Int = 7,
    /** Key for the history files. Never in backups: a restored phone starts its history afresh. */
    val historyKeyHex: String? = null,
)

@Serializable
data class Member(val pubkey: String, val name: String)

/** What a member chose to show the circle: their name, avatar color and optional photo. */
@Serializable
data class MemberProfile(
    val name: String,
    val color: Int? = null,
    /** Small JPEG, base64. */
    val photo: String? = null,
    val updated: Long,
)

@Serializable
data class Circle(
    val id: String,
    val name: String,
    val keyHex: String,
    val epoch: Int,
    val adminPubkey: String,
    val members: List<Member>,
    /** Members the admin allowed to invite and approve people. Only the admin removes or rotates keys. */
    val managers: List<String> = emptyList(),
    /** Previous key, accepted for a short overlap after a rotation. */
    val previousKeyHex: String? = null,
    val previousEpoch: Int? = null,
    val previousValidUntil: Long? = null,
    /** Circle protocol version: 1 (one static key per epoch) or 2 (see CircleCrypto). */
    val version: Int = 1,
    /** Timestamp of the newest admin roster accepted for this epoch; older ones are replays. */
    val adminRosterTs: Long = 0,
    /**
     * After an identity reset, the old identity's secret, kept only to sign the hand-over (roster
     * resends and closed notices) for members who missed it, until they confirm or [handoverUntil].
     */
    val handoverSecretHex: String? = null,
    val handoverUntil: Long = 0,
    /** v2: UTC day that [keyHex] belongs to. Keys for earlier days are deleted. */
    val keyDay: Long = 0,
    val previousKeyDay: Long? = null,
    /** v2: this epoch's control key. */
    val ctlKeyHex: String? = null,
    /** v2: control keys of recent epochs, kept a while so stragglers can still be reached. */
    val oldControls: List<OldControl> = emptyList(),
    /** v2 admin: when a fresh key was last made (removal, weekly rotation, or creation). */
    val rotatedAt: Long = 0,
    /** v2 admin: when the last heartbeat for this epoch was published. */
    val heartbeatPublishedAt: Long = 0,
    /** v2 member: time of the newest valid admin heartbeat (or roster) for the current epoch. */
    val heartbeatAt: Long = 0,
    /** v2 member: the admin said this epoch is over, or a newer one exists. Publishing stops. */
    val stale: Boolean = false,
    val rosterRequestedAt: Long = 0,
    /** v2 admin or manager: members who haven't confirmed the newest roster yet. */
    val pendingAcks: List<PendingAck> = emptyList(),
    /** Who joined and left, and when; newest last. Kept on this phone only. */
    val history: List<MembershipEvent> = emptyList(),
    /** How precisely this phone shares its location with this circle. */
    val precision: Precision = Precision.EXACT,
    /** Send the last few points (the path) with each update. */
    val shareTrail: Boolean = true,
    /**
     * A circle we just joined starts unconfirmed: nothing is shared with it until the user has
     * seen who is in it and tapped Start sharing.
     */
    val confirmed: Boolean = true,
)

@Serializable
enum class Precision { EXACT, NEIGHBORHOOD, CITY }

@Serializable
enum class MembershipChange { CREATED, JOINED, LEFT, NEW_ADMIN }

@Serializable
data class MembershipEvent(
    val change: MembershipChange,
    val pubkey: String,
    val name: String,
    /** Who approved the join, or removed them, when known. */
    val by: String? = null,
    val at: Long,
)

/** A PIN, stored only as a slow salted hash, with attempt limiting. */
@Serializable
data class PinLock(
    val hashHex: String,
    val saltHex: String,
    val iterations: Int,
    val failedAttempts: Int = 0,
    /** Wall-clock end of the lockout, for display only. */
    val lockedUntil: Long = 0,
    /**
     * The lockout as measured by the phone's monotonic clock (seconds since boot), which changing
     * the date and time can't move. A reboot restarts the wait.
     */
    val lockStartElapsed: Long = 0,
    val lockUntilElapsed: Long = 0,
)

@Serializable
enum class SecurityEventType { BACKUP_EXPORTED, BACKUP_RESTORED, PIN_SET, PIN_CHANGED, PIN_REMOVED, PIN_LOCKED, IDENTITY_RESET }

@Serializable
data class SecurityEvent(val type: SecurityEventType, val at: Long, val device: String? = null)

/** A replaced epoch's control key, kept until [until] to answer and close out stragglers. */
@Serializable
data class OldControl(val epoch: Int, val ctlKeyHex: String, val until: Long, val closedPublishedDay: Long = -1)

/** A roster we sent that the member hasn't acknowledged yet. */
@Serializable
data class PendingAck(
    val pubkey: String,
    val epoch: Int,
    val firstSentAt: Long,
    val lastSentAt: Long,
    val attempts: Int,
    /** Inboxes beyond the circle's own, e.g. a joiner's invite inbox. */
    val extraInboxes: List<String> = emptyList(),
)

/** Local-only knowledge about another person. */
@Serializable
data class Contact(
    val label: String? = null,
    val verified: Boolean = false,
)

/** An invite link this device created (we are the circle admin). */
@Serializable
data class Invite(
    val secret: String,
    val circleId: String,
    val expiresAt: Long,
    /** No uses left: [maxUses] people have asked to join with it. */
    val used: Boolean = false,
    /** 2: signed link, join request arrives on the invite's own inbox. */
    val v: Int = 1,
    /** How many people can use the link: 1, or up to [MAX_USES] for a link meant for several. */
    val maxUses: Int = 1,
    /** Everyone who has asked to join with it, in order. */
    val usedBy: List<String> = emptyList(),
    /** Cancelled early by whoever made it. */
    val cancelled: Boolean = false,
) {
    companion object {
        const val MAX_USES = 10
    }
}

/** Someone opened our invite link and is waiting for approval. */
@Serializable
data class JoinRequest(
    val id: String,
    val circleId: String,
    val pubkey: String,
    val name: String,
    val receivedAt: Long,
    /** v2: the invite secret, so the approval can reach the joiner's invite inbox. */
    val secret: String? = null,
)

/** Our join request was turned away. [reason] is a [app.opaq.core.protocol.JoinRefusedBody] reason. */
@Serializable
data class JoinRefusal(
    val circleId: String,
    val circleName: String,
    val inviterName: String,
    val reason: String,
    val at: Long,
) {
    /** What to tell the person who wanted to join. */
    val message: String
        get() = when (reason) {
            "used" -> "This invite was already used. Ask for a new one."
            "expired" -> "This invite has expired."
            "cancelled" -> "This invite was cancelled. Ask for a new one."
            "denied" -> "${inviterName.ifBlank { "The inviter" }} didn't let you in."
            else -> "${inviterName.ifBlank { "The inviter" }} couldn't let you in."
        }
}

/** We opened someone's invite link and are waiting for them to approve. */
@Serializable
data class PendingJoin(
    val circleId: String,
    val circleName: String,
    val inviterPubkey: String,
    val inviterName: String,
    val sentAt: Long,
    /** v2: the invite inbox we listen on until we're let in. Null for a v1 invite (our public key). */
    val inbox: String? = null,
    /** Relays the invite asked for, used only until the join completes (unless the user kept them). */
    val relays: List<String> = emptyList(),
)

@Serializable
data class Point(val lat: Double, val lon: Double, val t: Long)

@Serializable
data class MemberLocation(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float? = null,
    val heading: Float? = null,
    val activity: Activity = Activity.UNKNOWN,
    val battery: Int? = null,
    val time: Long,
    val points: List<Point> = emptyList(),
    val status: String? = null,
    /** Set when the sender shares an approximate location; null means exact. */
    val precision: Precision? = null,
)

@Serializable
enum class Activity { STILL, WALKING, CYCLING, DRIVING, UNKNOWN }

/**
 * Turns an exact fix into what a circle should see. Approximate modes snap to the centre of a fixed
 * grid cell (about 2 km for a neighbourhood, 20 km for a city) instead of adding random noise,
 * which repeated updates would average away: every update from inside one cell is identical. Speed,
 * heading and the recent path are left out, since they would narrow the position down again.
 */
object LocationPrecision {
    private const val NEIGHBORHOOD_DEGREES = 0.02
    private const val CITY_DEGREES = 0.2
    private const val METERS_PER_DEGREE = 111_320.0

    fun apply(loc: MemberLocation, precision: Precision, trail: Boolean = true): MemberLocation {
        val base = if (trail) loc else loc.copy(points = emptyList())
        val step = when (precision) {
            Precision.EXACT -> return base
            Precision.NEIGHBORHOOD -> NEIGHBORHOOD_DEGREES
            Precision.CITY -> CITY_DEGREES
        }
        val latCell = kotlin.math.floor(loc.lat / step)
        val lat = ((latCell + 0.5) * step).coerceIn(-89.999, 89.999)
        // Cells stay roughly square: longitude steps widen towards the poles.
        val lonStep = step / kotlin.math.cos(lat * kotlin.math.PI / 180).coerceAtLeast(0.05)
        val lon = ((kotlin.math.floor((loc.lon + 180) / lonStep) + 0.5) * lonStep - 180).coerceIn(-180.0, 180.0)
        return MemberLocation(
            lat = lat,
            lon = lon,
            accuracy = (step * METERS_PER_DEGREE * 0.71).toFloat(),
            activity = loc.activity,
            battery = loc.battery,
            time = loc.time,
            status = loc.status,
            precision = precision,
        )
    }
}

/** A location fix from the platform. */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float?,
    val heading: Float?,
    val time: Long,
)
