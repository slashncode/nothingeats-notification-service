package io.github.slashncode.nothingeats.notification.adapters.mail

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.notification.application.MailSender
import io.github.slashncode.nothingeats.notification.domain.Mail
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

data class SmtpConfig(
    /** Local default: Mailpit in the k3d cluster, as seen from the host. */
    val host: String = "localhost",
    val port: Int = 11025,
    val from: String = "NothingEats <hello@nothingeats.localhost>",
    val username: String? = null,
    val password: Secret? = null,
    val startTls: Boolean = false,
    val timeoutMillis: Int = 10_000,
)

/** Sends multipart (text + HTML) mails over SMTP: Mailpit locally, any SMTP relay elsewhere. */
class SmtpMailSender(
    private val config: SmtpConfig,
) : MailSender {
    private val session: Session =
        Session.getInstance(
            Properties().apply {
                put("mail.smtp.host", config.host)
                put("mail.smtp.port", config.port.toString())
                put("mail.smtp.auth", (config.username != null).toString())
                put("mail.smtp.starttls.enable", config.startTls.toString())
                put("mail.smtp.starttls.required", config.startTls.toString())
                put("mail.smtp.connectiontimeout", config.timeoutMillis.toString())
                put("mail.smtp.timeout", config.timeoutMillis.toString())
                put("mail.smtp.writetimeout", config.timeoutMillis.toString())
            },
            config.username?.let { user ->
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(user, config.password?.value.orEmpty())
                }
            },
        )

    override suspend fun send(mail: Mail) {
        withContext(Dispatchers.IO) {
            val message =
                MimeMessage(session).apply {
                    setFrom(InternetAddress(config.from))
                    setRecipient(Message.RecipientType.TO, InternetAddress(mail.to))
                    setSubject(mail.subject, Charsets.UTF_8.name())
                    setContent(
                        MimeMultipart("alternative").apply {
                            addBodyPart(MimeBodyPart().apply { setText(mail.text, Charsets.UTF_8.name()) })
                            addBodyPart(MimeBodyPart().apply { setContent(mail.html, "text/html; charset=UTF-8") })
                        },
                    )
                }
            Transport.send(message)
        }
    }
}
