package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiNodeSnapshot

internal sealed interface PlatformScreenshotResult {
    data class Success(val screenshot: AccessibilityScreenshot) : PlatformScreenshotResult

    data class Failure(val errorCode: Int) : PlatformScreenshotResult
}

internal fun interface ScreenshotPlatform {
    fun capture(callback: (PlatformScreenshotResult) -> Unit)
}

internal fun interface HardwareBufferScreenshotEncoder {
    fun encode(
        buffer: HardwareBuffer,
        colorSpace: ColorSpace,
        timestampMs: Long,
    ): AccessibilityScreenshot?
}

internal class AndroidScreenshotPlatform(
    private val service: AccessibilityService,
    private val callbackExecutor: Executor,
    private val encoder: HardwareBufferScreenshotEncoder =
        HardwareBufferScreenshotEncoder(::encodeHardwareBuffer),
) : ScreenshotPlatform {
    override fun capture(callback: (PlatformScreenshotResult) -> Unit) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            callbackExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val encoded = try {
                        encoder.encode(
                            buffer = buffer,
                            colorSpace = result.colorSpace,
                            timestampMs = result.timestamp,
                        )
                    } catch (_: RuntimeException) {
                        null
                    } finally {
                        buffer.close()
                    }
                    callback(
                        encoded?.let(PlatformScreenshotResult::Success)
                            ?: PlatformScreenshotResult.Failure(
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                            ),
                    )
                }

                override fun onFailure(errorCode: Int) {
                    callback(PlatformScreenshotResult.Failure(errorCode))
                }
            },
        )
    }

    private companion object {
        fun encodeHardwareBuffer(
            buffer: HardwareBuffer,
            colorSpace: ColorSpace,
            timestampMs: Long,
        ): AccessibilityScreenshot? {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: run {
                    hardwareBitmap.recycle()
                    return null
                }
            return try {
                val output = ByteArrayOutputStream()
                if (!softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    return null
                }
                AccessibilityScreenshot(
                    pngBytes = output.toByteArray(),
                    width = softwareBitmap.width,
                    height = softwareBitmap.height,
                    timestampMs = timestampMs,
                )
            } finally {
                softwareBitmap.recycle()
                hardwareBitmap.recycle()
            }
        }
    }
}

internal class AccessibilityScreenshotController(
    private val platform: ScreenshotPlatform,
    private val snapshot: (String) -> AccessibilityResult<UiNodeSnapshot>,
    private val isAuthorized: (String) -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    suspend fun capture(targetPackage: String): AccessibilityResult<AccessibilityScreenshot> {
        if (sdkInt < Build.VERSION_CODES.R) {
            return failure(
                code = AccessibilityErrorCode.SCREENSHOT_NOT_SUPPORTED,
                message = "Accessibility screenshots require Android 11 or newer",
            )
        }
        if (!isAuthorized(targetPackage)) {
            return failure(
                code = AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED,
                message = "The active automation session has not authorized screenshots",
            )
        }
        val root = when (val result = snapshot(targetPackage)) {
            is AccessibilityResult.Failure -> return result
            is AccessibilityResult.Success -> result.value
        }
        if (root.containsSensitiveNode()) {
            return failure(
                code = AccessibilityErrorCode.SCREENSHOT_SENSITIVE_CONTENT,
                message = "The active window contains sensitive content",
            )
        }

        return suspendCancellableCoroutine { continuation ->
            platform.capture callback@{ result ->
                if (!continuation.isActive) {
                    if (result is PlatformScreenshotResult.Success) {
                        result.screenshot.close()
                    }
                    return@callback
                }
                continuation.resume(
                    when (result) {
                        is PlatformScreenshotResult.Success ->
                            AccessibilityResult.Success(result.screenshot)

                        is PlatformScreenshotResult.Failure -> result.toAccessibilityFailure()
                    },
                )
            }
        }
    }

    private fun UiNodeSnapshot.containsSensitiveNode(): Boolean =
        state.sensitive || children.any { it.containsSensitiveNode() }

    private fun PlatformScreenshotResult.Failure.toAccessibilityFailure():
        AccessibilityResult.Failure = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> failure(
            code = AccessibilityErrorCode.SCREENSHOT_SECURE_WINDOW,
            message = "Android blocked the screenshot because the active window is secure",
        )

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> failure(
            code = AccessibilityErrorCode.SCREENSHOT_FAILED,
            message = "Android rate-limited the accessibility screenshot",
            retryable = true,
        )

        else -> failure(
            code = AccessibilityErrorCode.SCREENSHOT_FAILED,
            message = "Android could not capture the active window",
            retryable = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
            details = mapOf("platformErrorCode" to errorCode.toString()),
        )
    }

    private fun failure(
        code: AccessibilityErrorCode,
        message: String,
        retryable: Boolean = false,
        details: Map<String, String> = emptyMap(),
    ): AccessibilityResult.Failure = AccessibilityResult.Failure(
        code = code,
        message = message,
        retryable = retryable,
        details = details,
    )
}
