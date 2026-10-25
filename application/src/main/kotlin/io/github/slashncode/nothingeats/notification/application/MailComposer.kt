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

    fun passwordReset(
        to: String,
        language: Language,
        token: String,
    ) = compose(
        to,
        Template.PASSWORD_RESET,
        language,
        "${links.webBaseUrl}/reset-password?token=${URLEncoder.encode(token, Charsets.UTF_8)}",
    )

    /** Ends with a way out for the owner if someone else changed it. */
    fun passwordChanged(
        to: String,
        language: Language,
    ) = compose(to, Template.PASSWORD_CHANGED, language, "${links.webBaseUrl}/forgot-password")

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

    // Exhaustive per template, so a new template cannot fall back to another mail's text.
    private fun copy(
        template: Template,
        language: Language,
    ): Copy {
        val de = language == Language.DE
        return when (template) {
            Template.VERIFY_EMAIL ->
                if (de) {
                    Copy(
                        "Bitte bestätige deine E-Mail-Adresse",
                        "Fast geschafft!",
                        "Bestätige deine E-Mail-Adresse, dann kannst du bei NothingEats alles bestellen – und nichts essen. Der Link gilt 24 Stunden.",
                        "E-Mail-Adresse bestätigen",
                    )
                } else {
                    Copy(
                        "Confirm your email address",
                        "Almost there!",
                        "Confirm your email address and start ordering everything on NothingEats – and eating nothing. The link is valid for 24 hours.",
                        "Confirm email address",
                    )
                }
            Template.REGISTRATION_ATTEMPTED ->
                if (de) {
                    Copy(
                        "Jemand wollte sich mit deiner Adresse registrieren",
                        "Du hast schon ein Konto",
                        "Gerade wollte sich jemand mit deiner E-Mail-Adresse neu registrieren. Warst du das? Dann melde dich einfach an. Wenn nicht, kannst du diese Mail ignorieren.",
                        "Anmelden",
                    )
                } else {
                    Copy(
                        "Someone tried to sign up with your address",
                        "You already have an account",
                        "Someone just tried to create a new account with your email address. Was it you? Then simply log in. If not, you can ignore this mail.",
                        "Log in",
                    )
                }
            Template.WELCOME ->
                if (de) {
                    Copy(
                        "Willkommen bei NothingEats",
                        "Willkommen!",
                        "Deine Adresse ist bestätigt. Bestell alles, worauf du Lust hast – geliefert wird nichts, gegessen auch nicht.",
                        "Jetzt stöbern",
                    )
                } else {
                    Copy(
                        "Welcome to NothingEats",
                        "Welcome!",
                        "Your address is confirmed. Order everything you crave – nothing gets delivered, nothing gets eaten.",
                        "Start browsing",
                    )
                }
            Template.PASSWORD_RESET ->
                if (de) {
                    Copy(
                        "Neues Passwort für NothingEats",
                        "Passwort vergessen?",
                        "Kein Problem: Über den Link legst du ein neues Passwort fest. Er gilt eine Stunde. Wenn du kein neues Passwort angefordert hast, ignoriere diese Mail einfach – dein Passwort bleibt, wie es ist.",
                        "Neues Passwort festlegen",
                    )
                } else {
                    Copy(
                        "A new password for NothingEats",
                        "Forgot your password?",
                        "No problem: use the link to choose a new password. It is valid for one hour. If you didn't ask for a new password, just ignore this mail – your password stays as it is.",
                        "Choose a new password",
                    )
                }
            Template.PASSWORD_CHANGED ->
                if (de) {
                    Copy(
                        "Dein Passwort wurde geändert",
                        "Passwort geändert",
                        "Das Passwort deines NothingEats-Kontos wurde gerade geändert, und alle Geräte wurden abgemeldet. Warst du das nicht? Dann setz dein Passwort sofort zurück.",
                        "Passwort zurücksetzen",
                    )
                } else {
                    Copy(
                        "Your password was changed",
                        "Password changed",
                        "The password of your NothingEats account was just changed, and every device was logged out. Wasn't you? Then reset your password right away.",
                        "Reset password",
                    )
                }
        }
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
