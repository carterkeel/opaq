package app.opaq.core

import app.opaq.core.crypto.PlatformCrypto
import app.opaq.core.model.Activity
import app.opaq.core.model.LocationSample
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Point
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides when to publish and what activity to report.
 *
 * Only two steady rates exist (moving / still) plus jitter, so relays can tell at most whether
 * someone is moving. On top of that, one extra update goes out when someone starts or stops
 * moving, so the circle never sees "Driving" for minutes after you've parked, or waits five
 * minutes to see you've left. The activity itself travels inside the encrypted update.
 *
 * Phones stop sending fixes when they stop moving (iOS's distance filter, a simulator, an idle
 * GPS), so the policy also works from the clock: no fix for [QUIET_SECONDS] means still, and a
 * still update carries the time it was sent, not the time of the last fix. Otherwise a phone
 * that stays put would look stale and then offline to everyone, while sharing normally.
 */
class MotionPolicy {
    private val buffer = ArrayDeque<LocationSample>()
    private var activity = Activity.UNKNOWN
    private var motionHintAt: Long? = null

    private var lastPublishAt: Long? = null
    private var lastPublishedMoving: Boolean? = null
    private var lastPublished: LocationSample? = null

    /** Until when a fresh fix is wanted for the locate button ([requestRefresh]). */
    private var refreshUntil: Long? = null
    /** The refresh was asked for by someone else (#149): send the fix even if the shared one was right. */
    private var refreshAlways = false
    private var jitter = 1.0

    /** When the newest fix arrived, on this phone's clock (a fix's own time can come from GPS). */
    private var lastFixAt: Long? = null

    /**
     * Holding a drive through a stop (#155): when a driver first went below driving speed, and when
     * an unbroken run of walking pace began. Null while driving at speed.
     */
    private var slowSince: Long? = null
    private var walkingSince: Long? = null

    /**
     * Adds a fix that arrived at [receivedAt] (this phone's clock) and was [ageSeconds] old then.
     * A fix already older than [MAX_FIX_AGE] (a cached position from before sharing started, maybe
     * somewhere else) is ignored rather than shared. The age is passed in rather than worked out
     * from [receivedAt] - time, because a fix's time can come from GPS while the phone's clock is
     * set wrong.
     */
    fun add(sample: LocationSample, receivedAt: Long = sample.time, ageSeconds: Long = receivedAt - sample.time) {
        if (ageSeconds > MAX_FIX_AGE) return
        if (buffer.isNotEmpty() && sample.time < buffer.last().time) return
        lastFixAt = receivedAt
        buffer.addLast(sample)
        val cutoff = sample.time - BUFFER_SECONDS
        while (buffer.isNotEmpty() && buffer.first().time < cutoff) buffer.removeFirst()
        settle(classify(estimateSpeed(), activity), receivedAt)
        checkRefresh(sample, receivedAt)
    }

    /**
     * The locate button: get a fresh GPS fix (as for a [motionHint]) to check the shared position.
     * If the first accurate fix within [REFRESH_SECONDS] is more than [REFRESH_METERS] (or its own
     * error) from what was last shared, it goes out straight away instead of at the next interval,
     * which can be up to five minutes away while still. If the shared position was right, nothing
     * extra is sent, so the relays rarely see an update off the steady rates.
     */
    fun requestRefresh(now: Long, always: Boolean = false) {
        motionHint(now)
        refreshUntil = now + REFRESH_SECONDS
        refreshAlways = refreshAlways || always
    }

    private fun checkRefresh(sample: LocationSample, receivedAt: Long) {
        val until = refreshUntil ?: return
        val always = refreshAlways
        if (receivedAt > until) {
            // No accurate fix in time: someone who asked still gets the best one there is.
            refreshUntil = null
            refreshAlways = false
            if (always) publishSoon()
            return
        }
        if (sample.accuracy > GOOD_ACCURACY) return
        refreshUntil = null
        refreshAlways = false
        val shared = lastPublished
        val off = shared == null ||
            distanceMeters(shared.lat, shared.lon, sample.lat, sample.lon) > max(REFRESH_METERS, sample.accuracy.toDouble())
        if (always || off) publishSoon()
    }

    /**
     * Called on every tick. When fixes have stopped coming for [QUIET_SECONDS], the phone isn't
     * moving any more: classify it as still, so the stop is published instead of "Driving" forever.
     */
    fun refresh(now: Long) {
        if (quiet(now)) settle(classify(0f, activity), now)
    }

