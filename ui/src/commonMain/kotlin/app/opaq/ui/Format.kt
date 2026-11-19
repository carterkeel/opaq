package app.opaq.ui

import app.opaq.core.Engine
import app.opaq.core.MotionPolicy
import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Precision
import kotlin.math.round

object Format {
    private val usesMiles = regionUsesMiles()

    fun ago(seconds: Long): String = when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }

    fun isOffline(loc: MemberLocation?, now: Long): Boolean = loc == null || now - loc.time > Engine.OFFLINE_AFTER_SECONDS

    fun speed(mps: Float): String =
        if (usesMiles) "${(mps * 2.23694f).toInt()} mph" else "${(mps * 3.6f).toInt()} km/h"

    /** "3.2 mi" or "5.1 km" (one decimal under 10, whole numbers above). */
    fun distance(meters: Double): String {
        val v = if (usesMiles) meters / 1609.344 else meters / 1000
        val unit = if (usesMiles) "mi" else "km"
        val text = if (v < 10) "${round(v * 10) / 10}" else "${round(v).toLong()}"
        return "$text $unit"
    }

    /** "25 min", "3 h 10 min". */
    fun duration(seconds: Long): String {
        val m = (seconds + 30) / 60
        return when {
            m < 1 -> "under a minute"
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    /** How long history is kept, for Settings and the history screen. */
    fun historyDays(days: Int): String = when (days) {
        0 -> "Off"
        1 -> "1 day"
        7 -> "1 week"
        30 -> "1 month"
        90 -> "3 months"
        else -> "$days days"
    }

    fun activity(a: Activity): String = when (a) {
        Activity.STILL -> "Still"
        Activity.WALKING -> "Walking"
        Activity.CYCLING -> "Cycling"
        Activity.DRIVING -> "Driving"
        Activity.UNKNOWN -> "Moving"
    }

    private fun isMovingActivity(a: Activity) = a == Activity.WALKING || a == Activity.CYCLING || a == Activity.DRIVING

    /**
     * "Driving · 42 mph · just now", "Still · 64% · 3 min ago", or "Offline · last seen 2 h ago".
     *
     * A moving update older than about two send intervals is shown as "Was driving" without a
     * speed, since the person may well have stopped since.
     */
    fun status(loc: MemberLocation?, now: Long, showBattery: Boolean = true): String {
        if (loc == null) return "No location yet"
        val age = (now - loc.time).coerceAtLeast(0)
        if (isOffline(loc, now)) {
            val why = loc.status?.let { " ($it)" } ?: ""
            return "Offline$why · last seen ${ago(age)}"
        }
        val stale = isMovingActivity(loc.activity) && age > 2 * MotionPolicy.MOVING_INTERVAL + 30
        val parts = mutableListOf<String>()
        if (stale) {
            parts += "Was ${activity(loc.activity).lowercase()}"
        } else {
            // UNKNOWN means the sender's phone hadn't worked out a speed yet (often its first fix),
            // so say nothing rather than guess "Moving".
            if (loc.activity != Activity.UNKNOWN) parts += activity(loc.activity)
            val s = loc.speed
            if (s != null && (loc.activity == Activity.DRIVING || loc.activity == Activity.CYCLING)) parts += speed(s)
        }
        if (showBattery) loc.battery?.let { parts += "$it%" }
        when (loc.precision) {
            Precision.NEIGHBORHOOD -> parts += "area only"
            Precision.CITY -> parts += "city only"
            else -> Unit
        }
        parts += ago(age)
        return parts.joinToString(" · ")
    }

    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
