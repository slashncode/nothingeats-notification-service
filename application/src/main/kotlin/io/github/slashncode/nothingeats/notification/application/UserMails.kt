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
        analytics: Boolean,
    ) = send(eventId, userId, Template.VERIFY_EMAIL, composer.verifyEmail(email, language, token), analytics)

    suspend fun registrationAttempted(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        analytics: Boolean,
    ) = send(eventId, userId, Template.REGISTRATION_ATTEMPTED, composer.registrationAttempted(email, language), analytics)

    suspend fun welcome(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        analytics: Boolean,
    ) = send(eventId, userId, Template.WELCOME, composer.welcome(email, language), analytics)

    suspend fun passwordReset(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        token: String,
        analytics: Boolean,
    ) = send(eventId, userId, Template.PASSWORD_RESET, composer.passwordReset(email, language, token), analytics)

    suspend fun magicLink(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        token: String,
        analytics: Boolean,
    ) = send(eventId, userId, Template.MAGIC_LINK, composer.magicLink(email, language, token), analytics)

    suspend fun passwordChanged(
        eventId: String,
        userId: String,
        email: String,
        language: Language,
        analytics: Boolean,
    ) = send(eventId, userId, Template.PASSWORD_CHANGED, composer.passwordChanged(email, language), analytics)

    private suspend fun send(
        eventId: String,
        userId: String,
        template: Template,
        mail: Mail,
        consented: Boolean,
    ) {
        sender.send(mail)
        deliveries.record(eventId, template, mail.recipientDomain)
        // Only for people who agreed to analytics (the event says whether they had).
        if (consented) analytics.capture("email_sent", mapOf("template" to template.key), distinctId = userId)
    }
}
