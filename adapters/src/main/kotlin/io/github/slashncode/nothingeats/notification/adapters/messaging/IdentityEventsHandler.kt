package io.github.slashncode.nothingeats.notification.adapters.messaging

import io.github.slashncode.nothingeats.notification.application.UserMails
import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Tolerant reader of the identity service's events (contract: identity-service docs/events.md):
 * only the fields this service needs, unknown fields and unknown event types are ignored.
 */
@Serializable
internal data class IdentityUserEvent(
    val userId: String,
    val email: String,
    val locale: String? = null,
    val verificationToken: String? = null,
)

class IdentityEventsHandler(
    private val mails: UserMails,
) : EventHandler {
    override val topic = "identity.user.v1"

    private val log = LoggerFactory.getLogger(IdentityEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        val type = event.type
        if (type !in HANDLED) {
            log.debug("Ignoring {}", type)
            return
        }
        val user = event.decode(IdentityUserEvent.serializer())
        val language = Language.parse(user.locale)
        when (type) {
            REGISTERED ->
                mails.verifyEmail(
                    event.id,
                    user.userId,
                    user.email,
                    language,
                    requireNotNull(user.verificationToken) { "registered event without token" },
                )
            REGISTRATION_ATTEMPTED -> mails.registrationAttempted(event.id, user.userId, user.email, language)
            VERIFIED -> mails.welcome(event.id, user.userId, user.email, language)
        }
    }

    private companion object {
        const val REGISTERED = "nothingeats.identity.user.registered.v1"
        const val REGISTRATION_ATTEMPTED = "nothingeats.identity.user.registration-attempted.v1"
        const val VERIFIED = "nothingeats.identity.user.verified.v1"
        val HANDLED = setOf(REGISTERED, REGISTRATION_ATTEMPTED, VERIFIED)
    }
}
