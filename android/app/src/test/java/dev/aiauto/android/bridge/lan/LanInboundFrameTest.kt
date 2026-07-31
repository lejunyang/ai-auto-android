package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证 Android 入站 frame 严格拒绝错误方向、重放、乱序、nonce 和 AAD/tag 篡改。
 */

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanInboundFrameTest {
    @Test
    fun `desktop frames decrypt in strict sequence and receiver destroys plaintext ownership`() {
        val fixture = FrameFixture()
        val receiver = LanFrameReceiver(
            key = fixture.key,
            transcriptHash = fixture.transcriptHash,
        )
        val firstPlaintext = """{"method":"device.info"}""".encodeToByteArray()
        val secondPlaintext = """{"method":"ui.snapshot"}""".encodeToByteArray()
        val first = fixture.desktop.encrypt(0, "bridge.request", firstPlaintext)
        val second = fixture.desktop.encrypt(1, "bridge.request", secondPlaintext)

        try {
            val openedFirst = receiver.open(first)
            val openedSecond = receiver.open(second)
            try {
                assertEquals("bridge.request", openedFirst.type)
                assertArrayEquals(firstPlaintext, openedFirst.plaintext)
                assertArrayEquals(secondPlaintext, openedSecond.plaintext)
            } finally {
                openedFirst.close()
                openedSecond.close()
            }
            assertTrue(openedFirst.plaintext.all { it.toInt() == 0 })
            assertTrue(openedSecond.plaintext.all { it.toInt() == 0 })
        } finally {
            firstPlaintext.fill(0)
            secondPlaintext.fill(0)
            first.destroy()
            second.destroy()
            receiver.close()
            fixture.close()
        }
    }

    @Test
    fun `metadata sequence nonce aad and tag violations fail closed`() {
        val cases = listOf<Pair<String, (FrameFixture, LanEncryptedFrame) -> LanEncryptedFrame>>(
            "LAN_FRAME_INVALID" to { fixture, frame ->
                fixture.copy(frame, direction = LanFrameDirection.CLIENT_TO_DESKTOP)
            },
            "LAN_FRAME_INVALID" to { _, frame -> frame.copyForTest(version = "2.0") },
            "LAN_FRAME_REPLAYED" to { _, frame -> frame.copyForTest(sequence = 0) },
            "LAN_FRAME_OUT_OF_ORDER" to { _, frame -> frame.copyForTest(sequence = 2) },
            "LAN_FRAME_INVALID" to { _, frame ->
                frame.copyForTest(nonce = frame.nonce.copyOf().also { it[0] = 0 })
            },
            "LAN_FRAME_INVALID" to { _, frame ->
                frame.copyForTest(type = "bridge.response")
            },
            "LAN_FRAME_INVALID" to { _, frame ->
                frame.copyForTest(
                    ciphertext = frame.ciphertext.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    },
                )
            },
        )

        cases.forEach { (expectedCode, mutate) ->
            val fixture = FrameFixture()
            val receiver = LanFrameReceiver(fixture.key, fixture.transcriptHash)
            val accepted = fixture.desktop.encrypt(0, "bridge.request", byteArrayOf(1))
            val next = fixture.desktop.encrypt(1, "bridge.request", byteArrayOf(2))
            if (expectedCode != "LAN_FRAME_OUT_OF_ORDER") {
                receiver.open(accepted).close()
            }
            val candidate = if (expectedCode == "LAN_FRAME_REPLAYED") {
                mutate(fixture, accepted)
            } else {
                mutate(fixture, next)
            }
            try {
                assertFailure(expectedCode) { receiver.open(candidate) }
                assertTrue(receiver.closed)
                assertFailure("LAN_SESSION_CLOSED") { receiver.open(next) }
            } finally {
                accepted.destroy()
                next.destroy()
                if (candidate !== accepted && candidate !== next) candidate.destroy()
                receiver.close()
                fixture.close()
            }
        }
    }

    @Test
    fun `transcript aad mismatch fails closed before plaintext is exposed`() {
        val fixture = FrameFixture()
        val changedTranscript = fixture.transcriptHash.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        val receiver = LanFrameReceiver(fixture.key, changedTranscript)
        val frame = fixture.desktop.encrypt(0, "bridge.request", byteArrayOf(7))
        try {
            assertFailure("LAN_FRAME_INVALID") { receiver.open(frame) }
            assertTrue(receiver.closed)
        } finally {
            frame.destroy()
            receiver.close()
            changedTranscript.fill(0)
            fixture.close()
        }
    }

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
            fail("expected $expectedCode")
        } catch (error: LanProtocolException) {
            assertEquals(expectedCode, error.code)
        }
    }

    private class FrameFixture : AutoCloseable {
        val key = ByteArray(32) { (it + 1).toByte() }
        val transcriptHash = ByteArray(32) { (it + 65).toByte() }
        val desktop = LanFrameCodec(
            key = key,
            transcriptHash = transcriptHash,
            direction = LanFrameDirection.DESKTOP_TO_CLIENT,
        )

        fun copy(
            frame: LanEncryptedFrame,
            direction: LanFrameDirection,
        ) = frame.copyForTest(direction = direction)

        override fun close() {
            desktop.destroy()
            key.fill(0)
            transcriptHash.fill(0)
        }
    }

    private companion object {
        fun LanEncryptedFrame.copyForTest(
            version: String = this.version,
            direction: LanFrameDirection = this.direction,
            sequence: Long = this.sequence,
            type: String = this.type,
            nonce: ByteArray = this.nonce.copyOf(),
            ciphertext: ByteArray = this.ciphertext.copyOf(),
        ) = LanEncryptedFrame(
            version = version,
            direction = direction,
            sequence = sequence,
            type = type,
            nonce = nonce,
            ciphertext = ciphertext,
        )
    }
}
