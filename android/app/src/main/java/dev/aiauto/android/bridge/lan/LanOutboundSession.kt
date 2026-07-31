package dev.aiauto.android.bridge.lan

/**
 * 功能用途：通过注入端口编排 N36 出站握手，并在切网、过期或认证失败时销毁短期会话。
 */

import java.security.PrivateKey
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import dev.aiauto.android.bridge.BridgeMethodHandler
import dev.aiauto.android.bridge.LanBridgeRpcDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LanReplayKey(
    val invitationIdDigest: String,
    val nonceDigest: String,
    val expiresAt: Instant,
)

enum class LanReplayClaim {
    CLAIMED,
    INVITATION_REPLAYED,
    NONCE_REPLAYED,
}

internal fun LanInvitation.replayKey(): LanReplayKey {
    val invitationDigest = LanCrypto.sha256(invitationId.encodeToByteArray())
    val nonceDigest = LanCrypto.sha256(nonce)
    return try {
        LanReplayKey(
            invitationIdDigest = invitationDigest.toHex(),
            nonceDigest = nonceDigest.toHex(),
            expiresAt = expiresAt,
        )
    } finally {
        invitationDigest.fill(0)
        nonceDigest.fill(0)
    }
}

interface LanReplayStore {
    /**
     * 原子记录 invitation ID 与 nonce 摘要；失败的首次网络尝试也不得恢复为可用。
     */
    fun claim(key: LanReplayKey, now: Instant): LanReplayClaim
}

fun interface LanClock {
    fun now(): Instant
}

fun interface LanNetworkIdentity {
    fun current(): LanLocalInterface?
}

interface LanSocketConnector : AutoCloseable {
    fun connect(
        endpoint: LanAddressCandidate,
        localInterface: LanLocalInterface,
    ): LanSocketPort
}

interface LanSocketPort : AutoCloseable {
    fun writeHandshake(message: JsonObject)

    fun readHandshake(): JsonObject

    /**
     * 在短轮询期限内读取一个完整加密 frame；超时返回 null，调用方据此复核网络与过期状态。
     */
    fun readEncrypted(timeoutMillis: Int): LanEncryptedFrame? =
        throw LanProtocolException("LAN_CONNECTION_CLOSED")

    /**
     * 实现必须在返回前复制或完成写入，因为调用方会立即清零 frame 缓冲。
     */
    fun writeEncrypted(frame: LanEncryptedFrame)
}

interface LanKeyAgreement {
    val publicKey: ByteArray

    fun sharedSecret(peerPublicKey: ByteArray): ByteArray

    fun destroy()
}

fun interface LanKeyAgreementFactory {
    fun generate(): LanKeyAgreement
}

object PlatformLanKeyAgreementFactory : LanKeyAgreementFactory {
    override fun generate(): LanKeyAgreement {
        val keyPair = PlatformX25519.generate()
        return PlatformLanKeyAgreement(keyPair)
    }

    private class PlatformLanKeyAgreement(
        private val keyPair: LanX25519KeyPair,
    ) : LanKeyAgreement {
        override val publicKey: ByteArray
            get() = keyPair.publicKey.copyOf()

        override fun sharedSecret(peerPublicKey: ByteArray): ByteArray =
            PlatformX25519.sharedSecret(keyPair.privateKey, peerPublicKey)

        override fun destroy() {
            keyPair.destroy()
        }
    }
}

data class LanConnectRequest(
    val invitation: LanInvitation,
    val selectedCandidate: LanAddressCandidate?,
    val selectedLocalInterface: LanLocalInterface?,
    val confirmedFingerprint: String?,
    val requestedCapabilities: List<String>,
)

