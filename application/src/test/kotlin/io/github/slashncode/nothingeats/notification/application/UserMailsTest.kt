package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.notification.domain.Template
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class UserMailsTest :
    FunSpec({
        val composer = MailComposer(Links("http://nothingeats.localhost"))

        test("verification mails link to the web app with the encoded token, in the user's language") {
            val de = composer.verifyEmail("ghost@nothingeats.dev", Language.DE, "a+b/c=")
            val en = composer.verifyEmail("ghost@nothingeats.dev", Language.EN, "a+b/c=")

            de.subject shouldBe "Bitte bestätige deine E-Mail-Adresse"
            en.subject shouldBe "Confirm your email address"
            de.text shouldContain "http://nothingeats.localhost/verify?token=a%2Bb%2Fc%3D"
            de.html shouldContain "href=\"http://nothingeats.localhost/verify?token=a%2Bb%2Fc%3D\""
        }

        test("HTML output escapes what goes into it") {
            val mail = MailComposer(Links("http://evil.example/\"><script>")).welcome("a@b.de", Language.EN)

            mail.html shouldNotContain "<script>"
            mail.html shouldContain "&lt;script&gt;"
        }

        test("each mail is sent, logged by domain only, and counted for analytics") {
            val sender = RecordingMailSender()
            val deliveries = RecordingDeliveries()
            val analytics = RecordingAnalytics()
            val mails = UserMails(composer, sender, deliveries, analytics)

            mails.verifyEmail("event-1", "user-1", "ghost@nothingeats.dev", Language.EN, "token")
            mails.registrationAttempted("event-2", "user-1", "ghost@nothingeats.dev", Language.DE)

            sender.sent.map { it.subject } shouldBe
                listOf("Confirm your email address", "Jemand wollte sich mit deiner Adresse registrieren")
            deliveries.recorded.first() shouldBe Triple("event-1", Template.VERIFY_EMAIL, "nothingeats.dev")
            analytics.events.map { it.name to it.properties["template"] } shouldBe
                listOf("email_sent" to "verify_email", "email_sent" to "registration_attempted")
            analytics.events.first().distinctId shouldBe "user-1"
        }
    })
