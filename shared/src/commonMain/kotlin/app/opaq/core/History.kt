package app.opaq.core

import app.opaq.core.crypto.Nip44
import app.opaq.core.crypto.PlatformCrypto
import app.opaq.core.crypto.toHex
import app.opaq.core.model.Activity
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.Precision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Where location history is kept on the phone: small text files that only grow by appending.
 * Android keeps them in the app's private files, iOS in Application Support (not backed up).
 */
interface HistoryFiles {
    fun list(): List<String>
    fun append(name: String, line: String)
    fun read(name: String): List<String>
    fun delete(name: String)
}

/** One remembered position, with what the person was doing (still, walking, driving...). */
@Serializable
data class HistoryPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val a: Activity = Activity.UNKNOWN,
    val acc: Float? = null,
    /** Speed in m/s, kept while driving. Missing in older files. */
    val v: Float? = null,
)

/**
 * Location history (#58), kept only on this phone, never sent anywhere.
 *
 * - One file per person per UTC day, named from a hash of their key (not the key itself). Each
 *   line is a batch of points encrypted with NIP-44 under a key kept in the (Keystore- or
 *   Keychain-protected) app state, so the files are unreadable without it.
 * - Points are thinned as they arrive: while moving, about one every 15 m or 2 minutes; while
 *   still, one every 15 minutes or after a real move. A change of activity is always kept.
 * - Approximate (area or city only) updates aren't recorded.
 */
class LocationHistory(private val files: HistoryFiles) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val points = ListSerializer(HistoryPoint.serializer())
    private val lastKept = HashMap<String, HistoryPoint?>()

    suspend fun record(key: ByteArray, pubkey: String, loc: MemberLocation) = mutex.withLock {
        if (loc.precision != null && loc.precision != Precision.EXACT) return@withLock
        val prefix = prefix(pubkey)
        var last = lastKept.getOrPut(pubkey) { latestStored(key, prefix) }
        val activity = drivingIfCycling(loc.activity)
        val sorted = (loc.points.map { HistoryPoint(it.t, it.lat, it.lon, activity) } +
            HistoryPoint(loc.time, loc.lat, loc.lon, activity, loc.accuracy, loc.speed)).sortedBy { it.t }
        // While driving, keep the speed: the reported one for the newest point, and for the recent
        // path the distance to the next point over the time it took.
        val candidates = if (activity != Activity.DRIVING) sorted.map { it.copy(v = null) } else sorted.mapIndexed { i, p ->
            if (p.v != null) return@mapIndexed p
            val next = sorted.getOrNull(i + 1) ?: return@mapIndexed p
            val dt = next.t - p.t
            if (dt <= 0) p else p.copy(v = (MotionPolicy.distanceMeters(p.lat, p.lon, next.lat, next.lon) / dt).toFloat().takeIf { it < 90f })
        }
        val kept = mutableListOf<HistoryPoint>()
        for (p in candidates) {
            if (keep(last, p)) {
                kept += p
                last = p
            }
        }
        if (kept.isEmpty()) return@withLock
        lastKept[pubkey] = last
        kept.groupBy { day(it.t) }.forEach { (d, ps) ->
            files.append("$prefix$d", Nip44.encrypt(json.encodeToString(points, ps), conversationKey(key)))
        }
    }

    /** Points for [pubkey] between [from] and [to] (seconds), oldest first. */
    suspend fun read(key: ByteArray, pubkey: String, from: Long, to: Long): List<HistoryPoint> = mutex.withLock {
        val prefix = prefix(pubkey)
        (day(from)..day(to)).flatMap { d -> readFile(key, "$prefix$d") }
            .filter { it.t in from until to }
            .distinctBy { it.t }
            .sortedBy { it.t }
    }

    /**
     * Deletes days older than [keepDays] and everything about people no longer in [keepPubkeys]
     * (they left your circles, so you stop keeping their history). [keepDays] 0 deletes it all.
     */
    suspend fun prune(now: Long, keepDays: Int, keepPubkeys: Set<String>) = mutex.withLock {
        val oldest = day(now) - keepDays + 1
        val prefixes = keepPubkeys.map { prefix(it) }.toSet()
        files.list().filter { it.startsWith("h-") }.forEach { name ->
            val prefix = name.substringBeforeLast('-') + "-"
            val d = name.substringAfterLast('-').toLongOrNull()
            if (keepDays <= 0 || d == null || d < oldest || prefix !in prefixes) files.delete(name)
        }
        lastKept.keys.retainAll(keepPubkeys)
    }

    /** Deletes all history, e.g. when its key is replaced. */
    suspend fun clear() = mutex.withLock {
        files.list().filter { it.startsWith("h-") }.forEach { files.delete(it) }
        lastKept.clear()
    }

    private fun keep(last: HistoryPoint?, p: HistoryPoint): Boolean {
        if (last == null) return true
        if (p.t <= last.t) return false
        if (p.a != last.a) return true
        val moved = MotionPolicy.distanceMeters(last.lat, last.lon, p.lat, p.lon)
        val dt = p.t - last.t
        return if (moving(p.a)) moved >= MOVING_STEP_METERS || dt >= MOVING_EVERY_SECONDS
        else moved >= maxOf(STILL_STEP_METERS, 2.0 * (p.acc ?: 0f)) || dt >= STILL_EVERY_SECONDS
    }

    private fun latestStored(key: ByteArray, prefix: String): HistoryPoint? {
        val names = files.list().filter { it.startsWith(prefix) }.sortedBy { it.substringAfterLast('-').toLongOrNull() ?: 0 }
        return names.lastOrNull()?.let { readFile(key, it).maxByOrNull { p -> p.t } }
    }

    private fun readFile(key: ByteArray, name: String): List<HistoryPoint> =
        files.read(name).flatMap { line ->
            // A line from an older key, or cut short by a crash mid-write, is skipped.
            runCatching { json.decodeFromString(points, Nip44.decrypt(line, conversationKey(key))) }.getOrDefault(emptyList())
        }

    private fun conversationKey(key: ByteArray) = Nip44.conversationKeyFromSecret(key, "qm-history")

    companion object {
        const val MAX_DAYS = 90
        const val DEFAULT_DAYS = 7
        private const val MOVING_STEP_METERS = 15.0
        private const val MOVING_EVERY_SECONDS = 120L
        private const val STILL_STEP_METERS = 50.0
        private const val STILL_EVERY_SECONDS = 15 * 60L

        fun prefix(pubkey: String) = "h-" + PlatformCrypto.sha256(("qm-history|$pubkey").encodeToByteArray()).copyOf(8).toHex() + "-"
        fun day(t: Long): Long = t.floorDiv(86_400L)
        fun moving(a: Activity) = a == Activity.WALKING || a == Activity.CYCLING || a == Activity.DRIVING

        /** Cycling isn't reported any more; older updates and files that say so count as driving. */
        fun drivingIfCycling(a: Activity) = if (a == Activity.CYCLING) Activity.DRIVING else a
    }
}

