package app.opaq.ui

import app.opaq.core.Engine
import app.opaq.core.HistoryPoint
import app.opaq.core.PinResult
import app.opaq.core.ShareStatus
import app.opaq.core.model.Circle
import app.opaq.core.model.Precision
import app.opaq.core.protocol.InviteData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thin bridge from UI events to the engine; shows errors as platform messages. */
class Actions(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val platform: PlatformBridge,
) {
    val me: String get() = engine.myPubkey

    /** Debug builds pointed at a test relay: the relays used instead of the saved ones. */
    val testRelays: List<String>? get() = engine.testRelays

    /** Whether this phone is currently sending its location to [circle], and if not, why. */
    fun shareStatus(circle: Circle): ShareStatus = engine.shareStatus(circle)

    /** Runs [block]; then [after] on success, or shows the error and runs [failed], on the main thread. */
    private fun launchAction(after: (() -> Unit)? = null, failed: (() -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                if (after != null) withContext(Dispatchers.Main) { after() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    platform.showMessage(t.message ?: "Something went wrong")
                    failed?.invoke()
                }
            }
        }
    }

    /** Reads location history for [pubkey] between [from] and [to] (seconds) and hands it back on the main thread. */
    fun history(pubkey: String, from: Long, to: Long, onResult: (List<HistoryPoint>) -> Unit) {
        scope.launch {
            val points = runCatching { engine.history(pubkey, from, to) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { onResult(points) }
        }
    }

    fun setHistoryDays(days: Int) = launchAction { engine.setHistoryDays(days) }

    /** Asks [pubkey] for their location now (#149) and says so. */
    fun requestLocation(circleId: String, pubkey: String, name: String) = launchAction {
        val sent = engine.requestLocation(circleId, pubkey)
        withContext(Dispatchers.Main) {
            platform.showMessage(if (sent) "Asked $name for their location" else "You asked $name a moment ago")
        }
    }

    fun setName(name: String) = launchAction { engine.setMyName(name) }
    fun setAvatar(color: Int?, photo: String?) = launchAction { engine.setAvatar(color, photo) }
    fun setSharing(on: Boolean) = launchAction(after = { if (on) platform.ensureSharingService() }) { engine.setSharing(on) }
    fun setShareBattery(on: Boolean) = launchAction { engine.setShareBattery(on) }
    fun setShowPhotos(on: Boolean) = launchAction { engine.setShowPhotos(on) }
    fun setDarkMap(on: Boolean) = launchAction { engine.setDarkMap(on) }
    fun setThemeMode(mode: String) = launchAction { engine.setThemeMode(mode) }
    fun setPeoplePanelHidden(hidden: Boolean) = launchAction { engine.setPeoplePanelHidden(hidden) }
    fun setLastCircle(circleId: String) = launchAction { engine.setLastCircle(circleId) }
    fun setSetupDone() = launchAction(after = { platform.ensureSharingService() }) { engine.setSetupDone() }
    fun setRelays(relays: List<String>) = launchAction { engine.setRelays(relays) }
    fun setLabel(pubkey: String, label: String?) = launchAction { engine.setLabel(pubkey, label) }
    fun setVerified(pubkey: String, verified: Boolean) = launchAction { engine.setVerified(pubkey, verified) }

    /** [onFailed] runs (after the error is shown) if the circle couldn't be made. */
    fun createCircle(name: String, onFailed: () -> Unit = {}, onCreated: (String) -> Unit) {
        var id = ""
        launchAction(after = { onCreated(id); platform.ensureSharingService() }, failed = onFailed) { id = engine.createCircle(name).id }
    }

    fun createInvite(circleId: String, hours: Int = 1, multiUse: Boolean = false, onLink: (String) -> Unit) {
        var link = ""
        launchAction(after = { onLink(link) }) { link = engine.createInvite(circleId, hours, multiUse) }
    }

    fun cancelInvite(secret: String, onDone: () -> Unit = {}) = launchAction(after = onDone) { engine.cancelInvite(secret) }

    /** [codesMatch]: the user saw that the inviter's device code matches; false joins unverified. */
    fun acceptInvite(invite: InviteData, scannedInPerson: Boolean, codesMatch: Boolean, keepRelays: Boolean, onDone: () -> Unit) = launchAction(after = onDone) {
        engine.acceptInvite(invite, scannedInPerson, codesMatch, keepRelays)
        engine.setSetupDone()
    }

    fun dismissJoinRefusal(circleId: String) = launchAction { engine.dismissJoinRefusal(circleId) }

    fun setCirclePrecision(circleId: String, precision: Precision) = launchAction { engine.setCirclePrecision(circleId, precision) }
    fun setCirclePaused(circleId: String, paused: Boolean) = launchAction { engine.setCirclePaused(circleId, paused) }
    fun setCircleTrail(circleId: String, on: Boolean) = launchAction { engine.setCircleTrail(circleId, on) }

    fun confirmCircle(circleId: String) = launchAction(after = { platform.ensureSharingService() }) { engine.confirmCircle(circleId) }
    fun approve(requestId: String) = launchAction(after = { platform.ensureSharingService() }) { engine.approve(requestId) }
    fun deny(requestId: String) = launchAction { engine.deny(requestId) }
    fun removeMember(circleId: String, pubkey: String) = launchAction { engine.removeMember(circleId, pubkey) }
    fun leaveCircle(circleId: String) = launchAction { engine.leaveCircle(circleId) }
    fun deleteCircle(circleId: String) = launchAction { engine.deleteCircle(circleId) }
    fun setManager(circleId: String, pubkey: String, manager: Boolean) = launchAction { engine.setManager(circleId, pubkey, manager) }
    fun transferAdmin(circleId: String, pubkey: String) = launchAction { engine.transferAdmin(circleId, pubkey) }

    /** Reads unreadable saved state again; [onDone] runs either way. */
    fun retryLoad(onDone: () -> Unit) {
        scope.launch {
            val ok = runCatching { engine.retryLoad() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) platform.ensureSharingService() else platform.showMessage("Still can't read the saved data")
                onDone()
            }
        }
    }

    fun startFresh() = launchAction { engine.startFresh() }

    // PIN and security

    fun isAuthorized(): Boolean = engine.isAuthorized()
    val hasPin: Boolean get() = engine.hasPin

    /** Checks the PIN off the main thread (it's a slow hash) and reports back on the main thread. */
    /** [onFailed] runs (after the error is shown) if the check itself couldn't run. */
    fun unlock(pin: String, onFailed: () -> Unit = {}, onResult: (PinResult) -> Unit) {
        var result: PinResult? = null
        launchAction(after = { result?.let(onResult) }, failed = onFailed) { result = engine.unlock(pin) }
    }

    fun setPin(pin: String, onFailed: () -> Unit = {}, onDone: () -> Unit = {}) = launchAction(after = onDone, failed = onFailed) { engine.setPin(pin) }
    /** After the phone confirmed a face or fingerprint: counts as entering the PIN. */
    fun unlockWithBiometrics(onResult: (PinResult) -> Unit) {
        var result: PinResult? = null
        launchAction(after = { result?.let(onResult) }) { result = engine.unlockWithBiometrics() }
    }

    fun setBiometrics(on: Boolean, onDone: () -> Unit = {}) = launchAction(after = onDone) { engine.setBiometrics(on) }
    fun removePin() = launchAction(after = { platform.showMessage("PIN removed") }) { engine.removePin() }
    fun resetIdentity(onDone: () -> Unit) = launchAction(after = onDone) { engine.resetIdentity() }
    fun setHideInRecents(on: Boolean) = launchAction { engine.setHideInRecents(on) }
    fun setUseTor(on: Boolean) = launchAction { engine.setUseTor(on) }
    fun consentDue(): Boolean = engine.consentDue()
    fun confirmConsent() = launchAction { engine.confirmConsent() }

    /** Builds an encrypted backup off the main thread (PBKDF2 takes a moment) and hands back the text. */
    fun exportBackup(passphrase: String, onReady: (String?) -> Unit) {
        scope.launch {
            val text = try {
                engine.exportBackup(passphrase)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { platform.showMessage(t.message ?: "Couldn't make the backup") }
                null
            }
            withContext(Dispatchers.Main) { onReady(text) }
        }
    }

    /** Restores a backup; [onDone] gets null on success or the error to show. */
    fun importBackup(text: String, passphrase: String, onDone: (String?) -> Unit) {
        scope.launch {
            val error = try {
                engine.importBackup(text, passphrase)
                null
            } catch (t: Throwable) {
                t.message ?: "Couldn't restore that backup"
            }
            withContext(Dispatchers.Main) {
                if (error == null) platform.ensureSharingService()
                onDone(error)
            }
        }
    }
}
