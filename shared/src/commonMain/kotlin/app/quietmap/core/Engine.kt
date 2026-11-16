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
import app.quietmap.core.model.PendingJoin
import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Filter
import app.quietmap.core.nostr.Kinds
import app.quietmap.core.nostr.RelayPool
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.nostr.RelayTransport
import app.quietmap.core.nostr.RelayUrls
import app.quietmap.core.protocol.CircleCrypto
import app.quietmap.core.protocol.DirectMessages
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.core.protocol.Backups
import app.quietmap.core.protocol.JoinRequestBody
import app.quietmap.core.protocol.LeftBody
import app.quietmap.core.protocol.LocationBody
import app.quietmap.core.protocol.ProfileBody
import app.quietmap.core.protocol.RemovedBody
import app.quietmap.core.protocol.RosterBody
import app.quietmap.core.protocol.RosterCircle
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

    /** What a d-tag we listen on belongs to. */
    private class TagTarget(val circleId: String, val key: ByteArray, val epoch: Int, val isProfile: Boolean)

    private var tagIndex: Map<String, TagTarget> = emptyMap()
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

    suspend fun createCircle(name: String): Circle {
        checkReady()
        val circle = Circle(
            id = CircleCrypto.newId(),
            name = name.trim().ifEmpty { "Family" },
            keyHex = CircleCrypto.newKey(),
            epoch = 1,
            adminPubkey = myPubkey,
            members = listOf(Member(myPubkey, state.value.myName)),
        )
        update { it.copy(circles = it.circles + circle, lastCircleId = circle.id) }
        refreshSubscriptions()
        publishProfile(circle)
        return circle
    }

    /** The admin and managers can invite and approve people. */
    fun canInvite(circle: Circle): Boolean = circle.adminPubkey == myPubkey || myPubkey in circle.managers

    /** Creates a single-use invite link valid for 24 hours. Only the admin and managers can invite. */
    suspend fun createInvite(circleId: String): String {
        val s = state.value
        val circle = s.circles.first { it.id == circleId }
        require(canInvite(circle)) { "Only the circle admin and managers can invite" }
        val secret = PlatformCrypto.randomBytes(16).toHex()
        val expires = now() + 24 * 3600
        update { it.copy(invites = it.invites + Invite(secret, circleId, expires)) }
        return InviteLinks.encode(InviteData(k = myPubkey, n = s.myName, r = s.relays, c = circleId, cn = circle.name, s = secret, e = expires))
    }

    /** Sends a join request for an invite the user has agreed to. */
    suspend fun acceptInvite(invite: InviteData) {
        checkReady()
        require(invite.e > now()) { "This invite has expired" }
        require(invite.k != myPubkey) { "This is your own invite" }
        // Make sure we can reach the inviter's relays too.
        val merged = (state.value.relays + invite.r).distinct().take(8)
        if (merged != state.value.relays) setRelays(merged)
        update {
            it.copy(pendingJoins = it.pendingJoins.filterNot { p -> p.circleId == invite.c } +
                PendingJoin(invite.c, invite.cn, invite.k, invite.n, now()))
        }
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
        sendRoster(updated)
        refreshSubscriptions()
        // The new member needs our profile; republishing also refreshes it on the relays.
        publishProfile(updated)
    }

    suspend fun deny(requestId: String) = update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.id == requestId }) }

    /** Removes a member and rotates the circle key so they can't read anything new. */
    suspend fun removeMember(circleId: String, pubkey: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can remove people" }
        require(pubkey != myPubkey)
        val rotated = circle.copy(
            keyHex = CircleCrypto.newKey(),
            epoch = circle.epoch + 1,
            members = circle.members.filterNot { it.pubkey == pubkey },
            previousKeyHex = circle.keyHex,
            previousEpoch = circle.epoch,
            previousValidUntil = now() + OVERLAP_SECONDS,
        )
        update { s ->
            s.copy(
                circles = s.circles.map { if (it.id == circleId) rotated else it },
                locations = s.locations + (circleId to (s.locations[circleId].orEmpty() - pubkey)),
            )
        }
        sendRoster(rotated)
        pool.publish(DirectMessages.seal(identity, pubkey, RemovedBody(myPubkey, now(), circleId), now()))
        refreshSubscriptions()
        publishProfile(rotated)
    }

    /** Lets a member invite and approve people (admin only). */
    suspend fun setManager(circleId: String, pubkey: String, manager: Boolean) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can choose managers" }
        require(circle.members.any { it.pubkey == pubkey } && pubkey != myPubkey)
        val updated = circle.copy(managers = if (manager) (circle.managers + pubkey).distinct() else circle.managers - pubkey)
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) updated else it }) }
        sendRoster(updated)
    }

    /** Hands the admin role to another member, so the old admin can leave (admin only). */
    suspend fun transferAdmin(circleId: String, pubkey: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can do this" }
        require(circle.members.any { it.pubkey == pubkey } && pubkey != myPubkey)
        val updated = circle.copy(adminPubkey = pubkey, managers = circle.managers - pubkey)
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) updated else it }) }
        sendRoster(updated)
    }

    /** Deletes the circle for everyone (admin only). */
    suspend fun deleteCircle(circleId: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can delete it" }
        circle.members.filter { it.pubkey != myPubkey }.forEach { m ->
            pool.publish(DirectMessages.seal(identity, m.pubkey, RemovedBody(myPubkey, now(), circleId, deleted = true), now()))
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
        if (circle.adminPubkey != myPubkey) {
            pool.publish(DirectMessages.seal(identity, circle.adminPubkey, LeftBody(myPubkey, now(), circleId), now()))
        }
        forgetCircle(circleId)
    }

    private suspend fun forgetCircle(circleId: String) {
        update { s ->
            s.copy(
                circles = s.circles.filterNot { it.id == circleId },
                locations = s.locations - circleId,
                joinRequests = s.joinRequests.filterNot { it.circleId == circleId },
                lastCircleId = if (s.lastCircleId == circleId) null else s.lastCircleId,
            )
        }
        refreshSubscriptions()
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

    private suspend fun sendRoster(circle: Circle) {
        val body = RosterBody(myPubkey, now(), RosterCircle(circle.id, circle.name, circle.keyHex, circle.epoch, circle.adminPubkey, circle.members, circle.managers))
        circle.members.filter { it.pubkey != myPubkey }.forEach { m ->
            pool.publish(DirectMessages.seal(identity, m.pubkey, body, now()))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Location
    // -----------------------------------------------------------------------------------------

    /** Publishes one encrypted update to every circle. Called by the platform's location service. */
    suspend fun publishLocation(location: MemberLocation) {
        val s = state.value
        if (!s.sharing || _recovery.value != null) return
        val t = now()
        val location = if (s.shareBattery) location else location.copy(battery = null)
        s.circles.forEach { c ->
            var loc = location
            var event: Event
            // Drop the oldest batched points until the padded envelope fits the fixed size.
            while (true) {
                event = CircleCrypto.seal(identity, c.keyHex.hexToBytes(), c.epoch, LocationBody(myPubkey, t, c.id, c.epoch, loc), t)
                if (event.content.length <= MAX_LOCATION_CONTENT || loc.points.isEmpty()) break
                loc = loc.copy(points = loc.points.drop(1))
            }
            pool.publish(event)
        }
        update { st ->
            st.copy(locations = st.locations + st.circles.associate { c -> c.id to (st.locations[c.id].orEmpty() + (myPubkey to location)) })
        }
    }

    // -----------------------------------------------------------------------------------------
    // Incoming events
    // -----------------------------------------------------------------------------------------

    private suspend fun onEvent(relay: String, event: Event) {
        val fresh = mutex.withLock { seen.add(event.id) }
        if (!fresh || !event.isValid()) return
        when (event.kind) {
            Kinds.SEALED_DIRECT -> handleDirect(event)
            Kinds.CIRCLE_UPDATE -> handleCircleUpdate(event)
        }
    }

    private suspend fun handleCircleUpdate(event: Event) {
        val target = tagIndex[event.tag("d") ?: return] ?: return
        if (target.isProfile) return handleProfile(target, event)
        val circleId = target.circleId
        val key = target.key
        val epoch = target.epoch
        val body = CircleCrypto.open(key, epoch, event) as? LocationBody ?: return
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        if (body.circle != circleId || body.from == myPubkey || circle.members.none { it.pubkey == body.from }) return
        // The publisher key must be the one this member derives for this hour, or someone else is posing as them.
        if (CircleCrypto.publisher(key, body.from, CircleCrypto.slot(event.createdAt)).publicHex != event.pubkey) return
        update { s ->
            val current = s.locations[circleId].orEmpty()
            val existing = current[body.from]
            if (existing != null && existing.time >= body.loc.time) s
            else s.copy(locations = s.locations + (circleId to (current + (body.from to body.loc))))
        }
    }

    private suspend fun handleProfile(target: TagTarget, event: Event) {
        val body = CircleCrypto.open(target.key, target.epoch, event) as? ProfileBody ?: return
        if (body.from == myPubkey) return
        val circle = state.value.circles.firstOrNull { it.id == target.circleId } ?: return
        if (circle.members.none { it.pubkey == body.from }) return
        if (CircleCrypto.profilePublisher(target.key, body.from, target.epoch).publicHex != event.pubkey) return
        val photo = body.photo?.takeIf { it.length <= CircleCrypto.MAX_PHOTO_CHARS }
        update { s ->
            val existing = s.profiles[body.from]
            if (existing != null && existing.updated >= body.ts) s
            else s.copy(profiles = s.profiles + (body.from to MemberProfile(body.name.take(40), body.color, photo, body.ts)))
        }
    }

    private suspend fun publishProfile(circle: Circle) {
        val s = state.value
        val body = ProfileBody(myPubkey, maxOf(s.profileChangedAt, 1), s.myName, s.myColor, s.myPhoto)
        pool.publish(CircleCrypto.sealProfile(identity, circle.keyHex.hexToBytes(), circle.epoch, body, now()))
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
        when (val body = DirectMessages.open(identity, event) ?: return) {
            is JoinRequestBody -> onJoinRequest(body)
            is RosterBody -> onRoster(body)
            is RemovedBody -> onRemoved(body)
            is LeftBody -> onLeft(body)
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

    private suspend fun onRoster(body: RosterBody) {
        val rc = body.circle
        val s = state.value
        val existing = s.circles.firstOrNull { it.id == rc.id }
        val pending = s.pendingJoins.firstOrNull { it.circleId == rc.id }
        val trusted = when {
            // The admin can do anything: add people, rotate keys, change managers, hand over the role.
            existing != null && body.from == existing.adminPubkey -> rc.epoch >= existing.epoch
            // Managers can only add people under the current key.
            existing != null && body.from in existing.managers ->
                rc.epoch == existing.epoch && rc.key == existing.keyHex && rc.admin == existing.adminPubkey
            existing != null -> false
            // Joining: the roster must come from whoever invited us (the admin or a manager).
            pending != null -> body.from == pending.inviterPubkey
            else -> false
        }
        if (!trusted) return
        val fromAdmin = existing == null || body.from == existing.adminPubkey
        val sameKey = existing != null && rc.epoch == existing.epoch
        // Within one key, rosters only add people (removal always rotates the key), so merge them.
        // That way an admin and a manager approving people at the same moment don't drop anyone.
        val members = if (sameKey) (existing.members + rc.members).distinctBy { it.pubkey } else rc.members
        if (members.none { it.pubkey == myPubkey }) {
            if (fromAdmin) forgetCircle(rc.id)
            return
        }
        val rotated = existing != null && rc.epoch > existing.epoch
        val circle = Circle(
            id = rc.id,
            name = rc.name,
            keyHex = rc.key,
            epoch = rc.epoch,
            adminPubkey = if (fromAdmin) rc.admin else existing.adminPubkey,
            members = members,
            managers = if (fromAdmin) rc.managers else existing.managers,
            previousKeyHex = if (rotated) existing.keyHex else existing?.previousKeyHex,
            previousEpoch = if (rotated) existing.epoch else existing?.previousEpoch,
            previousValidUntil = if (rotated) now() + OVERLAP_SECONDS else existing?.previousValidUntil,
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
        // Joined, rotated or someone new arrived: (re)publish our profile under the current key.
        if (existing == null || rotated || existing.members.size != members.size) publishProfile(circle)
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
        val index = mutableMapOf<String, TagTarget>()
        state.value.circles.forEach { c ->
            val keys = mutableListOf(c.keyHex to c.epoch)
            if (c.previousKeyHex != null && c.previousEpoch != null && (c.previousValidUntil ?: 0) > t) keys += c.previousKeyHex to c.previousEpoch
            keys.forEach { (k, e) ->
                val kb = k.hexToBytes()
                // Current and previous hour, so updates published just before the hour still arrive.
                listOf(slot, slot - 1).forEach { sl -> index[CircleCrypto.tag(kb, e, sl)] = TagTarget(c.id, kb, e, isProfile = false) }
                index[CircleCrypto.profileTag(kb, e)] = TagTarget(c.id, kb, e, isProfile = true)
            }
        }
        tagIndex = index
        val subs = buildMap {
            put("qm-dm", listOf(Filter(kinds = listOf(Kinds.SEALED_DIRECT), tagP = listOf(myPubkey), since = t - 7 * 24 * 3600)))
            if (index.isNotEmpty()) put("qm-circles", listOf(Filter(kinds = listOf(Kinds.CIRCLE_UPDATE), tagD = index.keys.sorted())))
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
                    if (c.previousValidUntil != null && c.previousValidUntil < t) c.copy(previousKeyHex = null, previousEpoch = null, previousValidUntil = null) else c
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

        /**
         * Envelopes are padded to 1400 bytes, which NIP-44 pads to 1536, which base64-encodes to 2140
         * characters. Anything bigger drops batched points, so every update on the relays is the same size.
         */
        const val MAX_LOCATION_CONTENT = 2140

        /** No update for this long means the person is shown as offline. */
        const val OFFLINE_AFTER_SECONDS = 45 * 60L
    }
}
