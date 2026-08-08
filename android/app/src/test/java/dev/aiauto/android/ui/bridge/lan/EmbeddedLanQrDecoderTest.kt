package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证内置 QR 解码支持 CameraX 四种旋转、只接受 QR 且始终清零输入帧。
 */

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLanQrDecoderTest {
    private val decoder = EmbeddedLanQrDecoder()

    @Test
    fun `decodes qr payload across camera rotations and clears every frame`() {
        val payload = """{"kind":"ai-auto-lan-invitation","version":"1.0"}"""
        val original = qrFrame(payload)

        listOf(0, 90, 180, 270).forEach { rotation ->
            val frame = rotateForCamera(original, rotation)

            assertEquals(payload, decoder.decode(frame))
            assertTrue(frame.luminance.all { it.toInt() == 0 })
        }
    }

    @Test
    fun `blank non qr frame returns null and is cleared`() {
        val frame = QrLuminanceFrame(
            width = 64,
            height = 64,
            rotationDegrees = 0,
            luminance = ByteArray(64 * 64) { 0xff.toByte() },
        )

        assertNull(decoder.decode(frame))
        assertTrue(frame.luminance.all { it.toInt() == 0 })
    }

    @Test
    fun `decodes inverted terminal style qr and clears frame`() {
        val payload = """{"kind":"ai-auto-lan-invitation","theme":"dark"}"""
        val frame = qrFrame(payload).let { original ->
            original.copy(
                luminance = original.luminance.map { value ->
                    (0xff - value.toInt().and(0xff)).toByte()
                }.toByteArray(),
            )
        }

        assertEquals(payload, decoder.decode(frame))
        assertTrue(frame.luminance.all { it.toInt() == 0 })
    }

    @Test
    fun `unsupported camera rotation still clears frame`() {
        val frame = qrFrame("""{"kind":"ai-auto-lan-invitation"}""").copy(
            rotationDegrees = 45,
        )

        runCatching { decoder.decode(frame) }

        assertTrue(frame.luminance.all { it.toInt() == 0 })
    }

    @Test
    fun `camera analyzer copies padded and interleaved y plane without extra bytes`() {
        val analyzer = EmbeddedLanQrAnalyzer(
            decoder = LanQrFrameDecoder { null },
            onResult = {},
        )
        val source = byteArrayOf(
            1, 99, 2, 99, 3, 99, 0, 0,
            4, 99, 5, 99, 6, 99, 0, 0,
        )

        val copied = analyzer.copyLuminance(
            buffer = ByteBuffer.wrap(source),
            width = 3,
            height = 2,
            rowStride = 8,
            pixelStride = 2,
        )

        assertTrue(copied.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))
        analyzer.close()
    }

    @Test
    fun `camera analyzer clears copied frame and closes image independently of decoder`() {
        var decoderFrame: ByteArray? = null
        val analyzer = EmbeddedLanQrAnalyzer(
            decoder = LanQrFrameDecoder { frame ->
                decoderFrame = frame.luminance
                null
            },
            onResult = {},
        )
        val plane = mockk<ImageProxy.PlaneProxy>()
        val imageInfo = mockk<ImageInfo>()
        val image = mockk<ImageProxy>(relaxed = true)
        every { plane.buffer } returns ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        every { plane.rowStride } returns 2
        every { plane.pixelStride } returns 1
        every { image.planes } returns arrayOf(plane)
        every { image.width } returns 2
        every { image.height } returns 2
        every { image.imageInfo } returns imageInfo
        every { imageInfo.rotationDegrees } returns 0

        analyzer.analyze(image)

        assertTrue(decoderFrame?.all { it.toInt() == 0 } == true)
        verify(exactly = 1) { image.close() }
        analyzer.close()
    }

    private fun qrFrame(payload: String): QrLuminanceFrame {
        val matrix = MultiFormatWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            320,
            320,
            mapOf(
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                EncodeHintType.MARGIN to 4,
            ),
        )
        val luminance = ByteArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0 else 0xff.toByte()
        }
        return QrLuminanceFrame(matrix.width, matrix.height, 0, luminance)
    }

    private fun rotateForCamera(
        original: QrLuminanceFrame,
        rotation: Int,
    ): QrLuminanceFrame {
        if (rotation == 0) return original.copy(luminance = original.luminance.copyOf())
        val width = if (rotation in setOf(90, 270)) original.height else original.width
        val height = if (rotation in setOf(90, 270)) original.width else original.height
        val output = ByteArray(original.luminance.size)
        for (targetY in 0 until height) {
            for (targetX in 0 until width) {
                val source = when (rotation) {
                    90 -> targetY to (original.height - 1 - targetX)
                    180 -> (original.width - 1 - targetX) to
                        (original.height - 1 - targetY)
                    else -> (original.width - 1 - targetY) to targetX
                }
                output[targetY * width + targetX] =
                    original.luminance[source.second * original.width + source.first]
            }
        }
        return QrLuminanceFrame(width, height, rotation, output)
    }
}