    /**
     * Takes the activity [raw] from speed alone, except that a drive isn't given up at the first
     * slowdown (#155). Waiting at lights, creeping in traffic and pulling away all look like
     * walking or standing still for a while. So once driving, it stays driving (shown as stopped
     * when the speed is near zero) until [WALK_TAKEOVER_SECONDS] of steady walking pace, which is
     * someone who parked and walked off, or [STOP_TAKEOVER_SECONDS] below driving speed in all,
     * which is someone who parked and stayed.
     */
    private fun settle(raw: Activity, at: Long) {
        if (activity != Activity.DRIVING || raw == Activity.DRIVING) {
            slowSince = null
            walkingSince = null
            activity = raw
            return
        }
        val slow = slowSince ?: at.also { slowSince = it }
        walkingSince = if (raw == Activity.WALKING) walkingSince ?: at else null
        val walkedOff = walkingSince?.let { at - it >= WALK_TAKEOVER_SECONDS } == true
        if (walkedOff || at - slow >= STOP_TAKEOVER_SECONDS) {
            slowSince = null
            walkingSince = null
            activity = raw
        }
    }

    /** No fix has arrived for [QUIET_SECONDS]. */
    private fun quiet(now: Long): Boolean = lastFixAt?.let { now - it >= QUIET_SECONDS } == true

    /** The phone's motion sensor says it started moving; treat as moving until fixes say otherwise. */
    fun motionHint(now: Long) {
        motionHintAt = now
    }

    val latest: LocationSample? get() = buffer.lastOrNull()

    fun activity(): Activity = activity

    fun isMoving(now: Long): Boolean = when (activity) {
        Activity.WALKING, Activity.CYCLING, Activity.DRIVING -> true
        Activity.STILL -> false
        Activity.UNKNOWN -> motionHintAt?.let { now - it < HINT_SECONDS } == true
    }

    /** Whether location should be collected with GPS (moving, just woke up, or no fix yet). */
    fun wantsHighAccuracy(now: Long): Boolean = latest == null || isMoving(now) || motionHintAt?.let { now - it < HINT_SECONDS } == true

    fun shouldPublish(now: Long): Boolean {
        if (latest == null) return false
        val last = lastPublishAt ?: return true
        val moving = isMoving(now)
        val elapsed = now - last
        if (lastPublishedMoving != null && moving != lastPublishedMoving && elapsed >= MIN_GAP_SECONDS) return true
        val base = if (moving) MOVING_INTERVAL else STILL_INTERVAL
        return elapsed >= (base * jitter).toLong()
    }

    /**
     * How long the location loop may wait before its next check, in milliseconds. Every 2 s while
     * moving, about to (GPS on, a motion hint, the locate button) or just stopped (the stop goes out
     * once [MIN_GAP_SECONDS] have passed); otherwise until the next still update is due, but at most
     * 30 s. Waking every 2 s all night while nothing happens costs battery for nothing. A fix or a motion
     * hint still wakes the loop at once, and the wait ends exactly when the update is due, so update
     * timing (what relays can see) is the same as checking every 2 s.
     */
    fun nextCheckMillis(now: Long): Long {
        val last = lastPublishAt
        if (latest == null || last == null || wantsHighAccuracy(now) || lastPublishedMoving == true) return FAST_CHECK_MILLIS
        val due = last + (STILL_INTERVAL * jitter).toLong() - now
        return due.coerceIn(1L, SLOW_CHECK_SECONDS) * 1000
    }

    /** Publish on the next check, e.g. right after joining a circle, so nobody waits for the next interval. */
    fun publishSoon() {
        lastPublishAt = null
    }

    fun markPublished(now: Long) {
        lastPublishAt = now
        lastPublishedMoving = isMoving(now)
        lastPublished = buffer.lastOrNull()
        val r = (PlatformCrypto.randomBytes(1)[0].toInt() and 0xff) / 255.0 // 0..1
        jitter = 0.85 + 0.3 * r
    }

