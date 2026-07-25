package dev.aiauto.android.bridge.lan

/**
 * 功能用途：承载 N36 invitation 的公开字段，并提供不包含 nonce 或密钥的 UI 摘要。
 */

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class LanInterface(
    val id: String,
    val name: String,
    val kind: String,
)

data class LanAddressCandidate(
    val host: String,
    val family: String,
    val scope: String,
    val interfaceId: String,
    val zoneId: String?,
    val port: Int,
)

class LanInvitation internal constructor(
    val kind: String,
    val version: String,
    val invitationId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val ttlSeconds: Int,
    internal val nonce: ByteArray,
    val desktopInterface: LanInterface,
    val addressCandidates: List<LanAddressCandidate>,
    internal val desktopEphemeralPublicKey: ByteArray,
    val fingerprint: String,
    val capabilities: List<String>,
    original: JsonObject,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal var original: JsonObject = original
        private set

    val destroyed: Boolean
        get() = closed.get()

    fun summary(): LanInvitationSummary {
        ensureAvailable()
        val digest = LanCrypto.sha256(invitationId.encodeToByteArray())
        return try {
            LanInvitationSummary(
                invitationIdHash = digest.copyOfRange(0, 6).toHex(),
                desktopInterfaceName = desktopInterface.name,
                desktopInterfaceKind = desktopInterface.kind,
                desktopFingerprint = fingerprint,
                expiresAt = expiresAt,
                candidates = addressCandidates,
            )
        } finally {
            digest.fill(0)
        }
    }

    internal fun nonceBase64Url(): String {
        ensureAvailable()
        return LanBase64Url.encode(nonce)
    }

    internal fun desktopPublicKeyCopy(): ByteArray {
        ensureAvailable()
        return desktopEphemeralPublicKey.copyOf()
    }

    internal fun secretBytesForTest(): Pair<ByteArray, ByteArray> =
        nonce to desktopEphemeralPublicKey

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            nonce.fill(0)
            desktopEphemeralPublicKey.fill(0)
            original = JsonObject(emptyMap())
        }
    }

    private fun ensureAvailable() {
        if (closed.get()) throw LanProtocolException("LAN_INVITATION_DESTROYED")
    }
}

data class LanInvitationSummary(
    val invitationIdHash: String,
    val desktopInterfaceName: String,
    val desktopInterfaceKind: String,
    val desktopFingerprint: String,
    val expiresAt: Instant,
    val candidates: List<LanAddressCandidate>,
)

data class LanLocalInterface(
    val id: String,
    val name: String,
    val kind: String,
)

