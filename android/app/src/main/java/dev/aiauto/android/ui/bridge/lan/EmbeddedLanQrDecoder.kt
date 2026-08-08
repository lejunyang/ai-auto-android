package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：提供只在内存中把 CameraX 灰度帧解析为有界 QR payload 的能力，并在返回前清零帧副本。
 */

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

data class QrLuminanceFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val luminance: ByteArray,
)

fun interface LanQrFrameDecoder {
    fun decode(frame: QrLuminanceFrame): String?
}

class EmbeddedLanQrDecoder : LanQrFrameDecoder {
    override fun decode(frame: QrLuminanceFrame): String? {
        require(frame.width > 0 && frame.height > 0)
        require(frame.luminance.size == frame.width * frame.height)
        var normalized: NormalizedFrame? = null
        try {
            normalized = rotate(frame)
            val source = PlanarYUVLuminanceSource(
                normalized.bytes,
                normalized.width,
                normalized.height,
                0,
                0,
                normalized.width,
                normalized.height,
                false,
            )
            val payload = decodeSource(source) ?: decodeSource(source.invert()) ?: return null
            return payload.takeIf {
                it.isNotBlank() && it.encodeToByteArray().size <= MAX_QR_PAYLOAD_BYTES
            }
        } finally {
            normalized?.bytes?.fill(0)
            frame.luminance.fill(0)
        }
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val result = runCatching {
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(source)),
                DECODE_HINTS,
            )
        }.getOrNull() ?: return null
        if (result.barcodeFormat != BarcodeFormat.QR_CODE) return null
        return result.text
    }

    private fun rotate(frame: QrLuminanceFrame): NormalizedFrame {
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) {
            return NormalizedFrame(
                width = frame.width,
                height = frame.height,
                bytes = frame.luminance.copyOf(),
            )
        }
        require(rotation in setOf(90, 180, 270))
        val outputWidth = if (rotation in setOf(90, 270)) frame.height else frame.width
        val outputHeight = if (rotation in setOf(90, 270)) frame.width else frame.height
        val output = ByteArray(frame.luminance.size)
        for (sourceY in 0 until frame.height) {
            for (sourceX in 0 until frame.width) {
                val sourceIndex = sourceY * frame.width + sourceX
                val destination = when (rotation) {
                    90 -> (frame.height - 1 - sourceY) to sourceX
                    180 -> (frame.width - 1 - sourceX) to (frame.height - 1 - sourceY)
                    else -> sourceY to (frame.width - 1 - sourceX)
                }
                output[destination.second * outputWidth + destination.first] =
                    frame.luminance[sourceIndex]
            }
        }
        return NormalizedFrame(outputWidth, outputHeight, output)
    }

    private data class NormalizedFrame(
        val width: Int,
        val height: Int,
        val bytes: ByteArray,
    )

    private companion object {
        const val MAX_QR_PAYLOAD_BYTES = 64 * 1024
        val DECODE_HINTS = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            DecodeHintType.TRY_HARDER to true,
        )
    }
}
