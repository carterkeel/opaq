package app.opaq.core

import app.opaq.core.crypto.KeyPair
import app.opaq.core.crypto.Keys
import app.opaq.core.crypto.PlatformCrypto
import app.opaq.core.crypto.constantTimeEquals
import app.opaq.core.crypto.hexToBytes
import app.opaq.core.crypto.toHex
import app.opaq.core.model.RELAY_DEFAULTS_CHANGES
import app.opaq.core.model.Activity
import app.opaq.core.model.AppState
import app.opaq.core.model.THEME_DARK
import app.opaq.core.model.THEME_LIGHT
import app.opaq.core.model.THEME_MIDNIGHT
import app.opaq.core.model.THEME_SYSTEM
import app.opaq.core.model.Circle
import app.opaq.core.model.Contact
import app.opaq.core.model.Invite
import app.opaq.core.model.LocationPrecision
import app.opaq.core.model.Precision
import app.opaq.core.model.JoinRefusal
import app.opaq.core.model.JoinRequest
import app.opaq.core.model.Member
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.MemberProfile
import app.opaq.core.model.MembershipChange
import app.opaq.core.model.MembershipEvent
import app.opaq.core.model.PinLock
import app.opaq.core.model.Place
import app.opaq.core.model.SecurityEvent
import app.opaq.core.model.SecurityEventType
import app.opaq.core.model.OldControl
import app.opaq.core.model.PendingAck
import app.opaq.core.model.PendingJoin
import app.opaq.core.model.withNewDefaultRelays
import app.opaq.core.nostr.Event
import app.opaq.core.nostr.Filter
import app.opaq.core.nostr.Kinds
import app.opaq.core.nostr.RelayPool
import app.opaq.core.nostr.RelayStatus
import app.opaq.core.nostr.RelayTransport
import app.opaq.core.nostr.RelayUrls
import app.opaq.core.nostr.SocksProxy
import app.opaq.core.protocol.Body
import app.opaq.core.protocol.CircleCrypto
import app.opaq.core.protocol.DirectMessages
import app.opaq.core.protocol.EpochBody
import app.opaq.core.protocol.InviteData
import app.opaq.core.protocol.InviteLinks
import app.opaq.core.protocol.Backups
import app.opaq.core.protocol.JoinRefusedBody
import app.opaq.core.protocol.JoinRequestBody
import app.opaq.core.protocol.LeftBody
import app.opaq.core.protocol.LocationRequestBody
import app.opaq.core.protocol.LocationBody
import app.opaq.core.protocol.ProfileBody
import app.opaq.core.protocol.RemovedBody
import app.opaq.core.protocol.Roster2Body
import app.opaq.core.protocol.RosterAckBody
import app.opaq.core.protocol.RosterBody
import app.opaq.core.protocol.RosterCircle
import app.opaq.core.protocol.RosterRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * The whole Opaq protocol: identity, circles, invites, key rotation and location exchange.
 * Platform code only supplies storage, a clock, and location fixes.
 */