class LanProtocolException(
    val code: String,
    message: String = code,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun interface LanInvitationSecretObserver {
    fun onAllocated(secret: ByteArray)
}

object LanInvitationParser {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun parse(
        payload: String,
        secretObserver: LanInvitationSecretObserver = LanInvitationSecretObserver {},
    ): LanInvitation {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (error: Exception) {
            throw LanProtocolException(
                "LAN_INVITATION_SCHEMA_INVALID",
                "invitation must be a strict JSON object",
                error,
            )
        }
        var nonce: ByteArray? = null
        var publicKey: ByteArray? = null
        var completed = false
        try {
            requireKeys(
                root,
                setOf(
                    "kind",
                    "version",
                    "invitationId",
                    "issuedAt",
                    "expiresAt",
                    "ttlSeconds",
                    "nonce",
                    "listener",
                    "ephemeralKey",
                    "fingerprint",
                    "capabilities",
                    "security",
                ),
            )
            require(root.string("kind") == "ai-auto-lan-invitation")
            require(root.string("version") == "1.0")
            val invitationId = root.string("invitationId")
            require(UUID_PATTERN.matches(invitationId))
            val issuedAt = Instant.parse(root.string("issuedAt"))
            val expiresAt = Instant.parse(root.string("expiresAt"))
            val ttlSeconds = root.integer("ttlSeconds")
            require(ttlSeconds in MIN_TTL_SECONDS..MAX_TTL_SECONDS)
            val nonceValue = root.string("nonce")
            require(BASE64_URL_32.matches(nonceValue))
            val decodedNonce = LanBase64Url.decode32(nonceValue)
            nonce = decodedNonce
            secretObserver.onAllocated(decodedNonce)

            val listener = root.objectValue("listener")
            requireKeys(listener, setOf("port", "selectedInterface", "addressCandidates"))
            val port = listener.integer("port")
            require(port in 1024..65535)
            val selectedInterface = listener.objectValue("selectedInterface")
            requireKeys(selectedInterface, setOf("id", "name", "kind"))
            val desktopInterface = LanInterface(
                id = selectedInterface.string("id").also { require(INTERFACE_ID.matches(it)) },
                name = selectedInterface.string("name").also { require(INTERFACE_NAME.matches(it)) },
                kind = selectedInterface.string("kind").also { require(it in INTERFACE_KINDS) },
            )
            val candidates = listener.arrayValue("addressCandidates")
            require(candidates.size in 1..16)
            val addressCandidates = candidates.map { element ->
                val candidate = element.jsonObject
                val allowed = setOf("host", "family", "scope", "interfaceId", "zoneId")
                requireKeys(candidate, allowed, required = allowed - "zoneId")
                LanAddressCandidate(
                    host = candidate.string("host").also {
                        require(it.length in 2..64 && HOST_PATTERN.matches(it))
                    },
                    family = candidate.string("family").also { require(it in FAMILIES) },
                    scope = candidate.string("scope").also { require(it in SCOPES) },
                    interfaceId = candidate.string("interfaceId").also {
                        require(INTERFACE_ID.matches(it))
                    },
                    zoneId = candidate.optionalString("zoneId")?.also {
                        require(INTERFACE_NAME.matches(it))
                    },
                    port = port,
                )
            }
            require(addressCandidates.distinct() == addressCandidates)

            val ephemeralKey = root.objectValue("ephemeralKey")
            requireKeys(ephemeralKey, setOf("algorithm", "encoding", "publicKey"))
            require(ephemeralKey.string("algorithm") == "X25519")
            require(ephemeralKey.string("encoding") == "base64url")
            val publicKeyValue = ephemeralKey.string("publicKey")
            require(BASE64_URL_32.matches(publicKeyValue))
            val decodedPublicKey = LanBase64Url.decode32(publicKeyValue)
            publicKey = decodedPublicKey
            secretObserver.onAllocated(decodedPublicKey)

            val fingerprint = root.string("fingerprint")
            require(FINGERPRINT_PATTERN.matches(fingerprint))
            val capabilities = root.arrayValue("capabilities").map {
                it.jsonPrimitive.requireString()
            }
            require(capabilities.size in 2..64)
            require(capabilities.distinct() == capabilities)
            require(capabilities.all { CAPABILITY_PATTERN.matches(it) })

            val security = root.objectValue("security")
            requireKeys(security, setOf("suite", "transcript", "confirmation"))
            require(security.string("suite") == SUITE)
            require(security.string("transcript") == TRANSCRIPT_DOMAIN)
            require(security.string("confirmation") == "HMAC-SHA256")

            val invitation = LanInvitation(
                kind = root.string("kind"),
                version = root.string("version"),
                invitationId = invitationId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                ttlSeconds = ttlSeconds,
                nonce = decodedNonce,
                desktopInterface = desktopInterface,
                addressCandidates = addressCandidates,
                desktopEphemeralPublicKey = decodedPublicKey,
                fingerprint = fingerprint,
                capabilities = capabilities,
                original = root,
            )
            completed = true
            return invitation
        } catch (error: LanProtocolException) {
            throw error
        } catch (error: Exception) {
            throw LanProtocolException(
                "LAN_INVITATION_SCHEMA_INVALID",
                "invitation does not match the N36 strict schema",
                error,
            )
        } finally {
            if (!completed) {
                nonce?.fill(0)
                publicKey?.fill(0)
            }
        }
    }

    private fun requireKeys(
        value: JsonObject,
        allowed: Set<String>,
        required: Set<String> = allowed,
    ) {
        require(value.keys == allowed || value.keys.all(allowed::contains) && value.keys.containsAll(required))
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.requireString()

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.jsonPrimitive?.requireString()

    private fun JsonObject.integer(name: String): Int {
        val primitive = getValue(name).jsonPrimitive
        require(!primitive.isString && primitive.booleanOrNull == null && primitive.longOrNull != null)
        return requireNotNull(primitive.intOrNull)
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        getValue(name).jsonObject

    private fun JsonObject.arrayValue(name: String): JsonArray =
        getValue(name).jsonArray

    private fun JsonPrimitive.requireString(): String {
        require(isString)
        return requireNotNull(contentOrNull)
    }

    private const val MIN_TTL_SECONDS = 15
    private const val MAX_TTL_SECONDS = 120
    private const val SUITE = "X25519-HKDF-SHA256-AES-256-GCM"
    private const val TRANSCRIPT_DOMAIN = "AIAUTO-LAN-BRIDGE-TRANSCRIPT-V1"
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val INTERFACE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val INTERFACE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
    private val HOST_PATTERN = Regex("^[0-9A-Fa-f:.]+$")
    private val BASE64_URL_32 = Regex("^[A-Za-z0-9_-]{43}$")
    private val FINGERPRINT_PATTERN = Regex("^[0-9A-F]{4}(?:-[0-9A-F]{4}){3}$")
    private val CAPABILITY_PATTERN = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")
    private val INTERFACE_KINDS = setOf("wifi", "ethernet", "hotspot", "other")
    private val FAMILIES = setOf("ipv4", "ipv6")
    private val SCOPES = setOf("private", "linkLocal")
}

internal fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
