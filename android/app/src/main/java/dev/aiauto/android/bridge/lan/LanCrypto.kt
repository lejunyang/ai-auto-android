package dev.aiauto.android.bridge.lan

/**
 * 功能用途：按 N36 固定域分隔实现 canonical JSON、X25519、HKDF、双方确认和加密帧。
 */

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.Destroyable

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class LanConfirmationRole(val wireValue: String) {
    CLIENT("client"),
    DESKTOP("desktop"),
}

enum class LanFrameDirection(val wireValue: String, val noncePrefix: Int) {
    CLIENT_TO_DESKTOP("client-to-desktop", 1),
    DESKTOP_TO_CLIENT("desktop-to-client", 2),
}

class LanSessionKeys internal constructor(
    val clientToDesktop: ByteArray,
    val desktopToClient: ByteArray,
    val confirmation: ByteArray,
    val tokenBinding: ByteArray,
) {
    fun destroy() {
        clientToDesktop.fill(0)
        desktopToClient.fill(0)
        confirmation.fill(0)
        tokenBinding.fill(0)
    }
}

class LanX25519KeyPair internal constructor(
    internal val privateKey: PrivateKey,
    val publicKey: ByteArray,
) {
    fun destroy() {
        publicKey.fill(0)
        runCatching { (privateKey as? Destroyable)?.destroy() }
    }
}

object LanBase64Url {
    fun decode32(value: String): ByteArray {
        if (!BASE64_URL_32.matches(value)) {
            throw LanProtocolException(
                "LAN_EPHEMERAL_KEY_INVALID",
                "value must be unpadded base64url containing 32 bytes",
            )
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw LanProtocolException("LAN_EPHEMERAL_KEY_INVALID", cause = error)
        }
        if (decoded.size != 32 || encode(decoded) != value) {
            decoded.fill(0)
            throw LanProtocolException("LAN_EPHEMERAL_KEY_INVALID")
        }
        return decoded
    }

    fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private val BASE64_URL_32 = Regex("^[A-Za-z0-9_-]{43}$")
}

object PlatformX25519 {
    private val x509Prefix = byteArrayOf(
        0x30,
        0x2a,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x6e,
        0x03,
        0x21,
        0x00,
    )

    fun generate(): LanX25519KeyPair {
        val keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val encoded = keyPair.public.encoded
        if (encoded.size < 32) {
            throw LanProtocolException("LAN_EPHEMERAL_KEY_INVALID")
        }
        return LanX25519KeyPair(
            privateKey = keyPair.private,
            publicKey = encoded.copyOfRange(encoded.size - 32, encoded.size),
        )
    }

    fun sharedSecret(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        if (peerPublicKey.size != 32) {
            throw LanProtocolException("LAN_EPHEMERAL_KEY_INVALID")
        }
        if (LanX25519PublicKeys.isLowOrder(peerPublicKey)) {
            throw LanProtocolException("LAN_EPHEMERAL_KEY_WEAK")
        }
        val encoded = x509Prefix + peerPublicKey
        return try {
            val peer = KeyFactory.getInstance("X25519")
                .generatePublic(X509EncodedKeySpec(encoded))
            val agreement = KeyAgreement.getInstance("X25519")
            agreement.init(privateKey)
            agreement.doPhase(peer, true)
            agreement.generateSecret().also { secret ->
                if (secret.size != 32 || secret.all { it.toInt() == 0 }) {
                    secret.fill(0)
                    throw LanProtocolException("LAN_EPHEMERAL_KEY_WEAK")
                }
            }
        } catch (error: LanProtocolException) {
            throw error
        } catch (error: Exception) {
            throw LanProtocolException(
                "LAN_EPHEMERAL_KEY_WEAK",
                "X25519 peer key produced an invalid or all-zero shared secret",
                error,
            )
        } finally {
            encoded.fill(0)
        }
    }
}

object LanX25519PublicKeys {
    /*
     * X25519 会忽略最高位；这些小阶点会导出全零 shared secret，必须在任何网络 I/O
     * 前拒绝。列表与常用 Curve25519 实现的低阶 blacklist 一致。
     */
    private val lowOrderPoints = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224e7dd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map(::hexToBytes)