class LanOutboundCoordinator(
    private val socketConnector: LanSocketConnector,
    private val keyAgreements: LanKeyAgreementFactory = PlatformLanKeyAgreementFactory,
    private val replayStore: LanReplayStore,
    private val networkIdentity: LanNetworkIdentity,
    private val clock: LanClock,
) {
    fun connect(request: LanConnectRequest): LanOutboundSession = try {
        connectOwned(request)
    } catch (error: LanProtocolException) {
        request.invitation.close()
        throw error
    } catch (error: Exception) {
        request.invitation.close()
        throw LanProtocolException(
            "LAN_CONNECTION_FAILED",
            "LAN outbound setup failed closed",
            error,
        )
    }

    private fun connectOwned(request: LanConnectRequest): LanOutboundSession {
        val invitation = request.invitation
        val candidate = request.selectedCandidate
        val localInterface = request.selectedLocalInterface
        if (
            candidate == null ||
            candidate !in invitation.addressCandidates ||
            localInterface == null ||
            request.confirmedFingerprint != invitation.fingerprint
        ) {
            throw LanProtocolException("LAN_USER_CONFIRMATION_REQUIRED")
        }
        if (networkIdentity.current() != localInterface) {
            throw LanProtocolException("LAN_INTERFACE_MISMATCH")
        }
        validateCapabilities(invitation, request.requestedCapabilities)
        val replayKey = invitation.replayKey()
        val preflight = LanInvitationPreflight.validate(
            invitation = invitation,
            context = LanPreflightContext(
                now = clock.now(),
            ),
        )
        if (!preflight.accepted) {
            throw LanProtocolException(requireNotNull(preflight.code))
        }
        when (replayStore.claim(replayKey, clock.now())) {
            LanReplayClaim.CLAIMED -> Unit
            LanReplayClaim.INVITATION_REPLAYED ->
                throw LanProtocolException("LAN_INVITATION_REPLAYED")
            LanReplayClaim.NONCE_REPLAYED ->
                throw LanProtocolException("LAN_NONCE_REPLAYED")
        }

        val agreement = keyAgreements.generate()
        var port: LanSocketPort? = null
        var sharedSecret: ByteArray? = null
        var transcriptHash: ByteArray? = null
        var keys: LanSessionKeys? = null
        try {
            val clientHello = buildClientHello(
                invitation = invitation,
                candidate = candidate,
                clientPublicKey = agreement.publicKey,
                requestedCapabilities = request.requestedCapabilities,
            )
            port = socketConnector.connect(candidate, localInterface)
            port.writeHandshake(clientHello)

            val selectionMessage = port.readHandshake()
            val selection = validateSelection(
                invitation = invitation,
                requestedCapabilities = request.requestedCapabilities,
                message = selectionMessage,
            )
            transcriptHash = LanCrypto.transcriptHash(
                invitation.original,
                clientHello,
                selection,
            )
            val receivedTranscript = decode32For(
                selectionMessage.string("transcriptHash"),
                "LAN_TRANSCRIPT_MISMATCH",
            )
            try {
                if (!java.security.MessageDigest.isEqual(transcriptHash, receivedTranscript)) {
                    throw LanProtocolException("LAN_TRANSCRIPT_MISMATCH")
                }
            } finally {
                receivedTranscript.fill(0)
            }

            val desktopPublicKey = invitation.desktopPublicKeyCopy()
            try {
                sharedSecret = agreement.sharedSecret(desktopPublicKey)
            } finally {
                desktopPublicKey.fill(0)
            }
            keys = LanCrypto.deriveSessionKeys(sharedSecret, transcriptHash)
            val desktopConfirmation = port.readHandshake()
            validateDesktopConfirmation(
                invitation = invitation,
                message = desktopConfirmation,
                transcriptHash = transcriptHash,
                confirmationKey = keys.confirmation,
            )
            val clientTag = LanCrypto.signConfirmation(
                LanConfirmationRole.CLIENT,
                keys.confirmation,
                transcriptHash,
            )
            try {
                port.writeHandshake(
                    buildConfirmation(
                        invitationId = invitation.invitationId,
                        role = LanConfirmationRole.CLIENT,
                        transcriptHash = transcriptHash,
                        tag = clientTag,
                    ),
                )
            } finally {
                clientTag.fill(0)
            }

            val session = LanOutboundSession.established(
                socket = port,
                invitation = invitation,
                outboundKey = keys.clientToDesktop,
                inboundKey = keys.desktopToClient,
                tokenBindingKey = keys.tokenBinding,
                transcriptHash = transcriptHash,
                networkIdentity = networkIdentity,
                clock = clock,
                localInterface = localInterface,
                desktopFingerprint = invitation.fingerprint,
                expiresAt = invitation.expiresAt,
            )
            port = null
            return session
        } catch (error: LanProtocolException) {
            port?.closeQuietly() ?: socketConnector.closeQuietly()
            invitation.close()
            throw error
        } catch (error: Exception) {
            port?.closeQuietly() ?: socketConnector.closeQuietly()
            invitation.close()
            throw LanProtocolException(
                "LAN_CONNECTION_FAILED",
                "LAN outbound connection failed closed",
                error,
            )
        } finally {
            agreement.destroy()
            sharedSecret?.fill(0)
            transcriptHash?.fill(0)
            keys?.destroy()
        }
    }

    private fun validateCapabilities(
        invitation: LanInvitation,
        requested: List<String>,
    ) {
        if (
            requested.size !in 2..64 ||
            requested.distinct() != requested ||
            requested.any { it !in invitation.capabilities } ||
            !requested.containsAll(REQUIRED_CAPABILITIES)
        ) {
            throw LanProtocolException("LAN_CAPABILITY_MISMATCH")
        }
    }

    private fun buildClientHello(
        invitation: LanInvitation,
        candidate: LanAddressCandidate,
        clientPublicKey: ByteArray,
        requestedCapabilities: List<String>,
    ): JsonObject = try {
        buildJsonObject {
            put("type", "lan.clientHello")
            put("version", "1.0")
            put("invitationId", invitation.invitationId)
            put("nonce", invitation.nonceBase64Url())
            put("clientEphemeralPublicKey", LanBase64Url.encode(clientPublicKey))
            put(
                "selectedEndpoint",
                buildJsonObject {
                    put("host", candidate.host)
                    put("family", candidate.family)
                    put("port", candidate.port)
                    put("interfaceId", candidate.interfaceId)
                },
            )
            put(
                "supportedBridgeVersions",
                buildJsonArray {
                    add(JsonPrimitive("1.0"))
                },
            )
            put(
                "requestedCapabilities",
                buildJsonArray {
                    requestedCapabilities.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    } finally {
        clientPublicKey.fill(0)
    }

    private fun validateSelection(
        invitation: LanInvitation,
        requestedCapabilities: List<String>,
        message: JsonObject,
    ): JsonObject {
        requireExactKeys(
            message,
            setOf(
                "type",
                "version",
                "invitationId",
                "transcriptHash",
                "selectedBridgeVersion",
                "selectedCapabilities",
            ),
            "LAN_TRANSCRIPT_MISMATCH",
        )
        if (
            message.string("type") != "lan.desktopSelection" ||
            message.string("version") != "1.0" ||
            message.string("invitationId") != invitation.invitationId ||
            message.string("selectedBridgeVersion") != "1.0"
        ) {
            throw LanProtocolException("LAN_TRANSCRIPT_MISMATCH")
        }
        val selectedCapabilities = message.array("selectedCapabilities").mapStrictStrings(
            "LAN_CAPABILITY_MISMATCH",
        )
        val expected = requestedCapabilities.sortedWith(::compareUtf8)
        if (
            selectedCapabilities != expected ||
            !selectedCapabilities.containsAll(REQUIRED_CAPABILITIES)
        ) {
            throw LanProtocolException("LAN_CAPABILITY_MISMATCH")
        }
        return buildJsonObject {
            put("selectedBridgeVersion", "1.0")
            put(
                "selectedCapabilities",
                buildJsonArray {
                    selectedCapabilities.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }

    private fun validateDesktopConfirmation(
        invitation: LanInvitation,
        message: JsonObject,
        transcriptHash: ByteArray,
        confirmationKey: ByteArray,
    ) {
        requireExactKeys(
            message,
            setOf("type", "version", "invitationId", "role", "transcriptHash", "tag"),
            "LAN_CONFIRMATION_INVALID",
        )
        if (
            message.string("type") != "lan.confirmation" ||
            message.string("version") != "1.0" ||
            message.string("invitationId") != invitation.invitationId ||
            message.string("role") != LanConfirmationRole.DESKTOP.wireValue
        ) {
            throw LanProtocolException("LAN_CONFIRMATION_INVALID")
        }
        val receivedHash = decode32For(
            message.string("transcriptHash"),
            "LAN_TRANSCRIPT_MISMATCH",
        )
        val receivedTag = decode32For(
            message.string("tag"),
            "LAN_CONFIRMATION_INVALID",
        )
        try {
            if (!java.security.MessageDigest.isEqual(transcriptHash, receivedHash)) {
                throw LanProtocolException("LAN_TRANSCRIPT_MISMATCH")
            }
            if (
                !LanCrypto.verifyConfirmation(
                    LanConfirmationRole.DESKTOP,
                    confirmationKey,
                    transcriptHash,
                    receivedTag,
                )
            ) {
                throw LanProtocolException("LAN_CONFIRMATION_INVALID")
            }
        } finally {
            receivedHash.fill(0)
            receivedTag.fill(0)
        }
    }

    private fun buildConfirmation(
        invitationId: String,
        role: LanConfirmationRole,
        transcriptHash: ByteArray,
        tag: ByteArray,
    ): JsonObject = buildJsonObject {
        put("type", "lan.confirmation")
        put("version", "1.0")
        put("invitationId", invitationId)
        put("role", role.wireValue)
        put("transcriptHash", LanBase64Url.encode(transcriptHash))
        put("tag", LanBase64Url.encode(tag))
    }

    private companion object {
        val REQUIRED_CAPABILITIES = setOf(
            "lan.bridge.mutual-confirmation.v1",
            "lan.bridge.rpc.v1",
        )
    }
}

class LanOutboundSession private constructor(
    private var socket: LanSocketPort?,
    private var outboundCodec: LanFrameCodec?,
    private var inboundReceiver: LanFrameReceiver?,
    private var token: LanSessionToken?,
    private var invitation: LanInvitation?,
    private val networkIdentity: LanNetworkIdentity?,
    private val clock: LanClock?,
    val localInterface: LanLocalInterface,
    val desktopFingerprint: String,
    val expiresAt: Instant,
    stopCode: String?,
) : AutoCloseable {
    private val active = AtomicBoolean(stopCode == null)
    private val serving = AtomicBoolean(false)
    private val sequence = AtomicLong(0)

    @Volatile
    var stopCode: String? = stopCode
        private set

    val canSubmitActions: Boolean
        get() = active.get()

    @Synchronized
    fun send(type: String, plaintext: ByteArray) {
        ensureActive()
        validateEnvironment()
        val frame = try {
            outboundCodec!!.encrypt(sequence.getAndIncrement(), type, plaintext)
        } catch (error: LanProtocolException) {
            stop(error.code)
            throw error
        } catch (error: Exception) {
            stop("LAN_CONFIRMATION_INVALID")
            throw error
        }
        try {
            socket!!.writeEncrypted(frame)
        } catch (error: Exception) {
            stop("LAN_CONNECTION_FAILED")
            throw LanProtocolException(
                "LAN_CONNECTION_FAILED",
                "encrypted LAN frame write failed",
                error,
            )
        } finally {
            frame.destroy()
        }
    }

    fun serve(methodHandler: BridgeMethodHandler) {
        ensureActive()
        if (!serving.compareAndSet(false, true)) {
            throw LanProtocolException("LAN_SESSION_CLOSED", "LAN RPC loop is already serving")
        }
        val sessionToken = token ?: run {
            serving.set(false)
            throw LanProtocolException(stopCode ?: "LAN_SESSION_CLOSED")
        }
        val adapter = LanBridgeRpcDispatcher(
            methodHandler = methodHandler,
            token = sessionToken,
        )
        try {
            while (active.get()) {
                validateEnvironment()
                val port = socket ?: return
                val frame = try {
                    port.readEncrypted(READ_POLL_MILLIS)
                } catch (error: LanProtocolException) {
                    if (!active.get() && error.code == "LAN_CONNECTION_CLOSED") return
                    stop(error.code)
                    throw error
                } catch (error: Exception) {
                    if (!active.get()) return
                    stop("LAN_CONNECTION_CLOSED")
                    throw LanProtocolException(
                        "LAN_CONNECTION_CLOSED",
                        "encrypted LAN frame read failed",
                        error,
                    )
                } ?: continue
                if (!active.get()) {
                    frame.destroy()
                    return
                }
                val receiver = inboundReceiver
                if (receiver == null) {
                    frame.destroy()
                    if (!active.get()) return
                    stop("LAN_SESSION_CLOSED")
                    throw LanProtocolException("LAN_SESSION_CLOSED")
                }
                val opened = try {
                    receiver.open(frame)
                } catch (error: LanProtocolException) {
                    stop(error.code)
                    throw error
                } finally {
                    frame.destroy()
                }
                opened.use {
                    val request = try {
                        it.plaintext.decodeToString(throwOnInvalidSequence = true)
                    } catch (error: CharacterCodingException) {
                        stop("LAN_FRAME_INVALID")
                        throw LanProtocolException(
                            "LAN_FRAME_INVALID",
                            "encrypted LAN request is not valid UTF-8",
                            error,
                        )
                    }
                    val result = adapter.dispatch(request)
                    val response = result.response.encodeToByteArray()
                    try {
                        send(RESPONSE_FRAME_TYPE, response)
                    } finally {
                        response.fill(0)
                    }
                    if (result.closeSession) {
                        stop("LAN_SESSION_REMOTE_CLOSED")
                        return
                    }
                }
            }
        } finally {
            adapter.close()
            serving.set(false)
        }
    }

    @Synchronized
    override fun close() {
        stop("LAN_SESSION_STOPPED")
    }

    private fun ensureActive() {
        if (!active.get()) {
            throw LanProtocolException(stopCode ?: "LAN_SESSION_STOPPED")
        }
    }

    private fun validateEnvironment() {
        if (clock!!.now() >= expiresAt) {
            stop("LAN_SESSION_EXPIRED")
            throw LanProtocolException("LAN_SESSION_EXPIRED")
        }
        if (networkIdentity!!.current() != localInterface) {
            stop("LAN_NETWORK_CHANGED")
            throw LanProtocolException("LAN_NETWORK_CHANGED")
        }
    }

    private fun stop(code: String) {
        if (active.compareAndSet(true, false)) {
            stopCode = code
            outboundCodec?.destroy()
            outboundCodec = null
            inboundReceiver?.close()
            inboundReceiver = null
            token?.close()
            token = null
            socket?.closeQuietly()
            socket = null
            invitation?.close()
            invitation = null
        }
    }

    companion object {
        internal fun established(
            socket: LanSocketPort,
            invitation: LanInvitation,
            outboundKey: ByteArray,
            inboundKey: ByteArray,
            tokenBindingKey: ByteArray,
            transcriptHash: ByteArray,
            networkIdentity: LanNetworkIdentity,
            clock: LanClock,
            localInterface: LanLocalInterface,
            desktopFingerprint: String,
            expiresAt: Instant,
        ): LanOutboundSession {
            val sessionToken = LanSessionToken.derive(
                tokenBindingKey = tokenBindingKey,
                transcriptHash = transcriptHash,
                invitationId = invitation.invitationId,
                expiresAt = expiresAt,
            )
            return try {
                LanOutboundSession(
                    socket = socket,
                    invitation = invitation,
                    outboundCodec = LanFrameCodec(
                        key = outboundKey,
                        transcriptHash = transcriptHash,
                        direction = LanFrameDirection.CLIENT_TO_DESKTOP,
                    ),
                    inboundReceiver = LanFrameReceiver(
                        key = inboundKey,
                        transcriptHash = transcriptHash,
                    ),
                    token = sessionToken,
                    networkIdentity = networkIdentity,
                    clock = clock,
                    localInterface = localInterface,
                    desktopFingerprint = desktopFingerprint,
                    expiresAt = expiresAt,
                    stopCode = null,
                )
            } catch (error: Exception) {
                sessionToken.close()
                throw error
            }
        }

        fun restoreAfterProcessDeath(
            desktopFingerprint: String,
            localInterface: LanLocalInterface,
            expiresAt: Instant,
        ): LanOutboundSession = LanOutboundSession(
            socket = null,
            outboundCodec = null,
            inboundReceiver = null,
            token = null,
            invitation = null,
            networkIdentity = null,
            clock = null,
            localInterface = localInterface,
            desktopFingerprint = desktopFingerprint,
            expiresAt = expiresAt,
            stopCode = "LAN_PROCESS_RESTORED_RECONFIRM_REQUIRED",
        )

        private const val READ_POLL_MILLIS = 250
        private const val RESPONSE_FRAME_TYPE = "bridge.response"
    }
}

private fun decode32For(value: String, code: String): ByteArray = try {
    LanBase64Url.decode32(value)
} catch (error: LanProtocolException) {
    throw LanProtocolException(code, cause = error)
}

private fun requireExactKeys(value: JsonObject, keys: Set<String>, code: String) {
    if (value.keys != keys) throw LanProtocolException(code)
}

private fun JsonObject.string(name: String): String {
    val primitive = get(name)?.jsonPrimitive ?: throw LanProtocolException(
        "LAN_TRANSCRIPT_MISMATCH",
    )
    if (!primitive.isString) throw LanProtocolException("LAN_TRANSCRIPT_MISMATCH")
    return primitive.content
}

private fun JsonObject.array(name: String): JsonArray =
    try {
        getValue(name).jsonArray
    } catch (error: Exception) {
        throw LanProtocolException("LAN_TRANSCRIPT_MISMATCH", cause = error)
    }

private fun JsonArray.mapStrictStrings(code: String): List<String> =
    map { element ->
        val primitive = element as? JsonPrimitive ?: throw LanProtocolException(code)
        if (!primitive.isString) throw LanProtocolException(code)
        primitive.content
    }

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val limit = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until limit) {
        val difference =
            (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return leftBytes.size - rightBytes.size
}

private fun AutoCloseable.closeQuietly() {
    runCatching(::close)
}
