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