    fun isLowOrder(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return true
        val normalized = publicKey.copyOf()
        normalized[31] = (normalized[31].toInt() and 0x7f).toByte()
        return try {
            lowOrderPoints.any { point -> MessageDigest.isEqual(normalized, point) }
        } finally {
            normalized.fill(0)
        }
    }

    private fun hexToBytes(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

object LanCrypto {
    private const val FINGERPRINT_DOMAIN = "AIAUTO-LAN-INVITATION-FINGERPRINT-V1"
    private const val TRANSCRIPT_DOMAIN = "AIAUTO-LAN-BRIDGE-TRANSCRIPT-V1"
    private const val HKDF_SALT_DOMAIN = "AIAUTO-LAN-BRIDGE-HKDF-SALT-V1"
    private const val HKDF_INFO_DOMAIN = "AIAUTO-LAN-BRIDGE-KEYS-V1"
    private const val CONFIRMATION_DOMAIN = "AIAUTO-LAN-BRIDGE-CONFIRMATION-V1"

    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    fun invitationFingerprint(invitation: JsonObject): String {
        val withoutFingerprint = JsonObject(invitation.filterKeys { it != "fingerprint" })
        val digest = domainHash(FINGERPRINT_DOMAIN, canonicalize(withoutFingerprint))
        try {
            return digest.copyOfRange(0, 8)
                .toHex()
                .uppercase()
                .chunked(4)
                .joinToString("-")
        } finally {
            digest.fill(0)
        }
    }

    fun transcriptHash(
        invitation: JsonObject,
        clientHello: JsonObject,
        selection: JsonObject,
    ): ByteArray = domainHash(
        TRANSCRIPT_DOMAIN,
        canonicalize(
            JsonObject(
                linkedMapOf(
                    "invitation" to invitation,
                    "clientHello" to clientHello,
                    "selection" to selection,
                ),
            ),
        ),
    )

    fun deriveSessionKeys(
        sharedSecret: ByteArray,
        transcriptHash: ByteArray,
    ): LanSessionKeys {
        if (sharedSecret.size != 32 || sharedSecret.all { it.toInt() == 0 }) {
            throw LanProtocolException("LAN_EPHEMERAL_KEY_WEAK")
        }
        if (transcriptHash.size != 32) {
            throw LanProtocolException("LAN_TRANSCRIPT_MISMATCH")
        }
        val saltInput = domainBytes(HKDF_SALT_DOMAIN, transcriptHash)
        val salt = sha256(saltInput)
        saltInput.fill(0)
        val info = "$HKDF_INFO_DOMAIN\u0000".encodeToByteArray()
        val material = hkdfSha256(sharedSecret, salt, info, 128)
        salt.fill(0)
        info.fill(0)
        return try {
            LanSessionKeys(
                clientToDesktop = material.copyOfRange(0, 32),
                desktopToClient = material.copyOfRange(32, 64),
                confirmation = material.copyOfRange(64, 96),
                tokenBinding = material.copyOfRange(96, 128),
            )
        } finally {
            material.fill(0)
        }
    }

    fun signConfirmation(
        role: LanConfirmationRole,
        confirmationKey: ByteArray,
        transcriptHash: ByteArray,
    ): ByteArray {
        val prefix = "$CONFIRMATION_DOMAIN\u0000${role.wireValue}\u0000".encodeToByteArray()
        val input = prefix + transcriptHash
        prefix.fill(0)
        return try {
            hmacSha256(confirmationKey, input)
        } finally {
            input.fill(0)
        }
    }

    fun verifyConfirmation(
        role: LanConfirmationRole,
        confirmationKey: ByteArray,
        transcriptHash: ByteArray,
        received: ByteArray,
    ): Boolean {
        if (received.size != 32) return false
        val expected = signConfirmation(role, confirmationKey, transcriptHash)
        return try {
            MessageDigest.isEqual(expected, received)
        } finally {
            expected.fill(0)
        }
    }

    internal fun canonicalize(value: JsonElement): ByteArray =
        canonicalString(value).toByteArray(StandardCharsets.UTF_8)

    private fun canonicalString(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonPrimitive -> value.toString()
        is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]") {
            canonicalString(it)
        }
        is JsonObject -> value.keys
            .sortedWith(::compareUtf8)
            .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                "${JsonPrimitive(key)}:${canonicalString(value.getValue(key))}"
            }
    }

    private fun compareUtf8(left: String, right: String): Int {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        val limit = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until limit) {
            val difference =
                (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return leftBytes.size - rightBytes.size
    }

    private fun domainHash(domain: String, canonical: ByteArray): ByteArray {
        val input = domainBytes(domain, canonical)
        canonical.fill(0)
        return try {
            sha256(input)
        } finally {
            input.fill(0)
        }
    }

    private fun domainBytes(domain: String, suffix: ByteArray): ByteArray =
        "$domain\u0000".encodeToByteArray() + suffix

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val pseudoRandomKey = hmacSha256(salt, inputKeyMaterial)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        try {
            while (offset < length) {
                val blockInput = previous + info + byteArrayOf(counter.toByte())
                previous.fill(0)
                previous = hmacSha256(pseudoRandomKey, blockInput)
                blockInput.fill(0)
                val count = minOf(previous.size, length - offset)
                previous.copyInto(output, offset, 0, count)
                offset += count
                counter += 1
            }
            return output
        } finally {
            pseudoRandomKey.fill(0)
            previous.fill(0)
        }
    }

    private fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
}

