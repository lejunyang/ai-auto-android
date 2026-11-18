package dev.aiauto.fixture

import java.nio.ByteBuffer

/** 功能用途：按 YUV 平面真实行列步长采样颜色，提供不依赖截图分析的稳定象限验收文本。 */
object CameraSample {
    fun channel(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int =
        buffer.get(buffer.position() + y * rowStride + x * pixelStride).toInt() and 255

    fun color(y: Int, u: Int, v: Int): String {
        // 接受 YUV 有限范围/全范围的常见转换偏差，仅对高对比验收色给出名称。
        val r = (y + 1.402 * (v - 128)).coerceIn(0.0, 255.0)
        val g = (y - 0.344136 * (u - 128) - 0.714136 * (v - 128)).coerceIn(0.0, 255.0)
        val b = (y + 1.772 * (u - 128)).coerceIn(0.0, 255.0)
        return when {
            r > 170 && g > 170 && b > 170 -> "white"
            r > 170 && g < 90 && b < 90 -> "red"
            r < 90 && g > 170 && b < 90 -> "green"
            r < 90 && g < 90 && b > 170 -> "blue"
            r < 90 && g < 90 && b < 90 -> "black"
            else -> "other"
        }
    }
}
