package app.quietmap.core.nostr

import io.ktor.client.engine.HttpClientEngineConfig

/** Darwin sessions get Ktor's own pinger from the WebSockets plugin, so nothing to add here. */
internal actual fun HttpClientEngineConfig.relayKeepAlive(intervalMillis: Long) = Unit