class LanEncryptedFrame internal constructor(
    internal val nonce: ByteArray,
    internal val ciphertext: ByteArray,
) {
    fun destroy() {
        nonce.fill(0)
        ciphertext.fill(0)
    }
}

class LanFrameCodec(
    key: ByteArray,
    transcriptHash: ByteArray,
    private val direction: LanFrameDirection,
) {
    private val key = key.copyOf()
    private val transcriptHash = transcriptHash.copyOf()

    init {
        require(this.key.size == 32)
        require(this.transcriptHash.size == 32)
    }

    fun encrypt(sequence: Long, type: String, plaintext: ByteArray): LanEncryptedFrame {
        val nonce = nonce(sequence)
        val aad = aad(sequence, type)
        return try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
            cipher.updateAAD(aad)
            LanEncryptedFrame(nonce, cipher.doFinal(plaintext))
        } finally {
            aad.fill(0)
        }
    }

    fun decrypt(sequence: Long, type: String, frame: LanEncryptedFrame): ByteArray {
        val expectedNonce = nonce(sequence)
        if (!MessageDigest.isEqual(expectedNonce, frame.nonce)) {
            expectedNonce.fill(0)
            throw LanProtocolException("LAN_CONFIRMATION_INVALID", "frame nonce mismatch")
        }
        expectedNonce.fill(0)
        val aad = aad(sequence, type)
        return try {
            val cipher = cipher(Cipher.DECRYPT_MODE, frame.nonce)
            cipher.updateAAD(aad)
            cipher.doFinal(frame.ciphertext)
        } catch (error: AEADBadTagException) {
            throw LanProtocolException("LAN_CONFIRMATION_INVALID", "frame tag mismatch", error)
        } finally {
            aad.fill(0)
        }
    }

    fun destroy() {
        key.fill(0)
        transcriptHash.fill(0)
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }

    private fun nonce(sequence: Long): ByteArray =
        ByteBuffer.allocate(12)
            .putInt(direction.noncePrefix)
            .putLong(sequence)
            .array()

    private fun aad(sequence: Long, type: String): ByteArray {
        val directionBytes = direction.wireValue.encodeToByteArray()
        val typeBytes = type.encodeToByteArray()
        return ByteBuffer.allocate(
            transcriptHash.size + 1 + directionBytes.size + Long.SIZE_BYTES +
                Int.SIZE_BYTES + typeBytes.size,
        )
            .put(transcriptHash)
            .put(0)
            .put(directionBytes)
            .putLong(sequence)
            .putInt(typeBytes.size)
            .put(typeBytes)
            .array()
    }
}
