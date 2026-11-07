package io.github.slashncode.nothingeats.notification.adapters.push

import io.github.slashncode.nothingeats.notification.application.Pushes
import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI

@Serializable
internal data class PushKeyResponse(
    val publicKey: String,
)

@Serializable
internal data class PushKeysRequest(
    val p256dh: String,
    val auth: String,
)

/** `PushSubscription.toJSON()` from the browser, plus the language on screen. */
@Serializable
internal data class PushSubscriptionRequest(
    val endpoint: String,
    val keys: PushKeysRequest,
    val language: String? = null,
)

/** From the service worker's `pushsubscriptionchange`: the old endpoint and the new subscription. */
@Serializable
internal data class PushRotationRequest(
    val oldEndpoint: String,
    val endpoint: String,
    val keys: PushKeysRequest,
)

/** One of the user's devices: which push service, not the endpoint (a capability URL). */
@Serializable
internal data class PushSubscriptionResponse(
    val service: String,
    val language: String,
    val createdAt: String,
)

@Serializable
internal data class PushTestResponse(
    val sent: Int,
    val gone: Int,
    val failed: Int,
)

@Serializable
internal data class PushSubscriptionsResponse(
    val items: List<PushSubscriptionResponse>,
)

/**
 * `/api/notification/v1/push`: the VAPID public key for `pushManager.subscribe` (public; 404
 * `PUSH_DISABLED` without keys), and the signed-in user's subscriptions: list (also for the
 * GDPR download), save, remove (`?endpoint=`, on switching off and on logout), and `POST /test`:
 * a push to every device now, with how each went. `POST /subscriptions/rotation` is public: the
 * service worker has no session, and the old endpoint proves the browser (see [Pushes.rotate]).
 * It answers 204 whether or not the old endpoint was known, so it tells a caller nothing.
 */
class PushRoutes(
    private val pushes: Pushes,
    private val publicKey: String?,
) : HttpRoutes {
    private val log = LoggerFactory.getLogger(PushRoutes::class.java)

    override fun Route.register() {
        route("/api/notification/v1/push") {
            public {
                get("/key") {
                    val key = publicKey ?: throw AppError.NotFound(ErrorCode("PUSH_DISABLED"), "Push notifications are not set up")
                    call.respond(PushKeyResponse(key))
                }
                post("/subscriptions/rotation") {
                    val request = call.receive<PushRotationRequest>()
                    val moved = pushes.rotate(request.oldEndpoint, request.endpoint, request.keys.p256dh, request.keys.auth)
                    log.info(
                        "Push subscription rotation at {}: {}",
                        URI(request.endpoint).host,
                        if (moved) "moved" else "old endpoint unknown",
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            authenticated {
                get("/subscriptions") {
                    val items =
                        pushes.list(call.user.id).map {
                            PushSubscriptionResponse(URI(it.endpoint).host, it.language.tag, it.createdAt.toString())
                        }
                    call.respond(PushSubscriptionsResponse(items))
                }
                put("/subscriptions") {
                    val request = call.receive<PushSubscriptionRequest>()
                    pushes.subscribe(
                        call.user.id,
                        request.endpoint,
                        request.keys.p256dh,
                        request.keys.auth,
                        Language.parse(request.language),
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/test") {
                    val result = pushes.test(call.user.id)
                    call.respond(PushTestResponse(result.sent, result.gone, result.failed))
                }
                delete("/subscriptions") {
                    val endpoint =
                        call.request.queryParameters["endpoint"]
                            ?: throw AppError.Invalid(
                                ErrorCode("PUSH_SUBSCRIPTION_INVALID"),
                                "The push subscription is invalid",
                                listOf(Violation("endpoint", "is required")),
                            )
                    pushes.unsubscribe(call.user.id, endpoint)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
