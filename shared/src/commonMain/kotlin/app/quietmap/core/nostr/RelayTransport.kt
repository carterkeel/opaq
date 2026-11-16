package app.quietmap.core.nostr

import kotlinx.coroutines.flow.StateFlow

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * How the engine talks to relays. [RelayPool] is the real one; tests use an in-memory network so
 * several engines can exchange events without a network.
 */
interface RelayTransport {
    val status: StateFlow<Map<String, RelayStatus>>
    suspend fun setRelays(urls: List<String>)

    /** Replaces all subscriptions. Keys are subscription ids. */
    suspend fun setSubscriptions(subs: Map<String, List<Filter>>)

    /**
     * Sends an event to every relay. With [queueIfOffline] false, the event is dropped for relays
     * that aren't connected right now rather than sent later (used for location updates, which must
     * never go out late under a key that may have been replaced in the meantime).
     */
    suspend fun publish(event: Event, queueIfOffline: Boolean = true)

    /**
     * True once at least one connected relay has finished sending what it stores for subscription
     * [subscriptionId] (EOSE) since that relay connected and since the subscription last changed.
     */
    fun isSynced(subscriptionId: String): Boolean

    fun shutdown()
}
