package io.github.slashncode.nothingeats.notification.adapters.persistence

import io.github.slashncode.nothingeats.notification.application.Recipient
import io.github.slashncode.nothingeats.notification.application.Recipients
import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock

internal object RecipientsTable : Table("recipients") {
    val userId = text("user_id")
    val email = text("email")
    val language = text("language")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

class ExposedRecipients(
    private val db: PostgresDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : Recipients {
    override suspend fun upsert(recipient: Recipient) {
        db.transaction {
            RecipientsTable.upsert {
                it[userId] = recipient.userId
                it[email] = recipient.email
                it[language] = recipient.language.tag
                it[updatedAt] = clock.instant()
            }
        }
    }

    override suspend fun find(userId: String): Recipient? =
        db.transaction(readOnly = true) {
            RecipientsTable.selectAll().where { RecipientsTable.userId eq userId }.singleOrNull()?.let {
                Recipient(it[RecipientsTable.userId], it[RecipientsTable.email], Language.parse(it[RecipientsTable.language]))
            }
        }

    override suspend fun delete(userId: String) {
        db.transaction { RecipientsTable.deleteWhere { RecipientsTable.userId eq userId } }
    }
}
