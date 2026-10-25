package io.github.slashncode.nothingeats.notification.domain

enum class Language(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        fun parse(tag: String?): Language = entries.firstOrNull { it.tag == tag?.trim()?.lowercase()?.take(2) } ?: DE
    }
}

/** Every mail NothingEats sends; the name is what deliveries and analytics refer to. */
enum class Template(
    val key: String,
) {
    VERIFY_EMAIL("verify_email"),
    REGISTRATION_ATTEMPTED("registration_attempted"),
    WELCOME("welcome"),
    PASSWORD_RESET("password_reset"),
    PASSWORD_CHANGED("password_changed"),
    MAGIC_LINK("magic_link"),
}

data class Mail(
    val to: String,
    val subject: String,
    val text: String,
    val html: String,
) {
    init {
        require(to.contains('@')) { "Not a mail address" }
    }

    /** For logs and analytics: the domain only, never the full address. */
    val recipientDomain: String get() = to.substringAfterLast('@')
}
