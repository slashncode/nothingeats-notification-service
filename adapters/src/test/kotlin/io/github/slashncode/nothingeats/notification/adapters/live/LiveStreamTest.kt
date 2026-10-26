package io.github.slashncode.nothingeats.notification.adapters.live

import io.github.slashncode.nothingeats.notification.application.LiveUpdate
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val tokens = TestTokens()

/** The live stream over a real socket, read line by line like a browser's fetch does. */
class LiveStreamTest :
    FunSpec({
        val pubSub = ValkeyPubSub(ValkeyTestServer.config(), setOf(ValkeyLiveUpdates.PATTERN))
        val updates = ValkeyLiveUpdates(pubSub)
        val port = ServerSocket(0).use { it.localPort }
        val server =
            embeddedServer(Netty, port = port) {
                installPlatform(Readiness(), HealthChecks(emptySet()), tokens.jwtAuth)
                install(SSE)
                routing { with(LiveStreamRoutes(pubSub, heartbeat = 300.milliseconds)) { register() } }
            }
        val http = HttpClient.newHttpClient()

        fun stream(token: String?) =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/api/notification/v1/stream"))
                .timeout(Duration.ofSeconds(10))
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .GET()
                .build()

        beforeSpec {
            pubSub.start()
            server.start()
        }
        afterSpec {
            server.stop(0, 0)
            pubSub.stop()
        }

        test("a user gets their own live updates, never other users', and heartbeats in between") {
            val response =
                withContext(
                    Dispatchers.IO,
                ) { http.send(stream(tokens.accessToken(userId = "ghost-1")), HttpResponse.BodyHandlers.ofLines()) }
            response.statusCode() shouldBe 200
            response.headers().firstValue("Content-Type").orElse("") shouldBe "text/event-stream"

            val lines = mutableListOf<String>()
            val iterator = response.body().iterator()
            // "ready" means the stream listens; everything published from now on arrives.
            withContext(Dispatchers.IO) {
                while (iterator.hasNext() && lines.lastOrNull() != "event: ready") lines += iterator.next()
            }
            updates.publish(LiveUpdate("ghost-2", "order.updated", """{"orderId":"o-2"}"""))
            updates.publish(LiveUpdate("ghost-1", "order.updated", """{"orderId":"o-1","status":"COOKING"}"""))
            withContext(Dispatchers.IO) {
                while (iterator.hasNext() && !(lines.any { it.contains("o-1") } && lines.any { it.startsWith(": ping") })) {
                    lines += iterator.next()
                }
                response.body().close()
            }

            lines shouldContain "event: order.updated"
            lines shouldContain """data: {"orderId":"o-1","status":"COOKING"}"""
            lines shouldNotContain """data: {"orderId":"o-2"}"""
        }

        test("the stream needs a valid token") {
            withContext(Dispatchers.IO) { http.send(stream(null), HttpResponse.BodyHandlers.discarding()) }.statusCode() shouldBe 401
            withContext(Dispatchers.IO) {
                http.send(stream(tokens.accessToken(audience = "elsewhere")), HttpResponse.BodyHandlers.discarding())
            }.statusCode() shouldBe 401
        }
    })
