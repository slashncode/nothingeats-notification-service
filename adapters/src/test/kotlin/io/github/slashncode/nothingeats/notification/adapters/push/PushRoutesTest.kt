package io.github.slashncode.nothingeats.notification.adapters.push

import io.github.slashncode.nothingeats.notification.adapters.persistence.ExposedPushSubscriptions
import io.github.slashncode.nothingeats.notification.application.PushEndpoints
import io.github.slashncode.nothingeats.notification.application.Pushes
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

private val tokens = TestTokens()

class PushRoutesTest :
    FunSpec({
        val config = PostgresTestDatabases.create()
        val migrations = FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE))
        lateinit var db: PostgresDatabase
        val service = FakePushService()
        val browser = TestBrowser()

        beforeSpec {
            migrations.migrate()
            db = PostgresDatabase(config, migrations).also { it.start() }
        }
        afterSpec {
            db.stop()
            service.close()
        }

        fun routes(publicKey: String?): PushRoutes =
            PushRoutes(Pushes(ExposedPushSubscriptions(db), NoPushSender(), PushEndpoints(listOf(service.origin))), publicKey)

        fun pushTest(
            publicKey: String? = "BKey",
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                installPlatform(Readiness(), HealthChecks(emptySet()), tokens.jwtAuth)
                routing { with(routes(publicKey)) { register() } }
            }
            block()
        }

        fun subscription(
            endpoint: String,
            language: String = "en",
        ) = """{"endpoint":"$endpoint","keys":{"p256dh":"${browser.p256dh}","auth":"${browser.auth}"},"language":"$language"}"""

        test("the public key is public; without keys push is off") {
            pushTest { client.get("/api/notification/v1/push/key").bodyAsText() shouldBe """{"publicKey":"BKey"}""" }
            pushTest(publicKey = null) {
                val response = client.get("/api/notification/v1/push/key")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain "PUSH_DISABLED"
            }
        }

        test("a signed-in browser subscribes, sees its device, and switches off; strangers and bad endpoints are refused") {
            pushTest {
                val ghost = tokens.accessToken(userId = "ghost")
                val endpoint = service.endpoint("/push/ghost-phone")

                client
                    .put("/api/notification/v1/push/subscriptions") {
                        contentType(ContentType.Application.Json)
                        setBody(subscription(endpoint))
                    }.status shouldBe HttpStatusCode.Unauthorized

                val refused =
                    client.put("/api/notification/v1/push/subscriptions") {
                        bearerAuth(ghost)
                        contentType(ContentType.Application.Json)
                        setBody(subscription("http://identity-service/api/identity/v1/admin/users"))
                    }
                refused.status shouldBe HttpStatusCode.UnprocessableEntity
                refused.bodyAsText() shouldContain "PUSH_SUBSCRIPTION_INVALID"

                client
                    .put("/api/notification/v1/push/subscriptions") {
                        bearerAuth(ghost)
                        contentType(ContentType.Application.Json)
                        setBody(subscription(endpoint))
                    }.status shouldBe HttpStatusCode.NoContent
                client.get("/api/notification/v1/push/subscriptions") { bearerAuth(ghost) }.bodyAsText() shouldContain
                    """"service":"127.0.0.1","language":"en""""

                // Somebody else's device cannot be switched off from here.
                client
                    .delete("/api/notification/v1/push/subscriptions") {
                        bearerAuth(tokens.accessToken(userId = "stranger"))
                        parameter("endpoint", endpoint)
                    }.status shouldBe HttpStatusCode.NoContent
                ExposedPushSubscriptions(db).forUser("ghost") shouldHaveSize 1

                client
                    .delete("/api/notification/v1/push/subscriptions") {
                        bearerAuth(ghost)
                        parameter("endpoint", endpoint)
                    }.status shouldBe HttpStatusCode.NoContent
                ExposedPushSubscriptions(db).forUser("ghost") shouldHaveSize 0
            }
        }

        test("the service worker moves a rotated subscription without a session; the old endpoint is the proof") {
            pushTest {
                val old = service.endpoint("/push/rotating-old")
                val new = service.endpoint("/push/rotating-new")
                client.put("/api/notification/v1/push/subscriptions") {
                    bearerAuth(tokens.accessToken(userId = "rotator"))
                    contentType(ContentType.Application.Json)
                    setBody(subscription(old, "de"))
                }

                fun rotation(
                    from: String,
                    to: String,
                ) = """{"oldEndpoint":"$from","endpoint":"$to","keys":{"p256dh":"${browser.p256dh}","auth":"${browser.auth}"}}"""

                // Unknown old endpoints get the same answer and change nothing.
                client
                    .post("/api/notification/v1/push/subscriptions/rotation") {
                        contentType(ContentType.Application.Json)
                        setBody(rotation(service.endpoint("/push/never-seen"), service.endpoint("/push/hijack")))
                    }.status shouldBe HttpStatusCode.NoContent
                client
                    .post("/api/notification/v1/push/subscriptions/rotation") {
                        contentType(ContentType.Application.Json)
                        setBody(rotation(old, "http://identity-service/api/identity/v1/admin/users"))
                    }.status shouldBe HttpStatusCode.UnprocessableEntity
                client
                    .post("/api/notification/v1/push/subscriptions/rotation") {
                        contentType(ContentType.Application.Json)
                        setBody(rotation(old, new))
                    }.status shouldBe HttpStatusCode.NoContent

                val moved = ExposedPushSubscriptions(db).forUser("rotator").single()
                moved.endpoint shouldBe new
                moved.language.tag shouldBe "de"
                ExposedPushSubscriptions(db).byEndpoint(service.endpoint("/push/hijack")) shouldBe null
            }
        }

        test("a browser belongs to whoever subscribed last on it, and a user keeps ten devices") {
            pushTest {
                val endpoint = service.endpoint("/push/shared-laptop")
                for (user in listOf("first", "second")) {
                    client.put("/api/notification/v1/push/subscriptions") {
                        bearerAuth(tokens.accessToken(userId = user))
                        contentType(ContentType.Application.Json)
                        setBody(subscription(endpoint, "de"))
                    }
                }
                val subscriptions = ExposedPushSubscriptions(db)
                subscriptions.forUser("first") shouldHaveSize 0
                subscriptions.forUser("second").single().endpoint shouldBe endpoint

                repeat(11) {
                    client.put("/api/notification/v1/push/subscriptions") {
                        bearerAuth(tokens.accessToken(userId = "collector"))
                        contentType(ContentType.Application.Json)
                        setBody(subscription(service.endpoint("/push/device-$it")))
                    }
                }
                subscriptions.forUser("collector") shouldHaveSize 10
            }
        }
    })
