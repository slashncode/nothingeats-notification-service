package io.github.slashncode.nothingeats.notification.app

import io.github.slashncode.nothingeats.notification.adapters.MailpitTestServer
import io.github.slashncode.nothingeats.platform.core.run.RunMode
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.github.slashncode.nothingeats.platform.valkey.ValkeyMessage
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun event(
    type: String,
    subject: String,
    data: JsonObject,
) = CloudEvent(
    id = UUID.randomUUID().toString(),
    source = "/test",
    type = type,
    subject = subject,
    time = "2026-10-26T12:00:00Z",
    data = data,
)

private fun Producer<String, ByteArray>.publish(
    topic: String,
    event: CloudEvent,
) {
    send(ProducerRecord(topic, event.subject, EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray())).get()
}

/** Orders and deliveries in; mails in Mailpit and live updates on Valkey out — through the real composition root. */
class OrderNotificationsFlowTest :
    FunSpec({
        test("an order is confirmed and its arrival announced, by mail and live; the address book follows identity") {
            KafkaTestCluster.createTopics(
                "identity.user.v1",
                "ordering.order.v1",
                "delivery.delivery.v1",
                "delivery.courier.v1",
                "loyalty.reward.v1",
                "notification-service.dlq",
                "notification-service-live.dlq",
            )
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = freePort()),
                    database = PostgresTestDatabases.create(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    smtp = MailpitTestServer.smtp,
                    valkey = ValkeyTestServer.config(),
                    lifecycle = LifecycleConfig(drainDelay = Duration.ZERO),
                )
            val service = ServiceComponent::class.create(config)
            service.migrations.migrate()
            service.lifecycle.start()
            // A second pod's view of Valkey: what the browser's stream would get.
            val listener = ValkeyPubSub(config.valkey, setOf("live:*"))
            listener.start()
            val received = CopyOnWriteArrayList<ValkeyMessage>()
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch { listener.messages.collect { received += it } }
            try {
                val userId = "user-${UUID.randomUUID()}"
                val email = "ghost-${UUID.randomUUID()}@nothingeats.dev"
                val orderId = UUID.randomUUID().toString()
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(
                        "identity.user.v1",
                        event(
                            "nothingeats.identity.user.registered.v1",
                            userId,
                            buildJsonObject {
                                put("userId", userId)
                                put("email", email)
                                put("locale", "de")
                                put("verificationToken", "token")
                            },
                        ),
                    )
                }
                eventually(20.seconds) { service.recipients.find(userId)?.email shouldBe email }

                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(
                        "ordering.order.v1",
                        event(
                            "nothingeats.ordering.order.placed.v1",
                            orderId,
                            buildJsonObject {
                                put("orderId", orderId)
                                put("userId", userId)
                                put("locale", "de")
                                putJsonObject("restaurant") { put("name", "Phantom Pizza") }
                                putJsonArray("lines") {
                                    addJsonObject {
                                        putJsonObject("name") {
                                            put("de", "Margherita des Nichts")
                                            put("en", "Margherita of Nothing")
                                        }
                                        put("quantity", 2)
                                        put("unitPriceCents", 1090)
                                    }
                                }
                                put("totalCents", 2379)
                                put("calories", 1640)
                                put("estimatedArrivalAt", "2026-10-26T12:03:00Z")
                            },
                        ),
                    )
                    producer.publish(
                        "delivery.courier.v1",
                        event(
                            "nothingeats.delivery.courier.moved.v1",
                            orderId,
                            buildJsonObject {
                                put("orderId", orderId)
                                put("userId", userId)
                                put("latitude", 53.55)
                                put("longitude", 9.97)
                                put("status", "EN_ROUTE")
                                put("at", "2026-10-26T12:02:00Z")
                            },
                        ),
                    )
                    producer.publish(
                        "delivery.delivery.v1",
                        event(
                            "nothingeats.delivery.delivery.status-changed.v1",
                            orderId,
                            buildJsonObject {
                                put("orderId", orderId)
                                put("userId", userId)
                                put("status", "DELIVERED")
                                put("at", "2026-10-26T12:03:00Z")
                                put("estimatedArrivalAt", "2026-10-26T12:03:00Z")
                                put("restaurantName", "Phantom Pizza")
                                putJsonObject("courier") {
                                    put("name", "Nora Nebel")
                                    put("vehicle", "BIKE")
                                }
                            },
                        ),
                    )
                    producer.publish(
                        "loyalty.reward.v1",
                        event(
                            "nothingeats.loyalty.reward.earned.v1",
                            userId,
                            buildJsonObject {
                                put("userId", userId)
                                put("cause", "ORDER_DELIVERED")
                                put("orderId", orderId)
                                putJsonObject("xp") {
                                    put("gained", 63)
                                    put("level", 1)
                                }
                            },
                        ),
                    )
                }

                // The verification mail, the confirmation and the arrival.
                val mails = eventually(30.seconds) { MailpitTestServer.messagesTo(email).also { it shouldHaveSize 3 } }
                val confirmation = mails.single { it.subject.startsWith("Bestellt bei Phantom Pizza") }
                // Money is formatted with a non-breaking space before the euro sign, as it should be.
                MailpitTestServer.text(confirmation.id).replace('\u00A0', ' ').let {
                    it shouldContain "2 × Margherita des Nichts – 21,80 €"
                    it shouldContain "Gespart: 23,79 €"
                    // 12:03 UTC is 13:03 in Berlin.
                    it shouldContain "Ankunft gegen 13:03 Uhr"
                    it shouldContain "/orders/$orderId"
                }
                mails.single { it.subject == "Dein Nichts ist da" } shouldNotBe null

                eventually(10.seconds) {
                    val mine = received.filter { it.channel == "live:$userId" }.map { it.message }
                    mine.count { it.contains("\"type\":\"order.updated\"") } shouldBe 2
                    mine.count { it.contains("\"type\":\"courier.moved\"") } shouldBe 1
                    mine.single { it.contains("\"type\":\"reward.earned\"") } shouldContain "\"gained\":63"
                }

                // An account older than the address book: the replay brings it back from the topic.
                service.recipients.delete(userId)
                ServiceComponent::class.create(config).runner.run(RunMode.Cron("replay-identity"))
                service.recipients.find(userId)?.email shouldBe email

                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(
                        "identity.user.v1",
                        event("nothingeats.identity.user.deleted.v1", userId, buildJsonObject { put("userId", userId) }),
                    )
                }
                eventually(20.seconds) { service.recipients.find(userId) shouldBe null }
            } finally {
                scope.cancel()
                listener.stop()
                service.lifecycle.stop()
            }
        }
    })
