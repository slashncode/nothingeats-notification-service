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
    val resetToken: String? = null,
    val magicToken: String? = null,
)

class IdentityEventsHandler(
    private val mails: UserMails,
) : EventHandler {
    override val topic = "identity.user.v1"

    private val log = LoggerFactory.getLogger(IdentityEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        val type = event.type
        if (type == DELETED) {
            // Nothing to erase: deliveries keep only the recipient's domain, never the address or the user id.
            log.debug("Account deleted; this service holds no personal data about it")
            return
        }
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
            PASSWORD_RESET_REQUESTED ->
                mails.passwordReset(
                    event.id,
                    user.userId,
                    user.email,
                    language,
                    requireNotNull(user.resetToken) { "password reset event without token" },
                )
            PASSWORD_CHANGED -> mails.passwordChanged(event.id, user.userId, user.email, language)
            MAGIC_LINK_REQUESTED ->
                mails.magicLink(
                    event.id,
                    user.userId,
                    user.email,
                    language,
                    requireNotNull(user.magicToken) { "magic link event without token" },
                )
        }
    }

    private companion object {
        const val REGISTERED = "nothingeats.identity.user.registered.v1"
        const val REGISTRATION_ATTEMPTED = "nothingeats.identity.user.registration-attempted.v1"
        const val VERIFIED = "nothingeats.identity.user.verified.v1"
        const val PASSWORD_RESET_REQUESTED = "nothingeats.identity.user.password-reset-requested.v1"
        const val PASSWORD_CHANGED = "nothingeats.identity.user.password-changed.v1"
        const val MAGIC_LINK_REQUESTED = "nothingeats.identity.user.magic-link-requested.v1"
        const val DELETED = "nothingeats.identity.user.deleted.v1"
        val HANDLED = setOf(REGISTERED, REGISTRATION_ATTEMPTED, VERIFIED, PASSWORD_RESET_REQUESTED, PASSWORD_CHANGED, MAGIC_LINK_REQUESTED)
    }
}
