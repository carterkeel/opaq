package app.opaq.core

import app.opaq.core.crypto.Nip44
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small diagnostic log kept on the phone (#154), so that when a phone stopped sharing, its owner
 * can find out why and choose to send the log to someone.
 *
 * It records events and times only: the app starting, the sharing service starting and stopping
 * and why, permission and battery changes, each publish (sent, skipped and why, or no relay),
 * relays connecting, dropping and refusing, and crashes. Never locations, speeds or distances,
 * keys, invites, PINs, message contents, names or photos: circles are "circle 1", "circle 2" by
 * their place in the list. Every line also goes through [clean], which blanks anything that looks
 * like a coordinate, a key or encoded data, in case a message slips through.
 *
 * Lines are encrypted one by one (NIP-44 under a key derived from the history key, like the
 * location history) and kept in day files named `d-<day>` alongside the history files, for
 * [KEEP_DAYS] days and at most [MAX_LINES_PER_DAY] lines a day.
 */
class DiagnosticLog(private val files: HistoryFiles) {
    private val mutex = Mutex()
    private var counted: Pair<Long, Int>? = null

    suspend fun note(key: ByteArray, at: Long, event: String, maxLength: Int = 300) = mutex.withLock {
        val day = at / 86_400
        val name = "$PREFIX$day"
        val (countedDay, lines) = counted?.takeIf { it.first == day } ?: (day to files.read(name).size)
        if (lines >= MAX_LINES_PER_DAY) { counted = day to lines; return@withLock }
        val text = if (lines == MAX_LINES_PER_DAY - 1) "(too many events today; the rest aren't kept)" else clean(event, maxLength)
        files.append(name, Nip44.encrypt("$at $text", conversationKey(key)))
        counted = countedDay to lines + 1
    }

    /** Everything kept, oldest first, as "2026-11-21 14:03:07Z  event" lines. */
    suspend fun read(key: ByteArray): List<Pair<Long, String>> = mutex.withLock {
        files.list().filter { it.startsWith(PREFIX) }
            .sortedBy { it.removePrefix(PREFIX).toLongOrNull() ?: 0 }
            .flatMap { name ->
                files.read(name).mapNotNull { line ->
                    runCatching { Nip44.decrypt(line, conversationKey(key)) }.getOrNull()?.let { plain ->
                        val at = plain.substringBefore(' ').toLongOrNull() ?: return@let null
                        at to plain.substringAfter(' ')
                    }
                }
            }
    }

    /** Drops day files older than [KEEP_DAYS]. */
    suspend fun prune(now: Long) = mutex.withLock {
        val oldest = now / 86_400 - KEEP_DAYS + 1
        files.list().filter { it.startsWith(PREFIX) }.forEach { name ->
            val day = name.removePrefix(PREFIX).toLongOrNull()
            if (day == null || day < oldest) files.delete(name)
        }
    }

    suspend fun clear() = mutex.withLock {
        files.list().filter { it.startsWith(PREFIX) }.forEach { files.delete(it) }
        counted = null
    }

    private fun conversationKey(key: ByteArray) = Nip44.conversationKeyFromSecret(key, "qm-diagnostics")

    companion object {
        const val PREFIX = "d-"
        const val KEEP_DAYS = 7
        const val MAX_LINES_PER_DAY = 3000

        // A coordinate-like number (3+ decimals), a long hex run (keys, ids, hashes), or a long
        // base64/base64url run (invites, encrypted payloads, photos).
        private val COORDINATE = Regex("""-?\d{1,3}\.\d{3,}""")
        private val HEX = Regex("""\b[0-9a-fA-F]{16,}\b""")
        private val DATA = Regex("""[A-Za-z0-9+/_-]{32,}={0,2}""")

        /** Blanks anything in [text] that could be a location, a key or encoded data, and caps its length. */
        fun clean(text: String, maxLength: Int = 300): String = text
            .replace(HEX, "[hex]")
            .replace(DATA, "[data]")
            .replace(COORDINATE, "[number]")
            .replace('\n', ' ')
            .take(maxLength)

        /** Whether [text] still holds anything [clean] would blank. Tests use it. */
        fun looksSensitive(text: String): Boolean = HEX.containsMatchIn(text) || DATA.containsMatchIn(text) || COORDINATE.containsMatchIn(text)
    }
}
