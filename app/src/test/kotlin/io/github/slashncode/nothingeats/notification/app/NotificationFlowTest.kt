package io.github.slashncode.nothingeats.notification.app

import io.github.slashncode.nothingeats.notification.adapters.MailpitTestServer
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.kafka.clients.producer.ProducerRecord
import java.net.ServerSocket
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TOPIC = "identity.user.v1"

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun identityEvent(
    id: String,
    type: String,
    email: String,
    token: String? = null,
) = CloudEvent(
    id = id,
    source = "/nothingeats/identity",
    type = type,
    subject = "user-1",
    time = "2026-10-24T12:00:00Z",
    data =
        buildJsonObject {
            put("userId", "user-1")
            put("email", email)
            put("locale", "en")
            token?.let { put("verificationToken", it) }
            put("somethingNew", "ignored by tolerant readers")
        },
)

/** Identity events on Kafka in, mails in Mailpit out — through the real composition root. */
class NotificationFlowTest :
    FunSpec({
        test("registration and verification events become mails, duplicates are sent once") {
            KafkaTestCluster.createTopics(TOPIC, "notification-service.dlq")
            val service =
                ServiceComponent::class.create(
                    ServiceConfig(
                        http = HttpServerConfig(port = freePort()),
                        database = PostgresTestDatabases.create(),
                        kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                        smtp = MailpitTestServer.smtp,
                        lifecycle = LifecycleConfig(drainDelay = Duration.ZERO),
                    ),
                )
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val email = "ghost-${UUID.randomUUID()}@nothingeats.dev"
                val registered =
                    identityEvent(UUID.randomUUID().toString(), "nothingeats.identity.user.registered.v1", email, "secret-token")
                KafkaClients.producer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test").use { producer ->
                    val json = EventJson.encodeToString(CloudEvent.serializer(), registered)
                    producer.send(ProducerRecord(TOPIC, "user-1", json.toByteArray())).get()
                    producer.send(ProducerRecord(TOPIC, "user-1", json.toByteArray())).get()
                }

                val verification = eventually(20.seconds) { MailpitTestServer.messagesTo(email).also { it shouldHaveSize 1 } }.single()
                MailpitTestServer.text(verification.id) shouldContain "/verify?token=secret-token"

                KafkaClients.producer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test").use { producer ->
                    val verified = identityEvent(UUID.randomUUID().toString(), "nothingeats.identity.user.verified.v1", email)
                    producer
                        .send(
                            ProducerRecord(TOPIC, "user-1", EventJson.encodeToString(CloudEvent.serializer(), verified).toByteArray()),
                        ).get()
                }
                eventually(20.seconds) { MailpitTestServer.messagesTo(email) shouldHaveSize 2 }
                delay(1.seconds)
                MailpitTestServer.messagesTo(email) shouldHaveSize 2
            } finally {
                service.lifecycle.stop()
            }
        }
    })
