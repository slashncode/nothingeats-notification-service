package io.github.slashncode.nothingeats.notification.adapters

import io.github.slashncode.nothingeats.notification.adapters.mail.SmtpConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

/** Mailpit (the same tool as in the cluster) in a container; its HTTP API shows what was sent. */
object MailpitTestServer {
    data class Received(
        val id: String,
        val to: List<String>,
        val subject: String,
    )

    private const val SMTP_PORT = 1025
    private const val HTTP_PORT = 8025

    private val container by lazy {
        GenericContainer("axllent/mailpit:v1.31.1")
            .withExposedPorts(SMTP_PORT, HTTP_PORT)
            .waitingFor(Wait.forHttp("/livez").forPort(HTTP_PORT))
            .apply { start() }
    }
    private val http = HttpClient.newHttpClient()

    val smtp: SmtpConfig get() = SmtpConfig(host = container.host, port = container.getMappedPort(SMTP_PORT))

    private val api get() = "http://${container.host}:${container.getMappedPort(HTTP_PORT)}/api/v1"

    fun messagesTo(address: String): List<Received> =
        get("$api/search?query=${java.net.URLEncoder.encode("to:$address", Charsets.UTF_8)}")["messages"]!!
            .jsonArray
            .map { it.jsonObject }
            .map { message ->
                Received(
                    id = message["ID"]!!.jsonPrimitive.content,
                    to = message["To"]!!.jsonArray.map { it.jsonObject["Address"]!!.jsonPrimitive.content },
                    subject = message["Subject"]!!.jsonPrimitive.content,
                )
            }

    fun text(id: String): String = get("$api/message/$id")["Text"]!!.jsonPrimitive.content

    fun html(id: String): String = get("$api/message/$id")["HTML"]!!.jsonPrimitive.content

    private fun get(url: String): JsonObject =
        Json.parseToJsonElement(http.send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofString()).body()).jsonObject
}
