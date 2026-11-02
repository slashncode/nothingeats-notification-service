package io.github.slashncode.nothingeats.notification.application

import io.github.slashncode.nothingeats.notification.domain.Language
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

private val p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0x04) + ByteArray(64) { 1 })
private val auth = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 2 })
private const val FCM = "https://fcm.googleapis.com/fcm/send/abc"

class PushesTest :
    FunSpec({
        fun pushes(
            subscriptions: PushSubscriptions = InMemoryPushSubscriptions(),
            sender: PushSender = RecordingPushSender(),
        ) = Pushes(
            subscriptions,
            sender,
            PushEndpoints(PushEndpoints.KNOWN),
            Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC),
        )

        test("only push services' https endpoints are accepted, Windows' subdomains included") {
            val endpoints = PushEndpoints(PushEndpoints.KNOWN)

            endpoints.allowed(FCM) shouldBe true
            endpoints.allowed("https://updates.push.services.mozilla.com/wpush/v2/x") shouldBe true
            endpoints.allowed("https://web.push.apple.com/QGz") shouldBe true
            endpoints.allowed("https://wns2-am3p.notify.windows.com/w/?token=x") shouldBe true
            endpoints.allowed("https://notify.windows.com/w/") shouldBe false
            endpoints.allowed("http://fcm.googleapis.com/fcm/send/abc") shouldBe false
            endpoints.allowed("https://fcm.googleapis.com:8443/fcm/send/abc") shouldBe false
            endpoints.allowed("https://user@fcm.googleapis.com/fcm/send/abc") shouldBe false
            endpoints.allowed("https://fcm.googleapis.com.evil.example/x") shouldBe false
            endpoints.allowed("http://identity-service/api/identity/v1/admin/users") shouldBe false
            endpoints.allowed("not a url") shouldBe false
        }

        test("a subscription needs a known endpoint and real keys; a browser keeps one row, a user ten") {
            val subscriptions = InMemoryPushSubscriptions()
            val pushes = pushes(subscriptions)

            val refused = shouldThrow<AppError.Invalid> { pushes.subscribe("ghost", "http://valkey:6379/", "AAAA", "short", Language.DE) }
            refused.code.value shouldBe "PUSH_SUBSCRIPTION_INVALID"
            refused.violations.map { it.field } shouldContainExactly listOf("endpoint", "keys.p256dh", "keys.auth")

            pushes.subscribe("ghost", FCM, p256dh, auth, Language.DE)
            pushes.subscribe("other", FCM, p256dh, auth, Language.EN)
            pushes.list("ghost").shouldBeEmpty()
            pushes.list("other").single().language shouldBe Language.EN

            repeat(12) { pushes.subscribe("other", "$FCM-$it", p256dh, auth, Language.EN) }
            pushes.list("other") shouldHaveSize 10
        }

        test("the courier leaving and nothing arriving are pushed in each device's language; other steps are not") {
            val subscriptions = InMemoryPushSubscriptions()
            val sender = RecordingPushSender()
            val pushes = pushes(subscriptions, sender)
            pushes.subscribe("ghost", FCM, p256dh, auth, Language.DE)
            pushes.subscribe("ghost", "$FCM-laptop", p256dh, auth, Language.EN)

            pushes.deliveryChanged("ghost", "order-1", "COOKING", "Kalle", "Phantom Pizza")
            sender.sent.shouldBeEmpty()

            pushes.deliveryChanged("ghost", "order-1", "EN_ROUTE", "Kalle", "Phantom Pizza")
            pushes.deliveryChanged("ghost", "order-1", "DELIVERED", "Kalle", "Phantom Pizza")

            sender.sent.map { (subscription, message) -> subscription.language to message.title } shouldContainExactlyInAnyOrder
                listOf(
                    Language.EN to "On its way",
                    Language.DE to "Unterwegs zu dir",
                    Language.EN to "Nothing has arrived",
                    Language.DE to "Nichts ist angekommen",
                )
            sender.sent.map { it.second } shouldContain
                PushMessage(
                    "Nichts ist angekommen",
                    "Deine Bestellung von Phantom Pizza ist da. Guten Appetit!",
                    "/orders/order-1",
                    "order-order-1",
                )
        }

        test("a level, else badges, become a push; a reward without either does not") {
            val sender = RecordingPushSender()
            val pushes = pushes(sender = sender)
            pushes.subscribe("ghost", FCM, p256dh, auth, Language.DE)

            pushes.rewardEarned(
                "ghost",
                EarnedReward("order-1", level = 3, previousLevel = 2, spinsGranted = 1, badges = listOf(BadgeName("Stammgast", "Regular"))),
            )
            pushes.rewardEarned(
                "ghost",
                EarnedReward("order-2", level = 3, previousLevel = 3, spinsGranted = 0, badges = listOf(BadgeName("Stammgast", "Regular"))),
            )
            pushes.rewardEarned("ghost", EarnedReward("order-3", level = 3, previousLevel = 3, spinsGranted = 0, badges = emptyList()))

            sender.sent.map { it.second } shouldContainExactly
                listOf(
                    PushMessage("Level 3!", "Ein Dreh am Glücksrad wartet auf dich.", "/account/rewards", "reward-order-1"),
                    PushMessage("Neues Abzeichen", "Stammgast", "/account/rewards", "reward-order-2"),
                )
        }

        test("a test push goes to every device at once, shows with the shop open, and says how each went") {
            val subscriptions = InMemoryPushSubscriptions()
            val sender =
                object : PushSender {
                    val sent = mutableListOf<PushMessage>()

                    override suspend fun send(
                        subscription: PushSubscription,
                        message: PushMessage,
                    ): PushOutcome {
                        sent += message
                        return if (subscription.endpoint.endsWith("forgotten")) PushOutcome.GONE else PushOutcome.SENT
                    }
                }
            val pushes = pushes(subscriptions, sender)
            pushes.subscribe("ghost", FCM, p256dh, auth, Language.DE)
            pushes.subscribe("ghost", "$FCM-forgotten", p256dh, auth, Language.EN)

            pushes.test("ghost") shouldBe PushTestResult(sent = 1, gone = 1, failed = 0)
            sender.sent.map { it.always }.distinct() shouldBe listOf(true)
            pushes.list("ghost").map { it.endpoint } shouldContainExactly listOf(FCM)
            pushes.test("nobody") shouldBe PushTestResult(0, 0, 0)
        }

        test("a device the push service forgot is removed, and a failing push never fails the event") {
            val subscriptions = InMemoryPushSubscriptions()
            val sender = RecordingPushSender()
            val pushes = pushes(subscriptions, sender)
            pushes.subscribe("ghost", FCM, p256dh, auth, Language.DE)

            sender.outcome = PushOutcome.GONE
            pushes.deliveryChanged("ghost", "order-1", "DELIVERED", "Kalle", "Phantom Pizza")
            pushes.list("ghost").shouldBeEmpty()

            pushes.subscribe("ghost", FCM, p256dh, auth, Language.DE)
            sender.failWith = IllegalStateException("push service on fire")
            pushes.deliveryChanged("ghost", "order-1", "DELIVERED", "Kalle", "Phantom Pizza")
            pushes.list("ghost") shouldHaveSize 1
        }
    })
