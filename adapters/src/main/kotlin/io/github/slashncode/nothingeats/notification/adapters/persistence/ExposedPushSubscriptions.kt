package io.github.slashncode.nothingeats.notification.adapters.persistence

import io.github.slashncode.nothingeats.notification.application.PushSubscription
import io.github.slashncode.nothingeats.notification.application.PushSubscriptions
import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

internal object PushSubscriptionsTable : Table("push_subscriptions") {
    val endpoint = text("endpoint")
    val userId = text("user_id")
    val p256dh = text("p256dh")
    val auth = text("auth")
    val language = text("language")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(endpoint)
}

class ExposedPushSubscriptions(
    private val db: PostgresDatabase,
) : PushSubscriptions {
    override suspend fun save(
        subscription: PushSubscription,
        keep: Int,
    ) {
        db.transaction {
            PushSubscriptionsTable.upsert {
                it[endpoint] = subscription.endpoint
                it[userId] = subscription.userId
                it[p256dh] = subscription.p256dh
                it[auth] = subscription.auth
                it[language] = subscription.language.tag
                it[createdAt] = subscription.createdAt
            }
            val newest =
                PushSubscriptionsTable
                    .selectAll()
                    .where { PushSubscriptionsTable.userId eq subscription.userId }
                    .orderBy(PushSubscriptionsTable.createdAt, SortOrder.DESC)
                    .limit(keep)
                    .map { it[PushSubscriptionsTable.endpoint] }
            PushSubscriptionsTable.deleteWhere {
                (PushSubscriptionsTable.userId eq subscription.userId) and (PushSubscriptionsTable.endpoint notInList newest)
            }
        }
    }

    override suspend fun forUser(userId: String): List<PushSubscription> =
        db.transaction(readOnly = true) {
            PushSubscriptionsTable
                .selectAll()
                .where { PushSubscriptionsTable.userId eq userId }
                .orderBy(PushSubscriptionsTable.createdAt, SortOrder.DESC)
                .map { it.toSubscription() }
        }

    override suspend fun delete(
        userId: String,
        endpoint: String,
    ) {
        db.transaction {
            PushSubscriptionsTable.deleteWhere {
                (PushSubscriptionsTable.userId eq userId) and (PushSubscriptionsTable.endpoint eq endpoint)
            }
        }
    }

    override suspend fun expire(endpoint: String) {
        db.transaction { PushSubscriptionsTable.deleteWhere { PushSubscriptionsTable.endpoint eq endpoint } }
    }

    override suspend fun deleteAll(userId: String) {
        db.transaction { PushSubscriptionsTable.deleteWhere { PushSubscriptionsTable.userId eq userId } }
    }

    private fun ResultRow.toSubscription() =
        PushSubscription(
            endpoint = this[PushSubscriptionsTable.endpoint],
            p256dh = this[PushSubscriptionsTable.p256dh],
            auth = this[PushSubscriptionsTable.auth],
            userId = this[PushSubscriptionsTable.userId],
            language = Language.parse(this[PushSubscriptionsTable.language]),
            createdAt = this[PushSubscriptionsTable.createdAt],
        )
}
