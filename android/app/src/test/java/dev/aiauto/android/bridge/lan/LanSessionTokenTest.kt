package dev.aiauto.android.bridge.lan

/**
 * 测试用途：固定 Android 与 Go N37 的 LAN token 派生字节，并验证错误 token 只能常量时间失败。
 */

import java.time.Instant
import java.util.Base64

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSessionTokenTest {
    @Test
    fun `token binding formula matches go n37 and rejects malformed values`() {
        val tokenBindingKey =
            "7b567eef0e3f848a88001cf540c628df8da0d1f2b7732975ccba364af051300d"
                .hexToBytes()
        val transcriptHash =
            "2514787ca15ed4adb68993ea37fd5933f1804f9e80b1b239886fe80b867ac9bf"
                .hexToBytes()
        val token = LanSessionToken.derive(
            tokenBindingKey = tokenBindingKey,
            transcriptHash = transcriptHash,
            invitationId = "647d42e0-47c3-4ac8-9e7f-8f8bcf716c41",
            expiresAt = Instant.parse("2026-07-25T10:01:30Z"),
        )

        try {
            assertTrue(token.matches("OV7XA1pDgpzb3v7JGVz8Piza267wb_CIK3JUKuwIFHA"))
            assertFalse(token.matches("PV7XA1pDgpzb3v7JGVz8Piza267wb_CIK3JUKuwIFHA"))
            assertFalse(token.matches("OV7XA1pDgpzb3v7JGVz8Piza267wb_CIK3JUKuwIFHB"))
            assertFalse(token.matches("not-base64url!"))
            assertFalse(token.matches(""))
        } finally {
            token.close()
            tokenBindingKey.fill(0)
            transcriptHash.fill(0)
        }
        assertFalse(token.matches("OV7XA1pDgpzb3v7JGVz8Piza267wb_CIK3JUKuwIFHA"))
    }

    @Test
    fun `rfc3339 nano formatting trims fractional zeros like go`() {
        val tokenBindingKey = ByteArray(32) { (it + 1).toByte() }
        val transcriptHash = ByteArray(32) { (it + 33).toByte() }
        val equivalent = listOf(
            Instant.parse("2026-07-25T10:01:30.123400Z"),
            Instant.ofEpochSecond(
                Instant.parse("2026-07-25T10:01:30Z").epochSecond,
                123_400_000,
            ),
        ).map { expiresAt ->
            LanSessionToken.derive(
                tokenBindingKey,
                transcriptHash,
                "647d42e0-47c3-4ac8-9e7f-8f8bcf716c41",
                expiresAt,
            )
        }

        try {
            val expected = deriveForTest(
                tokenBindingKey,
                transcriptHash,
                "647d42e0-47c3-4ac8-9e7f-8f8bcf716c41",
                "2026-07-25T10:01:30.1234Z",
            )
            assertTrue(equivalent[0].matches(expected))
            assertTrue(equivalent[1].matches(expected))
        } finally {
            equivalent.forEach(LanSessionToken::close)
            tokenBindingKey.fill(0)
            transcriptHash.fill(0)
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun deriveForTest(
        tokenBindingKey: ByteArray,
        transcriptHash: ByteArray,
        invitationId: String,
        expiresAt: String,
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
        mac.update(expiresAt.encodeToByteArray())
        val token = mac.doFinal()
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        } finally {
            token.fill(0)
        }
    }
}
