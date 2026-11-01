package io.github.slashncode.nothingeats.notification.adapters.push

import com.interaso.webpush.VapidKeys
import com.sksamuel.hoplite.Secret
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val COORDINATE_BYTES = 32

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun BigInteger.fixed(): ByteArray =
    toByteArray().let {
        if (it.size >=
            COORDINATE_BYTES
        ) {
            it.copyOfRange(it.size - COORDINATE_BYTES, it.size)
        } else {
            ByteArray(COORDINATE_BYTES - it.size) + it
        }
    }

private fun ECPublicKey.uncompressed(): ByteArray = byteArrayOf(0x04) + w.affineX.fixed() + w.affineY.fixed()

/** A fresh VAPID key pair in the form the service reads it, for a push service at [origins]. */
fun testPushConfig(vararg origins: String): PushConfig {
    val keys = VapidKeys.generate()
    return PushConfig(
        publicKey = base64Url(keys.publicKey.uncompressed()),
        privateKey = Secret(base64Url(keys.privateKey.s.fixed())),
        allowedOrigins = origins.toList(),
    )
}

/**
 * The browser's side of RFC 8291: its keys for `pushManager.subscribe`, and the decryption of
 * what arrives, so tests read the payload the service worker would.
 */
class TestBrowser {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = keyPair.public as ECPublicKey
    private val authSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }

    val p256dh: String = base64Url(publicKey.uncompressed())
    val auth: String = base64Url(authSecret)

    fun decrypt(body: ByteArray): String {
        val salt = body.copyOfRange(0, 16)
        val keyIdLength = body[20].toInt() and 0xff
        val serverKey = body.copyOfRange(21, 21 + keyIdLength)
        val ciphertext = body.copyOfRange(21 + keyIdLength, body.size)
        require(ByteBuffer.wrap(body, 16, 4).int >= ciphertext.size) { "one record expected" }

        val sharedSecret =
            KeyAgreement
                .getInstance("ECDH")
                .apply {
                    init(keyPair.private)
                    doPhase(serverPublicKey(serverKey), true)
                }.generateSecret()
        val ikm = hmac(hmac(authSecret, sharedSecret), "WebPush: info".toByteArray() + 0 + publicKey.uncompressed() + serverKey + 1)
        val prk = hmac(salt, ikm)
        val contentKey = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        val padded =
            Cipher
                .getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, nonce)) }
                .doFinal(ciphertext)
        val delimiter = padded.indexOfLast { it != 0.toByte() }
        require(padded[delimiter] == 2.toByte()) { "last record delimiter expected" }
        return String(padded, 0, delimiter)
    }

    private fun serverPublicKey(bytes: ByteArray) =
        KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65))),
                publicKey.params,
            ),
        )

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byteArrayOf(byte.toByte())
}

/** A push service on a local port: records what arrives and answers [statusFor] the path (201 by default). */
class FakePushService : AutoCloseable {
    data class Push(
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    val received = CopyOnWriteArrayList<Push>()
    val statusFor = ConcurrentHashMap<String, Int>()

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readAllBytes()
                val headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
                received += Push(exchange.requestURI.path, headers, body)
                exchange.sendResponseHeaders(statusFor[exchange.requestURI.path] ?: CREATED, -1)
                exchange.close()
            }
            start()
        }

    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    fun endpoint(path: String) = "$origin$path"

    override fun close() = server.stop(0)

    private companion object {
        const val CREATED = 201
    }
}
