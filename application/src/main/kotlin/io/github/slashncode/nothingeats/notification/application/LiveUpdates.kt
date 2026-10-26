package io.github.slashncode.nothingeats.notification.application

/**
 * Something for one user's open browser tabs right now: an order status, a courier position.
 * Live updates are not stored. A tab that was closed or reconnects loads the current state from
 * the owning service; the stream only saves it from polling.
 */
data class LiveUpdate(
    val userId: String,
    /** `order.updated`, `courier.moved`, … ; the browser dispatches on it. */
    val type: String,
    /** A JSON object, passed through as is. */
    val payloadJson: String,
)

interface LiveUpdates {
    suspend fun publish(update: LiveUpdate)
}
