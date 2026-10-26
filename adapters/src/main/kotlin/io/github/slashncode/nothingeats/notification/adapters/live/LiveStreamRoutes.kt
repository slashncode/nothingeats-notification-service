package io.github.slashncode.nothingeats.notification.adapters.live

import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.SerializationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server-sent events plugin, installed on the platform's Ktor application. */
class SseExtension : HttpExtension {
    override fun io.ktor.server.application.Application.install() {
        install(SSE)
    }
}

/**
 * `GET /api/notification/v1/stream`: the signed-in user's live updates as server-sent events.
 * The token comes in the Authorization header (the web app reads the stream with fetch, not
 * EventSource, for that reason). A comment every [heartbeat] keeps proxies from closing it.
 */
class LiveStreamRoutes(
    private val pubSub: ValkeyPubSub,
    private val heartbeat: Duration = 15.seconds,
) : HttpRoutes {
    override fun Route.register() {
        authenticated {
            sse("/api/notification/v1/stream") {
                val channel = liveChannel(call.user.id)
                val updates =
                    pubSub.messages
                        // "ready" only once this stream listens: whatever is published after it arrives.
                        .onSubscription { send(ServerSentEvent(event = "ready", data = "{}", retry = RETRY_MILLIS)) }
                        .filter { it.channel == channel }
                        .mapNotNull { message ->
                            try {
                                val envelope = LiveJson.decodeFromString(LiveEnvelope.serializer(), message.message)
                                ServerSentEvent(event = envelope.type, data = envelope.data.toString())
                            } catch (e: SerializationException) {
                                null
                            }
                        }
                val pings =
                    flow {
                        while (true) {
                            delay(heartbeat)
                            emit(Unit)
                        }
                    }.map { ServerSentEvent(comments = "ping") }
                // One collector, so events never interleave mid-write; ends when the browser goes away.
                merge(updates, pings).collect { send(it) }
            }
        }
    }

    private companion object {
        /** Browsers reconnect after this long when the stream drops. */
        const val RETRY_MILLIS = 3_000L
    }
}
