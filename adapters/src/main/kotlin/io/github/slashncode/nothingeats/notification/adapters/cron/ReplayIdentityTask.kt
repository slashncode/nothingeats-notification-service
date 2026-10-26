package io.github.slashncode.nothingeats.notification.adapters.cron

import io.github.slashncode.nothingeats.notification.adapters.messaging.RecipientProjection
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors

/**
 * `cron replay-identity`: rebuilds the recipients projection from what `identity.user.v1` still
 * holds (seven days), reading without a consumer group, so the live consumer is not disturbed.
 * For accounts that existed before the projection; applying an event twice changes nothing.
 */
class ReplayIdentityTask(
    private val kafka: KafkaConfig,
    private val projection: RecipientProjection,
    private val topic: String = "identity.user.v1",
) : CronTask {
    override val name = "replay-identity"

    private val log = LoggerFactory.getLogger(ReplayIdentityTask::class.java)

    override suspend fun run() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { thread ->
            withContext(thread) {
                consumer().use { consumer ->
                    val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
                    consumer.assign(partitions)
                    consumer.seekToBeginning(partitions)
                    val end = consumer.endOffsets(partitions)
                    var applied = 0
                    while (partitions.any { consumer.position(it) < end.getValue(it) }) {
                        for (record in consumer.poll(Duration.ofSeconds(1))) {
                            val value = record.value() ?: continue
                            projection.apply(EventJson.decodeFromString(CloudEvent.serializer(), value.decodeToString()))
                            applied++
                        }
                    }
                    log.info("Replayed {} identity events into the recipients projection", applied)
                }
            }
        }
    }

    private fun consumer() =
        KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.CLIENT_ID_CONFIG to "notification-service-replay",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
            ),
            StringDeserializer(),
            ByteArrayDeserializer(),
        )
}
