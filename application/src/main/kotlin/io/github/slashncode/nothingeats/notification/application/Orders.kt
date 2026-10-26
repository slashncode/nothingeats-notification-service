package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.notification.domain.Template
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import org.slf4j.LoggerFactory
import java.time.Instant

/** Where order mails go: kept from identity events, erased with the account (`user.deleted`). */
data class Recipient(
    val userId: String,
    val email: String,
    val language: Language,
)

interface Recipients {
    suspend fun upsert(recipient: Recipient)

    suspend fun find(userId: String): Recipient?

    suspend fun delete(userId: String)
}

data class OrderedLine(
    val name: String,
    val quantity: Int,
    val totalCents: Long,
)

/** What the confirmation mail shows, already in the customer's language. */
data class PlacedOrder(
    val orderId: String,
    val userId: String,
    val language: Language,
    val restaurantName: String,
    val lines: List<OrderedLine>,
    val totalCents: Long,
    val calories: Int,
    val estimatedArrivalAt: Instant,
    /** The customer's analytics consent at checkout (from the order event). */
    val analytics: Boolean = false,
)

/**
 * Order mails. The address comes from the recipients projection; a user it doesn't know (an
 * account older than the projection, before `cron replay-identity`) gets no mail, never an error.
 */
class OrderMails(
    private val recipients: Recipients,
    private val composer: MailComposer,
    private val sender: MailSender,
    private val deliveries: Deliveries,
    private val analytics: ProductAnalytics,
) {
    private val log = LoggerFactory.getLogger(OrderMails::class.java)

    suspend fun placed(
        eventId: String,
        order: PlacedOrder,
    ) {
        val recipient = recipients.find(order.userId) ?: return skipped(order.orderId)
        send(eventId, recipient, Template.ORDER_PLACED, composer.orderPlaced(recipient.email, order.language, order), order.analytics)
    }

    suspend fun delivered(
        eventId: String,
        userId: String,
        orderId: String,
        restaurantName: String,
        analytics: Boolean,
    ) {
        val recipient = recipients.find(userId) ?: return skipped(orderId)
        send(
            eventId,
            recipient,
            Template.ORDER_DELIVERED,
            composer.orderDelivered(recipient.email, recipient.language, orderId, restaurantName),
            analytics,
        )
    }

    private suspend fun send(
        eventId: String,
        recipient: Recipient,
        template: Template,
        mail: io.github.slashncode.nothingeats.notification.domain.Mail,
        consented: Boolean,
    ) {
        sender.send(mail)
        deliveries.record(eventId, template, mail.recipientDomain)
        if (consented) analytics.capture("email_sent", mapOf("template" to template.key), distinctId = recipient.userId)
    }

    private fun skipped(orderId: String) = log.info("No address known for the customer of order {}; no mail", orderId)
}
