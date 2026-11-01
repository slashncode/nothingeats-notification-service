package io.github.slashncode.nothingeats.notification.adapters.push

import io.github.slashncode.nothingeats.notification.application.PushMessage
import io.github.slashncode.nothingeats.notification.application.PushOutcome
import io.github.slashncode.nothingeats.notification.application.PushSubscription
import io.github.slashncode.nothingeats.notification.domain.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.net.ServerSocket
import java.time.Instant

/** Against a fake push service, decrypted like a browser would: what the service worker gets. */
class WebPushSenderTest :
    FunSpec({
        val service = FakePushService()
        val browser = TestBrowser()
        val config = testPushConfig(service.origin)
        val sender = WebPushSender(config)
        val message = PushMessage("Nichts ist angekommen", "Deine Bestellung ist da.", "/orders/o-1", "order-o-1")

        fun subscription(endpoint: String) = PushSubscription(endpoint, browser.p256dh, browser.auth, "ghost", Language.DE, Instant.now())

        afterSpec { service.close() }

        test("a push is encrypted for the browser and signed with our VAPID key") {
            sender.send(subscription(service.endpoint("/push/one")), message) shouldBe PushOutcome.SENT

            val push = service.received.single { it.path == "/push/one" }
            push.headers["content-encoding"] shouldBe "aes128gcm"
            push.headers["ttl"] shouldBe "3600"
            push.headers["authorization"] shouldStartWith "vapid t="
            push.headers["authorization"] shouldContain "k=${config.publicKey}"
            browser.decrypt(push.body) shouldBe
                """{"title":"Nichts ist angekommen","body":"Deine Bestellung ist da.","url":"/orders/o-1","tag":"order-o-1"}"""
        }

        test("404 and 410 mean the browser is gone; other answers and dead hosts are failures") {
            service.statusFor["/push/gone"] = 410
            service.statusFor["/push/unknown"] = 404
            service.statusFor["/push/busy"] = 503
            val deadPort = ServerSocket(0).use { it.localPort }

            sender.send(subscription(service.endpoint("/push/gone")), message) shouldBe PushOutcome.GONE
            sender.send(subscription(service.endpoint("/push/unknown")), message) shouldBe PushOutcome.GONE
            sender.send(subscription(service.endpoint("/push/busy")), message) shouldBe PushOutcome.FAILED
            sender.send(subscription("http://127.0.0.1:$deadPort/push"), message) shouldBe PushOutcome.FAILED
        }
    })
