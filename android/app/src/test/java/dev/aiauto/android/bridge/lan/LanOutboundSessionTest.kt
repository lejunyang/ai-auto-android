package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证出站连接只在显式选择与确认后发生，并在认证、切网或过期时停止动作。
 */

import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanOutboundSessionTest {
    private val invitationPayload = fixture("lan-invitation-v1-valid.json")
    private val invitation = LanInvitationParser.parse(invitationPayload)
    private val vector = Json.parseToJsonElement(
        fixture("lan-handshake-v1-vector.json"),
    ).jsonObject
    private val now = Instant.parse("2026-07-25T10:00:30Z")
    private val localInterface = LanLocalInterface(
        id = "android-network-42",
        name = "wlan0",
        kind = "wifi",
    )

    @Test
    fun `socket is not attempted before candidate interface and fingerprint confirmation`() {
        val socket = FakeSocket()
        val coordinator = coordinator(socket = socket)
        val incomplete = listOf<(LanInvitation) -> LanConnectRequest>(
            { request(invitation = it, candidate = null) },
            { request(invitation = it, localInterface = null) },
            { request(invitation = it, confirmedFingerprint = null) },
            { request(invitation = it, confirmedFingerprint = "1111-2222-3333-4444") },
        )

        incomplete.forEach { buildRequest ->
            assertFailure("LAN_USER_CONFIRMATION_REQUIRED") {
                coordinator.connect(buildRequest(LanInvitationParser.parse(invitationPayload)))
            }
        }

        assertEquals(0, socket.connectAttempts)
        assertEquals(0, socket.handshakeWrites.size)
    }

    @Test
    fun `preflight replay and current network mismatch are rejected before socket`() {
        val socket = FakeSocket()
        val replayStore = FakeReplayStore().apply {
            claim(invitation.replayKey(), now)
        }
        assertFailure("LAN_INVITATION_REPLAYED") {
            coordinator(socket = socket, replayStore = replayStore).connect(request())
        }
        assertEquals(0, socket.connectAttempts)

        val changedNetwork = FakeNetworkIdentity(
            LanLocalInterface("android-network-99", "rmnet0", "other"),
        )
        assertFailure("LAN_INTERFACE_MISMATCH") {
            coordinator(socket = socket, network = changedNetwork).connect(request())
        }
        assertEquals(0, socket.connectAttempts)
    }

    @Test
    fun `valid confirmed invitation completes mutual confirmation and sends encrypted frames`() {
        val socket = FakeSocket()
        val replayStore = FakeReplayStore()
        val session = coordinator(socket = socket, replayStore = replayStore).connect(request())

        assertEquals(1, socket.connectAttempts)
        assertEquals(2, socket.handshakeWrites.size)
        assertEquals("lan.clientHello", socket.handshakeWrites[0].string("type"))
        assertEquals("lan.confirmation", socket.handshakeWrites[1].string("type"))
        assertEquals("client", socket.handshakeWrites[1].string("role"))
        assertTrue(replayStore.isConsumed(invitation.replayKey()))
        assertEquals(localInterface, session.localInterface)
        assertEquals(invitation.fingerprint, session.desktopFingerprint)
        assertEquals(invitation.expiresAt, session.expiresAt)

        val action = """{"jsonrpc":"2.0","method":"ui.snapshot"}""".encodeToByteArray()
        try {
            session.send("rpc", action)
            assertEquals(1, socket.encryptedWrites.size)
            assertFalse(socket.encryptedWrites.single().ciphertext.contentEquals(action))
            assertEquals(0, socket.plaintextWrites)
        } finally {
            action.fill(0)
            session.close()
        }
        assertTrue(socket.closed)
        assertFalse(session.canSubmitActions)
    }

    @Test
    fun `unreachable address consumes invitation and closes without plaintext fallback`() {
        val socket = FakeSocket(connectFailure = LanProtocolException("LAN_ADDRESS_UNREACHABLE"))
        val replayStore = FakeReplayStore()

        assertFailure("LAN_ADDRESS_UNREACHABLE") {
            coordinator(socket = socket, replayStore = replayStore).connect(request())
        }

        assertEquals(1, socket.connectAttempts)
        assertTrue(replayStore.isConsumed(invitation.replayKey()))
        assertTrue(socket.closed)
        assertEquals(0, socket.plaintextWrites)
    }

    @Test
    fun `transcript and desktop confirmation tampering close before encrypted actions`() {
        listOf("transcript", "confirmation").forEach { tamper ->
            val socket = FakeSocket(tamper = tamper)
            val expected = if (tamper == "transcript") {
                "LAN_TRANSCRIPT_MISMATCH"
            } else {
                "LAN_CONFIRMATION_INVALID"
            }

            assertFailure(expected) {
                coordinator(socket = socket).connect(
                    request(invitation = LanInvitationParser.parse(invitationPayload)),
                )
            }

            assertTrue(tamper, socket.closed)
            assertEquals(tamper, 0, socket.encryptedWrites.size)
            assertEquals(tamper, 0, socket.plaintextWrites)
        }
    }

    @Test
    fun `network switch and expiry close the session before another action`() {
        val network = FakeNetworkIdentity(localInterface)
        val socket = FakeSocket()
        val clock = FakeClock(now)
        val session = coordinator(socket = socket, network = network, clock = clock).connect(request())

        network.current = LanLocalInterface("android-network-99", "rmnet0", "other")
        assertFailure("LAN_NETWORK_CHANGED") {
            session.send("rpc", byteArrayOf(1))
        }
        assertTrue(socket.closed)
        assertEquals(0, socket.encryptedWrites.size)

        val expirySocket = FakeSocket()
        val expiryClock = FakeClock(now)
        val expiring = coordinator(socket = expirySocket, clock = expiryClock).connect(
            request(invitation = LanInvitationParser.parse(invitationPayload)),
        )
        expiryClock.now = invitation.expiresAt
        assertFailure("LAN_SESSION_EXPIRED") {
            expiring.send("rpc", byteArrayOf(2))
        }
        assertTrue(expirySocket.closed)
        assertEquals(0, expirySocket.encryptedWrites.size)
    }

    @Test
    fun `restored process cannot recover an established session or submit actions`() {
        val restored = LanOutboundSession.restoreAfterProcessDeath(
            desktopFingerprint = invitation.fingerprint,
            localInterface = localInterface,
            expiresAt = invitation.expiresAt,
        )

        assertFalse(restored.canSubmitActions)
        assertEquals("LAN_PROCESS_RESTORED_RECONFIRM_REQUIRED", restored.stopCode)
        assertFailure("LAN_PROCESS_RESTORED_RECONFIRM_REQUIRED") {
            restored.send("rpc", byteArrayOf(3))
        }
    }

    @Test
    fun `concurrent sends serialize complete encrypted frame writes`() {
        val socket = FakeSocket(blockFirstWrite = true)
        val session = coordinator(socket = socket).connect(request())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                session.send("rpc", byteArrayOf(1))
            }
            socket.firstWriteEntered.await()
            val secondStarted = CountDownLatch(1)
            val second = executor.submit {
                secondStarted.countDown()
                session.send("rpc", byteArrayOf(2))
            }
            secondStarted.await()

            Thread.sleep(50)
            assertEquals(1, socket.maximumConcurrentWrites)
            assertEquals(1, socket.encryptedWrites.size)
            socket.releaseFirstWrite.countDown()
            first.get()
            second.get()

            assertEquals(1, socket.maximumConcurrentWrites)
            assertEquals(2, socket.encryptedWrites.size)
        } finally {
            socket.releaseFirstWrite.countDown()
            session.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `session close destroys invitation secret bytes`() {
        val socket = FakeSocket()
        val managedInvitation = LanInvitationParser.parse(invitationPayload)
        val secretBytes = managedInvitation.secretBytesForTest()
        val session = coordinator(socket = socket).connect(
            request().copy(invitation = managedInvitation),
        )

        session.close()

        assertTrue(managedInvitation.destroyed)
        assertTrue(secretBytes.first.all { it.toInt() == 0 })
        assertTrue(secretBytes.second.all { it.toInt() == 0 })
    }

    private fun coordinator(
        socket: FakeSocket,
        replayStore: FakeReplayStore = FakeReplayStore(),
        network: FakeNetworkIdentity = FakeNetworkIdentity(localInterface),
        clock: FakeClock = FakeClock(now),
    ): LanOutboundCoordinator = LanOutboundCoordinator(
        socketConnector = socket,
        keyAgreements = FixedKeyAgreements(vector),
        replayStore = replayStore,
        networkIdentity = network,
        clock = clock,
    )

    private fun request(
        invitation: LanInvitation = LanInvitationParser.parse(invitationPayload),
        candidate: LanAddressCandidate? = invitation.addressCandidates.first(),
        localInterface: LanLocalInterface? = this.localInterface,
        confirmedFingerprint: String? = invitation.fingerprint,
    ) = LanConnectRequest(
        invitation = invitation,
        selectedCandidate = candidate,
        selectedLocalInterface = localInterface,
        confirmedFingerprint = confirmedFingerprint,
        requestedCapabilities = listOf(
            "lan.bridge.mutual-confirmation.v1",
            "lan.bridge.rpc.v1",
            "ui.snapshot",
        ),
    )

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
            fail("expected $expectedCode")
        } catch (error: LanProtocolException) {
            assertEquals(expectedCode, error.code)
        }
    }

    private inner class FakeSocket(
        private val connectFailure: LanProtocolException? = null,
        private val tamper: String? = null,
        private val blockFirstWrite: Boolean = false,
    ) : LanSocketConnector, LanSocketPort {
        var connectAttempts = 0
        var plaintextWrites = 0
        var closed = false
        val handshakeWrites = Collections.synchronizedList(mutableListOf<JsonObject>())
        val encryptedWrites = Collections.synchronizedList(mutableListOf<LanEncryptedFrame>())
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        private val concurrentWrites = AtomicInteger(0)
        private val maximumWrites = AtomicInteger(0)
        val maximumConcurrentWrites: Int
            get() = maximumWrites.get()
        private val reads = ArrayDeque<JsonObject>()

        override fun connect(
            endpoint: LanAddressCandidate,
            localInterface: LanLocalInterface,
        ): LanSocketPort {
            connectAttempts += 1
            connectFailure?.let { throw it }
            assertEquals(invitation.addressCandidates.first(), endpoint)
            assertEquals(this@LanOutboundSessionTest.localInterface, localInterface)
            return this
        }

        override fun writeHandshake(message: JsonObject) {
            handshakeWrites += message
            if (message.string("type") == "lan.clientHello") {
                prepareDesktopMessages(message)
            }
        }

        override fun readHandshake(): JsonObject =
            reads.removeFirstOrNull() ?: error("no fake handshake message")

        override fun writeEncrypted(frame: LanEncryptedFrame) {
            val activeWrites = concurrentWrites.incrementAndGet()
            maximumWrites.accumulateAndGet(activeWrites, ::maxOf)
            try {
                encryptedWrites += frame.copyForTest()
                if (blockFirstWrite && encryptedWrites.size == 1) {
                    firstWriteEntered.countDown()
                    releaseFirstWrite.await()
                }
            } finally {
                concurrentWrites.decrementAndGet()
            }
        }

        override fun close() {
            closed = true
            encryptedWrites.forEach(LanEncryptedFrame::destroy)
        }

        private fun prepareDesktopMessages(clientHello: JsonObject) {
            val selectionBody = vector.getValue("selection").jsonObject
            val transcriptHash = LanCrypto.transcriptHash(
                invitation.original,
                clientHello,
                selectionBody,
            )
            if (tamper == "transcript") transcriptHash[0] = (transcriptHash[0].toInt() xor 1).toByte()
            val selection = buildJsonObject {
                put("type", "lan.desktopSelection")
                put("version", "1.0")
                put("invitationId", invitation.invitationId)
                put("transcriptHash", LanBase64Url.encode(transcriptHash))
                put("selectedBridgeVersion", selectionBody.string("selectedBridgeVersion"))
                put("selectedCapabilities", selectionBody.getValue("selectedCapabilities"))
            }
            reads += selection

            val trueHash = LanCrypto.transcriptHash(invitation.original, clientHello, selectionBody)
            val sharedSecret = vector.string("sharedSecretHex").hexToBytes()
            val keys = LanCrypto.deriveSessionKeys(sharedSecret, trueHash)
            val tag = LanCrypto.signConfirmation(
                LanConfirmationRole.DESKTOP,
                keys.confirmation,
                trueHash,
            )
            if (tamper == "confirmation") tag[0] = (tag[0].toInt() xor 1).toByte()
            reads += buildJsonObject {
                put("type", "lan.confirmation")
                put("version", "1.0")
                put("invitationId", invitation.invitationId)
                put("role", "desktop")
                put("transcriptHash", LanBase64Url.encode(trueHash))
                put("tag", LanBase64Url.encode(tag))
            }
            transcriptHash.fill(0)
            trueHash.fill(0)
            sharedSecret.fill(0)
            tag.fill(0)
            keys.destroy()
        }
    }

    private class FixedKeyAgreements(
        vector: JsonObject,
    ) : LanKeyAgreementFactory {
        private val publicKey = LanBase64Url.decode32(
            vector.getValue("clientHello").jsonObject.string("clientEphemeralPublicKey"),
        )
        private val sharedSecret = vector.string("sharedSecretHex").hexToBytes()

        override fun generate(): LanKeyAgreement = object : LanKeyAgreement {
            override val publicKey: ByteArray = this@FixedKeyAgreements.publicKey.copyOf()

            override fun sharedSecret(peerPublicKey: ByteArray): ByteArray =
                sharedSecret.copyOf()

            override fun destroy() {
                publicKey.fill(0)
            }
        }
    }

    private class FakeReplayStore : LanReplayStore {
        private val consumed = mutableSetOf<LanReplayKey>()

        override fun claim(key: LanReplayKey, now: Instant): LanReplayClaim {
            if (consumed.any { it.invitationIdDigest == key.invitationIdDigest }) {
                return LanReplayClaim.INVITATION_REPLAYED
            }
            if (consumed.any { it.nonceDigest == key.nonceDigest }) {
                return LanReplayClaim.NONCE_REPLAYED
            }
            consumed += key
            return LanReplayClaim.CLAIMED
        }

        fun isConsumed(key: LanReplayKey): Boolean = key in consumed
    }

    private class FakeNetworkIdentity(
        var current: LanLocalInterface?,
    ) : LanNetworkIdentity {
        override fun current(): LanLocalInterface? = current
    }

    private class FakeClock(
        var now: Instant,
    ) : LanClock {
        override fun now(): Instant = now
    }

    private companion object {
        fun JsonObject.string(name: String): String =
            getValue(name).jsonPrimitive.content

        fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
    }
}
