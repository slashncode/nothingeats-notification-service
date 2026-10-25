package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.notification.domain.Mail
import io.github.slashncode.nothingeats.notification.domain.Template
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics

interface MailSender {
    suspend fun send(mail: Mail)
}

interface Deliveries {
    suspend fun record(
        eventId: String,
        template: Template,
        recipientDomain: String,
    )
}

/**
 * The mails that follow from identity events. Called inside the consumer's transaction: if sending
 * fails the event is retried, so a mail is sent at least once; the inbox stops repeats after success.
 */
class UserMails(
    private val composer: MailComposer,
    private val sender: MailSender,
    private val deliveries: Deliveries,
    private val analytics: ProductAnalytics,
) {
    suspend fun verifyEmail(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        token: String,
    ) = send(eventId, userId, Template.VERIFY_EMAIL, composer.verifyEmail(email, language, token))

    suspend fun registrationAttempted(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
    ) = send(eventId, userId, Template.REGISTRATION_ATTEMPTED, composer.registrationAttempted(email, language))

    suspend fun welcome(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
    ) = send(eventId, userId, Template.WELCOME, composer.welcome(email, language))

    suspend fun passwordReset(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        token: String,
    ) = send(eventId, userId, Template.PASSWORD_RESET, composer.passwordReset(email, language, token))

    suspend fun magicLink(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        token: String,
    ) = send(eventId, userId, Template.MAGIC_LINK, composer.magicLink(email, language, token))

    suspend fun passwordChanged(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
    ) = send(eventId, userId, Template.PASSWORD_CHANGED, composer.passwordChanged(email, language))

    private suspend fun send(
        eventId: String,
        userId: String,
        template: Template,
        mail: Mail,
    ) {
        sender.send(mail)
        deliveries.record(eventId, template, mail.recipientDomain)
        analytics.capture("email_sent", mapOf("template" to template.key), distinctId = userId)
    }
}
