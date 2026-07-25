package dev.aiauto.android.automation.session

/**
 * 功能用途：实现显式授权截图与同轮脱敏 hierarchy 的短生命周期视觉 observation 绑定。
 */

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.observe.visual.NormalizedBounds
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.TrustedVisualImageVerifier
import dev.aiauto.android.observe.visual.VisualHierarchyNode
import dev.aiauto.android.observe.visual.VisualObservation
import dev.aiauto.android.observe.visual.VisualObservationCapture
import dev.aiauto.android.observe.visual.VisualObservationStore
import dev.aiauto.android.observe.visual.VisualResult
import dev.aiauto.android.observe.visual.VisualScreen
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class VisualCaptureGeometry(
    val screen: VisualScreen,
    val crop: PixelBounds,
)

/** 生产几何提供者把当前旋转平面转换为自然屏幕尺寸，并保留窗口实际 crop。 */
internal class AndroidVisualCaptureGeometryProvider(context: Context) {
    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext
        .getSystemService(WindowManager::class.java)
    private val displayManager = applicationContext
        .getSystemService(DisplayManager::class.java)

    fun capture(
        hierarchy: UiNodeSnapshot,
        screenshot: AccessibilityScreenshot,
    ): VisualCaptureGeometry {
        val currentBounds = windowManager.maximumWindowMetrics.bounds
        val rotation = when (
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: Surface.ROTATION_0
        ) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val currentWidth = currentBounds.width()
        val currentHeight = currentBounds.height()
        val naturalWidth = if (rotation == 90 || rotation == 270) {
            currentHeight
        } else {
            currentWidth
        }
        val naturalHeight = if (rotation == 90 || rotation == 270) {
            currentWidth
        } else {
            currentHeight
        }
        return VisualCaptureGeometry(
            screen = VisualScreen(naturalWidth, naturalHeight, rotation),
            crop = hierarchy.bounds.toPixelBounds(),
        )
    }
}

/** 视觉 lease 只在 Provider 调用期借出图片，并在关闭时撤销 observation。 */
internal class VisualSessionObservationLease(
    private val store: VisualObservationStore,
    val observation: VisualObservation,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun <T> withProviderImage(use: (ByteArray) -> T): T {
        check(!closed.get()) { "The visual observation lease is closed" }
        return when (val result = store.withImage(observation.id, use)) {
            is VisualResult.Success -> result.value
            is VisualResult.Failure -> throw SessionFailureException(
                "The visual observation image is unavailable: ${result.code.name}",
            )
        }
    }

    internal fun copyProviderImage(): ByteArray =
        withProviderImage(ByteArray::copyOf)

    internal fun hasObservation(): Boolean =
        !closed.get() && store.lookup(observation.id) != null

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        store.revoke(observation.id)
        store.close()
    }
}

/**
 * 工厂只接受已有 Automation Session 授权；截图前后 hierarchy 漂移会失败关闭。
 */
