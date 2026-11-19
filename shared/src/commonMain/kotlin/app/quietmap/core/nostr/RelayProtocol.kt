package app.quietmap.core.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val NostrJson = Json { ignoreUnknownKeys = true }

/** A REQ filter. Only the fields Opaq needs. */
data class Filter(
    val kinds: List<Int>,
    val tagD: List<String> = emptyList(),
    val tagP: List<String> = emptyList(),
    val since: Long? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("kinds") { kinds.forEach { add(JsonPrimitive(it)) } }
        if (tagD.isNotEmpty()) putJsonArray("#d") { tagD.forEach { add(JsonPrimitive(it)) } }
        if (tagP.isNotEmpty()) putJsonArray("#p") { tagP.forEach { add(JsonPrimitive(it)) } }
        if (since != null) put("since", since)
    }
}

object ClientMessages {
    fun event(event: Event): String = NostrJson.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.encodeToJsonElement(Event.serializer(), event))
        },
    )

    fun req(subscriptionId: String, filters: List<Filter>): String = NostrJson.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            filters.forEach { add(it.toJson()) }
        },
    )

    fun close(subscriptionId: String): String = """["CLOSE","$subscriptionId"]"""
}

sealed interface RelayMessage {
    data class EventMsg(val subscriptionId: String, val event: Event) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class Eose(val subscriptionId: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage

    companion object {
        fun parse(text: String): RelayMessage? = try {
            val arr = NostrJson.parseToJsonElement(text).jsonArray
            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> EventMsg(arr[1].jsonPrimitive.content, NostrJson.decodeFromJsonElement(Event.serializer(), arr[2]))
                "OK" -> Ok(arr[1].jsonPrimitive.content, arr[2].jsonPrimitive.content == "true", arr.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: "")
                "EOSE" -> Eose(arr[1].jsonPrimitive.content)
                "NOTICE" -> Notice(arr[1].jsonPrimitive.content)
                "CLOSED" -> Closed(arr[1].jsonPrimitive.content, arr.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: "")
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

/** Which relay addresses the app will connect to. */
object RelayUrls {
    /**
     * Plain ws:// to this machine or the Android emulator's host, for local relay testing. Only
     * debug builds turn this on; release builds connect to remote relays over TLS only.
     */
    var allowLocalPlaintext: Boolean = false

    private val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2")

    /** Normalises a typed address: trims it and drops a trailing slash. Null if it isn't allowed. */
    fun clean(url: String): String? = url.trim().removeSuffix("/").takeIf { isAllowed(it) }

    fun isAllowed(url: String): Boolean {
        val u = url.trim()
        if (u.any { it.isWhitespace() || it == '#' }) return false
        val (scheme, rest) = when {
            u.startsWith("wss://") -> "wss" to u.removePrefix("wss://")
            u.startsWith("ws://") -> "ws" to u.removePrefix("ws://")
            else -> return false
        }
        val host = rest.substringBefore('/').substringBefore('?').let { h -> if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':') }
        if (host.isEmpty() || host.contains('@')) return false
        return scheme == "wss" || (allowLocalPlaintext && host.lowercase() in localHosts)
    }
}
