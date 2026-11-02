package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/** A browser's push subscription (Push API). It belongs to whoever was signed in when it was made. */
data class PushSubscription(
    /** A capability URL at the browser's push service: with the keys, it reaches this browser. */
    val endpoint: String,
    /** The browser's P-256 public key, base64url (65 bytes, uncompressed). */
    val p256dh: String,
    /** The browser's 16-byte auth secret, base64url. */
    val auth: String,
    val userId: String,
    /** The language on screen when the browser subscribed; its pushes use it. */
    val language: Language,
    val createdAt: Instant,
)

interface PushSubscriptions {
    /**
     * Saves by endpoint: a browser that subscribes again, or for another account, replaces its row.
     * Keeps only the user's newest [keep] subscriptions.
     */
    suspend fun save(
        subscription: PushSubscription,
        keep: Int,
    )

    suspend fun forUser(userId: String): List<PushSubscription>

    /** Removes the user's subscription at [endpoint]; somebody else's stays. */
    suspend fun delete(
        userId: String,
        endpoint: String,
    )

    /** The push service no longer knows [endpoint] (404 or 410). */
    suspend fun expire(endpoint: String)

    suspend fun deleteAll(userId: String)
}

/** What the service worker shows. A newer push with the same [tag] replaces the older notification. */
data class PushMessage(
    val title: String,
    val body: String,
    /** A path in the shop, opened when the notification is clicked. */
    val url: String,
    val tag: String,
    /** Shown even while the shop is open (the test push); others leave that to the live stream. */
    val always: Boolean = false,
)

/** What a test push found: devices the push service took it for, forgot, or refused. */
data class PushTestResult(
    val sent: Int,
    val gone: Int,
    val failed: Int,
)

enum class PushOutcome { SENT, GONE, FAILED }

interface PushSender {
    suspend fun send(
        subscription: PushSubscription,
        message: PushMessage,
    ): PushOutcome
}

/**
 * Where a subscription may point: push services only. The endpoint comes from the browser, and
 * this service sends a request to it, so anything else would let a caller aim it at the cluster.
 * Origins are `scheme://host[:port]`; `*.` matches subdomains (Windows' push service has many).
 */
class PushEndpoints(
    allowedOrigins: List<String>,
) {
    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
        val subdomains: Boolean,
    )

    private val origins =
        allowedOrigins.map { origin ->
            val (scheme, rest) = origin.lowercase().trim().split("://", limit = 2)
            val hostAndPort = rest.trimEnd('/')
            val host = hostAndPort.substringBefore(':')
            val port = hostAndPort.substringAfter(':', "").toIntOrNull() ?: -1
            Origin(scheme, host.removePrefix("*."), port, host.startsWith("*."))
        }

    fun allowed(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.rawUserInfo != null) return false
        return origins.any {
            it.scheme == scheme &&
                it.port == uri.port &&
                if (it.subdomains) host.endsWith(".${it.host}") else host == it.host
        }
    }

    companion object {
        /** Chrome, Edge, Firefox and Safari (Opera, Brave and Samsung Internet use Google's). */
        val KNOWN =
            listOf(
                "https://fcm.googleapis.com",
                "https://updates.push.services.mozilla.com",
                "https://web.push.apple.com",
                "https://*.notify.windows.com",
            )
    }
}

/** A badge from a reward, named in both languages. */
data class BadgeName(
    val de: String,
    val en: String,
)

/** What a reward brought that is worth a notification. */
data class EarnedReward(
    val orderId: String?,
    val level: Int,
    val previousLevel: Int,
    val spinsGranted: Int,
    val badges: List<BadgeName>,
)

/**
 * Web push: subscriptions of signed-in browsers, and notifications for the moments that matter
 * when the shop is not open: the courier leaves, nothing arrives, a level or a badge. A push is
 * best effort: a failure is logged and never fails the event it came from (that would resend mails).
 */