internal class VisualSessionObservationFactory(
    private val screenshotCapture:
        suspend (String) -> AccessibilityResult<AccessibilityScreenshot>,
    private val currentSnapshot: (String) -> AccessibilityResult<UiNodeSnapshot>,
    private val acquireAuthorization: (String) -> ScreenshotAuthorization?,
    private val isAuthorizationActive: (ScreenshotAuthorization) -> Boolean,
    private val captureGeometry: (
        UiNodeSnapshot,
        AccessibilityScreenshot,
    ) -> VisualCaptureGeometry = { hierarchy, _ ->
        VisualCaptureGeometry(
            screen = VisualScreen(
                width = hierarchy.bounds.width,
                height = hierarchy.bounds.height,
                rotation = 0,
            ),
            crop = PixelBounds(
                left = 0,
                top = 0,
                right = hierarchy.bounds.width,
                bottom = hierarchy.bounds.height,
            ),
        )
    },
    private val now: () -> Instant = Instant::now,
    private val observationId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun create(
        targetPackage: String,
        hierarchy: UiNodeSnapshot,
    ): VisualSessionObservationLease {
        if (hierarchy.packageName != targetPackage) {
            throw SessionFailureException(
                "The visual hierarchy package does not match the authorized target.",
            )
        }
        val authorization = acquireAuthorization(targetPackage)
            ?: throw SessionFailureException(
                "Visual observation is not authorized for the active session.",
            )
        if (!isAuthorizationActive(authorization)) {
            throw SessionFailureException(
                "Visual observation authorization is no longer active.",
            )
        }
        val screenshot = when (val result = screenshotCapture(targetPackage)) {
            is AccessibilityResult.Failure -> throw SessionFailureException(result.error.message)
            is AccessibilityResult.Success -> result.value
        }
        try {
            val verifiedHierarchy = when (val result = currentSnapshot(targetPackage)) {
                is AccessibilityResult.Failure ->
                    throw SessionFailureException(result.error.message)

                is AccessibilityResult.Success -> result.value
            }
            if (verifiedHierarchy.packageName != targetPackage) {
                throw SessionFailureException(
                    "The active package changed during visual observation.",
                )
            }
            if (verifiedHierarchy != hierarchy) {
                throw SessionFailureException(
                    "The accessibility hierarchy changed during visual observation.",
                )
            }
            if (!isAuthorizationActive(authorization)) {
                throw SessionFailureException(
                    "Visual observation authorization expired during capture.",
                )
            }
            return register(
                targetPackage = targetPackage,
                hierarchy = hierarchy,
                screenshot = screenshot,
                authorization = authorization,
            )
        } finally {
            screenshot.close()
        }
    }

    private fun register(
        targetPackage: String,
        hierarchy: UiNodeSnapshot,
        screenshot: AccessibilityScreenshot,
        authorization: ScreenshotAuthorization,
    ): VisualSessionObservationLease {
        if (screenshot.width <= 0 || screenshot.height <= 0) {
            throw SessionFailureException("The visual screenshot dimensions are invalid.")
        }
        val geometry = captureGeometry(hierarchy, screenshot)
        if (!geometry.hasCompatibleAspectRatio(screenshot)) {
            throw SessionFailureException(
                "The visual screenshot and crop aspect ratios do not match.",
            )
        }
        val id = observationId()
        val capturedAt = now()
        val store = VisualObservationStore(
            trustedVerifier = TrustedVisualImageVerifier { bytes, context ->
                bytes.isNotEmpty() &&
                    context.observationId == id &&
                    context.pngSizeBytes == bytes.size &&
                    isAuthorizationActive(authorization)
            },
        )
        val result = store.register(
            VisualObservationCapture(
                id = id,
                foregroundPackage = targetPackage,
                screen = geometry.screen,
                crop = geometry.crop,
                capturedAt = capturedAt.toString(),
                expiresAt = capturedAt.plusSeconds(OBSERVATION_TTL_SECONDS).toString(),
                pngBytes = screenshot.pngBytes,
                hierarchy = listOf(mapHierarchy(hierarchy, hierarchy.bounds)),
            ),
        )
        return when (result) {
            is VisualResult.Success -> VisualSessionObservationLease(store, result.value)
            is VisualResult.Failure -> {
                store.close()
                throw SessionFailureException(
                    "The visual observation was rejected: ${result.code.name}",
                )
            }
        }
    }

    private fun mapHierarchy(
        node: UiNodeSnapshot,
        captureBounds: UiBounds,
        inheritedSensitive: Boolean = false,
    ): VisualHierarchyNode {
        if (captureBounds.width <= 0 || captureBounds.height <= 0) {
            throw SessionFailureException("The visual hierarchy bounds are invalid.")
        }
        val sensitive = inheritedSensitive || node.state.sensitive || node.state.password
        val bounds = node.bounds.clampTo(captureBounds)
        val children = node.children.map { child ->
            mapHierarchy(child, captureBounds, sensitive)
        }
        return VisualHierarchyNode(
            role = node.className?.substringAfterLast('.').orEmpty().ifEmpty { "unknown" },
            label = if (sensitive) {
                null
            } else {
                node.text?.takeIf(String::isNotBlank)
                    ?: node.contentDescription?.takeIf(String::isNotBlank)
            },
            bounds = NormalizedBounds(
                left = (bounds.left - captureBounds.left).toDouble() / captureBounds.width,
                top = (bounds.top - captureBounds.top).toDouble() / captureBounds.height,
                right = (bounds.right - captureBounds.left).toDouble() / captureBounds.width,
                bottom = (bounds.bottom - captureBounds.top).toDouble() / captureBounds.height,
            ),
            sensitive = sensitive,
            children = children,
        )
    }

    private fun UiBounds.clampTo(parent: UiBounds): UiBounds {
        val clamped = UiBounds(
            left = left.coerceIn(parent.left, parent.right),
            top = top.coerceIn(parent.top, parent.bottom),
            right = right.coerceIn(parent.left, parent.right),
            bottom = bottom.coerceIn(parent.top, parent.bottom),
        )
        if (clamped.width <= 0 || clamped.height <= 0) {
            throw SessionFailureException(
                "The visual hierarchy contains a node outside the capture bounds.",
            )
        }
        return clamped
    }

    private companion object {
        const val OBSERVATION_TTL_SECONDS = 10L
        const val MAX_ASPECT_RATIO_DELTA = 0.02
    }

    private fun VisualCaptureGeometry.hasCompatibleAspectRatio(
        screenshot: AccessibilityScreenshot,
    ): Boolean {
        val cropWidth = crop.right - crop.left
        val cropHeight = crop.bottom - crop.top
        if (cropWidth <= 0 || cropHeight <= 0) {
            return false
        }
        val cropRatio = cropWidth.toDouble() / cropHeight
        val screenshotRatio = screenshot.width.toDouble() / screenshot.height
        return kotlin.math.abs(cropRatio - screenshotRatio) <= MAX_ASPECT_RATIO_DELTA
    }
}

private fun UiBounds.toPixelBounds() = PixelBounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)
