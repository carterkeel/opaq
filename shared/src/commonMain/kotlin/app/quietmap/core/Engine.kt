package app.quietmap.core

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toHex
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.Contact
import app.quietmap.core.model.Invite
import app.quietmap.core.model.JoinRequest
import app.quietmap.core.model.Member
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.MemberProfile
import app.quietmap.core.model.OldControl
import app.quietmap.core.model.PendingAck
import app.quietmap.core.model.PendingJoin
import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Filter
import app.quietmap.core.nostr.Kinds
import app.quietmap.core.nostr.RelayPool
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.nostr.RelayTransport
import app.quietmap.core.nostr.RelayUrls
import app.quietmap.core.protocol.Body
import app.quietmap.core.protocol.CircleCrypto
import app.quietmap.core.protocol.DirectMessages
import app.quietmap.core.protocol.EpochBody
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.core.protocol.Backups
import app.quietmap.core.protocol.JoinRequestBody
import app.quietmap.core.protocol.LeftBody
import app.quietmap.core.protocol.LocationBody
import app.quietmap.core.protocol.ProfileBody
import app.quietmap.core.protocol.RemovedBody
import app.quietmap.core.protocol.Roster2Body
import app.quietmap.core.protocol.RosterAckBody
import app.quietmap.core.protocol.RosterBody
import app.quietmap.core.protocol.RosterCircle
import app.quietmap.core.protocol.RosterRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** What a [StateStore] found on disk. */
sealed interface StoredState {
    /** Nothing was ever saved: a first launch. The only case in which a new identity is made. */
    data object Missing : StoredState

    class Found(val data: String) : StoredState

    /**
     * Saved state exists but can't be read. [retryable] means the secure storage may come back
     * (phone still locked after a reboot, Keystore busy); otherwise the file or its key is damaged.
     */
    class Unreadable(val reason: String, val retryable: Boolean) : StoredState
}

/** Persists the app state blob, encrypted with a Keystore (Android) or Keychain (iOS) key. */
interface StateStore {
    fun read(): StoredState
    fun save(data: String)

    /** Renames an unreadable state file so a restore or a fresh start can't overwrite it. */
    fun setAside() {}
}

/** Saved state couldn't be read. Nothing is overwritten and nothing is shared until this is resolved. */
data class Recovery(val reason: String, val retryable: Boolean)

/**
 * The whole Quietmap protocol: identity, circles, invites, key rotation and location exchange.
 * Platform code only supplies storage, a clock, and location fixes.
 */
