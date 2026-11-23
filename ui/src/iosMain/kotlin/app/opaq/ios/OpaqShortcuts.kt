package app.opaq.ios

import app.opaq.ui.epochSeconds
import app.opaq.ui.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A circle as Shortcuts lists it. */
class ShortcutCircle(val id: String, val name: String, val paused: Boolean)

/**
 * What the Shortcuts actions in iosApp (App Intents, `OpaqShortcuts.swift`) call. Each action
 * returns the sentence Shortcuts or Siri shows. iOS runs them inside the app, starting it in the
 * background if needed, and only once the phone is unlocked (every action requires it), so saved
 * state can be read. Call on the main thread.
 *
 * Nothing here reads anyone's location: these only change what this phone shares. A shortcut can
 * pass its output to any other action, so a location in it would leave the encryption.
 */
object OpaqShortcuts {
    private val engine get() = OpaqIos.engine

    /** Why nothing can be done right now, or null if it can. */
    private fun problem(): String? {
        OpaqIos.start()
        if (engine.recovery.value != null) return "Open Opaq first. It can't read its saved data right now."
        if (engine.state.value.circles.none { it.confirmed }) return "You're not sharing with any circle yet. Open Opaq to create or join one."
        return null
    }

    fun circles(): List<ShortcutCircle> =
        engine.state.value.circles.filter { it.confirmed }.map { ShortcutCircle(it.id, it.name, it.paused) }

    fun isSharing(): Boolean = engine.state.value.sharing

    /**
     * Calls [onChange] on the main thread with whether sharing is on, now and whenever it changes
     * (in the app, from a shortcut or control, or when a timed pause ends), so the Control Center
     * toggle shows the truth. Call once.
     */
    fun watchSharing(onChange: (Boolean) -> Unit) {
        OpaqIos.scope.launch(Dispatchers.Main) {
            engine.state.map { it.sharing }.distinctUntilChanged().collect { onChange(it) }
        }
    }

    /** Turns sharing on, or pauses it for [minutes] (0 = until turned back on). */
    suspend fun setSharing(on: Boolean, minutes: Int): String {
        problem()?.let { return it }
        val s = engine.state.value
        if (on) {
            if (s.sharing) return "You're already sharing your location."
            engine.setSharing(true)
            OpaqIos.location.update()
            return if (OpaqIos.location.hasPermission) "Sharing your location again."
            else "Sharing is on, but Opaq doesn't have location access. Open Opaq to allow it."
        }
        if (!s.sharing && minutes <= 0) return "Sharing is already paused."
        val until = resumeAt(minutes)
        engine.setSharing(false, until)
        return if (until > 0) "Sharing paused until ${formatClock(until)}." else "Sharing paused until you turn it back on."
    }

    /** Pauses one circle for [minutes] (0 = until resumed), or resumes it. */
    suspend fun setCirclePaused(circleId: String, paused: Boolean, minutes: Int): String {
        problem()?.let { return it }
        val c = engine.state.value.circles.firstOrNull { it.id == circleId && it.confirmed }
            ?: return "That circle isn't on this phone any more."
        if (!paused) {
            if (!c.paused) return "You're already sharing with ${c.name}."
            engine.setCirclePaused(c.id, false)
            return if (engine.state.value.sharing) "Sharing with ${c.name} again." else "${c.name} is no longer paused, but all sharing is paused. Start sharing to send your location."
        }
        val until = resumeAt(minutes)
        engine.setCirclePaused(c.id, true, until)
        return if (until > 0) "Sharing with ${c.name} paused until ${formatClock(until)}." else "Sharing with ${c.name} paused until you turn it back on."
    }

    /** The locate button: a fresh fix, sent at once if it differs from what was last shared. */
    fun updateLocation(): String {
        problem()?.let { return it }
        if (!engine.state.value.sharing) return "Sharing is paused, so nothing was sent."
        if (!OpaqIos.location.hasPermission) return "Opaq doesn't have location access. Open Opaq to allow it."
        OpaqIos.location.refreshNow()
        return "Checking where you are. If you've moved, your circles see it straight away."
    }

    private fun resumeAt(minutes: Int): Long = if (minutes > 0) epochSeconds() + minutes * 60L else 0L
}
