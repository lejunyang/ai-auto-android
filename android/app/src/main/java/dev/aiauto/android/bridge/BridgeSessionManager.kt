package dev.aiauto.android.bridge

/**
 * 功能用途：实现 BridgeSessionManager 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。
 */

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale

data class PairingCode(
    val value: String,
    val expiresAt: Instant,
)

data class OpenedBridgeSession(
    val token: String,
    val expiresAt: Instant,
)

class BridgeSessionManager(
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
    private val observer: BridgeSessionObserver? = null,
) : BridgeSessionAuthority {
    private var pairingCode: PairingCode? = null
    private var activeSession: ActiveSession? = null

    @Synchronized
    fun issuePairingCode(): PairingCode {
        val hadSession = activeSession != null
        activeSession = null
        if (hadSession) {
            observer?.onSessionClosed()
        }
        val issued = PairingCode(
            value = String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000)),
            expiresAt = clock.instant().plusSeconds(BridgeLimits.PAIRING_CODE_TTL_SECONDS),
        )
        pairingCode = issued
        return issued
    }

    @Synchronized
    @Throws(BridgeException::class)
    override fun open(code: String, hostName: String): OpenedBridgeSession {
        val expected = pairingCode ?: throw BridgeException(
            code = BridgeErrorCode.AUTH_REQUIRED,
            message = "Generate a new pairing code in the Android app.",
        )
        if (!clock.instant().isBefore(expected.expiresAt)) {
            pairingCode = null
            throw BridgeException(
                code = BridgeErrorCode.AUTH_EXPIRED,
                message = "The pairing code has expired.",
            )
        }
        if (!constantTimeEquals(expected.value, code)) {
            throw BridgeException(
                code = BridgeErrorCode.AUTH_INVALID,
                message = "The pairing code is invalid.",
            )
        }

        // 配对码成功使用后立即失效；后续请求只接受随机短期会话 token。
        pairingCode = null
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val token = try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        } finally {
            tokenBytes.fill(0)
        }
        val expiresAt = clock.instant().plusSeconds(BridgeLimits.SESSION_TOKEN_TTL_SECONDS)
        activeSession = ActiveSession(
            token = token,
            expiresAt = expiresAt,
            hostName = hostName,
        )
        observer?.onSessionOpened(hostName, expiresAt)
        return OpenedBridgeSession(token = token, expiresAt = expiresAt)
    }

    @Synchronized
    @Throws(BridgeException::class)
    override fun authenticate(token: String?) {
        if (token.isNullOrEmpty()) {
            throw BridgeException(
                code = BridgeErrorCode.AUTH_REQUIRED,
                message = "A current bridge session token is required.",
            )
        }
        val session = activeSession ?: throw BridgeException(
            code = BridgeErrorCode.AUTH_INVALID,
            message = "The bridge session token is invalid.",
        )
        if (!clock.instant().isBefore(session.expiresAt)) {
            activeSession = null
            observer?.onSessionClosed()
            throw BridgeException(
                code = BridgeErrorCode.AUTH_EXPIRED,
                message = "The bridge session token has expired.",
            )
        }
        if (!constantTimeEquals(session.token, token)) {
            throw BridgeException(
                code = BridgeErrorCode.AUTH_INVALID,
                message = "The bridge session token is invalid.",
            )
        }
    }

    @Synchronized
    @Throws(BridgeException::class)
    override fun close(token: String?) {
        authenticate(token)
        activeSession = null
        observer?.onSessionClosed()
    }

    @Synchronized
    fun stop() {
        pairingCode = null
        val hadSession = activeSession != null
        activeSession = null
        if (hadSession) {
            observer?.onSessionClosed()
        }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

    private data class ActiveSession(
        val token: String,
        val expiresAt: Instant,
        val hostName: String,
    )
}

class RequestReplayCache(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val requestIds = LinkedHashMap<String, Instant>()

    @Synchronized
    @Throws(BridgeException::class)
    fun record(requestId: String) {
        val now = clock.instant()
        val oldestAllowed = now.minusSeconds(BridgeLimits.REPLAY_WINDOW_SECONDS)
        val iterator = requestIds.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isBefore(oldestAllowed)) {
                iterator.remove()
            }
        }
        // requestId 在时间窗内只能使用一次，重试方必须生成新请求而不能重放旧动作。
        if (requestIds.containsKey(requestId)) {
            throw BridgeException(
                code = BridgeErrorCode.REQUEST_REPLAYED,
                message = "The requestId was already used in the replay window.",
            )
        }
        if (requestIds.size >= BridgeLimits.MAX_REPLAY_ENTRIES) {
            throw BridgeException(
                code = BridgeErrorCode.RATE_LIMITED,
                message = "The request replay window is full.",
                retryable = true,
            )
        }
        requestIds[requestId] = now
    }
}
