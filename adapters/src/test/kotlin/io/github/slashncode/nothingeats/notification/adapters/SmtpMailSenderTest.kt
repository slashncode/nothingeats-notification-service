package io.github.slashncode.nothingeats.notification.adapters

import io.github.slashncode.nothingeats.notification.adapters.mail.SmtpMailSender
import io.github.slashncode.nothingeats.notification.application.Links
import io.github.slashncode.nothingeats.notification.application.MailComposer
import io.github.slashncode.nothingeats.notification.domain.Language
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SmtpMailSenderTest :
    FunSpec({
        test("sends a multipart mail with text and HTML through SMTP") {
            val to = "ghost-${UUID.randomUUID()}@nothingeats.dev"
            val mail = MailComposer(Links()).verifyEmail(to, Language.DE, "token-123")

            SmtpMailSender(MailpitTestServer.smtp).send(mail)

            val received = eventually(10.seconds) { MailpitTestServer.messagesTo(to).also { it shouldHaveSize 1 } }.single()
            received.subject shouldBe "Bitte bestätige deine E-Mail-Adresse"
            MailpitTestServer.text(received.id) shouldContain "verify?token=token-123"
            MailpitTestServer.html(received.id) shouldContain "E-Mail-Adresse bestätigen"
        }
    })