class Engine(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    /** Builds the relay connection; tests pass an in-memory network instead of real relays. */
    transport: ((onEvent: suspend (relay: String, event: Event) -> Unit) -> RelayTransport)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState>
    /** This device's identity key. Changes only when a backup is imported. */
    var identity: KeyPair
        private set

    private val pool: RelayTransport = transport?.invoke(::onEvent) ?: RelayPool(scope, ::onEvent, log)
    val relayStatus: StateFlow<Map<String, RelayStatus>> = pool.status

    private enum class TagKind { LOCATION, PROFILE, CONTROL }

    /** What a d-tag we listen on belongs to. [key] is the key that opens events under it. */
    private class TagTarget(val circleId: String, val key: ByteArray, val epoch: Int, val kind: TagKind, val v2: Boolean)

    /** What an inbox (`p` tag) we listen on belongs to: our identity key (v1), or a circle's control key (v2). */
    private class InboxRef(val circleId: String?, val ctl: ByteArray?)

    private var tagIndex: Map<String, TagTarget> = emptyMap()
    private var inboxIndex: Map<String, InboxRef> = emptyMap()
    private val seen = LinkedHashSet<String>()

    private val _recovery = MutableStateFlow<Recovery?>(null)

    /**
     * Non-null when saved state exists but couldn't be read. Then the engine fails closed: it keeps
     * the old file, never writes over it, doesn't connect and doesn't share, until the state is
     * read on a retry, restored from a backup, or the user explicitly starts over.
     */
    val recovery: StateFlow<Recovery?> = _recovery.asStateFlow()

    private var started = false
    private var connected = false

    init {
        val found = try {
            store.read()
        } catch (t: Throwable) {
            StoredState.Unreadable(t.message ?: "Secure storage isn't available", retryable = true)
        }
        val loaded: AppState? = when (found) {
            StoredState.Missing -> null
            is StoredState.Found -> decode(found.data) ?: run {
                _recovery.value = Recovery("The saved data is damaged", retryable = false)
                null
            }
            is StoredState.Unreadable -> {
                _recovery.value = Recovery(found.reason, found.retryable)
                null
            }
        }
        val initial = when {
            loaded != null -> loaded
            // Only a true first launch gets a new identity, and only that is saved.
            _recovery.value == null -> AppState(identitySecretHex = KeyPair.generate().secret.toHex())
            // Placeholder while recovering: never saved, never connected.
            else -> AppState(identitySecretHex = KeyPair.generate().secret.toHex(), relays = emptyList(), sharing = false, setupDone = true)
        }
        identity = KeyPair(initial.identitySecretHex.hexToBytes())
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        seen.addAll(initial.seenEventIds)
        if (found == StoredState.Missing) store.save(json.encodeToString(AppState.serializer(), initial))
    }

    private fun checkReady() = check(_recovery.value == null) { "Quietmap can't read its saved data yet" }

    private fun decode(data: String): AppState? = runCatching { json.decodeFromString(AppState.serializer(), data) }.getOrNull()

    val myPubkey: String get() = identity.publicHex

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                if (_recovery.value == null) {
                    connectIfNeeded()
                    housekeeping()
                }
                delay(60_000)
            }
        }
    }

    private suspend fun connectIfNeeded() {
        if (connected) return
        connected = true
        pool.setRelays(state.value.relays)
        refreshSubscriptions()
        publishProfileIfDue()
    }

    /** One pass of the periodic work. Tests call it directly instead of waiting for the timer. */
    suspend fun housekeeping() {
        if (_recovery.value != null) return
        pruneExpired()
        advanceRatchets()
        adminDuties()
        retryRosters()
        memberDuties()
        refreshSubscriptions()
        publishProfileIfDue()
    }

    /** Reads saved state again, e.g. once the phone is unlocked. Returns true if it worked. */
    suspend fun retryLoad(): Boolean {
        if (_recovery.value == null) return true
        val found = try {
            store.read()
        } catch (t: Throwable) {
            StoredState.Unreadable(t.message ?: "Secure storage isn't available", retryable = true)
        }
        val loaded = (found as? StoredState.Found)?.let { decode(it.data) } ?: run {
            if (found is StoredState.Unreadable) _recovery.value = Recovery(found.reason, found.retryable)
            return false
        }
        mutex.withLock {
            identity = KeyPair(loaded.identitySecretHex.hexToBytes())
            seen.clear()
            seen.addAll(loaded.seenEventIds)
            _state.value = loaded
            _recovery.value = null
        }
        if (started) connectIfNeeded()
        return true
    }

    /**
     * Gives up on unreadable saved state: the old file is kept under another name, and a new
     * identity starts. Circles have to be joined again with new invites.
     */
    suspend fun startFresh() {
        if (_recovery.value == null) return
        store.setAside()
        val fresh = AppState(identitySecretHex = KeyPair.generate().secret.toHex())
        mutex.withLock {
            identity = KeyPair(fresh.identitySecretHex.hexToBytes())
            seen.clear()
            _state.value = fresh
            store.save(json.encodeToString(AppState.serializer(), fresh))
            _recovery.value = null
        }
        if (started) connectIfNeeded()
    }

    fun stop() = pool.shutdown()

    // -----------------------------------------------------------------------------------------
    // Profile and settings
    // -----------------------------------------------------------------------------------------

    suspend fun setMyName(name: String) {
        val clean = name.trim().take(40)
        if (clean == state.value.myName) return
        update { it.copy(myName = clean, profileChangedAt = now()) }
        publishProfileIfDue()
    }

    /** Sets the avatar: a color for the default initial avatar, and an optional photo (small JPEG, base64). */
    suspend fun setAvatar(color: Int?, photo: String?) {
        require(photo == null || photo.length <= CircleCrypto.MAX_PHOTO_CHARS) { "That photo is too large" }
        update { it.copy(myColor = color, myPhoto = photo, profileChangedAt = now()) }
        publishProfileIfDue()
    }

    suspend fun setSharing(on: Boolean) = update { it.copy(sharing = on) }

    suspend fun setShareBattery(on: Boolean) = update { it.copy(shareBattery = on) }

    suspend fun setShowPhotos(on: Boolean) = update { it.copy(showPhotos = on) }

    suspend fun setLastCircle(circleId: String) = update { if (it.lastCircleId == circleId) it else it.copy(lastCircleId = circleId) }

    suspend fun setSetupDone() = update { it.copy(setupDone = true) }

    /** Remote relays must use TLS (wss://); anything else is dropped, never silently downgraded. */
    suspend fun setRelays(relays: List<String>) {
        checkReady()
        val clean = relays.mapNotNull { RelayUrls.clean(it) }.distinct()
        update { it.copy(relays = clean) }
        pool.setRelays(clean)
        refreshSubscriptions()
    }

    suspend fun setLabel(pubkey: String, label: String?) = update {
        val c = it.contacts[pubkey] ?: Contact()
        it.copy(contacts = it.contacts + (pubkey to c.copy(label = label?.trim()?.ifEmpty { null })))
    }

    suspend fun setVerified(pubkey: String, verified: Boolean) = update {
        val c = it.contacts[pubkey] ?: Contact()
        it.copy(contacts = it.contacts + (pubkey to c.copy(verified = verified)))
    }

    // -----------------------------------------------------------------------------------------
    // Circles and invites
    // -----------------------------------------------------------------------------------------

    /** Creates a circle on protocol v2: ratcheting location key, control key, own publisher keys. */
    suspend fun createCircle(name: String): Circle {
        checkReady()
        val t = now()
        val circle = Circle(
            id = CircleCrypto.newId(),
            name = name.trim().ifEmpty { "Family" },
            keyHex = CircleCrypto.newKey(),
            epoch = 1,
            adminPubkey = myPubkey,
            members = listOf(Member(myPubkey, state.value.myName)),
            version = 2,
            keyDay = CircleCrypto.day(t),
            ctlKeyHex = CircleCrypto.newKey(),
            rotatedAt = t,
            heartbeatAt = t,
        )
        update { it.copy(circles = it.circles + circle, lastCircleId = circle.id) }
        refreshSubscriptions()
        publishHeartbeat(circle)
        publishProfile(circle)
        return circle
    }

    /** The admin and managers can invite and approve people. */
    fun canInvite(circle: Circle): Boolean = circle.adminPubkey == myPubkey || myPubkey in circle.managers

    /** Creates a single-use invite link valid for 24 hours. Only the admin and managers can invite. */
    suspend fun createInvite(circleId: String): String {
        checkReady()
        val s = state.value
        val circle = s.circles.first { it.id == circleId }
        require(canInvite(circle)) { "Only the circle admin and managers can invite" }
        val secret = PlatformCrypto.randomBytes(16).toHex()
        val expires = now() + 24 * 3600
        update { it.copy(invites = it.invites + Invite(secret, circleId, expires)) }
        refreshSubscriptions()
        return InviteLinks.encode(InviteData(k = myPubkey, n = s.myName, r = s.relays, c = circleId, cn = circle.name, s = secret, e = expires))
    }

    /** Sends a join request for an invite the user has agreed to. */
    suspend fun acceptInvite(invite: InviteData) {
        checkReady()
        require(invite.e > now()) { "This invite has expired" }
        require(invite.k != myPubkey) { "This is your own invite" }
        // Make sure we can reach the inviter's relays too (TLS ones only).
        val merged = (state.value.relays + invite.r.mapNotNull { RelayUrls.clean(it) }).distinct().take(8)
        if (merged != state.value.relays) setRelays(merged)
        update {
            it.copy(pendingJoins = it.pendingJoins.filterNot { p -> p.circleId == invite.c } +
                PendingJoin(invite.c, invite.cn, invite.k, invite.n, now()))
        }
        refreshSubscriptions()
        val body = JoinRequestBody(myPubkey, now(), invite.c, invite.s, state.value.myName)
        pool.publish(DirectMessages.seal(identity, invite.k, body, now()))
    }

    suspend fun approve(requestId: String) {
        val req = state.value.joinRequests.firstOrNull { it.id == requestId } ?: return
        val circle = state.value.circles.firstOrNull { it.id == req.circleId } ?: return
        if (!canInvite(circle)) return
        val updated = circle.copy(members = circle.members.filterNot { it.pubkey == req.pubkey } + Member(req.pubkey, req.name))
        update { s ->
            s.copy(
                circles = s.circles.map { if (it.id == circle.id) updated else it },
                joinRequests = s.joinRequests.filterNot { it.id == requestId },
            )
        }
        // The new member is still listening on their public key (they don't know the circle's inboxes yet).
        sendRoster(updated, updated.members.map { it.pubkey }, identityInboxFor = setOf(req.pubkey))
        refreshSubscriptions()
        // The new member needs our profile; republishing also refreshes it on the relays.
        publishProfile(updated)
    }

    suspend fun deny(requestId: String) = update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.id == requestId }) }

    /** Removes a member and gives the circle a fresh key so they can't read anything new. */
    suspend fun removeMember(circleId: String, pubkey: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can remove people" }
        require(pubkey != myPubkey)
        rotate(circle, circle.members.filterNot { it.pubkey == pubkey }, removed = pubkey)
    }

    /** Lets a member invite and approve people (admin only). */
    suspend fun setManager(circleId: String, pubkey: String, manager: Boolean) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can choose managers" }
        require(circle.members.any { it.pubkey == pubkey } && pubkey != myPubkey)
        val updated = circle.copy(managers = if (manager) (circle.managers + pubkey).distinct() else circle.managers - pubkey)
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) updated else it }) }
        sendRoster(updated, updated.members.map { it.pubkey })
    }

    /** Hands the admin role to another member, so the old admin can leave (admin only). */
    suspend fun transferAdmin(circleId: String, pubkey: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can do this" }
        require(circle.members.any { it.pubkey == pubkey } && pubkey != myPubkey)
        val updated = circle.copy(adminPubkey = pubkey, managers = circle.managers - pubkey)
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) updated else it }) }
        sendRoster(updated, updated.members.map { it.pubkey })
    }

    /** Deletes the circle for everyone (admin only). */
    suspend fun deleteCircle(circleId: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can delete it" }
        circle.members.filter { it.pubkey != myPubkey }.forEach { m ->
            sendDirect(circle, m.pubkey, RemovedBody(myPubkey, now(), circleId, deleted = true))
        }
        forgetCircle(circleId)
    }

    /**
     * Leaves a circle. The admin is told, and removes you automatically, which gives the circle a
     * new key. An admin must hand over the role (or delete the circle) first.
     */
    suspend fun leaveCircle(circleId: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey != myPubkey || circle.members.size <= 1) { "Make someone else the admin first, or delete the circle" }
        if (circle.adminPubkey != myPubkey) sendDirect(circle, circle.adminPubkey, LeftBody(myPubkey, now(), circleId))
        forgetCircle(circleId)
    }

    private suspend fun forgetCircle(circleId: String) {
        update { s ->
            s.copy(
                circles = s.circles.filterNot { it.id == circleId },
                locations = s.locations - circleId,
                joinRequests = s.joinRequests.filterNot { it.circleId == circleId },
                lastCircleId = if (s.lastCircleId == circleId) null else s.lastCircleId,
                memberSeqs = s.memberSeqs.filterKeys { !it.startsWith("$circleId|") },
            )
        }
        refreshSubscriptions()
    }

    // -----------------------------------------------------------------------------------------
    // Keys (protocol v2)
    // -----------------------------------------------------------------------------------------

    private fun today(): Long = CircleCrypto.day(now())

    /** The location key for [day], or null if that day's key was already deleted (or never held). */
    private fun dayKey(c: Circle, day: Long): ByteArray? =
        if (c.version < 2) c.keyHex.hexToBytes() else CircleCrypto.keyForDay(c.keyHex.hexToBytes(), c.keyDay, c.id, c.epoch, day)

    private fun previousDayKey(c: Circle, day: Long): ByteArray? {
        val key = c.previousKeyHex ?: return null
        val epoch = c.previousEpoch ?: return null
        val heldDay = c.previousKeyDay ?: return key.hexToBytes() // the previous epoch was v1
        return CircleCrypto.keyForDay(key.hexToBytes(), heldDay, c.id, epoch, day)
    }

    /**
     * Moves each v2 circle's stored key forward so nothing older than yesterday is kept. Keeping
     * yesterday's key lets updates sent just before midnight still be read.
     */
    private suspend fun advanceRatchets() {
        val keep = today() - 1
        update { s ->
            s.copy(circles = s.circles.map { c ->
                if (c.version < 2 || c.keyDay >= keep) c
                else c.copy(keyHex = dayKey(c, keep)!!.toHex(), keyDay = keep)
            })
        }
    }

    /**
     * Gives [c] a fresh random key and control key (a new epoch), with [members] and [newAdmin].
     * Members still on the old key are told it's closed; everyone remaining gets the new roster,
     * which is resent until they confirm it.
     */
    private suspend fun rotate(c: Circle, members: List<Member>, newAdmin: String = c.adminPubkey, signer: KeyPair = identity, removed: String? = null): Circle {
        val t = now()
        val d = CircleCrypto.day(t)
        val oldCtl = c.ctlKeyHex
        val rotated = c.copy(
            version = 2,
            keyHex = CircleCrypto.newKey(),
            keyDay = d,
            ctlKeyHex = CircleCrypto.newKey(),
            epoch = c.epoch + 1,
            adminPubkey = newAdmin,
            members = members,
            managers = c.managers.filter { m -> m != newAdmin && members.any { it.pubkey == m } },
            previousKeyHex = dayKey(c, d)?.toHex(),
            previousKeyDay = if (c.version >= 2) d else null,
            previousEpoch = c.epoch,
            previousValidUntil = t + OVERLAP_SECONDS,
            oldControls = c.oldControls.filter { it.until > t } + listOfNotNull(oldCtl?.let { OldControl(c.epoch, it, t + OLD_CONTROL_SECONDS, closedPublishedDay = d) }),
            rotatedAt = t,
            heartbeatAt = t,
            heartbeatPublishedAt = 0,
            stale = false,
            pendingAcks = emptyList(),
        )
        val keep = members.map { it.pubkey }.toSet()
        update { s ->
            s.copy(
                circles = s.circles.map { if (it.id == c.id) rotated else it },
                locations = s.locations + (c.id to s.locations[c.id].orEmpty().filterKeys { it in keep }),
            )
        }
        // Anyone still publishing under the old key stops as soon as they see this.
        if (oldCtl != null) publishControl(c.id, oldCtl.hexToBytes(), c.epoch, closed = true, signer = signer)
        // v1 members only listen on their public key; v2 members get it on their old inbox too.
        val identityInboxes = if (c.version < 2) keep else emptySet()
        sendRoster(rotated, members.map { it.pubkey }, identityInboxFor = identityInboxes, signer = signer)
        if (removed != null) sendDirect(c, removed, RemovedBody(signer.publicHex, t, c.id), signer = signer)
        refreshSubscriptions()
        if (newAdmin == myPubkey) publishHeartbeat(rotated)
        if (rotated.members.any { it.pubkey == myPubkey }) publishProfile(rotated)
        return rotated
    }

    // -----------------------------------------------------------------------------------------
    // Messages to members
    // -----------------------------------------------------------------------------------------

    /**
     * The inbox tags [recipient] may be listening on for [c]: the current control key's inbox and
     * those of recent epochs (for members who missed a rotation), plus their public key for v1
     * circles or when [identityInbox] is set (someone who hasn't joined yet).
     */
    private fun inboxesFor(c: Circle, recipient: String, identityInbox: Boolean = false): List<String> {
        val d = today()
        val ctls = listOfNotNull(c.ctlKeyHex) + c.oldControls.filter { it.until > now() }.map { it.ctlKeyHex }
        val tags = ctls.map { CircleCrypto.inbox(it.hexToBytes(), recipient, d) }
        return (tags + listOfNotNull(recipient.takeIf { c.version < 2 || identityInbox })).distinct()
    }

    /** Seals [body] to [recipient] once per inbox they might be listening on. */
    private suspend fun sendDirect(c: Circle, recipient: String, body: Body, identityInbox: Boolean = false, signer: KeyPair = identity) {
        inboxesFor(c, recipient, identityInbox).forEach { inbox ->
            pool.publish(DirectMessages.seal(signer, recipient, body, now(), inbox))
        }
    }

    private fun rosterOf(c: Circle): RosterCircle =
        if (c.version < 2) RosterCircle(c.id, c.name, c.keyHex, c.epoch, c.adminPubkey, c.members, c.managers)
        else {
            val d = today()
            RosterCircle(c.id, c.name, dayKey(c, d)!!.toHex(), c.epoch, c.adminPubkey, c.members, c.managers, v = 2, day = d, ctl = c.ctlKeyHex)
        }

    /**
     * Sends the current roster of [c] to [recipients] (except ourselves) and, for v2, records them
     * as waiting for an acknowledgement so [retryRosters] resends until they confirm.
     */
    private suspend fun sendRoster(c: Circle, recipients: List<String>, identityInboxFor: Set<String> = emptySet(), signer: KeyPair = identity) {
        val t = now()
        val rc = rosterOf(c)
        val body = if (c.version < 2) RosterBody(signer.publicHex, t, rc) else Roster2Body(signer.publicHex, t, rc)
        val targets = recipients.filter { it != signer.publicHex && it != myPubkey }.distinct()
        targets.forEach { m -> sendDirect(c, m, body, identityInbox = m in identityInboxFor, signer = signer) }
        if (c.version < 2 || targets.isEmpty() || signer.publicHex != myPubkey) return
        update { s ->
            s.copy(circles = s.circles.map { cur ->
                if (cur.id != c.id) cur
                else cur.copy(pendingAcks = cur.pendingAcks.filterNot { it.pubkey in targets } + targets.map { m ->
                    val prior = cur.pendingAcks.firstOrNull { it.pubkey == m && it.epoch == c.epoch }
                    PendingAck(m, c.epoch, prior?.firstSentAt ?: t, t, (prior?.attempts ?: 0) + 1)
                })
            })
        }
    }

    /** Resends rosters that haven't been acknowledged, backing off, for up to two weeks. */
    private suspend fun retryRosters() {
        val t = now()
        state.value.circles.filter { it.version >= 2 && it.pendingAcks.isNotEmpty() && canInvite(it) }.forEach { c ->
            val memberKeys = c.members.map { it.pubkey }.toSet()
            val due = c.pendingAcks.filter { p ->
                p.pubkey in memberKeys && p.epoch == c.epoch && t - p.firstSentAt < RETRY_WINDOW_SECONDS && t - p.lastSentAt >= retryDelay(p.attempts)
            }
            if (due.isNotEmpty()) sendRoster(c, due.map { it.pubkey })
            // Forget entries for people no longer in the circle, or for an older key.
            update { s ->
                s.copy(circles = s.circles.map { cur ->
                    if (cur.id != c.id) cur else cur.copy(pendingAcks = cur.pendingAcks.filter { it.pubkey in memberKeys && it.epoch == cur.epoch })
                })
            }
        }
    }

    private fun retryDelay(attempts: Int): Long = when (attempts) {
        0, 1 -> 120L
        2 -> 600L
        3 -> 1_800L
        4 -> 7_200L
        else -> 21_600L
    }

    // -----------------------------------------------------------------------------------------
    // Heartbeats and key checks (protocol v2)
    // -----------------------------------------------------------------------------------------

    private suspend fun publishControl(circleId: String, ctl: ByteArray, epoch: Int, closed: Boolean, signer: KeyPair = identity) {
        val t = now()
        val body = EpochBody(signer.publicHex, t, circleId, epoch, closed)
        val publisher = CircleCrypto.controlPublisher(signer.secret, circleId, epoch, CircleCrypto.day(t))
        pool.publish(CircleCrypto.sealControl(signer, ctl, epoch, body, publisher, t))
    }

    /** The admin's signed "this epoch is current", which members need to keep publishing. */
    private suspend fun publishHeartbeat(c: Circle) {
        if (c.version < 2 || c.adminPubkey != myPubkey) return
        val ctl = c.ctlKeyHex ?: return
        publishControl(c.id, ctl.hexToBytes(), c.epoch, closed = false)
        val t = now()
        update { s -> s.copy(circles = s.circles.map { if (it.id == c.id) it.copy(heartbeatPublishedAt = t, heartbeatAt = t) else it }) }
    }

    /** Admin: upgrade v1 circles, rotate weekly, publish heartbeats and closed notices. */
    private suspend fun adminDuties() {
        val t = now()
        state.value.circles.filter { it.adminPubkey == myPubkey }.forEach { c ->
            val current = state.value.circles.firstOrNull { it.id == c.id } ?: return@forEach
            when {
                current.version < 2 -> rotate(current, current.members)
                t - current.rotatedAt >= ROTATE_EVERY_SECONDS && current.members.size > 1 -> rotate(current, current.members)
                CircleCrypto.day(current.heartbeatPublishedAt) != CircleCrypto.day(t) || t - current.heartbeatPublishedAt >= HEARTBEAT_EVERY_SECONDS ->
                    publishHeartbeat(current)
            }
        }
        // Repeat "closed" notices daily for recent epochs, so a phone that was off still hears it.
        val d = CircleCrypto.day(t)
        state.value.circles.filter { it.adminPubkey == myPubkey }.forEach { c ->
            val due = c.oldControls.filter { it.until > t && it.closedPublishedDay != d }
            if (due.isEmpty()) return@forEach
            due.forEach { old -> publishControl(c.id, old.ctlKeyHex.hexToBytes(), old.epoch, closed = true) }
            update { s ->
                s.copy(circles = s.circles.map { cur ->
                    if (cur.id != c.id) cur else cur.copy(oldControls = cur.oldControls.map { o -> if (due.any { it.epoch == o.epoch }) o.copy(closedPublishedDay = d) else o })
                })
            }
        }
    }

    /**
     * Whether this phone may publish its location to [c] right now. For v2 circles a member needs
     * to know its key is current: a fresh admin heartbeat for this epoch, no "closed" notice, and
     * relays that have finished sending what they hold since the last (re)connect. Fails closed.
     */
    fun shareStatus(c: Circle): ShareStatus = when {
        c.version < 2 || c.adminPubkey == myPubkey -> ShareStatus.OK
        c.stale -> ShareStatus.KEY_REPLACED
        now() - c.heartbeatAt > HEARTBEAT_MAX_AGE_SECONDS -> ShareStatus.ADMIN_AWAY
        !pool.isSynced(SUB_CONTROL) -> ShareStatus.CHECKING
        else -> ShareStatus.OK
    }

    /** Member: if this phone can't confirm its key, ask the admin for the roster again. */
    private suspend fun memberDuties() {
        state.value.circles.filter { it.version >= 2 && it.adminPubkey != myPubkey }.forEach { c ->
            val status = shareStatus(c)
            if (status == ShareStatus.KEY_REPLACED || status == ShareStatus.ADMIN_AWAY) requestRoster(c)
        }
    }

    private suspend fun requestRoster(c: Circle) {
        val t = now()
        if (t - c.rosterRequestedAt < ROSTER_REQUEST_EVERY_SECONDS) return
        update { s -> s.copy(circles = s.circles.map { if (it.id == c.id) it.copy(rosterRequestedAt = t) else it }) }
        sendDirect(c, c.adminPubkey, RosterRequestBody(myPubkey, t, c.id, c.epoch))
    }

    // -----------------------------------------------------------------------------------------
    // Backups
    // -----------------------------------------------------------------------------------------

    /** An encrypted backup of identity, profile, settings and circles. Slow on purpose (PBKDF2). */
    fun exportBackup(passphrase: String): String = Backups.encrypt(Backups.fromState(state.value), passphrase)

    /**
     * Replaces everything on this device with a backup, including the identity key. Sharing starts
     * paused. Slow on purpose (PBKDF2), so call it off the main thread.
     */
    suspend fun importBackup(text: String, passphrase: String) {
        // Decrypting first means a wrong passphrase or damaged file changes nothing at all.
        val restored = Backups.toState(Backups.decrypt(text, passphrase)).copy(profileChangedAt = now())
        val keys = KeyPair(restored.identitySecretHex.hexToBytes())
        val recovering = _recovery.value != null
        // Keep the unreadable file rather than writing over it.
        if (recovering) store.setAside()
        mutex.withLock {
            identity = keys
            seen.clear()
            _state.value = restored
            store.save(json.encodeToString(AppState.serializer(), restored))
            _recovery.value = null
        }
        if (recovering) {
            if (started) connectIfNeeded()
            return
        }
        pool.setRelays(restored.relays)
        refreshSubscriptions()
        publishProfileIfDue()
    }

    // -----------------------------------------------------------------------------------------
    // Location
    // -----------------------------------------------------------------------------------------

    /**
     * Publishes one encrypted update to every circle this phone may publish to (see [shareStatus]).
     * Called by the platform's location service. Updates are never queued for later: one that
     * can't go out now is dropped, so nothing is sent late under a key replaced in the meantime.
     */
    suspend fun publishLocation(location: MemberLocation) {
        val s = state.value
        if (!s.sharing || _recovery.value != null) return
        val t = now()
        val location = if (s.shareBattery) location else location.copy(battery = null)
        val seq = maxOf(s.lastSeq + 1, t * 1000)
        val sentTo = mutableListOf<String>()
        s.circles.forEach { c ->
            if (shareStatus(c) != ShareStatus.OK) return@forEach
            val event = if (c.version < 2) sealV1(c, location, t) else sealV2(c, location, t, seq) ?: return@forEach
            pool.publish(event, queueIfOffline = false)
            sentTo += c.id
        }
        update { st ->
            st.copy(
                lastSeq = maxOf(st.lastSeq, seq),
                locations = st.locations + st.circles.filter { it.id in sentTo }.associate { c -> c.id to (st.locations[c.id].orEmpty() + (myPubkey to location)) },
            )
        }
    }

    private fun sealV1(c: Circle, location: MemberLocation, t: Long): Event {
        var loc = location
        while (true) {
            val event = CircleCrypto.seal(identity, c.keyHex.hexToBytes(), c.epoch, LocationBody(myPubkey, t, c.id, c.epoch, loc), t)
            // Drop the oldest batched points until the padded envelope fits the fixed size.
            if (event.content.length <= MAX_LOCATION_CONTENT || loc.points.isEmpty()) return event
            loc = loc.copy(points = loc.points.drop(1))
        }
    }

    private fun sealV2(c: Circle, location: MemberLocation, t: Long, seq: Long): Event? {
        val key = dayKey(c, CircleCrypto.day(t)) ?: return null
        val publisher = CircleCrypto.memberPublisher(identity.secret, c.id, c.epoch, CircleCrypto.slot(t))
        var loc = location
        while (true) {
            val body = LocationBody(myPubkey, t, c.id, c.epoch, loc, pub = publisher.publicHex, seq = seq)
            val event = CircleCrypto.sealLocation(identity, key, c.epoch, body, publisher, t)
            if (event.content.length <= MAX_LOCATION_CONTENT || loc.points.isEmpty()) return event
            loc = loc.copy(points = loc.points.drop(1))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Incoming events
    // -----------------------------------------------------------------------------------------

    private suspend fun onEvent(relay: String, event: Event) {
        if (_recovery.value != null) return
        val fresh = mutex.withLock { seen.add(event.id) }
        if (!fresh || !event.isValid()) return
        when (event.kind) {
            Kinds.SEALED_DIRECT -> handleDirect(event)
            Kinds.CIRCLE_UPDATE -> handleCircleUpdate(event)
        }
    }

    private suspend fun handleCircleUpdate(event: Event) {
        val target = tagIndex[event.tag("d") ?: return] ?: return
        when (target.kind) {
            TagKind.PROFILE -> return handleProfile(target, event)
            TagKind.CONTROL -> return handleControl(target, event)
            TagKind.LOCATION -> Unit
        }
        val circleId = target.circleId
        val body = CircleCrypto.open(target.key, target.epoch, event) as? LocationBody ?: return
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        if (body.circle != circleId || body.from == myPubkey || circle.members.none { it.pubkey == body.from }) return
        if (body.loc.time > now() + MAX_CLOCK_SKEW_SECONDS) return
        val seqKey = "$circleId|${body.from}"
        if (target.v2) {
            // Only the member can sign with the key named inside their signed body, and each
            // update must be newer than the last one accepted: no overwrites, no replays.
            if (body.pub != event.pubkey || body.epoch != target.epoch) return
            if (body.seq <= (state.value.memberSeqs[seqKey] ?: 0L)) return
        } else {
            // v1: the publisher key must be the one this member derives for this hour.
            if (CircleCrypto.publisher(target.key, body.from, CircleCrypto.slot(event.createdAt)).publicHex != event.pubkey) return
        }
        update { s ->
            val current = s.locations[circleId].orEmpty()
            val existing = current[body.from]
            if (!target.v2 && existing != null && existing.time >= body.loc.time) s
            else if (target.v2 && body.seq <= (s.memberSeqs[seqKey] ?: 0L)) s
            else s.copy(
                locations = s.locations + (circleId to (current + (body.from to body.loc))),
                memberSeqs = if (target.v2) s.memberSeqs + (seqKey to body.seq) else s.memberSeqs,
            )
        }
    }

    private suspend fun handleProfile(target: TagTarget, event: Event) {
        val body = CircleCrypto.open(target.key, target.epoch, event) as? ProfileBody ?: return
        if (body.from == myPubkey) return
        val circle = state.value.circles.firstOrNull { it.id == target.circleId } ?: return
        if (circle.members.none { it.pubkey == body.from }) return
        if (target.v2) {
            if (body.pub != event.pubkey) return
        } else if (CircleCrypto.profilePublisher(target.key, body.from, target.epoch).publicHex != event.pubkey) return
        val photo = body.photo?.takeIf { it.length <= CircleCrypto.MAX_PHOTO_CHARS }
        update { s ->
            val existing = s.profiles[body.from]
            if (existing != null && existing.updated >= body.ts) s
            else s.copy(profiles = s.profiles + (body.from to MemberProfile(body.name.take(40), body.color, photo, body.ts)))
        }
    }

    /** An admin heartbeat or "closed" notice under the control key of our current epoch. */
    private suspend fun handleControl(target: TagTarget, event: Event) {
        val body = CircleCrypto.open(target.key, target.epoch, event) as? EpochBody ?: return
        val c = state.value.circles.firstOrNull { it.id == target.circleId } ?: return
        if (body.from != c.adminPubkey || body.circle != c.id || c.adminPubkey == myPubkey) return
        if (target.epoch != c.epoch || body.epoch != c.epoch) return
        if (body.closed) {
            update { s -> s.copy(circles = s.circles.map { if (it.id == c.id && it.epoch == body.epoch) it.copy(stale = true) else it }) }
            state.value.circles.firstOrNull { it.id == c.id }?.let { requestRoster(it) }
        } else {
            val ts = minOf(body.ts, now())
            update { s -> s.copy(circles = s.circles.map { if (it.id == c.id && it.epoch == body.epoch && ts > it.heartbeatAt) it.copy(heartbeatAt = ts) else it }) }
        }
    }

    private suspend fun publishProfile(circle: Circle) {
        val s = state.value
        val t = now()
        if (circle.version < 2) {
            val body = ProfileBody(myPubkey, maxOf(s.profileChangedAt, 1), s.myName, s.myColor, s.myPhoto)
            pool.publish(CircleCrypto.sealProfile(identity, circle.keyHex.hexToBytes(), circle.epoch, body, t))
        } else {
            val ctl = circle.ctlKeyHex ?: return
            val publisher = CircleCrypto.memberProfilePublisher(identity.secret, circle.id, circle.epoch)
            val body = ProfileBody(myPubkey, maxOf(s.profileChangedAt, 1), s.myName, s.myColor, s.myPhoto, pub = publisher.publicHex)
            pool.publish(CircleCrypto.sealProfileV2(identity, ctl.hexToBytes(), circle.epoch, body, publisher, t))
        }
    }

    /** Publishes the profile to every circle when it changed, and once a day so it stays on the relays. */
    private suspend fun publishProfileIfDue() {
        val s = state.value
        val t = now()
        if (s.circles.isEmpty()) return
        if (s.profileChangedAt <= s.profilePublishedAt && t - s.profilePublishedAt < 24 * 3600) return
        s.circles.forEach { publishProfile(it) }
        update { it.copy(profilePublishedAt = t) }
    }

    private suspend fun handleDirect(event: Event) {
        val ref = inboxIndex[event.tag("p") ?: return] ?: return
        when (val body = DirectMessages.open(identity, event) ?: return) {
            is JoinRequestBody -> onJoinRequest(body)
            is RosterBody -> onRoster(body.from, body.ts, body.circle, v2Body = false)
            is Roster2Body -> onRoster(body.from, body.ts, body.circle, v2Body = true)
            is RemovedBody -> onRemoved(body)
            is LeftBody -> onLeft(body)
            is RosterAckBody -> onRosterAck(body)
            is RosterRequestBody -> onRosterRequest(body, ref)
            else -> Unit
        }
        update { it.copy(seenEventIds = (it.seenEventIds + event.id).takeLast(500)) }
    }

    private suspend fun onJoinRequest(body: JoinRequestBody) {
        val s = state.value
        val invite = s.invites.firstOrNull { it.secret == body.secret && it.circleId == body.circle } ?: return
        if (invite.used || invite.expiresAt < now()) return
        if (s.joinRequests.any { it.pubkey == body.from && it.circleId == body.circle }) return
        val req = JoinRequest(PlatformCrypto.randomBytes(8).toHex(), body.circle, body.from, body.name.take(40), now())
        update { st ->
            st.copy(
                joinRequests = st.joinRequests + req,
                invites = st.invites.map { if (it.secret == invite.secret) it.copy(used = true) else it },
            )
        }
    }

    private suspend fun onRoster(from: String, ts: Long, rc: RosterCircle, v2Body: Boolean) {
        // A v2 roster must arrive as a v2 message (and v1 as v1), so neither can be misread as the other.
        if ((rc.v >= 2) != v2Body) return
        if (rc.v >= 2 && rc.ctl == null) return
        val s = state.value
        val existing = s.circles.firstOrNull { it.id == rc.id }
        val pending = s.pendingJoins.firstOrNull { it.circleId == rc.id }
        val sameEpochKey = existing != null && rc.epoch == existing.epoch &&
            (if (rc.v >= 2) rc.ctl == existing.ctlKeyHex else rc.key == existing.keyHex)
        val trusted = when {
            // The admin can do anything: add people, rotate keys, change managers, hand over the role.
            existing != null && from == existing.adminPubkey -> rc.epoch >= existing.epoch
            // Managers can only add people under the current key.
            existing != null && from in existing.managers -> sameEpochKey && rc.admin == existing.adminPubkey
            existing != null -> false
            // Joining: the roster must come from whoever invited us (the admin or a manager).
            pending != null -> from == pending.inviterPubkey
            else -> false
        }
        if (!trusted) return
        val fromAdmin = existing == null || from == existing.adminPubkey
        val newEpoch = existing == null || rc.epoch > existing.epoch
        // Within one key, rosters only add people (removal always rotates the key), so merge them.
        // That way an admin and a manager approving people at the same moment don't drop anyone.
        val members = if (!newEpoch && existing != null) (existing.members + rc.members).distinctBy { it.pubkey } else rc.members
        if (members.none { it.pubkey == myPubkey }) {
            if (fromAdmin) forgetCircle(rc.id)
            return
        }
        val rotated = existing != null && rc.epoch > existing.epoch
        val base = existing ?: Circle(id = rc.id, name = rc.name, keyHex = rc.key, epoch = rc.epoch, adminPubkey = rc.admin, members = members)
        val circle = base.copy(
            name = rc.name,
            members = members,
            adminPubkey = if (fromAdmin) rc.admin else base.adminPubkey,
            managers = if (fromAdmin) rc.managers else base.managers,
            version = if (newEpoch) rc.v else base.version,
            keyHex = if (newEpoch) rc.key else base.keyHex,
            keyDay = if (newEpoch) rc.day else base.keyDay,
            ctlKeyHex = if (newEpoch) rc.ctl else base.ctlKeyHex,
            epoch = rc.epoch,
            previousKeyHex = if (rotated) dayKey(existing!!, today())?.toHex() else base.previousKeyHex,
            previousKeyDay = if (rotated) (if (existing!!.version >= 2) today() else null) else base.previousKeyDay,
            previousEpoch = if (rotated) existing!!.epoch else base.previousEpoch,
            previousValidUntil = if (rotated) now() + OVERLAP_SECONDS else base.previousValidUntil,
            stale = if (newEpoch) false else base.stale,
            heartbeatAt = if (fromAdmin && rc.admin == from) maxOf(if (newEpoch) 0 else base.heartbeatAt, minOf(ts, now())) else base.heartbeatAt,
        )
        update { st ->
            val memberKeys = members.map { it.pubkey }.toSet()
            st.copy(
                circles = st.circles.filterNot { it.id == rc.id } + circle,
                lastCircleId = if (existing == null) rc.id else st.lastCircleId,
                pendingJoins = st.pendingJoins.filterNot { it.circleId == rc.id },
                locations = st.locations + (rc.id to st.locations[rc.id].orEmpty().filterKeys { it in memberKeys }),
            )
        }
        refreshSubscriptions()
        // Tell the sender we have it, so they stop resending. After a handover the new admin tracks it.
        if (circle.version >= 2) {
            val ackTo = if (fromAdmin) rc.admin else from
            if (ackTo != myPubkey) sendDirect(circle, ackTo, RosterAckBody(myPubkey, now(), circle.id, circle.epoch))
        }
        // Joined, rotated or someone new arrived: (re)publish our profile under the current key.
        if (existing == null || rotated || existing.members.size != members.size) publishProfile(circle)
    }

    private suspend fun onRosterAck(body: RosterAckBody) {
        update { s ->
            s.copy(circles = s.circles.map { c ->
                if (c.id != body.circle) c else c.copy(pendingAcks = c.pendingAcks.filterNot { it.pubkey == body.from && it.epoch <= body.epoch })
            })
        }
    }

    /** A member can't confirm its key: resend the roster if they're still in, otherwise tell them they're out. */
    private suspend fun onRosterRequest(body: RosterRequestBody, ref: InboxRef) {
        val c = state.value.circles.firstOrNull { it.id == body.circle } ?: return
        if (c.adminPubkey != myPubkey || c.version < 2) return
        if (c.members.any { it.pubkey == body.from }) {
            val recent = c.pendingAcks.firstOrNull { it.pubkey == body.from && it.epoch == c.epoch }
            if (recent != null && now() - recent.lastSentAt < 600) return
            sendRoster(c, listOf(body.from))
        } else if (ref.circleId == c.id) {
            sendDirect(c, body.from, RemovedBody(myPubkey, now(), c.id))
        }
    }

    private suspend fun onRemoved(body: RemovedBody) {
        val circle = state.value.circles.firstOrNull { it.id == body.circle } ?: return
        if (body.from != circle.adminPubkey) return
        forgetCircle(circle.id)
    }

    /** A member left: if we're the admin, remove them properly so the circle gets a new key. */
    private suspend fun onLeft(body: LeftBody) {
        val circle = state.value.circles.firstOrNull { it.id == body.circle } ?: return
        if (circle.adminPubkey != myPubkey || circle.members.none { it.pubkey == body.from }) return
        removeMember(circle.id, body.from)
    }

    // -----------------------------------------------------------------------------------------
    // Subscriptions and housekeeping
    // -----------------------------------------------------------------------------------------

    private suspend fun refreshSubscriptions() {
        val t = now()
        val slot = CircleCrypto.slot(t)
        val d = CircleCrypto.day(t)
        val s = state.value
        val index = mutableMapOf<String, TagTarget>()
        val inboxes = mutableMapOf<String, InboxRef>()
        s.circles.forEach { c ->
            val v2 = c.version >= 2
            // Current and previous hour, so updates published just before the hour still arrive.
            listOf(slot, slot - 1).forEach { sl ->
                val day = CircleCrypto.day(sl * 3600)
                dayKey(c, day)?.let { k -> index[CircleCrypto.tag(k, c.epoch, sl)] = TagTarget(c.id, k, c.epoch, TagKind.LOCATION, v2) }
                if (c.previousEpoch != null && (c.previousValidUntil ?: 0) > t) {
                    previousDayKey(c, day)?.let { k ->
                        index[CircleCrypto.tag(k, c.previousEpoch, sl)] = TagTarget(c.id, k, c.previousEpoch, TagKind.LOCATION, c.previousKeyDay != null)
                    }
                }
            }
            if (!v2) {
                val k = c.keyHex.hexToBytes()
                index[CircleCrypto.profileTag(k, c.epoch)] = TagTarget(c.id, k, c.epoch, TagKind.PROFILE, false)
            } else {
                val ctl = c.ctlKeyHex!!.hexToBytes()
                index[CircleCrypto.profileTag(ctl, c.epoch)] = TagTarget(c.id, ctl, c.epoch, TagKind.PROFILE, true)
                // Heartbeats and closed notices for the past week.
                if (c.adminPubkey != myPubkey) {
                    (0..7).forEach { back -> index[CircleCrypto.controlTag(ctl, c.epoch, d - back)] = TagTarget(c.id, ctl, c.epoch, TagKind.CONTROL, true) }
                    index[CircleCrypto.closedTag(ctl, c.epoch)] = TagTarget(c.id, ctl, c.epoch, TagKind.CONTROL, true)
                }
                // Our inboxes for the past week (messages expire after 7 days), under this and recent control keys.
                (listOf(c.ctlKeyHex) + c.oldControls.filter { it.until > t }.map { it.ctlKeyHex }).forEach { k ->
                    val kb = k.hexToBytes()
                    (0..7).forEach { back -> inboxes[CircleCrypto.inbox(kb, myPubkey, d - back)] = InboxRef(c.id, kb) }
                }
            }
        }
        // Our public key is only needed as an inbox for v1 circles and for joins in progress.
        val needIdentity = s.circles.any { it.version < 2 } || s.pendingJoins.isNotEmpty() || s.invites.any { !it.used && it.expiresAt > t }
        if (needIdentity) inboxes[myPubkey] = InboxRef(null, null)
        tagIndex = index
        inboxIndex = inboxes
        val subs = buildMap {
            if (inboxes.isNotEmpty()) put(SUB_DIRECT, listOf(Filter(kinds = listOf(Kinds.SEALED_DIRECT), tagP = inboxes.keys.sorted(), since = t - 7 * 24 * 3600)))
            val circleTags = index.filterValues { it.kind != TagKind.CONTROL }.keys.sorted()
            if (circleTags.isNotEmpty()) put(SUB_CIRCLES, listOf(Filter(kinds = listOf(Kinds.CIRCLE_UPDATE), tagD = circleTags)))
            val controlTags = index.filterValues { it.kind == TagKind.CONTROL }.keys.sorted()
            if (controlTags.isNotEmpty()) put(SUB_CONTROL, listOf(Filter(kinds = listOf(Kinds.CIRCLE_UPDATE), tagD = controlTags)))
        }
        pool.setSubscriptions(subs)
    }

    private suspend fun pruneExpired() {
        val t = now()
        update { s ->
            s.copy(
                invites = s.invites.filter { it.expiresAt > t - 24 * 3600 },
                pendingJoins = s.pendingJoins.filter { it.sentAt > t - 7 * 24 * 3600 },
                circles = s.circles.map { c ->
                    var n = c
                    if (n.previousValidUntil != null && n.previousValidUntil!! < t) {
                        n = n.copy(previousKeyHex = null, previousEpoch = null, previousValidUntil = null, previousKeyDay = null)
                    }
                    if (n.oldControls.any { it.until <= t }) n = n.copy(oldControls = n.oldControls.filter { it.until > t })
                    n
                },
            )
        }
        mutex.withLock { while (seen.size > 5000) seen.remove(seen.first()) }
    }

    private suspend fun update(transform: (AppState) -> AppState) {
        mutex.withLock {
            // Fail closed while saved state is unreadable: nothing changes and nothing is written.
            if (_recovery.value != null) return
            val next = transform(_state.value)
            if (next != _state.value) {
                _state.value = next
                store.save(json.encodeToString(AppState.serializer(), next))
            }
        }
    }

    companion object {
        /** How long the previous circle key stays valid after a rotation. */
        const val OVERLAP_SECONDS = 10 * 60L

        /** How long a replaced epoch's control key is kept to reach members who missed the rotation. */
        const val OLD_CONTROL_SECONDS = 14 * 24 * 3600L

        /** v2 circles get a fresh random key this often, so a stolen key stops working. */
        const val ROTATE_EVERY_SECONDS = 7 * 24 * 3600L

        const val HEARTBEAT_EVERY_SECONDS = 6 * 3600L

        /**
         * A member stops publishing if the admin's newest heartbeat is older than this. It bounds how
         * long a phone could keep using a replaced key if every relay withheld the "closed" notice.
         */
        const val HEARTBEAT_MAX_AGE_SECONDS = 3 * 24 * 3600L

        /** Unacknowledged rosters are resent for this long. */
        const val RETRY_WINDOW_SECONDS = 14 * 24 * 3600L

        const val ROSTER_REQUEST_EVERY_SECONDS = 30 * 60L

        /** Updates claiming to be from further in the future than this are ignored. */
        const val MAX_CLOCK_SKEW_SECONDS = 10 * 60L

        /**
         * Envelopes are padded to 1400 bytes, which NIP-44 pads to 1536, which base64-encodes to 2140
         * characters. Anything bigger drops batched points, so every update on the relays is the same size.
         */
        const val MAX_LOCATION_CONTENT = 2140

        /** No update for this long means the person is shown as offline. */
        const val OFFLINE_AFTER_SECONDS = 45 * 60L

        private const val SUB_DIRECT = "qm-dm"
        private const val SUB_CIRCLES = "qm-circles"
        private const val SUB_CONTROL = "qm-ctl"
    }
}

/** Whether this phone is sending its location to a circle, and if not, why. */
enum class ShareStatus {
    OK,

    /** Waiting for relays to confirm the circle key is still current (after starting or reconnecting). */
    CHECKING,

    /** The admin replaced the key and this phone hasn't received the new one yet. */
    KEY_REPLACED,

    /** No heartbeat from the admin for three days, so the key can't be confirmed. */
    ADMIN_AWAY,
}