class Pushes(
    private val subscriptions: PushSubscriptions,
    private val sender: PushSender,
    private val endpoints: PushEndpoints,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(Pushes::class.java)

    suspend fun subscribe(
        userId: String,
        endpoint: String,
        p256dh: String,
        auth: String,
        language: Language,
    ) {
        val violations =
            buildList {
                if (!endpoints.allowed(endpoint)) add(Violation("endpoint", "must be a push service's https URL"))
                if (base64Url(p256dh)?.let { it.size == P256_KEY_BYTES && it[0] == UNCOMPRESSED } != true) {
                    add(Violation("keys.p256dh", "must be an uncompressed P-256 public key"))
                }
                if (base64Url(auth)?.size != AUTH_BYTES) add(Violation("keys.auth", "must be 16 bytes"))
            }
        if (violations.isNotEmpty()) {
            throw AppError.Invalid(ErrorCode("PUSH_SUBSCRIPTION_INVALID"), "The push subscription is invalid", violations)
        }
        subscriptions.save(PushSubscription(endpoint, p256dh, auth, userId, language, clock.instant()), MAX_PER_USER)
    }

    suspend fun unsubscribe(
        userId: String,
        endpoint: String,
    ) = subscriptions.delete(userId, endpoint)

    suspend fun list(userId: String): List<PushSubscription> = subscriptions.forUser(userId)

    /** A push to every device of the user right now, shown even with the shop open; says how each went. */
    suspend fun test(userId: String): PushTestResult {
        val outcomes =
            subscriptions.forUser(userId).map { subscription ->
                sender.send(subscription, PushTexts.test(subscription.language)).also {
                    if (it == PushOutcome.GONE) subscriptions.expire(subscription.endpoint)
                }
            }
        return PushTestResult(
            sent = outcomes.count { it == PushOutcome.SENT },
            gone = outcomes.count { it == PushOutcome.GONE },
            failed = outcomes.count { it == PushOutcome.FAILED },
        )
    }

    suspend fun deliveryChanged(
        userId: String,
        orderId: String,
        status: String,
        courierName: String,
        restaurantName: String,
    ) = push(userId) { PushTexts.delivery(it, orderId, status, courierName, restaurantName) }

    suspend fun rewardEarned(
        userId: String,
        reward: EarnedReward,
    ) = push(userId) { PushTexts.reward(it, reward) }

    private suspend fun push(
        userId: String,
        compose: (Language) -> PushMessage?,
    ) {
        try {
            for (subscription in subscriptions.forUser(userId)) {
                val message = compose(subscription.language) ?: return
                if (sender.send(subscription, message) == PushOutcome.GONE) subscriptions.expire(subscription.endpoint)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Push for an event failed; the event is handled anyway", e)
        }
    }

    private fun base64Url(value: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(value.trimEnd('=')) }.getOrNull()

    private companion object {
        /** More than a few devices per person is a script, not a person. */
        const val MAX_PER_USER = 10
        const val P256_KEY_BYTES = 65
        const val UNCOMPRESSED: Byte = 0x04
        const val AUTH_BYTES = 16
    }
}

/** The notifications in German and English. Paths point into the shop, never at an API. */
internal object PushTexts {
    fun test(language: Language) =
        if (language == Language.DE) {
            PushMessage(
                "So sieht NothingEats auf diesem Gerät aus",
                "Hier meldet sich der Shop, wenn dein Kurier losfährt und wenn nichts angekommen ist.",
                "/account",
                "test",
                always = true,
            )
        } else {
            PushMessage(
                "This is NothingEats on this device",
                "The shop says hello here when your courier leaves and when nothing has arrived.",
                "/account",
                "test",
                always = true,
            )
        }

    fun delivery(
        language: Language,
        orderId: String,
        status: String,
        courierName: String,
        restaurantName: String,
    ): PushMessage? {
        val de = language == Language.DE
        val (title, body) =
            when (status) {
                "EN_ROUTE" ->
                    if (de) {
                        "Unterwegs zu dir" to "$courierName bringt dir nichts von $restaurantName."
                    } else {
                        "On its way" to "$courierName is bringing you nothing from $restaurantName."
                    }
                "DELIVERED" ->
                    if (de) {
                        "Nichts ist angekommen" to "Deine Bestellung von $restaurantName ist da. Guten Appetit!"
                    } else {
                        "Nothing has arrived" to "Your order from $restaurantName is here. Enjoy!"
                    }
                else -> return null
            }
        return PushMessage(title, body, "/orders/$orderId", "order-$orderId")
    }

    fun reward(
        language: Language,
        reward: EarnedReward,
    ): PushMessage? {
        val de = language == Language.DE
        val badges = reward.badges.map { if (de) it.de else it.en }
        val (title, body) =
            when {
                reward.level > reward.previousLevel -> {
                    val spin = reward.spinsGranted > 0
                    if (de) {
                        "Level ${reward.level}!" to if (spin) "Ein Dreh am Glücksrad wartet auf dich." else "Weiter so, mit nichts."
                    } else {
                        "Level ${reward.level}!" to if (spin) "A spin of the wheel is waiting for you." else "Keep going, with nothing."
                    }
                }
                badges.isNotEmpty() ->
                    if (de) {
                        (if (badges.size == 1) "Neues Abzeichen" else "${badges.size} neue Abzeichen") to badges.joinToString(", ")
                    } else {
                        (if (badges.size == 1) "New badge" else "${badges.size} new badges") to badges.joinToString(", ")
                    }
                else -> return null
            }
        return PushMessage(title, body, "/account/rewards", "reward-${reward.orderId ?: "wheel"}")
    }
}
