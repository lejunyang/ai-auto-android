package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证同一短期 LAN session 可持续处理 Bridge RPC，并由 close、切网和过期中断读取。
 */

import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import dev.aiauto.android.bridge.BridgeCapability
import dev.aiauto.android.bridge.BridgeErrorCode
import dev.aiauto.android.bridge.BridgeException
import dev.aiauto.android.bridge.BridgeMethodHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanPersistentRpcSessionTest {
    private val now = Instant.parse("2026-07-25T10:00:30Z")
    private val localInterface = LanLocalInterface("android-network-42", "wlan0", "wifi")
    private val invitationPayload = fixture("lan-invitation-v1-valid.json")

    @Test
    fun `one confirmed session serves multiple encrypted requests then remote close`() {
        val fixture = SessionFixture()
        val handler = RecordingHandler()
        val requests = listOf(
            request("device.info", fixture.token),
            request("ui.snapshot", fixture.token),
            request("session.close", fixture.token),
        )
        requests.forEachIndexed { sequence, request ->
            fixture.enqueue(sequence.toLong(), request)
        }

        fixture.session.serve(handler)

        assertEquals(listOf("device.info", "ui.snapshot"), handler.methods)
        assertEquals(3, fixture.socket.encryptedWrites.size)
        fixture.socket.encryptedWrites.forEachIndexed { sequence, frame ->
            assertEquals("bridge.response", frame.type)
            val plaintext = fixture.clientReceiver.decrypt(
                sequence.toLong(),
                "bridge.response",
                frame,
            )
            try {
                val response = Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
                assertTrue(response.containsKey("result"))
                if (sequence == 2) {
                    assertTrue(response.getValue("result").jsonObject.boolean("closed"))
                }
            } finally {
                plaintext.fill(0)
            }
        }
        assertFalse(fixture.session.canSubmitActions)
        assertTrue(fixture.socket.closed)
        fixture.close()
    }

    @Test
    fun `explicit close interrupts a blocked encrypted read`() {
        val fixture = SessionFixture(blockReads = true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val serving = executor.submit { fixture.session.serve(fixture.handler) }
            assertTrue(fixture.socket.readEntered.await(1, TimeUnit.SECONDS))

            fixture.session.close()

            serving.get(1, TimeUnit.SECONDS)
            assertTrue(fixture.socket.closed)
            assertFalse(fixture.session.canSubmitActions)
        } finally {
            fixture.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `fatal authentication responses are encrypted before session closes`() {
        listOf(
            Triple("AUTH_REQUIRED", null, null),
            Triple("AUTH_INVALID", "A".repeat(43), null),
            Triple(
                "AUTH_EXPIRED",
                "valid",
                BridgeException(
                    code = BridgeErrorCode.AUTH_EXPIRED,
                    message = "The LAN bridge session token has expired.",
                ),
            ),
        ).forEach { (expectedCode, suppliedToken, handlerFailure) ->
            val fixture = SessionFixture()
            fixture.handler.failure = handlerFailure
            fixture.enqueue(
                sequence = 0,
                plaintext = request(
                    method = "device.info",
                    token = when (suppliedToken) {
                        "valid" -> fixture.token
                        else -> suppliedToken
                    },
                ),
            )

            fixture.session.serve(fixture.handler)

            assertEquals(1, fixture.socket.encryptedWrites.size)
            val response = fixture.clientReceiver.decrypt(
                sequence = 0,
                type = "bridge.response",
                frame = fixture.socket.encryptedWrites.single(),
            )
            try {
                assertEquals(
                    expectedCode,
                    Json.parseToJsonElement(response.decodeToString()).jsonObject
                        .getValue("error").jsonObject
                        .getValue("data").jsonObject
                        .getValue("code").jsonPrimitive.content,
                )
            } finally {
                response.fill(0)
            }
            assertTrue(fixture.socket.closed)
            assertFalse(fixture.session.canSubmitActions)
            fixture.close()
        }
    }

    @Test
    fun `network change and expiry interrupt polling reads without dispatching actions`() {
        listOf("LAN_NETWORK_CHANGED", "LAN_SESSION_EXPIRED").forEach { expected ->
            val fixture = SessionFixture(blockReads = true)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val serving = executor.submit { fixture.session.serve(fixture.handler) }
                assertTrue(fixture.socket.readEntered.await(1, TimeUnit.SECONDS))
                if (expected == "LAN_NETWORK_CHANGED") {
                    fixture.network.current =
                        LanLocalInterface("android-network-99", "rmnet0", "other")
                } else {
                    fixture.clock.now = fixture.invitation.expiresAt
                }

                assertServingFailure(expected, serving)
                assertTrue(fixture.socket.closed)
                assertEquals(0, fixture.handler.methods.size)
            } finally {
                fixture.close()
                executor.shutdownNow()
            }
        }
    }

    private fun assertServingFailure(
        expectedCode: String,
        serving: java.util.concurrent.Future<*>,
    ) {
        try {
            serving.get(2, TimeUnit.SECONDS)
            fail("expected $expectedCode")
        } catch (error: ExecutionException) {
            val cause = error.cause as? LanProtocolException ?: throw error
            assertEquals(expectedCode, cause.code)
        }
    }

    private fun request(method: String, token: String?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", UUID.randomUUID().toString())
        put("requestId", UUID.randomUUID().toString())
        put("protocolVersion", "1.0")
        put("method", method)
        put("params", buildJsonObject {})
        put("deadlineMs", 1_000)
        token?.let { put("token", it) }
    }.toString()

    private inner class SessionFixture(
        blockReads: Boolean = false,
    ) : AutoCloseable {
        val invitation = LanInvitationParser.parse(invitationPayload)
        val outboundKey = ByteArray(32) { (it + 1).toByte() }
        val inboundKey = ByteArray(32) { (it + 65).toByte() }
        val tokenBindingKey = ByteArray(32) { (it + 97).toByte() }
        val transcriptHash = ByteArray(32) { (it + 129).toByte() }
        val socket = FakePersistentSocket(blockReads)
        val network = MutableNetworkIdentity(localInterface)
        val clock = MutableClock(now)
        val desktopSender = LanFrameCodec(
            inboundKey,
            transcriptHash,
            LanFrameDirection.DESKTOP_TO_CLIENT,
        )
        val clientReceiver = LanFrameCodec(
            outboundKey,
            transcriptHash,
            LanFrameDirection.CLIENT_TO_DESKTOP,
        )
        val tokenValue = LanSessionToken.derive(
            tokenBindingKey,
            transcriptHash,
            invitation.invitationId,
            invitation.expiresAt,
        )
        val token = deriveTokenForTest(
            tokenBindingKey,
            transcriptHash,
            invitation.invitationId,
            invitation.expiresAt,
        )
        val handler = RecordingHandler()
        val session = LanOutboundSession.established(
            socket = socket,
            invitation = invitation,
            outboundKey = outboundKey,
            inboundKey = inboundKey,
            tokenBindingKey = tokenBindingKey,
            transcriptHash = transcriptHash,
            networkIdentity = network,
            clock = clock,
            localInterface = localInterface,
            desktopFingerprint = invitation.fingerprint,
            expiresAt = invitation.expiresAt,
        )

        init {
            tokenValue.close()
        }

        fun enqueue(sequence: Long, plaintext: String) {
            val bytes = plaintext.encodeToByteArray()
            val frame = desktopSender.encrypt(sequence, "bridge.request", bytes)
            try {
                socket.enqueue(frame)
            } finally {
                bytes.fill(0)
                frame.destroy()
            }
        }

        override fun close() {
            session.close()
            socket.destroyFrames()
            desktopSender.destroy()
            clientReceiver.destroy()
            outboundKey.fill(0)
            inboundKey.fill(0)
            tokenBindingKey.fill(0)
            transcriptHash.fill(0)
        }
    }

    private class FakePersistentSocket(
        private val blockReads: Boolean,
    ) : LanSocketPort {
        private val incoming = ArrayDeque<LanEncryptedFrame>()
        val encryptedWrites =
            Collections.synchronizedList(mutableListOf<LanEncryptedFrame>())
        val readEntered = CountDownLatch(1)

        @Volatile
        var closed = false

        override fun writeHandshake(message: JsonObject) = error("handshake already completed")

        override fun readHandshake(): JsonObject = error("handshake already completed")

        override fun writeEncrypted(frame: LanEncryptedFrame) {
            if (closed) throw LanProtocolException("LAN_CONNECTION_CLOSED")
            encryptedWrites += frame.copyForTest()
        }

        override fun readEncrypted(timeoutMillis: Int): LanEncryptedFrame? {
            readEntered.countDown()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
            while (!closed) {
                synchronized(incoming) {
                    incoming.removeFirstOrNull()?.let { return it }
                }
                if (!blockReads || System.nanoTime() >= deadline) return null
                Thread.sleep(5)
            }
            throw LanProtocolException("LAN_CONNECTION_CLOSED")
        }

        fun enqueue(frame: LanEncryptedFrame) {
            synchronized(incoming) {
                incoming += frame.copyForTest()
            }
        }

        override fun close() {
            closed = true
        }

        fun destroyFrames() {
            synchronized(incoming) {
                incoming.forEach(LanEncryptedFrame::destroy)
                incoming.clear()
            }
            encryptedWrites.forEach(LanEncryptedFrame::destroy)
            encryptedWrites.clear()
        }
    }

    private class MutableNetworkIdentity(
        @Volatile var current: LanLocalInterface?,
    ) : LanNetworkIdentity {
        override fun current(): LanLocalInterface? = current
    }

    private class MutableClock(
        @Volatile var now: Instant,
    ) : LanClock {
        override fun now(): Instant = now
    }

    private class RecordingHandler : BridgeMethodHandler {
        val methods = Collections.synchronizedList(mutableListOf<String>())
        var failure: BridgeException? = null

        override fun capabilities(): List<BridgeCapability> = emptyList()

        override fun handle(method: String, params: JsonObject): JsonObject {
            methods += method
            failure?.let { throw it }
            return buildJsonObject { put("method", method) }
        }
    }

    private companion object {
        fun JsonObject.boolean(name: String): Boolean =
            getValue(name).jsonPrimitive.content.toBoolean()

        fun LanEncryptedFrame.copyForTest() = LanEncryptedFrame(
            version = version,
            direction = direction,
            sequence = sequence,
            type = type,
            nonce = nonce.copyOf(),
            ciphertext = ciphertext.copyOf(),
        )

        fun fixture(name: String): String {
            val start = requireNotNull(System.getProperty("user.dir")).let(::File)
            val file = generateSequence(start, File::getParentFile)
                .map { directory -> File(directory, "protocol/fixtures/$name") }
                .firstOrNull(File::isFile)
            return requireNotNull(file) { "Unable to locate protocol fixture $name" }.readText()
        }

        fun deriveTokenForTest(
            tokenBindingKey: ByteArray,
            transcriptHash: ByteArray,
            invitationId: String,
            expiresAt: Instant,
        ): String {
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(tokenBindingKey, "HmacSHA256"))
            }
            mac.update("AIAUTO-LAN-BRIDGE-SESSION-TOKEN-V1".encodeToByteArray())
            mac.update(0)
            mac.update(transcriptHash)
            mac.update(0)
            mac.update(invitationId.encodeToByteArray())
            mac.update(0)
            mac.update(expiresAt.toString().encodeToByteArray())
            val token = mac.doFinal()
            return try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(token)
            } finally {
                token.fill(0)
            }
        }
    }
}
