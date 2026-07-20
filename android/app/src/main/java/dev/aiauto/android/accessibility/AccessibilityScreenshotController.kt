package dev.aiauto.android.accessibility

/**
 * 功能用途：实现 AccessibilityScreenshotController 对应的无障碍观察、截图、动作或用户触摸安全控制。
 */

import android.accessibilityservice.AccessibilityService
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display

import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.session.ScreenshotAuthorization

internal sealed interface PlatformScreenshotResult {
    data class Success(val screenshot: AccessibilityScreenshot) : PlatformScreenshotResult

    data class Failure(val errorCode: Int) : PlatformScreenshotResult
}

internal fun interface ScreenshotPlatform {
    fun capture(
        bounds: UiBounds,
        callback: (PlatformScreenshotResult) -> Unit,
    )
}

internal fun interface HardwareBufferScreenshotEncoder {
    fun encode(
        buffer: HardwareBuffer,
        colorSpace: ColorSpace,
        timestampMs: Long,
        bounds: UiBounds,
        policy: ScreenshotImagePolicy,
    ): AccessibilityScreenshot?
}

internal class AndroidScreenshotPlatform(
    private val service: AccessibilityService,
    private val callbackExecutor: Executor,
    private val imagePolicy: ScreenshotImagePolicy = ScreenshotImagePolicy(),
    private val encoder: HardwareBufferScreenshotEncoder =
        HardwareBufferScreenshotEncoder(ScreenshotImageProcessor::encode),
) : ScreenshotPlatform {
    override fun capture(
        bounds: UiBounds,
        callback: (PlatformScreenshotResult) -> Unit,
    ) {
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
                            bounds = bounds,
                            policy = imagePolicy,
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
}

internal class AccessibilityScreenshotController(
    private val platform: ScreenshotPlatform,
    private val snapshot: (String) -> AccessibilityResult<UiNodeSnapshot>,
    private val acquireAuthorization: (String) -> ScreenshotAuthorization?,
    private val isAuthorizationActive: (ScreenshotAuthorization) -> Boolean,
    private val imagePolicy: ScreenshotImagePolicy = ScreenshotImagePolicy(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    constructor(
        platform: ScreenshotPlatform,
        snapshot: (String) -> AccessibilityResult<UiNodeSnapshot>,
        isAuthorized: (String) -> Boolean,
        imagePolicy: ScreenshotImagePolicy = ScreenshotImagePolicy(),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) : this(
        platform = platform,
        snapshot = snapshot,
        acquireAuthorization = { targetPackage ->
            if (isAuthorized(targetPackage)) {
                ScreenshotAuthorization(
                    sessionId = LEGACY_AUTHORIZATION_SESSION_ID,
                    targetPackage = targetPackage,
                )
            } else {
                null
            }
        },
        isAuthorizationActive = { authorization ->
            isAuthorized(authorization.targetPackage)
        },
        imagePolicy = imagePolicy,
        sdkInt = sdkInt,
    )

    suspend fun capture(targetPackage: String): AccessibilityResult<AccessibilityScreenshot> {
        if (sdkInt < Build.VERSION_CODES.R) {
            return failure(
                code = AccessibilityErrorCode.SCREENSHOT_NOT_SUPPORTED,
                message = "Accessibility screenshots require Android 11 or newer",
            )
        }
        val authorization = acquireAuthorization(targetPackage)
            ?: return failure(
                code = AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED,
                message = "The active automation session has not authorized screenshots",
            )
        val root = when (val result = validateSnapshot(targetPackage)) {
            is AccessibilityResult.Failure -> return result
            is AccessibilityResult.Success -> result.value
        }

        // 截图异步返回时必须重新校验会话代次、前台包和敏感节点，避免授权窗口被竞态复用。
        return suspendCancellableCoroutine { continuation ->
            platform.capture(root.bounds) callback@{ result ->
                if (!continuation.isActive) {
                    if (result is PlatformScreenshotResult.Success) {
                        result.screenshot.close()
                    }
                    return@callback
                }
                val checked = when (result) {
                    is PlatformScreenshotResult.Success ->
                        validateCapturedScreenshot(
                            targetPackage = targetPackage,
                            authorization = authorization,
                            screenshot = result.screenshot,
                        )

                    is PlatformScreenshotResult.Failure -> result.toAccessibilityFailure()
                }
                continuation.resume(checked) { _, resumedResult, _ ->
                    if (resumedResult is AccessibilityResult.Success) {
                        resumedResult.value.close()
                    }
                }
            }
        }
    }

    private fun validateCapturedScreenshot(
        targetPackage: String,
        authorization: ScreenshotAuthorization,
        screenshot: AccessibilityScreenshot,
    ): AccessibilityResult<AccessibilityScreenshot> {
        val failure = when {
            !isAuthorizationActive(authorization) -> failure(
                code = AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED,
                message = "Screenshot authorization expired while Android captured the window",
            )

            else -> when (val snapshotResult = validateSnapshot(targetPackage)) {
                is AccessibilityResult.Failure -> snapshotResult
                is AccessibilityResult.Success -> when {
                    !isAuthorizationActive(authorization) -> failure(
                        code = AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED,
                        message = "Screenshot authorization expired during safety validation",
                    )

                    screenshot.pngBytes.size > imagePolicy.maxPngBytes -> failure(
                        code = AccessibilityErrorCode.SCREENSHOT_FAILED,
                        message = "The local screenshot exceeded the image byte budget",
                    )

                    else -> null
                }
            }
        }
        if (failure != null) {
            // 所有拒绝路径都立即清零并关闭截图，防止未授权图像继续驻留在内存中。
            screenshot.close()
            return failure
        }
        return AccessibilityResult.Success(screenshot)
    }

    private fun validateSnapshot(
        targetPackage: String,
    ): AccessibilityResult<UiNodeSnapshot> {
        val root = when (val result = snapshot(targetPackage)) {
            is AccessibilityResult.Failure -> return result
            is AccessibilityResult.Success -> result.value
        }
        if (root.packageName != targetPackage) {
            return failure(
                code = AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED,
                message = "The active window does not match the screenshot target",
                retryable = true,
            )
        }
        if (root.containsSensitiveNode()) {
            return failure(
                code = AccessibilityErrorCode.SCREENSHOT_SENSITIVE_CONTENT,
                message = "The active window contains sensitive content",
            )
        }
        return AccessibilityResult.Success(root)
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

    private companion object {
        const val LEGACY_AUTHORIZATION_SESSION_ID = 0L
    }
}
