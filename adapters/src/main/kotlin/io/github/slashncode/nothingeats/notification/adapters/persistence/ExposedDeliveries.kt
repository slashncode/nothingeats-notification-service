package io.github.slashncode.nothingeats.notification.adapters.persistence

import io.github.slashncode.nothingeats.notification.application.Deliveries
import io.github.slashncode.nothingeats.notification.domain.Template
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Clock
import kotlin.uuid.Uuid

internal object DeliveriesTable : Table("deliveries") {
    val id = uuid("id")
    val eventId = text("event_id")
    val template = text("template")
    val recipientDomain = text("recipient_domain")
    val sentAt = timestamp("sent_at")

    override val primaryKey = PrimaryKey(id)
}

class ExposedDeliveries(
    private val db: PostgresDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : Deliveries {
    override suspend fun record(
        eventId: String,
        template: Template,
        recipientDomain: String,
    ) {
        db.transaction {
            DeliveriesTable.insert {
                it[id] = Uuid.random()
                it[DeliveriesTable.eventId] = eventId
                it[DeliveriesTable.template] = template.key
                it[DeliveriesTable.recipientDomain] = recipientDomain
                it[sentAt] = clock.instant()
            }
        }
    }
}