/** A stretch of history with one activity: "Driving 8:02–8:25", "Still 8:25–12:10". */
data class HistorySegment(val activity: Activity, val start: Long, val end: Long, val meters: Double, val points: List<HistoryPoint>) {
    /** Fastest speed kept for a drive, in m/s. */
    val topSpeed: Float? get() = if (activity == Activity.DRIVING) points.mapNotNull { it.v }.maxOrNull() else null

    /** Distance over time for a drive, in m/s. */
    val averageSpeed: Float? get() = if (activity == Activity.DRIVING && end > start && meters > 0) (meters / (end - start)).toFloat() else null
}

object HistorySegments {
    /** Moves or stops shorter than this are folded into what came before (GPS blips, traffic lights). */
    private const val MIN_SECONDS = 120L

    fun build(all: List<HistoryPoint>): List<HistorySegment> {
        if (all.isEmpty()) return emptyList()
        val points = all.map { if (it.a == Activity.CYCLING) it.copy(a = Activity.DRIVING) else it }
        val runs = mutableListOf<MutableList<HistoryPoint>>()
        for (p in points) {
            val run = runs.lastOrNull()
            if (run != null && group(run.first().a) == group(p.a)) run += p else runs += mutableListOf(p)
        }
        // A short stop inside a drive (a red light), or a short move inside a stay (GPS drift), is
        // folded into what surrounds it, so it doesn't split the stretch in three.
        // Shortest first (then fewest points), so a red light goes before the drive around it.
        while (true) {
            val candidates = (1 until runs.size - 1).filter { k ->
                runs[k + 1].first().t - runs[k].first().t < MIN_SECONDS && group(runs[k - 1].first().a) == group(runs[k + 1].first().a)
            }
            val k = candidates.minWithOrNull(compareBy({ runs[it + 1].first().t - runs[it].first().t }, { runs[it].size })) ?: break
            runs[k - 1] += runs[k] + runs[k + 1]
            runs.removeAt(k + 1)
            runs.removeAt(k)
        }
        return runs.mapIndexed { i, run ->
            // A segment ends where the next begins, so the list has no gaps.
            val end = runs.getOrNull(i + 1)?.first()?.t ?: run.last().t
            val activity = run.groupingBy { it.a }.eachCount().maxByOrNull { it.value }!!.key
            var meters = 0.0
            for (j in 1 until run.size) meters += MotionPolicy.distanceMeters(run[j - 1].lat, run[j - 1].lon, run[j].lat, run[j].lon)
            HistorySegment(activity, run.first().t, end, if (LocationHistory.moving(activity)) meters else 0.0, run)
        }
    }

    /** Still and unknown count as one "staying put" group; each way of moving is its own. */
    private fun group(a: Activity) = if (LocationHistory.moving(a)) a else Activity.STILL
}
