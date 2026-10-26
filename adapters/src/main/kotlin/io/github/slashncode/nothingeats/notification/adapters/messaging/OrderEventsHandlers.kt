package io.github.slashncode.nothingeats.notification.adapters.messaging

import io.github.slashncode.nothingeats.notification.application.LiveUpdate
import io.github.slashncode.nothingeats.notification.application.LiveUpdates
import io.github.slashncode.nothingeats.notification.application.OrderMails
import io.github.slashncode.nothingeats.notification.application.OrderedLine
import io.github.slashncode.nothingeats.notification.application.PlacedOrder
import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.EventPosition
import io.github.slashncode.nothingeats.platform.kafka.PositionedEventHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

// Tolerant readers of ordering-service and delivery-service docs/events.md: only what mails and the stream need.

@Serializable
internal data class NameV1(
    val de: String,
    val en: String,
)

@Serializable
internal data class OrderLineV1(
    val name: NameV1,
    val quantity: Int,
    val unitPriceCents: Long,
)

@Serializable
internal data class OrderRestaurantV1(
    val name: String,
)

@Serializable
internal data class OrderPlacedV1(
    val orderId: String,
    val userId: String,
    val locale: String? = null,
    val restaurant: OrderRestaurantV1,
    val lines: List<OrderLineV1>,
    val totalCents: Long,
    val calories: Int,
    val estimatedArrivalAt: String,
)

/** `ordering.order.v1`: the confirmation mail, and the first live update for open tabs. */
class OrderEventsHandler(
    private val mails: OrderMails,
    private val live: LiveUpdates,
) : EventHandler {
    override val topic = "ordering.order.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != PLACED) return
        val order = event.decode(OrderPlacedV1.serializer())
        val language = Language.parse(order.locale)
        mails.placed(
            event.id,
            PlacedOrder(
                orderId = order.orderId,
                userId = order.userId,
                language = language,
                restaurantName = order.restaurant.name,
                lines =
                    order.lines.map {
                        OrderedLine(
                            if (language ==
                                Language.DE
                            ) {
                                it.name.de
                            } else {
                                it.name.en
                            },
                            it.quantity,
                            it.unitPriceCents * it.quantity,
                        )
                    },
                totalCents = order.totalCents,
                calories = order.calories,
                estimatedArrivalAt = Instant.parse(order.estimatedArrivalAt),
            ),
        )
        live.publish(
            LiveUpdate(
                order.userId,
                ORDER_UPDATED,
                buildJsonObject {
                    put("orderId", order.orderId)
                    put("status", "PLACED")
                    put("at", event.time)
                    put("estimatedArrivalAt", order.estimatedArrivalAt)
                }.toString(),
            ),
        )
    }

    private companion object {
        const val PLACED = "nothingeats.ordering.order.placed.v1"
    }
}

@Serializable
internal data class CourierV1(
    val name: String,
    val vehicle: String,
)

@Serializable
internal data class StatusChangedV1(
    val orderId: String,
    val userId: String,
    val status: String,
    val at: String,
    val estimatedArrivalAt: String,
    val restaurantName: String,
    val courier: CourierV1,
)

/** `delivery.delivery.v1`: every step to the customer's open tabs, and a mail when nothing has arrived. */
class DeliveryEventsHandler(
    private val mails: OrderMails,
    private val live: LiveUpdates,
) : EventHandler {
    override val topic = "delivery.delivery.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != STATUS_CHANGED) return
        val change = event.decode(StatusChangedV1.serializer())
        live.publish(
            LiveUpdate(
                change.userId,
                ORDER_UPDATED,
                buildJsonObject {
                    put("orderId", change.orderId)
                    put("status", change.status)
                    put("at", change.at)
                    put("estimatedArrivalAt", change.estimatedArrivalAt)
                    putJsonObject("courier") {
                        put("name", change.courier.name)
                        put("vehicle", change.courier.vehicle)
                    }
                }.toString(),
            ),
        )
        if (change.status == DELIVERED) mails.delivered(event.id, change.userId, change.orderId, change.restaurantName)
    }

    private companion object {
        const val STATUS_CHANGED = "nothingeats.delivery.delivery.status-changed.v1"
        const val DELIVERED = "DELIVERED"
    }
}

@Serializable
internal data class CourierMovedV1(
    val orderId: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val status: String,
    val at: String,
)

/**
 * `delivery.courier.v1` straight into the stream. Runs in its own consumer group without an inbox:
 * a position every two seconds per delivery is not worth a database row, and a repeated one is harmless.
 */
class CourierEventsHandler(
    private val live: LiveUpdates,
) : PositionedEventHandler {
    override val topic = "delivery.courier.v1"

    override suspend fun handleAt(
        event: CloudEvent,
        position: EventPosition,
    ) {
        if (event.type != COURIER_MOVED) return
        val moved = event.decode(CourierMovedV1.serializer())
        live.publish(
            LiveUpdate(
                moved.userId,
                "courier.moved",
                buildJsonObject {
                    put("orderId", moved.orderId)
                    put("latitude", moved.latitude)
                    put("longitude", moved.longitude)
                    put("status", moved.status)
                    put("at", moved.at)
                }.toString(),
            ),
        )
    }

    private companion object {
        const val COURIER_MOVED = "nothingeats.delivery.courier.moved.v1"
    }
}

private const val ORDER_UPDATED = "order.updated"
