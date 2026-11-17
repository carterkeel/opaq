package app.quietmap.core

import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Filter
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.nostr.RelayTransport
import app.quietmap.core.nostr.SocksProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-memory relay shared by several engines, so protocol tests can run whole conversations
 * (join, rotate, go offline, come back) without a network.
 *
 * It behaves like a NIP-01 relay: it stores events, keeps only the newest addressable event per
 * (pubkey, kind, d tag), drops expired events, replays what it stores when a subscription starts,
 * then sends EOSE. Nothing is delivered until [flush] runs, so tests decide exactly when messages
 * arrive. Clients can go [Client.offline] to model a phone with no signal.
 */
class FakeRelayNetwork(private val clock: () -> Long) {
    private val stored = mutableListOf<Event>()
    private val queue = ArrayDeque<Pending>()
    private val clients = mutableListOf<Client>()

    /** Every event any client published, including rejected or replaced ones, for assertions. */
    val published = mutableListOf<Event>()

    private sealed interface Pending {
        class Deliver(val client: Client, val event: Event) : Pending
        class Eose(val client: Client, val subId: String, val filters: List<Filter>) : Pending
    }

    fun client(onEvent: suspend (String, Event) -> Unit): Client = Client(onEvent).also { clients += it }

    /** Delivers everything queued, including anything published while delivering. */
    suspend fun flush() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(++guard < 100_000) { "message loop" }
            when (val p = queue.removeFirst()) {
                is Pending.Deliver -> if (p.client.online && !p.client.drop(p.event)) p.client.onEvent(RELAY, p.event)
                is Pending.Eose -> if (p.client.online && p.client.subs[p.subId] == p.filters) p.client.synced += p.subId
            }
        }
    }

    /** Events currently stored that match [filter] (what a fresh subscriber would receive). */
    fun query(filter: Filter): List<Event> = live().filter { matches(filter, it) }

    private fun live(): List<Event> {
        val t = clock()
        stored.removeAll { e -> e.tag("expiration")?.toLongOrNull()?.let { it < t } == true }
        return stored.toList()
    }

    private fun accept(event: Event) {
        published += event
        if (!event.isValid()) return
        if (event.kind in 30000..39999) {
            val d = event.tag("d") ?: ""
            val existing = stored.firstOrNull { it.kind == event.kind && it.pubkey == event.pubkey && (it.tag("d") ?: "") == d }
            if (existing != null) {
                if (existing.createdAt > event.createdAt || (existing.createdAt == event.createdAt && existing.id <= event.id)) return
                stored.remove(existing)
            }
        }
        if (stored.any { it.id == event.id }) return
        stored += event
        clients.filter { it.online }.forEach { c ->
            if (c.subs.values.any { fs -> fs.any { matches(it, event) } }) queue.addLast(Pending.Deliver(c, event))
        }
    }

    inner class Client(val onEvent: suspend (String, Event) -> Unit) : RelayTransport {
        var online = true
            private set
        var subs: Map<String, List<Filter>> = emptyMap()
            private set
        val synced = mutableSetOf<String>()

        /** Events this client never receives, to model a relay that loses or withholds messages. */
        var drop: (Event) -> Boolean = { false }
        private val outbox = mutableListOf<Event>()
        private val _status = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
        override val status: StateFlow<Map<String, RelayStatus>> = _status

        override suspend fun setRelays(urls: List<String>) {
            _status.value = urls.associateWith { if (online) RelayStatus.CONNECTED else RelayStatus.DISCONNECTED }
        }

        override suspend fun setSubscriptions(subs: Map<String, List<Filter>>) {
            val old = this.subs
            this.subs = subs
            synced.retainAll { id -> subs[id] != null && subs[id] == old[id] }
            if (online) subs.forEach { (id, filters) -> if (old[id] != filters) replay(id, filters) }
        }

        /** Everything this client has published, in order. */
        val published = mutableListOf<Event>()

        override suspend fun publish(event: Event, queueIfOffline: Boolean) {
            published += event
            if (online) accept(event) else if (queueIfOffline) outbox += event
        }

        override fun isSynced(subscriptionId: String): Boolean = online && subscriptionId in synced

        override fun shutdown() = Unit

        /** The SOCKS proxy the engine asked for (Tor), or null for direct connections. */
        var proxy: SocksProxy? = null
            private set

        override suspend fun setSocksProxy(proxy: SocksProxy?) {
            this.proxy = proxy
        }

        /** No signal: nothing in or out until [reconnect]. Queued publishes wait, like the real pool. */
        fun offline() {
            online = false
            synced.clear()
            _status.value = _status.value.mapValues { RelayStatus.DISCONNECTED }
        }

        fun reconnect() {
            online = true
            _status.value = _status.value.mapValues { RelayStatus.CONNECTED }
            outbox.toList().also { outbox.clear() }.forEach { accept(it) }
            subs.forEach { (id, filters) -> replay(id, filters) }
        }

        private fun replay(id: String, filters: List<Filter>) {
            live().filter { e -> filters.any { matches(it, e) } }.forEach { queue.addLast(Pending.Deliver(this, it)) }
            queue.addLast(Pending.Eose(this, id, filters))
        }
    }

    private fun matches(f: Filter, e: Event): Boolean {
        if (e.kind !in f.kinds) return false
        if (f.since != null && e.createdAt < f.since) return false
        if (f.tagD.isNotEmpty() && e.tags.none { it.size >= 2 && it[0] == "d" && it[1] in f.tagD }) return false
        if (f.tagP.isNotEmpty() && e.tags.none { it.size >= 2 && it[0] == "p" && it[1] in f.tagP }) return false
        return true
    }

    private companion object {
        const val RELAY = "wss://fake.relay"
    }
}
