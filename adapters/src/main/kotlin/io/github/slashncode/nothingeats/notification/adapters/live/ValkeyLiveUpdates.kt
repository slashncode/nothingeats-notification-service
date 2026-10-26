package io.github.slashncode.nothingeats.notification.adapters.live

import io.github.slashncode.nothingeats.notification.application.LiveUpdate
import io.github.slashncode.nothingeats.notification.application.LiveUpdates
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The envelope on a user's channel; the SSE route turns it into `event: <type>` / `data: <data>`. */
@Serializable
internal data class LiveEnvelope(
    val type: String,
    val data: JsonObject,
)

internal val LiveJson = Json { ignoreUnknownKeys = true }

internal fun liveChannel(userId: String) = "$LIVE_PREFIX$userId"

internal const val LIVE_PREFIX = "live:"

/**
 * Publishes to the user's channel on Valkey, so the pod that holds the user's stream gets it,
 * whichever pod consumed the event. Nobody listening is fine: the update is simply gone.
 */
class ValkeyLiveUpdates(
    private val pubSub: ValkeyPubSub,
) : LiveUpdates {
    override suspend fun publish(update: LiveUpdate) {
        val envelope = LiveEnvelope(update.type, LiveJson.parseToJsonElement(update.payloadJson) as JsonObject)
        pubSub.publish(liveChannel(update.userId), LiveJson.encodeToString(LiveEnvelope.serializer(), envelope))
    }

    companion object {
        /** The pattern every notification pod subscribes to. */
        const val PATTERN = "$LIVE_PREFIX*"
    }
}
