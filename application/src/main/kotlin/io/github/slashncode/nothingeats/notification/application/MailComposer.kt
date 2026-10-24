package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.notification.domain.Mail
import io.github.slashncode.nothingeats.notification.domain.Template
import java.net.URLEncoder

data class Links(
    /** Origin of the web app; mail links point there, never at an API. */
    val webBaseUrl: String = "http://nothingeats.localhost",
)

private data class Copy(
    val subject: String,
    val heading: String,
    val body: String,
    val action: String?,
)

/** Renders the DE/EN mails as plain text plus a small HTML version. */
class MailComposer(
    private val links: Links,
) {
    fun verifyEmail(
        to: String,
        language: Language,
        token: String,
    ) = compose(to, Template.VERIFY_EMAIL, language, "${links.webBaseUrl}/verify?token=${URLEncoder.encode(token, Charsets.UTF_8)}")

    fun registrationAttempted(
        to: String,
        language: Language,
    ) = compose(to, Template.REGISTRATION_ATTEMPTED, language, "${links.webBaseUrl}/login")

    fun welcome(
        to: String,
        language: Language,
    ) = compose(to, Template.WELCOME, language, links.webBaseUrl)

    private fun compose(
        to: String,
        template: Template,
        language: Language,
        link: String,
    ): Mail {
        val copy = copy(template, language)
        val text =
            buildString {
                appendLine(copy.heading)
                appendLine()
                appendLine(copy.body)
                if (copy.action != null) {
                    appendLine()
                    appendLine("${copy.action}: $link")
                }
                appendLine()
                append(SIGNATURE)
            }
        val html =
            """
            <!doctype html>
            <html lang="${language.tag}"><body style="font-family: system-ui, sans-serif; color: #1c1917; max-width: 520px; margin: 0 auto; padding: 24px;">
              <h1 style="font-size: 22px;">${copy.heading.escaped()}</h1>
              <p style="font-size: 16px; line-height: 1.5;">${copy.body.escaped()}</p>
              ${copy.action?.let {
                """<p><a href="${link.escaped()}" style="display: inline-block; background: #ea580c; color: #fff; padding: 12px 20px; border-radius: 8px; text-decoration: none;">${it.escaped()}</a></p>"""
            } ?: ""}
              <p style="color: #78716c; font-size: 13px;">${SIGNATURE.escaped()}</p>
            </body></html>
            """.trimIndent()
        return Mail(to = to, subject = copy.subject, text = text, html = html)
    }

    private fun copy(
        template: Template,
        language: Language,
    ): Copy =
        when (template to language) {
            Template.VERIFY_EMAIL to Language.DE ->
                Copy(
                    "Bitte bestätige deine E-Mail-Adresse",
                    "Fast geschafft!",
                    "Bestätige deine E-Mail-Adresse, dann kannst du bei NothingEats alles bestellen – und nichts essen. Der Link gilt 24 Stunden.",
                    "E-Mail-Adresse bestätigen",
                )
            Template.VERIFY_EMAIL to Language.EN ->
                Copy(
                    "Confirm your email address",
                    "Almost there!",
                    "Confirm your email address and start ordering everything on NothingEats – and eating nothing. The link is valid for 24 hours.",
                    "Confirm email address",
                )
            Template.REGISTRATION_ATTEMPTED to Language.DE ->
                Copy(
                    "Jemand wollte sich mit deiner Adresse registrieren",
                    "Du hast schon ein Konto",
                    "Gerade wollte sich jemand mit deiner E-Mail-Adresse neu registrieren. Warst du das? Dann melde dich einfach an. Wenn nicht, kannst du diese Mail ignorieren.",
                    "Anmelden",
                )
            Template.REGISTRATION_ATTEMPTED to Language.EN ->
                Copy(
                    "Someone tried to sign up with your address",
                    "You already have an account",
                    "Someone just tried to create a new account with your email address. Was it you? Then simply log in. If not, you can ignore this mail.",
                    "Log in",
                )
            Template.WELCOME to Language.DE ->
                Copy(
                    "Willkommen bei NothingEats",
                    "Willkommen!",
                    "Deine Adresse ist bestätigt. Bestell alles, worauf du Lust hast – geliefert wird nichts, gegessen auch nicht.",
                    "Jetzt stöbern",
                )
            else ->
                Copy(
                    "Welcome to NothingEats",
                    "Welcome!",
                    "Your address is confirmed. Order everything you crave – nothing gets delivered, nothing gets eaten.",
                    "Start browsing",
                )
        }

    private companion object {
        const val SIGNATURE = "NothingEats – Order everything. Eat nothing."
    }
}

private fun String.escaped() =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
