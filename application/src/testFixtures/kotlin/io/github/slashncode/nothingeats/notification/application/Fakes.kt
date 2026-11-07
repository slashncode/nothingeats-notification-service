package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Mail
import io.github.slashncode.nothingeats.notification.domain.Template
import java.util.concurrent.CopyOnWriteArrayList

class RecordingMailSender : MailSender {
    val sent = CopyOnWriteArrayList<Mail>()
    var failWith: Exception? = null

    override suspend fun send(mail: Mail) {
        failWith?.let { throw it }
        sent += mail
    }
}

class RecordingDeliveries : Deliveries {
    val recorded = CopyOnWriteArrayList<Triple<String, Template, String>>()

    override suspend fun record(
        eventId: String,
        template: Template,
        recipientDomain: String,
    ) {
        recorded += Triple(eventId, template, recipientDomain)
    }
}

class InMemoryPushSubscriptions : PushSubscriptions {
    val rows = CopyOnWriteArrayList<PushSubscription>()

    override suspend fun save(
        subscription: PushSubscription,
        keep: Int,
    ) {
        rows.removeIf { it.endpoint == subscription.endpoint }
        rows += subscription
        val newest = rows.filter { it.userId == subscription.userId }.sortedByDescending { it.createdAt }.take(keep)
        rows.removeIf { it.userId == subscription.userId && it !in newest }
    }

    override suspend fun forUser(userId: String) = rows.filter { it.userId == userId }

    override suspend fun byEndpoint(endpoint: String) = rows.find { it.endpoint == endpoint }

    override suspend fun replace(
        oldEndpoint: String,
        subscription: PushSubscription,
    ) {
        rows.removeIf { it.endpoint == oldEndpoint || it.endpoint == subscription.endpoint }
        rows += subscription
    }

    override suspend fun delete(
        userId: String,
        endpoint: String,
    ) {
        rows.removeIf { it.userId == userId && it.endpoint == endpoint }
    }

    override suspend fun expire(endpoint: String) {
        rows.removeIf { it.endpoint == endpoint }
    }

    override suspend fun deleteAll(userId: String) {
        rows.removeIf { it.userId == userId }
    }
}

class RecordingPushSender : PushSender {
    val sent = CopyOnWriteArrayList<Pair<PushSubscription, PushMessage>>()
    var outcome = PushOutcome.SENT
    var failWith: Exception? = null

    override suspend fun send(
        subscription: PushSubscription,
        message: PushMessage,
    ): PushOutcome {
        failWith?.let { throw it }
        sent += subscription to message
        return outcome
    }
}
