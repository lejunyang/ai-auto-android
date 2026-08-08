package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：提供把 CameraX Y 平面转换为短生命周期灰度帧的分析能力，并确保立即关闭 ImageProxy。
 */

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddedLanQrAnalyzer(
    private val decoder: LanQrFrameDecoder,
    private val onResult: (LanQrScanResult) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val completed = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        var luminance: ByteArray? = null
        try {
            if (completed.get()) return
            val plane = image.planes.firstOrNull() ?: return
            luminance = copyLuminance(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            val payload = decoder.decode(
                QrLuminanceFrame(
                    width = image.width,
                    height = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    luminance = luminance,
                ),
            ) ?: return
            if (completed.compareAndSet(false, true)) {
                onResult(LanQrScanResult.Success(payload))
            }
        } catch (_: Exception) {
            if (completed.compareAndSet(false, true)) {
                onResult(LanQrScanResult.Invalid)
            }
        } finally {
            luminance?.fill(0)
            image.close()
        }
    }

    override fun close() {
        completed.set(true)
    }

    internal fun copyLuminance(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        require(width > 0 && height > 0)
        require(rowStride >= width && pixelStride > 0)
        val source = buffer.duplicate()
        val finalIndex = (height - 1) * rowStride + (width - 1) * pixelStride
        require(finalIndex < source.limit())
        val output = ByteArray(width * height)
        for (row in 0 until height) {
            val rowOffset = row * rowStride
            for (column in 0 until width) {
                output[row * width + column] = source.get(rowOffset + column * pixelStride)
            }
        }
        return output
    }
}
