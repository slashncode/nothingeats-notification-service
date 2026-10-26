package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.notification.domain.Template
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

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

        test("password mails: reset link with the encoded token, and a way out after a change") {
            val reset = composer.passwordReset("ghost@nothingeats.dev", Language.DE, "a+b")
            val changed = composer.passwordChanged("ghost@nothingeats.dev", Language.EN)

            reset.subject shouldBe "Neues Passwort für NothingEats"
            reset.text shouldContain "http://nothingeats.localhost/reset-password?token=a%2Bb"
            changed.subject shouldBe "Your password was changed"
            changed.text shouldContain "http://nothingeats.localhost/forgot-password"
            composer.magicLink("ghost@nothingeats.dev", Language.DE, "x/y").text shouldContain
                "http://nothingeats.localhost/magic-login?token=x%2Fy"
        }

        test("every template has its own text in both languages") {
            val subjects =
                Template.entries.flatMap { template ->
                    Language.entries.map { language ->
                        when (template) {
                            Template.VERIFY_EMAIL -> composer.verifyEmail("a@b.de", language, "t")
                            Template.REGISTRATION_ATTEMPTED -> composer.registrationAttempted("a@b.de", language)
                            Template.WELCOME -> composer.welcome("a@b.de", language)
                            Template.PASSWORD_RESET -> composer.passwordReset("a@b.de", language, "t")
                            Template.PASSWORD_CHANGED -> composer.passwordChanged("a@b.de", language)
                            Template.MAGIC_LINK -> composer.magicLink("a@b.de", language, "t")
                            Template.ORDER_PLACED ->
                                composer.orderPlaced(
                                    "a@b.de",
                                    language,
                                    PlacedOrder(
                                        "o-1",
                                        "u-1",
                                        language,
                                        "Phantom Pizza",
                                        listOf(OrderedLine("Pizza", 1, 1090)),
                                        1289,
                                        820,
                                        Instant.EPOCH,
                                    ),
                                )
                            Template.ORDER_DELIVERED -> composer.orderDelivered("a@b.de", language, "o-1", "Phantom Pizza")
                        }.subject
                    }
                }
            subjects.toSet().size shouldBe subjects.size
        }
    })
