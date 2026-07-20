package dev.aiauto.android.accessibility

// 功能用途：实现 ScreenshotImageProcessor 对应的无障碍观察、截图、动作或用户触摸安全控制。

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiBounds

internal data class ScreenshotImagePolicy(
    val maxLongEdgePx: Int = 1_280,
    val minLongEdgePx: Int = 256,
    val maxPngBytes: Int = 900 * 1_024,
)

internal object ScreenshotImageProcessor {
    fun encode(
        buffer: HardwareBuffer,
        colorSpace: ColorSpace,
        timestampMs: Long,
        requestedBounds: UiBounds,
        policy: ScreenshotImagePolicy,
    ): AccessibilityScreenshot? {
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
        val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: run {
                hardwareBitmap.recycle()
                return null
            }
        return try {
            val crop = requestedBounds.clampTo(softwareBitmap.width, softwareBitmap.height)
                ?: return null
            val current = Bitmap.createBitmap(
                softwareBitmap,
                crop.left,
                crop.top,
                crop.width,
                crop.height,
            )
            encodeWithinBudget(
                bitmap = current,
                timestampMs = timestampMs,
                policy = policy,
            )
        } finally {
            softwareBitmap.recycle()
            hardwareBitmap.recycle()
        }
    }

    private fun encodeWithinBudget(
        bitmap: Bitmap,
        timestampMs: Long,
        policy: ScreenshotImagePolicy,
    ): AccessibilityScreenshot? {
        var current = bitmap.scaleLongEdgeAtMost(policy.maxLongEdgePx)
        try {
            while (true) {
                val pngBytes = current.encodePng() ?: return null
                if (pngBytes.size <= policy.maxPngBytes) {
                    return AccessibilityScreenshot(
                        pngBytes = pngBytes,
                        width = current.width,
                        height = current.height,
                        timestampMs = timestampMs,
                    )
                }
                val currentLongEdge = maxOf(current.width, current.height)
                if (currentLongEdge <= policy.minLongEdgePx) {
                    pngBytes.fill(0)
                    return null
                }
                val ratio = sqrt(policy.maxPngBytes.toDouble() / pngBytes.size)
                    .coerceIn(MIN_SCALE_STEP, MAX_SCALE_STEP)
                pngBytes.fill(0)
                val nextLongEdge = maxOf(
                    policy.minLongEdgePx,
                    (currentLongEdge * ratio).roundToInt(),
                ).coerceAtMost(currentLongEdge - 1)
                current = current.replaceWithScaled(nextLongEdge)
            }
        } finally {
            current.recycle()
        }
    }

    private fun UiBounds.clampTo(width: Int, height: Int): UiBounds? {
        val clamped = UiBounds(
            left = left.coerceIn(0, width),
            top = top.coerceIn(0, height),
            right = right.coerceIn(0, width),
            bottom = bottom.coerceIn(0, height),
        )
        return clamped.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun Bitmap.scaleLongEdgeAtMost(maxLongEdge: Int): Bitmap {
        val currentLongEdge = maxOf(width, height)
        return if (currentLongEdge <= maxLongEdge) {
            this
        } else {
            replaceWithScaled(maxLongEdge)
        }
    }

    private fun Bitmap.replaceWithScaled(targetLongEdge: Int): Bitmap {
        val currentLongEdge = maxOf(width, height)
        val scale = targetLongEdge.toDouble() / currentLongEdge
        val targetWidth = maxOf(1, (width * scale).roundToInt())
        val targetHeight = maxOf(1, (height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        if (scaled !== this) {
            recycle()
        }
        return scaled
    }

    private fun Bitmap.encodePng(): ByteArray? = ByteArrayOutputStream().use { output ->
        if (compress(Bitmap.CompressFormat.PNG, 100, output)) {
            output.toByteArray()
        } else {
            null
        }
    }

    private const val MIN_SCALE_STEP = 0.5
    private const val MAX_SCALE_STEP = 0.85
}