class Engine(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    /** Builds the relay connection; tests pass an in-memory network instead of real relays. */
    transport: ((onEvent: suspend (relay: String, event: Event) -> Unit) -> RelayTransport)? = null,
    /** Shown in the security log, e.g. "Pixel 8". */
    private val deviceName: String = "this phone",
    /**
     * Seconds on a clock that changing the date and time can't move (time since boot), for PIN
     * lockouts and the PIN window. Tests use [now].
     */
    private val elapsed: () -> Long = now,
    /** Where location history is kept; null keeps none (as in most tests). */
    historyFiles: HistoryFiles? = null,
    /**
     * Debug builds only: relays to use instead of the saved ones (and any an invite asks for), such
     * as a relay on this machine (tools/test-relay.sh), so test devices never touch public relays.
     * Saved settings aren't changed. Addresses [RelayUrls] doesn't allow leave no relays at all
     * rather than falling back to the public ones.
     */
    val testRelays: List<String>? = null,
) {
    private val historyStore = historyFiles?.let { LocationHistory(it) }
    private var historyPrunedAt = 0L

    /** Events kept for "Export logs" (#154), next to the history files; null where history isn't kept. */
    private val diagnostics = historyFiles?.let { DiagnosticLog(it) }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** Serializes PIN checks. Separate from [mutex], which is never held during the slow PIN hash. */
    private val pinMutex = Mutex()

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState>
    /** This device's identity key. Changes only when a backup is imported. */
    var identity: KeyPair
        private set

    private val pool: RelayTransport = transport?.invoke(::onEvent) ?: RelayPool(scope, ::onEvent) { line ->
        log(line)
        diagnostic(line)
    }
    val relayStatus: StateFlow<Map<String, RelayStatus>> = pool.status

    private enum class TagKind { LOCATION, PROFILE, CONTROL }

    /** What a d-tag we listen on belongs to. [key] is the key that opens events under it. */
    private class TagTarget(val circleId: String, val key: ByteArray, val epoch: Int, val kind: TagKind, val v2: Boolean)

    /** What an inbox (`p` tag) we listen on belongs to: our identity key (v1), a circle's control key, or an invite. */
    private class InboxRef(val circleId: String?, val ctl: ByteArray?, val inviteSecret: String? = null)

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

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 16)

    /** Things worth a notification: someone joined or left a circle. The platform shows them. */
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private val _locationRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * Someone in a circle we share with asked where we are (#149), by circle id. The sharing service
     * takes a fresh fix and sends it at once, as a normal update.
     */
    val locationRequests: SharedFlow<String> = _locationRequests.asSharedFlow()

    /** When we last asked each person (circle|pubkey), and when we last answered each person. Memory only. */
    private val locationAsked = HashMap<String, Long>()
    private val locationAnswered = HashMap<String, Long>()
    private val locationAnswers = ArrayDeque<Long>()

    /** Until when the last correct PIN entry authorizes sensitive actions (memory only). */
    private var authorizedUntil = 0L
    /** Until when the last PIN (or face or fingerprint) entry still counts for one backup. */
    private var freshUntil = 0L

    init {
        val found = readStore()
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
            _recovery.value == null -> newState()
            // Placeholder while recovering: never saved, never connected.
            else -> AppState(identitySecretHex = KeyPair.generate().secret.toHex(), relays = emptyList(), sharing = false, setupDone = true)
        }
        identity = KeyPair(initial.identitySecretHex.hexToBytes())
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        seen.addAll(initial.seenEventIds)
        if (found == StoredState.Missing) store.save(json.encodeToString(AppState.serializer(), initial))
    }

    private fun readStore(): StoredState = try {
        store.read()
    } catch (t: Throwable) {
        StoredState.Unreadable(t.message ?: "Secure storage isn't available", retryable = true)
    }

    private fun checkReady() = check(_recovery.value == null) { "Opaq can't read its saved data yet" }

    /** A new install's state: a new identity and the current default relays. */
    private fun newState() = AppState(identitySecretHex = KeyPair.generate().secret.toHex(), relayDefaultsVersion = RELAY_DEFAULTS_CHANGES.size)

    private fun decode(data: String): AppState? = runCatching { json.decodeFromString(AppState.serializer(), data) }.getOrNull()?.withNewDefaultRelays()

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
                delay(housekeepingDelayMillis(now()))
            }
        }
    }

    private suspend fun connectIfNeeded() {
        if (connected) return
        connected = true
        pool.setSocksProxy(torProxy())
        pool.setRelays(effectiveRelays())
        refreshSubscriptions()
        publishProfileIfDue()
    }

    /** One pass of the periodic work. Tests call it directly instead of waiting for the timer. */
    suspend fun housekeeping() {
        if (_recovery.value != null) return
        if (state.value.consentAt == 0L) update { it.copy(consentAt = now()) }
        endTimedPauses()
        pruneExpired()
        advanceRatchets()
        adminDuties()
        retryRosters()
        endHandovers()
        pruneHistory()
        memberDuties()
        refreshSubscriptions()
        publishProfileIfDue()
    }

    /** Reads saved state again, e.g. once the phone is unlocked. Returns true if it worked. */
    suspend fun retryLoad(): Boolean {
        if (_recovery.value == null) return true
        val found = readStore()
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
        val fresh = newState()
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

    /**
     * Turns sharing on or off for every circle. Turning it on counts as confirming it, for the monthly
     * reminder. Turning it off first tells each circle (a last "paused" update), and [resumeAt] (unix
     * seconds, 0 = none) turns it back on then, the next time the app runs.
     */
    suspend fun setSharing(on: Boolean, resumeAt: Long = 0) {
        if (on != state.value.sharing) diagnostic(if (on) "Sharing turned on" else if (resumeAt > 0) "Sharing paused for a while" else "Sharing paused")
        if (!on && state.value.sharing) publishPausedNotice(state.value.circles)
        update { if (on) it.copy(sharing = true, consentAt = now(), sharingResumeAt = 0) else it.copy(sharing = false, sharingResumeAt = resumeAt) }
    }

    /**
     * Pauses or resumes sharing with one circle. Pausing tells the circle first (a last "paused"
     * update); [until] (unix seconds, 0 = until resumed by hand) ends the pause on its own. Resuming
     * needs no PIN, like turning sharing back on: it only goes back to what was shared before.
     */
    suspend fun setCirclePaused(circleId: String, paused: Boolean, until: Long = 0) {
        val c = state.value.circles.firstOrNull { it.id == circleId } ?: return
        if (paused != c.paused) diagnostic("${circleLabel(circleId)} ${if (paused) "paused" else "resumed"}")
        if (paused && !c.paused && state.value.sharing) publishPausedNotice(listOf(c))
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) it.copy(paused = paused, pausedUntil = if (paused) until else 0) else it }) }
    }

    /** Ends timed pauses that are over: all sharing, and single circles. */
    private suspend fun endTimedPauses() {
        val t = now()
        val s = state.value
        if (!s.sharing && s.sharingResumeAt in 1..t) setSharing(true)
        s.circles.filter { it.paused && it.pausedUntil in 1..t }.forEach { setCirclePaused(it.id, false) }
    }

    /**
     * One last update to each of [circles] that this phone shares with now, saying sharing is paused:
     * the last position shared, as still, without speed or path, stamped now. Without it the circle
     * would see the person's last update age and then show them as offline.
     */
    private suspend fun publishPausedNotice(circles: List<Circle>) {
        if (_recovery.value != null) return
        val s = state.value
        val t = now()
        val seq = maxOf(s.lastSeq + 1, t * 1000)
        var sent = false
        circles.forEach { c ->
            if (shareStatus(c) != ShareStatus.OK) return@forEach
            val last = s.locations[c.id]?.get(myPubkey) ?: return@forEach
            val notice = LocationPrecision.apply(last, c.precision, trail = false).copy(
                speed = null, heading = null, activity = Activity.STILL, time = t, points = emptyList(), status = MemberLocation.PAUSED,
            )
            val event = if (c.version < 2) sealV1(c, notice, t) else sealV2(c, notice, t, seq) ?: return@forEach
            pool.publish(event, queueIfOffline = false)
            sent = true
        }
        if (sent) update { it.copy(lastSeq = maxOf(it.lastSeq, seq)) }
        // Your own history shows the pause too (#156).
        s.circles.firstNotNullOfOrNull { s.locations[it.id]?.get(myPubkey) }?.let { last ->
            recordHistory(myPubkey, last.copy(speed = null, heading = null, activity = Activity.STILL, time = t, points = emptyList(), status = MemberLocation.PAUSED, precision = null))
        }
    }

    suspend fun setShareBattery(on: Boolean) = update { it.copy(shareBattery = on) }

    suspend fun setShowPhotos(on: Boolean) = update { it.copy(showPhotos = on) }

    suspend fun setDarkMap(on: Boolean) = update { it.copy(darkMap = on) }

    suspend fun setThemeMode(mode: String) {
        require(mode == THEME_SYSTEM || mode == THEME_LIGHT || mode == THEME_DARK || mode == THEME_MIDNIGHT) { "Unknown theme" }
        update { it.copy(themeMode = mode) }
    }

    suspend fun setPeoplePanelHidden(hidden: Boolean) = update { if (it.peoplePanelHidden == hidden) it else it.copy(peoplePanelHidden = hidden) }

    suspend fun setLastCircle(circleId: String) = update { if (it.lastCircleId == circleId) it else it.copy(lastCircleId = circleId) }

    suspend fun setSetupDone() = update { it.copy(setupDone = true) }

    /**
     * Changes the relay list (needs the PIN, if one is set). Remote relays must use TLS (wss://);
     * anything else is dropped, never silently downgraded.
     */
    suspend fun setRelays(relays: List<String>) {
        requireAuthorized()
        applyRelays(relays)
    }

    private suspend fun applyRelays(relays: List<String>) {
        checkReady()
        val clean = relays.mapNotNull { RelayUrls.clean(it) }.distinct()
        update { it.copy(relays = clean) }
        pool.setRelays(effectiveRelays())
        refreshSubscriptions()
    }

    /** The user's relays, plus relays an invite asked for while that join is still in progress. */
    private fun effectiveRelays(): List<String> {
        testRelays?.let { test -> return test.mapNotNull { RelayUrls.clean(it) }.distinct() }
        val s = state.value
        return (s.relays + s.pendingJoins.flatMap { it.relays }).mapNotNull { RelayUrls.clean(it) }.distinct()
    }

    private fun torProxy(): SocksProxy? = if (state.value.useTor) SocksProxy("127.0.0.1", TOR_SOCKS_PORT) else null

    /**
     * Routes relay traffic through Tor (Orbot's SOCKS proxy), so relays see a Tor exit instead of
     * this phone's IP address. If Orbot isn't running nothing connects, rather than falling back to
     * a direct connection. Turning it off needs the PIN, if one is set.
     */
    suspend fun setUseTor(on: Boolean) {
        if (!on) requireAuthorized()
        update { it.copy(useTor = on) }
        pool.setSocksProxy(torProxy())
        refreshSubscriptions()
    }

    /** Hides the app in recent apps and screenshots. Turning it off needs the PIN, if one is set. */
    suspend fun setHideInRecents(on: Boolean) {
        if (!on) requireAuthorized()
        update { it.copy(hideInRecents = on) }
    }

    // -----------------------------------------------------------------------------------------
    // PIN and the security log
    // -----------------------------------------------------------------------------------------

    val hasPin: Boolean get() = state.value.pin != null

    /** True when no PIN is set, or the PIN was entered in the last couple of minutes. */
    fun isAuthorized(): Boolean = state.value.pin == null || elapsed() < authorizedUntil

    private fun requireAuthorized() = check(isAuthorized()) { "Enter your PIN first" }

    /** Checks the PIN. Five wrong tries lock it for 30 seconds, doubling with each further miss. */
    suspend fun unlock(pin: String): PinResult = pinMutex.withLock {
        // One check at a time, so every wrong try is counted and a lockout one sets stops the next.
        unlockLocked(pin)
    }

    private suspend fun unlockLocked(pin: String): PinResult {
        val lock = state.value.pin ?: return PinResult.Ok
        val t = now()
        lockedUntil(lock)?.let { return PinResult.Locked(it) }
        if (constantTimeEquals(pinHash(pin, lock.saltHex.hexToBytes(), lock.iterations), lock.hashHex.hexToBytes())) {
            authorizedUntil = elapsed() + AUTH_WINDOW_SECONDS
            freshUntil = elapsed() + FRESH_SECONDS
            if (lock.failedAttempts != 0 || lock.lockUntilElapsed != 0L || lock.lockedUntil != 0L) {
                update { it.copy(pin = lock.copy(failedAttempts = 0, lockedUntil = 0, lockStartElapsed = 0, lockUntilElapsed = 0)) }
            }
            return PinResult.Ok
        }
        val failed = lock.failedAttempts + 1
        val wait = if (failed >= PIN_FREE_ATTEMPTS) 30L shl (failed - PIN_FREE_ATTEMPTS).coerceAtMost(7) else 0L
        val e = elapsed()
        update { s ->
            s.copy(
                pin = lock.copy(
                    failedAttempts = failed,
                    lockedUntil = if (wait > 0) t + wait else 0,
                    lockStartElapsed = if (wait > 0) e else 0,
                    lockUntilElapsed = if (wait > 0) e + wait else 0,
                ),
                securityLog = if (wait > 0) s.logged(SecurityEventType.PIN_LOCKED) else s.securityLog,
            )
        }
        return if (wait > 0) PinResult.Locked(t + wait) else PinResult.Wrong(PIN_FREE_ATTEMPTS - failed)
    }

    /**
     * When the current lockout ends (wall clock, for display), or null if not locked. Timed on
     * [elapsed], so moving the date forward doesn't end it; after a reboot the wait starts again.
     */
    private suspend fun lockedUntil(lock: PinLock): Long? {
        val t = now()
        if (lock.lockUntilElapsed == 0L) return lock.lockedUntil.takeIf { it > t } // saved by an older version
        val e = elapsed()
        if (e < lock.lockStartElapsed) {
            val wait = lock.lockUntilElapsed - lock.lockStartElapsed
            update { s -> s.copy(pin = s.pin?.copy(lockStartElapsed = e, lockUntilElapsed = e + wait, lockedUntil = t + wait)) }
            return t + wait
        }
        return if (e < lock.lockUntilElapsed) t + (lock.lockUntilElapsed - e) else null
    }

    /** Sets or changes the PIN (4 to 8 digits). Changing an existing PIN needs it entered first. */
    suspend fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it in '0'..'9' }) { "Use 4 to 8 digits" }
        val had = state.value.pin != null
        if (had) requireAuthorized()
        val salt = PlatformCrypto.randomBytes(16)
        // Changing the PIN keeps biometric unlock as it was.
        val lock = PinLock(pinHash(pin, salt, PIN_ITERATIONS).toHex(), salt.toHex(), PIN_ITERATIONS, biometrics = state.value.pin?.biometrics == true)
        update { it.copy(pin = lock, securityLog = it.logged(if (had) SecurityEventType.PIN_CHANGED else SecurityEventType.PIN_SET)) }
        authorizedUntil = elapsed() + AUTH_WINDOW_SECONDS
        freshUntil = elapsed() + FRESH_SECONDS
    }

    /**
     * Lets Face ID, Touch ID or a fingerprint stand in for the PIN. Turning it on needs a PIN set and
     * entered; turning it off never does (the phone turns it off when its enrolled faces or fingers
     * change).
     */
    suspend fun setBiometrics(on: Boolean) = pinMutex.withLock {
        val lock = state.value.pin ?: run {
            check(!on) { "Set a PIN first" }
            return@withLock
        }
        if (on) requireAuthorized()
        if (lock.biometrics == on) return@withLock
        update { it.copy(pin = lock.copy(biometrics = on), securityLog = it.logged(if (on) SecurityEventType.BIOMETRICS_ON else SecurityEventType.BIOMETRICS_OFF)) }
    }

    /**
     * Call once the phone has confirmed the owner's face or fingerprint: counts as entering the PIN.
     * Refused when biometric unlock is off, and waits out a PIN lockout like the PIN does.
     */
    suspend fun unlockWithBiometrics(): PinResult = pinMutex.withLock {
        val lock = state.value.pin ?: return@withLock PinResult.Ok
        check(lock.biometrics) { "Biometric unlock is off" }
        lockedUntil(lock)?.let { return@withLock PinResult.Locked(it) }
        authorizedUntil = elapsed() + AUTH_WINDOW_SECONDS
        freshUntil = elapsed() + FRESH_SECONDS
        PinResult.Ok
    }

    suspend fun removePin() {
        requireAuthorized()
        update { it.copy(pin = null, securityLog = it.logged(SecurityEventType.PIN_REMOVED)) }
    }

    private fun pinHash(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        PlatformCrypto.pbkdf2Sha256("quietmap-pin|$pin", salt, iterations, 32)

    private fun AppState.logged(type: SecurityEventType): List<SecurityEvent> =
        (securityLog + SecurityEvent(type, now(), deviceName)).takeLast(50)

    // -----------------------------------------------------------------------------------------
    // Sharing reminders
    // -----------------------------------------------------------------------------------------

    /** True once a month while sharing: time to show who receives this phone's location. */
    fun consentDue(): Boolean {
        val s = state.value
        return s.sharing && s.circles.isNotEmpty() && s.consentAt > 0 && now() - s.consentAt >= CONSENT_EVERY_SECONDS
    }

    suspend fun confirmConsent() = update { it.copy(consentAt = now()) }

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
            history = listOf(MembershipEvent(MembershipChange.CREATED, myPubkey, state.value.myName, at = t)),
        )
        update { it.copy(circles = it.circles + circle, lastCircleId = circle.id) }
        refreshSubscriptions()
        publishHeartbeat(circle)
        publishProfile(circle)
        return circle
    }

    /** The admin and managers can invite and approve people. */
    fun canInvite(circle: Circle): Boolean = !circle.stale && (circle.adminPubkey == myPubkey || myPubkey in circle.managers)

    /**
     * Creates an invite link, signed so none of it can be changed on the way, valid for [hours]
     * (1 by default). It works once, or for up to [Invite.MAX_USES] people if [multiUse]; whoever
     * made it still approves every join. Only the admin and managers can invite.
     */
    suspend fun createInvite(circleId: String, hours: Int = 1, multiUse: Boolean = false): String {
        checkReady()
        requireAuthorized()
        require(hours in 1..24) { "Invites last 1 to 24 hours" }
        val s = state.value
        val circle = s.circles.first { it.id == circleId }
        require(canInvite(circle)) { "Only the circle admin and managers can invite" }
        val secret = PlatformCrypto.randomBytes(16).toHex()
        val expires = now() + hours * 3600L
        val invite = Invite(secret, circleId, expires, v = 2, maxUses = if (multiUse) Invite.MAX_USES else 1)
        update { it.copy(invites = it.invites + invite) }
        refreshSubscriptions()
        val data = InviteData(k = myPubkey, n = s.myName, r = s.relays, c = circleId, cn = circle.name, s = secret, e = expires)
        return InviteLinks.encode(InviteLinks.signed(identity, data))
    }

    /**
     * Stops an invite working before it expires. Anyone who tries it afterwards is told it was
     * cancelled. Requests already received stay, to approve or deny.
     */
    suspend fun cancelInvite(secret: String) = update { s ->
        s.copy(invites = s.invites.map { if (it.secret == secret) it.copy(cancelled = true) else it })
    }

    /**
     * Sends a join request for an invite the user has agreed to. The inviter is marked verified
     * only if the QR code was scanned from their screen in person ([scannedInPerson]) or the user
     * saw that their device code matches the one on the inviter's phone ([codesMatch]). Otherwise
     * the join goes ahead unverified, and they can be verified later from their profile.
     * The invite's relays are used only until the join completes, unless [keepRelays].
     */
    suspend fun acceptInvite(invite: InviteData, scannedInPerson: Boolean = false, codesMatch: Boolean = false, keepRelays: Boolean = false) {
        checkReady()
        require(invite.e > now()) { "This invite has expired" }
        require(invite.k != myPubkey) { "This is your own invite" }
        val extra = invite.r.mapNotNull { RelayUrls.clean(it) }.filter { it !in state.value.relays }.take(4)
        if (keepRelays && extra.isNotEmpty()) requireAuthorized() // changing relays needs the PIN
        if (scannedInPerson || codesMatch) setVerified(invite.k, true)
        if (keepRelays && extra.isNotEmpty()) applyRelays(state.value.relays + extra)
        val inbox = if (invite.v >= 2) InviteLinks.inbox(invite.s) else null
        update {
            it.copy(
                pendingJoins = it.pendingJoins.filterNot { p -> p.circleId == invite.c } +
                    PendingJoin(invite.c, invite.cn, invite.k, invite.n, now(), inbox = inbox, relays = if (keepRelays) emptyList() else extra),
                joinRefusals = it.joinRefusals.filterNot { r -> r.circleId == invite.c },
            )
        }
        pool.setRelays(effectiveRelays())
        refreshSubscriptions()
        val body = JoinRequestBody(myPubkey, now(), invite.c, invite.s, state.value.myName)
        pool.publish(DirectMessages.seal(identity, invite.k, body, now(), inbox ?: invite.k))
    }

    /** Clears the message about a join that was turned away. */
    suspend fun dismissJoinRefusal(circleId: String) = update { s -> s.copy(joinRefusals = s.joinRefusals.filterNot { it.circleId == circleId }) }

    /**
     * How precisely this phone shares with a circle. Making it more precise needs the PIN (if set),
     * so someone holding the phone can't quietly turn approximate sharing back into exact.
     */
    suspend fun setCirclePrecision(circleId: String, precision: Precision) {
        val c = state.value.circles.firstOrNull { it.id == circleId } ?: return
        if (precision.ordinal < c.precision.ordinal) requireAuthorized()
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) it.copy(precision = precision) else it }) }
    }

    /** Whether updates to a circle carry the recent path. Turning it on needs the PIN (if set). */
    suspend fun setCircleTrail(circleId: String, on: Boolean) {
        if (on) requireAuthorized()
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) it.copy(shareTrail = on) else it }) }
    }

    /**
     * Starts sharing with a circle we were just let into. Until then nothing (location or profile)
     * goes to it, so the user sees who is in it before anyone sees them.
     */
    suspend fun confirmCircle(circleId: String) {
        // Starting to share with a new circle is as sensitive as making sharing more precise.
        requireAuthorized()
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) it.copy(confirmed = true) else it }, consentAt = now()) }
        state.value.circles.firstOrNull { it.id == circleId }?.let { publishProfile(it) }
    }

    suspend fun approve(requestId: String) {
        requireAuthorized()
        val req = state.value.joinRequests.firstOrNull { it.id == requestId } ?: return
        val circle = state.value.circles.firstOrNull { it.id == req.circleId } ?: return
        if (!canInvite(circle)) return
        val updated = circle.copy(
            members = circle.members.filterNot { it.pubkey == req.pubkey } + Member(req.pubkey, req.name),
            history = (circle.history + MembershipEvent(MembershipChange.JOINED, req.pubkey, req.name, by = myPubkey, at = now())).takeLast(HISTORY_SIZE),
        )
        update { s ->
            s.copy(
                circles = s.circles.map { if (it.id == circle.id) updated else it },
                joinRequests = s.joinRequests.filterNot { it.id == requestId },
            )
        }
        // The new member doesn't know the circle's inboxes yet: reach them on the invite's inbox
        // (v2), or on their public key (an invite from an older version).
        val joinerInbox = req.secret?.let { InviteLinks.inbox(it) } ?: req.pubkey
        sendRoster(updated, updated.members.map { it.pubkey }, extraInboxes = mapOf(req.pubkey to listOf(joinerInbox)))
        refreshSubscriptions()
        // The new member needs our profile; republishing also refreshes it on the relays.
        publishProfile(updated)
    }

    /** Turns a join request down and tells the person who asked, so they aren't left waiting. */
    suspend fun deny(requestId: String) {
        val req = state.value.joinRequests.firstOrNull { it.id == requestId } ?: return
        update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.id == requestId }) }
        sendRefusal(req.circleId, req.pubkey, req.secret, JoinRefusedBody.DENIED)
    }

    /** Removes a member and gives the circle a fresh key so they can't read anything new. */
    suspend fun removeMember(circleId: String, pubkey: String) {
        requireAuthorized()
        removeMemberInternal(circleId, pubkey)
    }

    private suspend fun removeMemberInternal(circleId: String, pubkey: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can remove people" }
        require(pubkey != myPubkey)
        rotate(circle, circle.members.filterNot { it.pubkey == pubkey }, removed = pubkey)
    }

    /** Lets a member invite and approve people (admin only). */
    suspend fun setManager(circleId: String, pubkey: String, manager: Boolean) {
        requireAuthorized()
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can choose managers" }
        require(circle.members.any { it.pubkey == pubkey } && pubkey != myPubkey)
        val updated = circle.copy(managers = if (manager) (circle.managers + pubkey).distinct() else circle.managers - pubkey)
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) updated else it }) }
        sendRoster(updated, updated.members.map { it.pubkey })
    }

    /** Hands the admin role to another member, so the old admin can leave (admin only). */
    suspend fun transferAdmin(circleId: String, pubkey: String) {
        requireAuthorized()
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can do this" }
        require(circle.members.any { it.pubkey == pubkey } && pubkey != myPubkey)
        val name = circle.members.first { it.pubkey == pubkey }.name
        val updated = circle.copy(
            adminPubkey = pubkey,
            managers = circle.managers - pubkey,
            history = (circle.history + MembershipEvent(MembershipChange.NEW_ADMIN, pubkey, name, by = myPubkey, at = now())).takeLast(HISTORY_SIZE),
        )
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) updated else it }) }
        sendRoster(updated, updated.members.map { it.pubkey })
    }

    /** Deletes the circle for everyone (admin only). */
    suspend fun deleteCircle(circleId: String) {
        requireAuthorized()
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can delete it" }
        circle.members.filter { it.pubkey != myPubkey }.forEach { m ->
            sendDirect(circle, m.pubkey, RemovedBody(myPubkey, now(), circleId, deleted = true, to = m.pubkey, epoch = circle.epoch + 1))
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
                // Alert areas for this circle alone have no one left to watch.
                places = s.places.filterNot { it.circleId == circleId },
                placeInside = s.placeInside.filterKeys { id -> s.places.none { it.id == id && it.circleId == circleId } },
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
    private suspend fun rotate(c: Circle, members: List<Member>, newAdmin: String = c.adminPubkey, signer: KeyPair = identity, removed: String? = null, epochStep: Int = 1): Circle {
        val t = now()
        val d = CircleCrypto.day(t)
        val oldCtl = c.ctlKeyHex
        val rotated = c.copy(
            version = 2,
            keyHex = CircleCrypto.newKey(),
            keyDay = d,
            ctlKeyHex = CircleCrypto.newKey(),
            epoch = c.epoch + epochStep,
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
            history = (c.history + c.members.filter { m -> members.none { it.pubkey == m.pubkey } && m.pubkey != newAdmin }
                .map { MembershipEvent(MembershipChange.LEFT, it.pubkey, it.name, by = signer.publicHex, at = t) }).takeLast(HISTORY_SIZE),
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
        val identityInboxes = if (c.version < 2) keep.associateWith { listOf(it) } else emptyMap()
        sendRoster(rotated, members.map { it.pubkey }, extraInboxes = identityInboxes, signer = signer)
        if (removed != null) sendDirect(c, removed, RemovedBody(signer.publicHex, t, c.id, to = removed, epoch = rotated.epoch), signer = signer)
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
     * circles, plus [extra] (a joiner's invite inbox).
     */
    private fun inboxesFor(c: Circle, recipient: String, extra: List<String> = emptyList()): List<String> {
        val d = today()
        val ctls = listOfNotNull(c.ctlKeyHex) + c.oldControls.filter { it.until > now() }.map { it.ctlKeyHex }
        val tags = ctls.map { CircleCrypto.inbox(it.hexToBytes(), recipient, d) }
        return (tags + listOfNotNull(recipient.takeIf { c.version < 2 }) + extra).distinct()
    }

    /** Seals [body] to [recipient] once per inbox they might be listening on. */
    private suspend fun sendDirect(c: Circle, recipient: String, body: Body, extraInboxes: List<String> = emptyList(), signer: KeyPair = identity) {
        inboxesFor(c, recipient, extraInboxes).forEach { inbox ->
            pool.publish(DirectMessages.seal(signer, recipient, body, now(), inbox))
        }
    }

    private fun rosterOf(c: Circle): RosterCircle =
        if (c.version < 2) RosterCircle(c.id, c.name, c.keyHex, c.epoch, c.adminPubkey, c.members, c.managers)
        else {
            // If the clock went back past the key's first day, send that day's key rather than crash.
            val d = maxOf(today(), c.keyDay)
            RosterCircle(c.id, c.name, dayKey(c, d)!!.toHex(), c.epoch, c.adminPubkey, c.members, c.managers, v = 2, day = d, ctl = c.ctlKeyHex)
        }

    /**
     * Sends the current roster of [c] to [recipients] (except ourselves) and, for v2, records them
     * as waiting for an acknowledgement so [retryRosters] resends until they confirm.
     */
    private suspend fun sendRoster(c: Circle, recipients: List<String>, extraInboxes: Map<String, List<String>> = emptyMap(), signer: KeyPair = identity) {
        val t = now()
        val rc = rosterOf(c)
        val body = if (c.version < 2) RosterBody(signer.publicHex, t, rc) else Roster2Body(signer.publicHex, t, rc)
        val targets = recipients.filter { it != signer.publicHex && it != myPubkey }.distinct()
        val current = state.value.circles.firstOrNull { it.id == c.id }
        // Keep reaching a joiner on their invite inbox when resending.
        fun extraFor(m: String) = (extraInboxes[m].orEmpty() + current?.pendingAcks?.firstOrNull { it.pubkey == m }?.extraInboxes.orEmpty()).distinct()
        targets.forEach { m -> sendDirect(c, m, body, extraFor(m), signer = signer) }
        // During a hand-over after an identity reset, members who missed it still only trust the
        // old identity: send them a copy it signed too.
        handoverSigner(current ?: c)?.takeIf { it.publicHex != signer.publicHex && c.version >= 2 }?.let { old ->
            val copy = Roster2Body(old.publicHex, t, rc)
            targets.filter { it != old.publicHex }.forEach { m -> sendDirect(c, m, copy, extraFor(m), signer = old) }
        }
        if (c.version < 2 || targets.isEmpty() || signer.publicHex != myPubkey) return
        update { s ->
            s.copy(circles = s.circles.map { cur ->
                if (cur.id != c.id) cur
                else cur.copy(pendingAcks = cur.pendingAcks.filterNot { it.pubkey in targets } + targets.map { m ->
                    val prior = cur.pendingAcks.firstOrNull { it.pubkey == m && it.epoch == c.epoch }
                    PendingAck(m, c.epoch, prior?.firstSentAt ?: t, t, (prior?.attempts ?: 0) + 1, extraFor(m))
                })
            })
        }
    }

    /** Forgets the old identity's secret once everyone has the new key, or after two weeks. */
    private suspend fun endHandovers() {
        val t = now()
        if (state.value.circles.none { it.handoverSecretHex != null && (t >= it.handoverUntil || it.pendingAcks.none { p -> p.epoch == it.epoch }) }) return
        update { s ->
            s.copy(circles = s.circles.map { c ->
                if (c.handoverSecretHex != null && (t >= c.handoverUntil || c.pendingAcks.none { it.epoch == c.epoch })) c.copy(handoverSecretHex = null, handoverUntil = 0) else c
            })
        }
    }

    /** The previous identity, while a hand-over after an identity reset is still in progress. */
    private fun handoverSigner(c: Circle): KeyPair? =
        c.handoverSecretHex?.takeIf { now() < c.handoverUntil }?.let { KeyPair(it.hexToBytes()) }

    /** Resends rosters that haven't been acknowledged, backing off, for up to two weeks. */
    private suspend fun retryRosters() {
        val t = now()
        state.value.circles.filter { it.version >= 2 && it.pendingAcks.isNotEmpty() && canInvite(it) }.forEach { c ->
            val memberKeys = c.members.map { it.pubkey }.toSet()
            val due = c.pendingAcks.filter { p ->
                p.pubkey in memberKeys && p.pubkey != myPubkey && p.epoch == c.epoch && t - p.firstSentAt < RETRY_WINDOW_SECONDS && t - p.lastSentAt >= retryDelay(p.attempts)
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
        state.value.circles.filter { it.adminPubkey == myPubkey && !it.stale }.forEach { c ->
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
            val handover = handoverSigner(c)
            due.forEach { old ->
                publishControl(c.id, old.ctlKeyHex.hexToBytes(), old.epoch, closed = true)
                handover?.let { publishControl(c.id, old.ctlKeyHex.hexToBytes(), old.epoch, closed = true, signer = it) }
            }
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
        !c.confirmed -> ShareStatus.NOT_CONFIRMED
        c.paused -> ShareStatus.PAUSED
        c.stale -> ShareStatus.KEY_REPLACED
        c.version < 2 || c.adminPubkey == myPubkey -> ShareStatus.OK
        now() - c.heartbeatAt > HEARTBEAT_MAX_AGE_SECONDS -> ShareStatus.ADMIN_AWAY
        !pool.isSynced(SUB_CONTROL) -> ShareStatus.CHECKING
        else -> ShareStatus.OK
    }

    /** Member: if this phone can't confirm its key, ask the admin for the roster again. */
    private suspend fun memberDuties() {
        state.value.circles.filter { it.version >= 2 && it.adminPubkey != myPubkey }.forEach { c ->
            if (c.stale || now() - c.heartbeatAt > HEARTBEAT_MAX_AGE_SECONDS) requestRoster(c)
        }
    }

    private suspend fun requestRoster(c: Circle) {
        val t = now()
        if (t - c.rosterRequestedAt < ROSTER_REQUEST_EVERY_SECONDS) return
        update { s -> s.copy(circles = s.circles.map { if (it.id == c.id) it.copy(rosterRequestedAt = t) else it }) }
        sendDirect(c, c.adminPubkey, RosterRequestBody(myPubkey, t, c.id, c.epoch))
    }

    // -----------------------------------------------------------------------------------------
    // Identity reset
    // -----------------------------------------------------------------------------------------

    /**
     * Replaces this phone's identity with a new one, for when it may have been copied (for example a
     * backup in the wrong hands). Circles this phone runs move to the new identity with fresh keys,
     * so the old identity can't read anything new. Circles run by someone else are left: their admin
     * removes the old identity and rotates the key, and rejoining needs a new invite.
     */
    suspend fun resetIdentity() {
        checkReady()
        requireAuthorized()
        val old = identity
        val fresh = KeyPair.generate()
        state.value.circles.forEach { c ->
            if (c.adminPubkey == old.publicHex) {
                val members = c.members.map { if (it.pubkey == old.publicHex) Member(fresh.publicHex, it.name) else it }
                // Jump far ahead, so a copy of the old identity rotating weekly can't land on the
                // same epoch first. Keep the old secret only to sign the hand-over for stragglers.
                rotate(c, members, newAdmin = fresh.publicHex, signer = old, epochStep = RESET_EPOCH_JUMP)
                val until = now() + RETRY_WINDOW_SECONDS
                update { s -> s.copy(circles = s.circles.map { if (it.id == c.id) it.copy(handoverSecretHex = old.secret.toHex(), handoverUntil = until) else it }) }
            } else {
                sendDirect(c, c.adminPubkey, LeftBody(old.publicHex, now(), c.id), signer = old)
                forgetCircle(c.id)
            }
        }
        val t = now()
        mutex.withLock {
            identity = fresh
            val s = _state.value
            val next = s.copy(
                identitySecretHex = fresh.secret.toHex(),
                invites = emptyList(),
                joinRequests = emptyList(),
                pendingJoins = emptyList(),
                profileChangedAt = t,
                locations = s.locations.mapValues { (_, byMember) -> byMember - old.publicHex },
                circles = s.circles.map { c -> c.copy(pendingAcks = c.pendingAcks.filterNot { it.pubkey == fresh.publicHex }) },
                securityLog = s.logged(SecurityEventType.IDENTITY_RESET),
            )
            _state.value = next
            store.save(json.encodeToString(AppState.serializer(), next))
        }
        refreshSubscriptions()
        state.value.circles.filter { it.adminPubkey == myPubkey }.forEach { publishHeartbeat(it) }
        publishProfileIfDue()
    }

    // -----------------------------------------------------------------------------------------
    // Backups
    // -----------------------------------------------------------------------------------------

    /**
     * An encrypted backup of identity, profile, settings and circles. Slow on purpose (PBKDF2).
     * Because a backup can recreate this identity on another phone, it needs a PIN to be set and
     * entered, and each one is recorded in the security log.
     */
    suspend fun exportBackup(passphrase: String): String {
        checkReady()
        check(hasPin) { "Set a PIN before making a backup" }
        // A backup copies this phone's identity, so it needs the PIN (or face or fingerprint) entered
        // just now, for this backup: an entry for something else a minute ago doesn't count.
        check(elapsed() < freshUntil) { "Enter your PIN first" }
        val text = Backups.encrypt(Backups.fromState(state.value), passphrase)
        freshUntil = 0 // used up, but not by a passphrase that was refused
        update { it.copy(securityLog = it.logged(SecurityEventType.BACKUP_EXPORTED)) }
        return text
    }

    /**
     * Replaces everything on this device with a backup, including the identity key. Sharing starts
     * paused. Slow on purpose (PBKDF2), so call it off the main thread.
     */
    suspend fun importBackup(text: String, passphrase: String) {
        if (_recovery.value == null) requireAuthorized()
        // Decrypting first means a wrong passphrase or damaged file changes nothing at all.
        val decrypted = Backups.toState(Backups.decrypt(text, passphrase))
        val restored = decrypted.copy(
            profileChangedAt = now(),
            consentAt = now(),
            // Keep this phone's PIN and log; record the restore.
            pin = state.value.pin.takeIf { _recovery.value == null },
            securityLog = (state.value.securityLog.takeIf { _recovery.value == null }.orEmpty() + SecurityEvent(SecurityEventType.BACKUP_RESTORED, now(), deviceName)).takeLast(50),
        )
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
        pool.setRelays(effectiveRelays())
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
     *
     * Returns whether the update reached at least one connected relay for at least one circle. When
     * it didn't, the location service sends a fresh one as soon as [canPublishNow] says it would get
     * through, instead of waiting out the whole interval (up to five minutes while still).
     */
    suspend fun publishLocation(location: MemberLocation): Boolean {
        val s = state.value
        if (!s.sharing || _recovery.value != null) {
            publishOutcome(if (!s.sharing) "Update not sent: sharing is paused" else "Update not sent: saved data can't be read")
            return false
        }
        val online = relayConnected()
        noteShareStatuses(s)
        val t = now()
        val location = if (s.shareBattery) location else location.copy(battery = null)
        val seq = maxOf(s.lastSeq + 1, t * 1000)
        val sentTo = mutableListOf<String>()
        s.circles.forEach { c ->
            if (shareStatus(c) != ShareStatus.OK) return@forEach
            // Each circle gets only the precision (and path) the user chose for it.
            val shared = LocationPrecision.apply(location, c.precision, c.shareTrail)
            val event = if (c.version < 2) sealV1(c, shared, t) else sealV2(c, shared, t, seq) ?: return@forEach
            pool.publish(event, queueIfOffline = false)
            sentTo += c.id
        }
        if (sentTo.isEmpty()) {
            publishOutcome(if (s.circles.isEmpty()) "Update not sent: no circles" else "Update not sent: no circle is sharing right now")
            return false
        }
        publishOutcome(if (online) "Updates sent" else "Update not sent: no relay connected")
        update { st ->
            st.copy(
                lastSeq = maxOf(st.lastSeq, seq),
                locations = st.locations + st.circles.filter { it.id in sentTo }.associate { c -> c.id to (st.locations[c.id].orEmpty() + (myPubkey to location)) },
            )
        }
        recordHistory(myPubkey, location)
        return online
    }

    /** Whether an update published now would reach a relay for at least one circle. */
    fun canPublishNow(): Boolean {
        val s = state.value
        if (!s.sharing || _recovery.value != null || !relayConnected()) return false
        return s.circles.any { shareStatus(it) == ShareStatus.OK }
    }

    private fun relayConnected(): Boolean = pool.status.value.values.any { it == RelayStatus.CONNECTED }

    /** The phone's network came back: reconnect to relays now rather than after the retry wait. */
    fun networkAvailable() = pool.reconnectNow()

    // -----------------------------------------------------------------------------------------
    // Diagnostic logs (#154): events and times only, kept on this phone, exported by hand
    // -----------------------------------------------------------------------------------------

    private var lastOutcome: String? = null
    private var lastOutcomeLoggedAt = 0L
    private val loggedShareStatus = HashMap<String, ShareStatus>()

    /** "circle 2": a circle by its place in the list, so the log never holds ids or names. */
    private fun circleLabel(circleId: String): String =
        "circle ${state.value.circles.indexOfFirst { it.id == circleId }.let { if (it < 0) "?" else (it + 1).toString() }}"

    /**
     * Adds [event] to the diagnostic log, if it's kept (on by default, Settings → Diagnostic logs).
     * Callers pass events and reasons only, never locations, keys, names or contents; the log blanks
     * anything that looks like one anyway ([DiagnosticLog.clean]). [maxLength] is longer for crashes.
     */
    fun diagnostic(event: String, maxLength: Int = 300) {
        if (diagnostics == null || !state.value.keepDiagnostics) return
        scope.launch { noteDiagnostic(event, maxLength) }
    }

    /** As [diagnostic], but waits until it's written: for a crash, just before the app ends. */
    suspend fun noteDiagnostic(event: String, maxLength: Int = 300) {
        val log = diagnostics ?: return
        if (!state.value.keepDiagnostics) return
        val key = historyKey() ?: return
        runCatching { log.note(key, now(), event, maxLength) }
    }

    /** Relays connecting and dropping, by host name only. */
    private val loggedRelays = HashMap<String, RelayStatus>()

    init {
        if (diagnostics != null) scope.launch {
            pool.status.collect { all ->
                all.forEach { (url, status) ->
                    if (loggedRelays[url] != status) {
                        loggedRelays[url] = status
                        diagnostic("relay ${url.substringAfter("://").substringBefore('/')}: ${status.name.lowercase()}")
                    }
                }
            }
        }
    }

    /** Publish results are logged when they change, and otherwise every half hour. */
    private fun publishOutcome(outcome: String) {
        val t = now()
        if (outcome == lastOutcome && t - lastOutcomeLoggedAt < 1800) return
        diagnostic(if (outcome == lastOutcome) "$outcome (still)" else outcome)
        lastOutcome = outcome
        lastOutcomeLoggedAt = t
    }

    private fun noteShareStatuses(s: AppState) {
        s.circles.forEach { c ->
            val status = shareStatus(c)
            if (loggedShareStatus[c.id] != status) {
                loggedShareStatus[c.id] = status
                diagnostic("${circleLabel(c.id)}: ${status.name.lowercase().replace('_', ' ')}")
            }
        }
    }

    /** The log as plain text for the person to read and send, oldest first, times in UTC. */
    suspend fun diagnosticsText(header: String): String {
        val log = diagnostics ?: return header
        val key = historyKey() ?: return header
        val lines = log.read(key)
        return buildString {
            appendLine(header)
            appendLine("Events only: no locations, keys, names or message contents. Times are UTC.")
            appendLine()
            lines.forEach { (at, text) -> appendLine("${utcTime(at)}  $text") }
            if (lines.isEmpty()) appendLine("(nothing recorded yet)")
        }
    }

    suspend fun clearDiagnostics() {
        diagnostics?.clear()
        lastOutcome = null
        loggedShareStatus.clear()
    }

    suspend fun setKeepDiagnostics(on: Boolean) {
        update { it.copy(keepDiagnostics = on) }
        if (!on) clearDiagnostics()
    }

        // -----------------------------------------------------------------------------------------
    // Location history (kept only on this phone)
    // -----------------------------------------------------------------------------------------

    /** The history key, made (and any unreadable old files cleared) the first time it's needed. */
    private suspend fun historyKey(): ByteArray? {
        val h = historyStore ?: return null
        state.value.historyKeyHex?.let { return it.hexToBytes() }
        h.clear()
        val key = PlatformCrypto.randomBytes(32)
        update { it.copy(historyKeyHex = it.historyKeyHex ?: key.toHex()) }
        return state.value.historyKeyHex!!.hexToBytes()
    }

    private suspend fun recordHistory(pubkey: String, loc: MemberLocation) {
        val h = historyStore ?: return
        if (state.value.historyDays <= 0) return
        val key = historyKey() ?: return
        runCatching { h.record(key, pubkey, loc) }.onFailure { log("history: couldn't save (${it::class.simpleName})") }
    }

    /** Remembered positions of [pubkey] between [from] and [to] (seconds), oldest first. */
    suspend fun history(pubkey: String, from: Long, to: Long): List<HistoryPoint> {
        val h = historyStore ?: return emptyList()
        if (state.value.historyDays <= 0) return emptyList()
        val key = historyKey() ?: return emptyList()
        return h.read(key, pubkey, maxOf(from, now() - state.value.historyDays * 86_400L), to)
    }

    /**
     * How many days of history to keep, 0 to [LocationHistory.MAX_DAYS]. Keeping more needs the
     * PIN, if one is set; keeping less (or none) never does, and deletes the rest straight away.
     */
    suspend fun setHistoryDays(days: Int) {
        require(days in 0..LocationHistory.MAX_DAYS) { "Choose up to ${LocationHistory.MAX_DAYS} days" }
        if (days > state.value.historyDays) requireAuthorized()
        update { it.copy(historyDays = days) }
        historyPrunedAt = 0
        pruneHistory()
    }

    /** Hourly: drops days past the limit, and history of people who are no longer in any circle. */
    private suspend fun pruneHistory() {
        val h = historyStore ?: return
        val t = now()
        if (t - historyPrunedAt < 3600) return
        historyPrunedAt = t
        runCatching { diagnostics?.prune(t) }
        val s = state.value
        val keep = s.circles.flatMap { c -> c.members.map { it.pubkey } }.toSet() + myPubkey
        runCatching { h.prune(t, s.historyDays, keep) }
    }

    /** Drops the oldest batched points until the padded envelope fits the fixed size. */
    private inline fun fitted(location: MemberLocation, seal: (MemberLocation) -> Event): Event {
        var loc = location
        while (true) {
            val event = seal(loc)
            if (event.content.length <= MAX_LOCATION_CONTENT || loc.points.isEmpty()) return event
            loc = loc.copy(points = loc.points.drop(1))
        }
    }

    private fun sealV1(c: Circle, location: MemberLocation, t: Long): Event = fitted(location) { loc ->
        CircleCrypto.seal(identity, c.keyHex.hexToBytes(), c.epoch, LocationBody(myPubkey, t, c.id, c.epoch, loc), t)
    }

    /** Last outer timestamp used per circle, so blurred timestamps still only move forward. */
    private val lastCreatedAt = mutableMapOf<String, Long>()

    private fun sealV2(c: Circle, location: MemberLocation, t: Long, seq: Long): Event? {
        // Relays see a timestamp blurred by up to 15 seconds rather than the exact moment of the fix;
        // it never goes backwards, so relays keep the newest update. The exact time is inside.
        // Each one is strictly newer than the last for this circle (a tie could make a relay keep the
        // older event), never in the future, and never before the start of the key's first day (there's
        // no key for earlier days). If that leaves no second up to now, this update is skipped.
        val jitter = (PlatformCrypto.randomBytes(1)[0].toInt() and 0xff) % 16
        val floor = maxOf((lastCreatedAt[c.id] ?: 0L) + 1, c.keyDay * SECONDS_PER_DAY)
        if (floor > t) return null
        val created = maxOf(t - jitter, floor)
        val key = dayKey(c, CircleCrypto.day(created)) ?: return null
        lastCreatedAt[c.id] = created
        val publisher = CircleCrypto.memberPublisher(identity.secret, c.id, c.epoch, CircleCrypto.slot(created))
        return fitted(location) { loc ->
            val body = LocationBody(myPubkey, t, c.id, c.epoch, loc, pub = publisher.publicHex, seq = seq)
            CircleCrypto.sealLocation(identity, key, c.epoch, body, publisher, created)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Incoming events
    // -----------------------------------------------------------------------------------------

    private suspend fun onEvent(relay: String, event: Event) {
        if (_recovery.value != null) return
        if (mutex.withLock { event.id in seen }) return
        // Remember an id only once the event checks out (id matches the content, signature is valid):
        // otherwise one relay sending a corrupted copy under a real id would block the real one.
        if (!event.isValid()) return
        val fresh = mutex.withLock { seen.add(event.id) }
        if (!fresh) return
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
        if (state.value.locations[circleId]?.get(body.from) == body.loc) {
            recordHistory(body.from, body.loc)
            checkPlaces(circleId, body.from, body.loc)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Alert areas (#151): worked out on this phone from updates it receives anyway. Nothing is
    // published, and places, names and who entered are never logged.
    // -----------------------------------------------------------------------------------------

    /** When each alert (place|person|change) last went off. Memory only. */
    private val placeAlerted = HashMap<String, Long>()

    /** Adds an alert area, or replaces the one with the same id. Returns it. */
    suspend fun savePlace(place: Place): Place {
        val name = place.name.trim()
        require(name.isNotEmpty()) { "Give the place a name" }
        require(place.radius in PlaceAlerts.MIN_RADIUS..PlaceAlerts.MAX_RADIUS) { "Choose a size between 100 ft and 3 miles" }
        require(place.circleId == null || state.value.circles.any { it.id == place.circleId }) { "Circle not found" }
        val saved = place.copy(name = name.take(40), id = place.id.ifEmpty { PlatformCrypto.randomBytes(8).toHex() })
        update { s ->
            val existing = s.places.any { it.id == saved.id }
            require(existing || s.places.size < PlaceAlerts.MAX_PLACES) { "You can have up to ${PlaceAlerts.MAX_PLACES} places" }
            // Whoever is already inside a new or moved place is noted without an alert.
            val inside = s.circles.filter { saved.circleId == null || it.id == saved.circleId }
                .flatMap { c -> s.locations[c.id].orEmpty().filter { (k, l) -> k != myPubkey && PlaceAlerts.isInside(saved, l) }.keys }
                .distinct()
            s.copy(
                places = if (existing) s.places.map { if (it.id == saved.id) saved else it } else s.places + saved,
                placeInside = s.placeInside + (saved.id to inside),
            )
        }
        return saved
    }

    suspend fun deletePlace(id: String) = update { it.copy(places = it.places.filter { p -> p.id != id }, placeInside = it.placeInside - id) }

    private suspend fun checkPlaces(circleId: String, pubkey: String, loc: MemberLocation) {
        val s = state.value
        if (pubkey == myPubkey || s.places.isEmpty()) return
        val t = now()
        val circle = s.circles.firstOrNull { it.id == circleId } ?: return
        val name = s.contacts[pubkey]?.label ?: s.profiles[pubkey]?.name?.ifBlank { null }
            ?: circle.members.firstOrNull { it.pubkey == pubkey }?.name?.ifBlank { null } ?: "Someone"
        val moved = HashMap<String, Boolean>()
        val alerts = ArrayList<Notice>()
        s.places.filter { it.enabled && (it.circleId == null || it.circleId == circleId) }.forEach { p ->
            val was = pubkey in s.placeInside[p.id].orEmpty()
            val r = PlaceAlerts.check(p, was, loc, t)
            if (r.inside != was) moved[p.id] = r.inside
            val wanted = when (r.change) {
                PlaceAlerts.Change.ARRIVED -> p.onArrive
                PlaceAlerts.Change.LEFT -> p.onLeave
                PlaceAlerts.Change.NONE -> false
            }
            val key = "${p.id}|$pubkey|${r.change}"
            if (wanted && t - (placeAlerted[key] ?: 0) >= PlaceAlerts.REPEAT_SECONDS) {
                placeAlerted[key] = t
                val what = if (r.change == PlaceAlerts.Change.ARRIVED) "arrived at" else "left"
                alerts += Notice("$name $what ${p.name}", "In ${circle.name}", Notice.PLACE)
            }
        }
        if (moved.isNotEmpty()) update { st ->
            st.copy(placeInside = st.placeInside + moved.map { (id, inside) ->
                val now = st.placeInside[id].orEmpty() - pubkey
                id to if (inside) now + pubkey else now
            })
        }
        alerts.forEach { _notices.tryEmit(it) }
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
        if (body.from != c.adminPubkey || body.circle != c.id) return
        if (target.epoch != c.epoch || body.epoch != c.epoch) return
        if (c.adminPubkey == myPubkey) {
            // Our own key closed our current epoch, which this phone never does: another phone with
            // this identity (say, an old backup) reset it. Stand down instead of competing with it.
            if (body.closed && !c.stale) {
                update { s -> s.copy(circles = s.circles.map { if (it.id == c.id && it.epoch == body.epoch) it.copy(stale = true) else it }) }
                _notices.tryEmit(Notice("${c.name} moved to another phone", "This phone's copy of your identity was replaced, so it stopped running ${c.name}."))
            }
            return
        }
        if (body.closed) {
            update { s -> s.copy(circles = s.circles.map { if (it.id == c.id && it.epoch == body.epoch) it.copy(stale = true) else it }) }
            state.value.circles.firstOrNull { it.id == c.id }?.let { requestRoster(it) }
        } else {
            val ts = minOf(body.ts, now())
            update { s -> s.copy(circles = s.circles.map { if (it.id == c.id && it.epoch == body.epoch && ts > it.heartbeatAt) it.copy(heartbeatAt = ts) else it }) }
        }
    }

    private suspend fun publishProfile(circle: Circle) {
        if (!circle.confirmed) return
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
            is JoinRequestBody -> onJoinRequest(body, ref)
            is JoinRefusedBody -> onJoinRefused(body)
            is RosterBody -> onRoster(body.from, body.ts, body.circle, v2Body = false)
            is Roster2Body -> onRoster(body.from, body.ts, body.circle, v2Body = true)
            is RemovedBody -> onRemoved(body)
            is LeftBody -> onLeft(body)
            is RosterAckBody -> onRosterAck(body)
            is RosterRequestBody -> onRosterRequest(body, ref)
            is LocationRequestBody -> onLocationRequest(body)
            else -> Unit
        }
        update { it.copy(seenEventIds = (it.seenEventIds + event.id).takeLast(500)) }
    }

    private suspend fun onJoinRequest(body: JoinRequestBody, ref: InboxRef) {
        val s = state.value
        val invite = s.invites.firstOrNull { it.secret == body.secret && it.circleId == body.circle } ?: return
        // A v2 invite's request must arrive on that invite's own inbox.
        if (invite.v >= 2 && ref.inviteSecret != invite.secret) return
        if (body.from == myPubkey || !Keys.isValidPublicKey(body.from)) return
        if (s.joinRequests.any { it.pubkey == body.from && it.circleId == body.circle }) return
        val existing = s.circles.firstOrNull { it.id == invite.circleId }
        if (existing?.members?.any { it.pubkey == body.from } == true) {
            // Already in the circle. A repeat of the request we approved changes nothing, but a newer
            // one means their phone lost the circle: send them the roster again on this invite's inbox.
            val joinedAt = existing.history.lastOrNull { it.change == MembershipChange.JOINED && it.pubkey == body.from }?.at ?: 0
            val recent = existing.pendingAcks.firstOrNull { it.pubkey == body.from && it.epoch == existing.epoch }
            if (body.ts > joinedAt && canInvite(existing) && (recent == null || now() - recent.lastSentAt >= 600)) {
                val inbox = if (invite.v >= 2) InviteLinks.inbox(invite.secret) else body.from
                sendRoster(existing, listOf(body.from), extraInboxes = mapOf(body.from to listOf(inbox)))
            }
            return
        }
        // Decided inside the update, so two requests at once can't both take the last use.
        var refusal: String? = null
        update { st ->
            val inv = st.invites.firstOrNull { it.secret == invite.secret } ?: return@update st
            refusal = when {
                // They asked before and were turned down (a repeat, or trying the same link again).
                body.from in inv.usedBy -> JoinRefusedBody.DENIED
                inv.cancelled -> JoinRefusedBody.CANCELLED
                inv.expiresAt < now() -> JoinRefusedBody.EXPIRED
                inv.used || inv.usedBy.size >= inv.maxUses -> JoinRefusedBody.USED
                else -> null
            }
            if (refusal != null) return@update st
            val req = JoinRequest(PlatformCrypto.randomBytes(8).toHex(), body.circle, body.from, body.name.take(40), now(), secret = inv.secret.takeIf { inv.v >= 2 })
            val usedBy = inv.usedBy + body.from
            st.copy(
                joinRequests = st.joinRequests + req,
                invites = st.invites.map { if (it.secret == inv.secret) it.copy(usedBy = usedBy, used = usedBy.size >= it.maxUses) else it },
            )
        }
        refusal?.let { reason ->
            if (refusalAllowed(body.from)) sendRefusal(invite.circleId, body.from, invite.secret.takeIf { invite.v >= 2 }, reason)
        }
    }

    /** When each joiner was last sent a refusal, and every refusal in the past hour (memory only). */
    private val refusedAt = mutableMapOf<String, Long>()
    private val refusals = ArrayDeque<Long>()
    private val refusalMutex = Mutex()

    /**
     * Replies to join requests that can't go ahead are rate-limited: one per person per
     * [REFUSAL_EVERY_SECONDS], and [MAX_REFUSALS_PER_HOUR] in all, so someone repeating requests
     * with a link (under one key or many) can't make this phone send a stream of messages.
     */
    private suspend fun refusalAllowed(pubkey: String): Boolean = refusalMutex.withLock {
        val t = now()
        refusedAt.entries.removeAll { t - it.value >= REFUSAL_EVERY_SECONDS }
        while (refusals.isNotEmpty() && t - refusals.first() >= 3600) refusals.removeFirst()
        if (pubkey in refusedAt || refusals.size >= MAX_REFUSALS_PER_HOUR) return@withLock false
        refusedAt[pubkey] = t
        refusals.addLast(t)
        true
    }

    /** Tells [to] their request to join [circleId] can't go ahead, on the invite's inbox (v2) or their key (v1). */
    private suspend fun sendRefusal(circleId: String, to: String, secret: String?, reason: String) {
        val inbox = secret?.let { InviteLinks.inbox(it) }
        val body = JoinRefusedBody(myPubkey, now(), circleId, to = to, invite = inbox ?: "", reason = reason)
        pool.publish(DirectMessages.seal(identity, to, body, now(), inbox ?: to))
    }

    /** Our request to join was turned away: stop waiting and say why. */
    private suspend fun onJoinRefused(body: JoinRefusedBody) {
        val p = state.value.pendingJoins.firstOrNull { it.circleId == body.circle } ?: return
        // Only the inviter we asked can turn us away, only about this join of ours, and only after
        // we asked: a refusal meant for someone else, or an old one, can't cancel it.
        if (body.from != p.inviterPubkey || body.to != myPubkey) return
        if (p.inbox != null && body.invite != p.inbox) return
        if (body.ts < p.sentAt - MAX_CLOCK_SKEW_SECONDS) return
        val refusal = JoinRefusal(p.circleId, p.circleName, p.inviterName, body.reason, now())
        update { s ->
            s.copy(
                pendingJoins = s.pendingJoins.filterNot { it.circleId == p.circleId },
                joinRefusals = (s.joinRefusals.filterNot { it.circleId == p.circleId } + refusal).takeLast(10),
            )
        }
        if (p.relays.isNotEmpty()) pool.setRelays(effectiveRelays())
        refreshSubscriptions()
        _notices.tryEmit(Notice("Couldn't join ${p.circleName}", refusal.message))
    }

    /** Rejects rosters with fields that would break key derivation later (and crash every pass). */
    private fun validRoster(rc: RosterCircle): Boolean {
        fun hex32(v: String?) = v != null && v.length == 64 && v.all { it in "0123456789abcdef" }
        if (!hex32(rc.key) || rc.epoch < 0 || !Keys.isValidPublicKey(rc.admin)) return false
        if (rc.members.isEmpty() || rc.members.size > MAX_MEMBERS || rc.members.any { !Keys.isValidPublicKey(it.pubkey) }) return false
        if (rc.v >= 2 && (!hex32(rc.ctl) || rc.day !in (today() - 30)..(today() + 1))) return false
        return true
    }

    private suspend fun onRoster(from: String, ts: Long, rc: RosterCircle, v2Body: Boolean) {
        // A v2 roster must arrive as a v2 message (and v1 as v1), so neither can be misread as the other.
        if ((rc.v >= 2) != v2Body) return
        if (!validRoster(rc)) return
        val s = state.value
        val existing = s.circles.firstOrNull { it.id == rc.id }
        val pending = s.pendingJoins.firstOrNull { it.circleId == rc.id }
        val sameEpochKey = existing != null && rc.epoch == existing.epoch &&
            (if (rc.v >= 2) rc.ctl == existing.ctlKeyHex else rc.key == existing.keyHex)
        val trusted = when {
            // The admin can do anything: add people, rotate keys, change managers, hand over the role.
            // Within an epoch, only a roster at least as new as the last one: an old roster (say, the
            // one that made someone a manager) can't be passed on again to undo a later change.
            existing != null && from == existing.adminPubkey ->
                rc.epoch > existing.epoch || (rc.epoch == existing.epoch && ts >= existing.adminRosterTs)
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
        val t = now()
        val oldMembers = existing?.members.orEmpty()
        val joined = if (existing == null) emptyList() else members.filter { m -> oldMembers.none { it.pubkey == m.pubkey } }
        val gone = if (rotated) oldMembers.filter { m -> members.none { it.pubkey == m.pubkey } } else emptyList()
        val events = buildList {
            if (existing == null) add(MembershipEvent(MembershipChange.JOINED, myPubkey, s.myName, by = from, at = t))
            joined.forEach { add(MembershipEvent(MembershipChange.JOINED, it.pubkey, it.name, by = from, at = t)) }
            gone.forEach { add(MembershipEvent(MembershipChange.LEFT, it.pubkey, it.name, by = from, at = t)) }
            if (existing != null && fromAdmin && rc.admin != existing.adminPubkey) {
                add(MembershipEvent(MembershipChange.NEW_ADMIN, rc.admin, members.firstOrNull { it.pubkey == rc.admin }?.name ?: "", by = from, at = t))
            }
        }
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
            adminRosterTs = if (fromAdmin) maxOf(if (newEpoch) 0 else base.adminRosterTs, ts) else base.adminRosterTs,
            history = (base.history + events).takeLast(HISTORY_SIZE),
            // Joining: nothing is shared until the user has seen who's in the circle.
            confirmed = if (existing == null) false else base.confirmed,
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
        if (pending != null && pending.relays.isNotEmpty()) pool.setRelays(effectiveRelays())
        refreshSubscriptions()
        val approver = members.firstOrNull { it.pubkey == from }?.name ?: oldMembers.firstOrNull { it.pubkey == from }?.name
        joined.filter { it.pubkey != myPubkey }.forEach {
            _notices.tryEmit(Notice("${it.name.ifBlank { "Someone" }} joined ${rc.name}", approver?.let { a -> "Approved by $a" } ?: "They can now see where you are in ${rc.name}."))
        }
        gone.filter { it.pubkey != myPubkey }.forEach {
            _notices.tryEmit(Notice("${it.name.ifBlank { "Someone" }} is no longer in ${rc.name}", "They can't see new locations in ${rc.name}."))
        }
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
        if (c.adminPubkey != myPubkey || c.version < 2 || c.stale) return
        if (c.members.any { it.pubkey == body.from }) {
            val recent = c.pendingAcks.firstOrNull { it.pubkey == body.from && it.epoch == c.epoch }
            if (recent != null && now() - recent.lastSentAt < 600) return
            sendRoster(c, listOf(body.from))
        } else if (ref.circleId == c.id) {
            sendDirect(c, body.from, RemovedBody(myPubkey, now(), c.id, to = body.from, epoch = c.epoch + 1))
        }
    }

    private suspend fun onRemoved(body: RemovedBody) {
        val circle = state.value.circles.firstOrNull { it.id == body.circle } ?: return
        if (body.from != circle.adminPubkey) return
        // v2 notices name their recipient and end an epoch newer than ours: one sent to someone
        // else, or an old one from before we were let back in, is ignored.
        if (circle.version >= 2 && (body.to != myPubkey || (body.epoch ?: Int.MIN_VALUE) <= circle.epoch)) return
        // A notice older than the admin's roster we hold belongs to an earlier membership (both
        // carry the admin's clock). Rejoining doesn't change the key, so one left on the relays
        // from a removal (the admin answers a roster request from someone who's out with
        // epoch + 1) would otherwise throw us out again.
        if (body.ts < circle.adminRosterTs) return
        forgetCircle(circle.id)
    }

    /** A member left: if we're the admin, remove them properly so the circle gets a new key. */
    /**
     * Asks [pubkey] in [circleId] for their location now (#149): a nudge to a phone that hasn't sent
     * an update for a while. Their phone answers on its own if it's online and sharing exactly with
     * the circle; they see nothing but their location going out. Returns false without sending if
     * their latest update is under [LOCATION_REQUEST_AFTER_SECONDS] old, or we asked them less than
     * [LOCATION_REQUEST_GAP_SECONDS] ago.
     */
    suspend fun requestLocation(circleId: String, pubkey: String): Boolean {
        val c = state.value.circles.firstOrNull { it.id == circleId } ?: throw IllegalStateException("Circle not found")
        if (pubkey == myPubkey || c.members.none { it.pubkey == pubkey }) throw IllegalStateException("They're not in this circle")
        if (!c.confirmed) throw IllegalStateException("Start sharing with this circle first")
        val t = now()
        val key = "$circleId|$pubkey"
        val last = state.value.locations[circleId]?.get(pubkey)
        if (last != null && t - last.time < LOCATION_REQUEST_AFTER_SECONDS) return false
        if (t - (locationAsked[key] ?: 0) < LOCATION_REQUEST_GAP_SECONDS) return false
        locationAsked[key] = t
        sendDirect(c, pubkey, LocationRequestBody(myPubkey, t, circleId))
        return true
    }

    private fun onLocationRequest(body: LocationRequestBody) {
        val s = state.value
        val c = s.circles.firstOrNull { it.id == body.circle } ?: return
        if (body.from == myPubkey || c.members.none { it.pubkey == body.from }) return
        // Only while we're sharing exactly with them anyway: a request never turns sharing on, and
        // never sends more than the circle is set to see.
        if (!s.sharing || c.paused || !c.confirmed || c.precision != Precision.EXACT) return
        val t = now()
        // Relays keep direct messages, so an old request (or a replayed one) is ignored.
        if (kotlin.math.abs(t - body.ts) > MAX_CLOCK_SKEW_SECONDS) return
        // At most one answer per person every 2 minutes, and 20 an hour in all.
        if (t - (locationAnswered[body.from] ?: 0) < LOCATION_REQUEST_GAP_SECONDS) return
        while (locationAnswers.isNotEmpty() && t - locationAnswers.first() > 3600) locationAnswers.removeFirst()
        if (locationAnswers.size >= LOCATION_ANSWERS_PER_HOUR) return
        locationAnswered[body.from] = t
        locationAnswers.addLast(t)
        _locationRequests.tryEmit(c.id)
    }

    private suspend fun onLeft(body: LeftBody) {
        val circle = state.value.circles.firstOrNull { it.id == body.circle } ?: return
        if (circle.adminPubkey != myPubkey || circle.members.none { it.pubkey == body.from }) return
        // A "left" signed by our own key (another copy of this identity, or a replay) can't remove
        // the admin: the admin hands over the role or deletes the circle instead.
        if (body.from == myPubkey) return
        // An old "left" from before they were let back in is a replay.
        val joinedAt = circle.history.lastOrNull { it.change == MembershipChange.JOINED && it.pubkey == body.from }?.at
        if (joinedAt != null && body.ts < joinedAt - MAX_CLOCK_SKEW_SECONDS) return
        removeMemberInternal(circle.id, body.from)
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
                // Heartbeats for the past week, and closed notices. The admin listens for closed
                // notices too, to notice another phone with its identity taking over.
                if (c.adminPubkey != myPubkey) {
                    (0..7).forEach { back -> index[CircleCrypto.controlTag(ctl, c.epoch, d - back)] = TagTarget(c.id, ctl, c.epoch, TagKind.CONTROL, true) }
                }
                index[CircleCrypto.closedTag(ctl, c.epoch)] = TagTarget(c.id, ctl, c.epoch, TagKind.CONTROL, true)
                // Our inboxes for the past week (messages expire after 7 days), under this and recent control keys.
                (listOf(c.ctlKeyHex) + c.oldControls.filter { it.until > t }.map { it.ctlKeyHex }).forEach { k ->
                    val kb = k.hexToBytes()
                    (0..7).forEach { back -> inboxes[CircleCrypto.inbox(kb, myPubkey, d - back)] = InboxRef(c.id, kb) }
                }
            }
        }
        // Open invites and joins in progress have their own inboxes.
        // Used, cancelled and recently expired invites too, to tell late joiners why they can't join.
        s.invites.filter { it.v >= 2 }.forEach { inboxes[InviteLinks.inbox(it.secret)] = InboxRef(it.circleId, null, inviteSecret = it.secret) }
        s.pendingJoins.forEach { p -> p.inbox?.let { inboxes[it] = InboxRef(p.circleId, null) } }
        // Our public key is only needed as an inbox for v1 circles, v1 invites and joins through them.
        val needIdentity = s.circles.any { it.version < 2 } || s.pendingJoins.any { it.inbox == null } || s.invites.any { it.v < 2 && !it.used && it.expiresAt > t }
        if (needIdentity) inboxes[myPubkey] = InboxRef(null, null)
        tagIndex = index
        inboxIndex = inboxes
        val subs = buildMap {
            // From the start of the hour a week ago, not the second: the filter then only changes when the
            // hourly tags do, instead of every minute, which made every relay replay a week of messages
            // each time and looked like abuse to relays that refuse the subscription.
            if (inboxes.isNotEmpty()) put(SUB_DIRECT, listOf(Filter(kinds = listOf(Kinds.SEALED_DIRECT), tagP = inboxes.keys.sorted(), since = directSince(t))))
            val circleTags = index.filterValues { it.kind != TagKind.CONTROL }.keys.sorted()
            if (circleTags.isNotEmpty()) put(SUB_CIRCLES, listOf(Filter(kinds = listOf(Kinds.CIRCLE_UPDATE), tagD = circleTags)))
            val controlTags = index.filterValues { it.kind == TagKind.CONTROL }.keys.sorted()
            if (controlTags.isNotEmpty()) put(SUB_CONTROL, listOf(Filter(kinds = listOf(Kinds.CIRCLE_UPDATE), tagD = controlTags)))
        }
        pool.setSubscriptions(subs)
    }

    private suspend fun pruneExpired() {
        val t = now()
        val hadTemporaryRelays = state.value.pendingJoins.any { it.sentAt <= t - 7 * 24 * 3600 && it.relays.isNotEmpty() }
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
        if (hadTemporaryRelays) pool.setRelays(effectiveRelays())
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

        /** How far an identity reset moves the epoch on, so a leftover copy can't get there first. */
        const val RESET_EPOCH_JUMP = 1_000

        /** Rosters with more people than this are rejected as malformed. */
        const val MAX_MEMBERS = 200

        const val ROSTER_REQUEST_EVERY_SECONDS = 30 * 60L

        /** Updates claiming to be from further in the future than this are ignored. */
        const val MAX_CLOCK_SKEW_SECONDS = 10 * 60L

        /** Asking one person for their location, and answering one person, at most this often (#149). */
        const val LOCATION_REQUEST_GAP_SECONDS = 2 * 60L

        /** Asking for someone's location is for a phone that's gone quiet: their update must be this old. */
        const val LOCATION_REQUEST_AFTER_SECONDS = 5 * 60L

        /** Answers to location requests per hour, from everyone together. */
        const val LOCATION_ANSWERS_PER_HOUR = 20

        /**
         * Envelopes are padded to 1400 bytes, which NIP-44 pads to 1536, which base64-encodes to 2140
         * characters. Anything bigger drops batched points, so every update on the relays is the same size.
         */
        const val MAX_LOCATION_CONTENT = 2140

        /** No update for this long means the person is shown as offline. */
        const val OFFLINE_AFTER_SECONDS = 45 * 60L

        /**
         * How long the periodic loop waits from [t]: a minute, or until just after the next hour
         * starts if that's sooner. Location tags and publisher keys change every hour, so subscribing
         * to the new hour's tags at once means the first updates of each hour aren't held up for up to
         * a minute (they'd wait on the relay until the next pass subscribed to them).
         */
        /** Where the sealed-DM subscription starts: the start of the hour, a week before [t] (messages expire after 7 days). */
        internal fun directSince(t: Long): Long = (t - 7 * 24 * 3600).let { it - it.mod(3600L) }

        internal fun housekeepingDelayMillis(t: Long): Long {
            val toNextHour = 3600 - t.mod(3600L)
            return if (toNextHour < 60) (toNextHour + 1) * 1000 else 60_000L
        }

        /** One correct PIN entry covers sensitive actions for this long. */
        const val AUTH_WINDOW_SECONDS = 120L
        /** How long a PIN entry counts for a backup ([exportBackup]), which uses it up. */
        const val FRESH_SECONDS = 30L

        const val PIN_FREE_ATTEMPTS = 5
        const val PIN_ITERATIONS = 150_000

        /** At most one reply to a join request that can't go ahead per person this often... */
        const val REFUSAL_EVERY_SECONDS = 10 * 60L

        /** ...and this many in all per hour. */
        const val MAX_REFUSALS_PER_HOUR = 20

        /** How often to remind someone that they're sharing, and with whom. */
        const val CONSENT_EVERY_SECONDS = 30 * 24 * 3600L

        const val HISTORY_SIZE = 100

        /** Orbot's default SOCKS port. */
        const val TOR_SOCKS_PORT = 9050

        private const val SECONDS_PER_DAY = 86_400L
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

    /** Just joined: waiting for the user to look at who's in the circle and start sharing. */
    NOT_CONFIRMED,

    /** The user paused sharing with this circle. */
    PAUSED,
}

/** The result of entering the PIN. */
sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult

    /** Too many wrong tries: try again after [until] (unix seconds). */
    data class Locked(val until: Long) : PinResult
}

/** Something the user should hear about even when the app is closed. */
data class Notice(val title: String, val text: String, val kind: String = CIRCLE) {
    companion object {
        /** Someone joined or left, and other changes to circles. */
        const val CIRCLE = "circle"
        /** Someone arrived at or left an alert area (#151). */
        const val PLACE = "place"
    }
}

/** "2026-11-21 14:03:07Z" for unix seconds [t]. */
internal fun utcTime(t: Long): String {
    val days = t.floorDiv(86_400L)
    val secs = t - days * 86_400
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "$y-${two(m)}-${two(d)} ${two(secs / 3600)}:${two(secs % 3600 / 60)}:${two(secs % 60)}Z"
}
