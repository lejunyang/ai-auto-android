package dev.aiauto.android.bridge.lan

/**
 * 功能用途：使用会话绑定密钥派生仅属于当前局域网会话的认证令牌，并严格校验桌面到
 * Android 的加密帧，防止令牌跨信任域复用以及方向、序号或认证标签篡改。
 */

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LanSessionToken private constructor(
    token: ByteArray,
) : AutoCloseable {
    private val token = token.copyOf()
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun matches(encoded: String): Boolean {
        if (closed.get() || !TOKEN_PATTERN.matches(encoded)) return false
        val received = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            received.size == TOKEN_BYTES &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(received) == encoded &&
                MessageDigest.isEqual(token, received)
        } finally {
            received.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            token.fill(0)
        }
    }

    companion object {
        private const val TOKEN_DOMAIN = "AIAUTO-LAN-BRIDGE-SESSION-TOKEN-V1"
        private const val TOKEN_BYTES = 32
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        private val RFC3339_NANO: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendLiteral('Z')
            .toFormatter(Locale.ROOT)
            .withZone(ZoneOffset.UTC)

        fun derive(
            tokenBindingKey: ByteArray,
            transcriptHash: ByteArray,
            invitationId: String,
            expiresAt: Instant,
        ): LanSessionToken {
            require(tokenBindingKey.size == TOKEN_BYTES)
            require(transcriptHash.size == TOKEN_BYTES)
            val domain = TOKEN_DOMAIN.encodeToByteArray()
            val invitation = invitationId.encodeToByteArray()
            val expiration = RFC3339_NANO.format(expiresAt).encodeToByteArray()
            val derived = try {
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(tokenBindingKey, "HmacSHA256"))
                    update(domain)
                    update(0)
                    update(transcriptHash)
                    update(0)
                    update(invitation)
                    update(0)
                    update(expiration)
                    doFinal()
                }
            } finally {
                domain.fill(0)
                invitation.fill(0)
                expiration.fill(0)
            }
            return try {
                LanSessionToken(derived)
            } finally {
                derived.fill(0)
            }
        }
    }
}

class LanOpenedFrame internal constructor(
    val type: String,
    val plaintext: ByteArray,
) : AutoCloseable {
    override fun close() {
        plaintext.fill(0)
    }
}

class LanFrameReceiver(
    key: ByteArray,
    transcriptHash: ByteArray,
) : AutoCloseable {
    private val codec = LanFrameCodec(
        key = key,
        transcriptHash = transcriptHash,
        direction = LanFrameDirection.DESKTOP_TO_CLIENT,
    )
    private var expectedSequence = 0L
    private val stopped = AtomicBoolean(false)

    val closed: Boolean
        get() = stopped.get()

    @Synchronized
    fun open(frame: LanEncryptedFrame): LanOpenedFrame {
        if (closed) throw LanProtocolException("LAN_SESSION_CLOSED")
        if (
            frame.version != "1.0" ||
            frame.direction != LanFrameDirection.DESKTOP_TO_CLIENT ||
            frame.type != REQUEST_FRAME_TYPE ||
            frame.nonce.size != GCM_NONCE_BYTES ||
            frame.ciphertext.size !in GCM_TAG_BYTES..LanFrameCodec.MAX_FRAME_CIPHERTEXT_BYTES
        ) {
            return failClosed("LAN_FRAME_INVALID")
        }
        if (frame.sequence < expectedSequence) {
            return failClosed("LAN_FRAME_REPLAYED")
        }
        if (frame.sequence > expectedSequence) {
            return failClosed("LAN_FRAME_OUT_OF_ORDER")
        }
        val plaintext = try {
            codec.decrypt(expectedSequence, frame.type, frame)
        } catch (error: Exception) {
            close()
            throw LanProtocolException(
                "LAN_FRAME_INVALID",
                "encrypted LAN frame authentication failed",
                error,
            )
        }
        expectedSequence = try {
            Math.addExact(expectedSequence, 1)
        } catch (error: ArithmeticException) {
            plaintext.fill(0)
            close()
            throw LanProtocolException("LAN_SESSION_CLOSED", cause = error)
        }
        return LanOpenedFrame(frame.type, plaintext)
    }

    override fun close() {
        if (stopped.compareAndSet(false, true)) {
            codec.destroy()
        }
    }

    private fun failClosed(code: String): Nothing {
        close()
        throw LanProtocolException(code)
    }

    private companion object {
        const val REQUEST_FRAME_TYPE = "bridge.request"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
    }
}
