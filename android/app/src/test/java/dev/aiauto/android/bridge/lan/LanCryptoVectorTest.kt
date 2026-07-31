package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证安卓密码实现与共享向量完全一致，并覆盖密钥分域、双方确认、低阶点
 * 拒绝和加密帧篡改等失败关闭边界。
 */

import java.io.File

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanCryptoVectorTest {
    private val invitationPayload = fixture("lan-invitation-v1-valid.json")
    private val invitationObject = Json.parseToJsonElement(invitationPayload).jsonObject
    private val vector = Json.parseToJsonElement(
        fixture("lan-handshake-v1-vector.json"),
    ).jsonObject

    @Test
    fun `fingerprint and transcript match the N36 vector`() {
        val invitation = LanInvitationParser.parse(invitationPayload)
        val clientHello = vector.getValue("clientHello").jsonObject
        val selection = vector.getValue("selection").jsonObject
        val expected = vector.getValue("expected").jsonObject

        assertEquals(expected.string("fingerprint"), LanCrypto.invitationFingerprint(invitationObject))
        assertEquals(
            expected.string("transcriptHashHex"),
            LanCrypto.transcriptHash(invitationObject, clientHello, selection).toHex(),
        )
    }

    @Test
    fun `HKDF keys and role separated confirmations match the N36 vector`() {
        val expected = vector.getValue("expected").jsonObject
        val transcriptHash = expected.string("transcriptHashHex").hexToBytes()
        val sharedSecret = vector.string("sharedSecretHex").hexToBytes()
        val keys = LanCrypto.deriveSessionKeys(sharedSecret, transcriptHash)

        try {
            assertEquals(expected.string("clientToDesktopKeyHex"), keys.clientToDesktop.toHex())
            assertEquals(expected.string("desktopToClientKeyHex"), keys.desktopToClient.toHex())
            assertEquals(expected.string("confirmationKeyHex"), keys.confirmation.toHex())
            assertEquals(expected.string("tokenBindingKeyHex"), keys.tokenBinding.toHex())

            val desktop = LanCrypto.signConfirmation(
                role = LanConfirmationRole.DESKTOP,
                confirmationKey = keys.confirmation,
                transcriptHash = transcriptHash,
            )
            val client = LanCrypto.signConfirmation(
                role = LanConfirmationRole.CLIENT,
                confirmationKey = keys.confirmation,
                transcriptHash = transcriptHash,
            )
            try {
                assertEquals(expected.string("desktopConfirmationHex"), desktop.toHex())
                assertEquals(expected.string("clientConfirmationHex"), client.toHex())
                assertTrue(
                    LanCrypto.verifyConfirmation(
                        LanConfirmationRole.DESKTOP,
                        keys.confirmation,
                        transcriptHash,
                        desktop,
                    ),
                )
                assertFalse(
                    LanCrypto.verifyConfirmation(
                        LanConfirmationRole.CLIENT,
                        keys.confirmation,
                        transcriptHash,
                        desktop,
                    ),
                )
            } finally {
                desktop.fill(0)
                client.fill(0)
            }
        } finally {
            sharedSecret.fill(0)
            transcriptHash.fill(0)
            keys.destroy()
        }
    }

    @Test
    fun `platform X25519 rejects every declared low order point`() {
        listOf(false, true).forEach { forcePortable ->
            val ephemeral = PlatformX25519.generate(forcePortable)
            try {
                vector.getValue("lowOrderPublicKeys").jsonArray.forEach { element ->
                    val lowOrder = LanBase64Url.decode32(
                        element.jsonObject.string("publicKey"),
                    )
                    try {
                        assertTrue(lowOrder.any { it.toInt() != 0 })
                        try {
                            PlatformX25519.sharedSecret(ephemeral.privateKey, lowOrder)
                            fail("${element.jsonObject.string("name")} should fail")
                        } catch (error: LanProtocolException) {
                            assertEquals("LAN_EPHEMERAL_KEY_WEAK", error.code)
                        }
                    } finally {
                        lowOrder.fill(0)
                    }
                }
            } finally {
                ephemeral.destroy()
            }
        }
    }

    @Test
    fun `portable X25519 fallback matches platform shared secret`() {
        val platform = PlatformX25519.generate()
        val portable = PlatformX25519.generate(forcePortable = true)
        try {
            val platformSecret = PlatformX25519.sharedSecret(
                platform.privateKey,
                portable.publicKey,
            )
            val portableSecret = PlatformX25519.sharedSecret(
                portable.privateKey,
                platform.publicKey,
            )
            try {
                assertEquals(32, platformSecret.size)
                assertTrue(platformSecret.contentEquals(portableSecret))
                assertTrue(platformSecret.any { it.toInt() != 0 })
            } finally {
                platformSecret.fill(0)
                portableSecret.fill(0)
            }
        } finally {
            platform.destroy()
            portable.destroy()
        }
    }

    @Test
    fun `AES GCM frame authenticates transcript direction sequence and type`() {
        val key = vector.getValue("expected").jsonObject
            .string("clientToDesktopKeyHex")
            .hexToBytes()
        val transcriptHash = vector.getValue("expected").jsonObject
            .string("transcriptHashHex")
            .hexToBytes()
        val codec = LanFrameCodec(key, transcriptHash, LanFrameDirection.CLIENT_TO_DESKTOP)
        val plaintext = """{"jsonrpc":"2.0","method":"ui.snapshot"}""".encodeToByteArray()
        val frame = codec.encrypt(sequence = 7, type = "rpc", plaintext = plaintext)

        try {
            assertEquals(
                "433244010000000000000007",
                frame.nonce.toHex(),
            )
            assertEquals(
                "1448637c910fdad676db265c2800e11da1f4bb64c653861a29cb2f85567574c9d" +
                    "1e43683f21c2e05fa3a8bad7633bc0225f80834815d194c",
                frame.ciphertext.toHex(),
            )
            assertTrue(
                plaintext.contentEquals(codec.decrypt(sequence = 7, type = "rpc", frame = frame)),
            )
            try {
                codec.decrypt(sequence = 8, type = "rpc", frame = frame)
                fail("changed sequence should invalidate the tag")
            } catch (error: LanProtocolException) {
                assertEquals("LAN_CONFIRMATION_INVALID", error.code)
            }
        } finally {
            plaintext.fill(0)
            frame.destroy()
            codec.destroy()
            key.fill(0)
            transcriptHash.fill(0)
        }
    }

    private companion object {
        fun kotlinx.serialization.json.JsonObject.string(name: String): String =
            getValue(name).jsonPrimitive.content

        fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

        fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun fixture(name: String): String {
            val start = requireNotNull(System.getProperty("user.dir")).let(::File)
            val file = generateSequence(start, File::getParentFile)
                .map { directory -> File(directory, "protocol/fixtures/$name") }
                .firstOrNull(File::isFile)
            return requireNotNull(file) { "Unable to locate protocol fixture $name" }.readText()
        }
    }
}
