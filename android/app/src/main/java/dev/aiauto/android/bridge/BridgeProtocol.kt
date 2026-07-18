package dev.aiauto.android.bridge

import kotlinx.serialization.json.JsonObject

object BridgeLimits {
    const val PORT = 38_383
    const val MAX_MESSAGE_BYTES = 1_048_576
    const val DEFAULT_DEADLINE_MS = 30_000
    const val MAX_DEADLINE_MS = 300_000
    const val MAX_CONNECTIONS = 8
    const val MAX_CONCURRENT_REQUESTS = 4
    const val PAIRING_CODE_TTL_SECONDS = 120L
    const val SESSION_TOKEN_TTL_SECONDS = 900L
    const val REPLAY_WINDOW_SECONDS = 300L
    const val MAX_REPLAY_ENTRIES = 4_096
}

object BridgeProtocol {
    const val VERSION = "1.0"
    const val SERVER_VERSION = "0.1.0"
    const val JSON_RPC_VERSION = "2.0"
    const val LOOPBACK_ADDRESS = "127.0.0.1"
    const val ZERO_UUID = "00000000-0000-4000-8000-000000000000"
}

enum class BridgeErrorCode(
    val wireCode: String,
    val rpcCode: Int,
) {
    INVALID_ARGUMENT("INVALID_ARGUMENT", -32_000),
    CAPABILITY_UNAVAILABLE("CAPABILITY_UNAVAILABLE", -32_001),
    VERSION_INCOMPATIBLE("VERSION_INCOMPATIBLE", -32_002),
    AUTH_REQUIRED("AUTH_REQUIRED", -32_003),
    AUTH_INVALID("AUTH_INVALID", -32_004),
    AUTH_EXPIRED("AUTH_EXPIRED", -32_005),
    REQUEST_REPLAYED("REQUEST_REPLAYED", -32_006),
    DEADLINE_EXCEEDED("DEADLINE_EXCEEDED", -32_007),
    MESSAGE_TOO_LARGE("MESSAGE_TOO_LARGE", -32_008),
    RATE_LIMITED("RATE_LIMITED", -32_009),
    PERMISSION_DENIED("PERMISSION_DENIED", -32_010),
    ACTION_NOT_ALLOWED("ACTION_NOT_ALLOWED", -32_011),
    ACTION_FAILED("ACTION_FAILED", -32_012),
    SELECTOR_NOT_FOUND("SELECTOR_NOT_FOUND", -32_013),
    SELECTOR_AMBIGUOUS("SELECTOR_AMBIGUOUS", -32_014),
    PROTOCOL_ERROR("PROTOCOL_ERROR", -32_015),
    INTERNAL_ERROR("INTERNAL_ERROR", -32_099),
}

class BridgeException(
    val code: BridgeErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
) : Exception(message)

data class BridgeCapability(
    val name: String,
    val available: Boolean,
    val permission: String = "notRequired",
    val reason: String? = null,
    val limits: Map<String, Long> = emptyMap(),
)

interface BridgeMethodHandler {
    fun capabilities(): List<BridgeCapability>

    @Throws(BridgeException::class)
    fun handle(method: String, params: JsonObject): JsonObject
}

data class BridgeConnectionState(
    var helloCompleted: Boolean = false,
    var protocolVersion: String? = null,
)

interface BridgeSessionObserver {
    fun onSessionOpened(hostName: String, expiresAt: java.time.Instant)

    fun onSessionClosed()
}
