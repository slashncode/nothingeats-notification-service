package io.github.slashncode.nothingeats.notification.adapters.push

import com.interaso.webpush.VapidKeys
import com.interaso.webpush.WebPush
import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.notification.application.PushEndpoints
import io.github.slashncode.nothingeats.notification.application.PushMessage
import io.github.slashncode.nothingeats.notification.application.PushOutcome
import io.github.slashncode.nothingeats.notification.application.PushSender
import io.github.slashncode.nothingeats.notification.application.PushSubscription
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Web push. `PUSH__PUBLIC_KEY` / `PUSH__PRIVATE_KEY` are the VAPID key pair as browsers use it
 * (base64url, uncompressed P-256); without them push is off and the shop hides the switch.
 */
data class PushConfig(
    /** Who push services contact about our pushes (VAPID `sub`). */
    val subject: String = "mailto:push@nothingeats.dev",
    val publicKey: String = "",
    val privateKey: Secret = Secret(""),
    /** Where subscriptions may point (see [PushEndpoints]); tests add a local fake. */
    val allowedOrigins: List<String> = PushEndpoints.KNOWN,
    /** How long a push service keeps a push for a device that is offline. */
    val ttl: Duration = 1.hours,
) {
    val enabled: Boolean get() = publicKey.isNotBlank() && privateKey.value.isNotBlank()
}

/** The JSON the service worker reads (`src/sw.ts` in nothingeats-web). */
@Serializable
internal data class PushPayload(
    val title: String,
    val body: String,
    val url: String,
    val tag: String,
    /** Left out unless true (the test push). */
    val always: Boolean = false,
)

/**
 * Encrypts (RFC 8291) and signs (VAPID, RFC 8292) with `com.interaso:webpush`, sends with the
 * JDK's client. Logs the push service's host only: the endpoint's path is a capability.
 */
class WebPushSender(
    private val config: PushConfig,
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT.toJavaDuration())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
) : PushSender {
    private val log = LoggerFactory.getLogger(WebPushSender::class.java)
    private val webPush = WebPush(config.subject, VapidKeys.fromUncompressedBytes(config.publicKey, config.privateKey.value))

    override suspend fun send(
        subscription: PushSubscription,
        message: PushMessage,
    ): PushOutcome {
        val host = URI(subscription.endpoint).host
        val payload =
            Json.encodeToString(
                PushPayload.serializer(),
                PushPayload(message.title, message.body, message.url, message.tag, message.always),
            )
        val body = webPush.getBody(payload.toByteArray(), base64Url(subscription.p256dh), base64Url(subscription.auth))
        val request =
            HttpRequest
                .newBuilder(URI(subscription.endpoint))
                .timeout(REQUEST_TIMEOUT.toJavaDuration())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .apply {
                    webPush
                        .getHeaders(subscription.endpoint, config.ttl.inWholeSeconds.toInt(), null, WebPush.Urgency.Normal)
                        .filterKeys { it.lowercase() !in RESTRICTED }
                        .forEach { (name, value) -> header(name, value) }
                }.build()
        return try {
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val status = response.statusCode()
            // The push service's own words, for the log: short, on one line, never the endpoint.
            val reason = { response.body().replace(Regex("\\s+"), " ").take(REASON_LENGTH) }
            when (status) {
                in SUCCESS -> PushOutcome.SENT
                NOT_FOUND, GONE -> {
                    log.info("Push service {} no longer knows a subscription ({}: {}); it is removed", host, status, reason())
                    PushOutcome.GONE
                }
                else -> {
                    log.warn("Push service {} refused a push ({}: {})", host, status, reason())
                    PushOutcome.FAILED
                }
            }
        } catch (e: IOException) {
            log.warn("Push service {} not reachable: {}", host, e.message)
            PushOutcome.FAILED
        }
    }

    private fun base64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trimEnd('='))

    private companion object {
        val CONNECT_TIMEOUT = 5.seconds
        val REQUEST_TIMEOUT = 10.seconds
        val SUCCESS = 200..299
        const val NOT_FOUND = 404
        const val GONE = 410
        const val REASON_LENGTH = 300

        /** Set by the JDK client itself; it refuses them from callers. */
        val RESTRICTED = setOf("content-length", "host", "connection")
    }
}

/** Push without VAPID keys: nothing is sent, nothing fails. */
class NoPushSender : PushSender {
    override suspend fun send(
        subscription: PushSubscription,
        message: PushMessage,
    ) = PushOutcome.FAILED
}
