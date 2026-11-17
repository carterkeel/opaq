package app.quietmap.core.nostr

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.util.concurrent.TimeUnit

/** OkHttp sends the pings itself, and fails the connection if a pong doesn't come back in time. */
internal actual fun HttpClientEngineConfig.relayKeepAlive(intervalMillis: Long) {
    (this as? OkHttpConfig)?.config { pingInterval(intervalMillis, TimeUnit.MILLISECONDS) }
}