    /**
     * Speed in m/s. Uses only fixes accurate to 30 m: the median of the fixes' own speeds over the
     * last 30 s, otherwise distance over time, counting movement only when it beats the GPS error.
     */
    fun estimateSpeed(now: Long? = null): Float? {
        val last = buffer.lastOrNull() ?: return null
        if (now != null && quiet(now)) return 0f
        val good = buffer.filter { it.accuracy <= GOOD_ACCURACY && it.time >= last.time - SPEED_WINDOW }
        val reported = good.mapNotNull { it.speed }
        // The lower median, so one bad reading can't say "moving" on its own. A lone reading only
        // counts if it says still: movement waits for a second fix (a few seconds on GPS).
        if (reported.size >= 2) return reported.sorted()[(reported.size - 1) / 2]
        if (reported.size == 1 && good.size == 1 && reported[0] < STILL_SPEED) return reported[0]
        if (good.size >= 2) {
            val a = good.first()
            val b = good.last()
            val dt = (b.time - a.time).toFloat()
            if (dt >= 10f) {
                val d = distanceMeters(a.lat, a.lon, b.lat, b.lon)
                return if (d <= max(a.accuracy, b.accuracy)) 0f else (d / dt).toFloat()
            }
        }
        // Only coarse fixes (network location): call it movement only for a clear jump.
        val older = buffer.firstOrNull { it.time <= last.time - 45 } ?: return null
        val d = distanceMeters(older.lat, older.lon, last.lat, last.lon)
        val noise = 2 * max(older.accuracy, last.accuracy)
        val dt = (last.time - older.time).toFloat()
        return if (d > max(noise.toDouble(), 75.0)) (d / dt).toFloat() else 0f
    }

    /**
     * Builds the update payload, including up to six recent points so viewers can replay the path.
     * A still update is stamped [now]: the phone is confirming it's still there, even if no new
     * fix arrived (iOS sends none until it moves 25 m).
     */
    fun snapshot(battery: Int?, status: String? = null, now: Long? = null): MemberLocation? {
        val last = buffer.lastOrNull() ?: return null
        val points = buffer.filter { it.time >= last.time - 60 }.takeLast(6).map { Point(round6(it.lat), round6(it.lon), it.time) }
        return MemberLocation(
            lat = round6(last.lat),
            lon = round6(last.lon),
            accuracy = last.accuracy,
            speed = estimateSpeed(now),
            heading = last.heading,
            activity = activity,
            battery = battery,
            // Still: the fix's time moved on by how long ago it arrived, so it stays on the same
            // clock as moving updates even if this phone's clock is off.
            time = if (now != null && activity == Activity.STILL) last.time + max(0L, now - (lastFixAt ?: now)) else last.time,
            points = points,
            status = status,
        )
    }

    companion object {
        const val MOVING_INTERVAL = 10L
        const val STILL_INTERVAL = 300L
        const val FAST_CHECK_MILLIS = 2_000L

        /** After an update reached no relay, check every [FAST_CHECK_MILLIS] for this long to resend it. */
        const val MISSED_RETRY_SECONDS = 60L
        private const val SLOW_CHECK_SECONDS = 30L
        private const val MIN_GAP_SECONDS = 10L

        /** How long the locate button waits for an accurate fix, and how far off the shared one may be. */
        const val REFRESH_SECONDS = 30L
        const val REFRESH_METERS = 25.0
        private const val HINT_SECONDS = 120L
        private const val BUFFER_SECONDS = 5 * 60L
        private const val SPEED_WINDOW = 30L
        private const val GOOD_ACCURACY = 30f

        /** No fix for this long means the phone has stopped (moving phones get one every few seconds). */
        const val QUIET_SECONDS = 60L

        /** A drive becomes walking after this long at steady walking pace (#155). */
        const val WALK_TAKEOVER_SECONDS = 120L

        /** A drive becomes still (or walking) after this long below driving speed in all (#155). */
        const val STOP_TAKEOVER_SECONDS = 240L

        /** A fix older than this when it arrives is a cached one, not where the phone is now. */
        const val MAX_FIX_AGE = 120L

        // Upper speed bounds in m/s: still < 1.8 km/h, walking (running too) < 14.4 km/h (9 mph),
        // driving above. There's no cycling: speed alone can't tell it from slow traffic or a run.
        private const val STILL_SPEED = 0.5f
        private val BOUNDS = listOf(Activity.STILL to STILL_SPEED, Activity.WALKING to 4f)

        /** Classifies speed, with 15% hysteresis so a reading near a boundary doesn't flip back and forth. */
        fun classify(speed: Float?, previous: Activity): Activity {
            if (speed == null) return previous
            fun bucket(s: Float): Activity = BOUNDS.firstOrNull { s < it.second }?.first ?: Activity.DRIVING
            val raw = bucket(speed)
            if (previous == Activity.UNKNOWN || raw == previous) return raw
            val lowered = bucket(speed * 1.15f)
            val raised = bucket(speed * 0.85f)
            return if (lowered == previous || raised == previous) previous else raw
        }

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * PI / 180
            val dLon = (lon2 - lon1) * PI / 180
            val a = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        private fun round6(v: Double): Double = kotlin.math.round(v * 1_000_000) / 1_000_000
    }
}
