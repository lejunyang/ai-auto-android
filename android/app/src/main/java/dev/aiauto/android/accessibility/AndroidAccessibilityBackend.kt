package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

import dev.aiauto.android.accessibility.action.AccessibilityActionBackend
import dev.aiauto.android.accessibility.action.AccessibilityNodeSession
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.Gesture
import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely

internal class AndroidAccessibilityBackend(
    private val service: AccessibilityService,
    private val settingsRepository: AccessibilitySettingsRepository,
    private val snapshotter: AccessibilitySnapshotter,
) : AccessibilityActionBackend {
    override fun validateTarget(expectedPackage: String?): AccessibilityResult<Unit> {
        val settings = settingsRepository.load()
        readinessFailure(settings)?.let { return it }
        if (expectedPackage != null && expectedPackage !in settings.targetPackages) {
            return failure(
                code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                message = "The requested package is not in the target package allowlist",
            )
        }

        val root = service.rootInActiveWindow ?: return failure(
            code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
            message = "No active accessibility window is available",
            retryable = true,
        )
        return try {
            val currentPackage = root.packageName?.toString()
            when {
                currentPackage !in settings.targetPackages -> failure(
                    code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                    message = "The active package is not in the target package allowlist",
                )

                expectedPackage != null && currentPackage != expectedPackage -> failure(
                    code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                    message = "The active package does not match the requested target",
                    retryable = true,
                )

                else -> AccessibilityResult.Success(Unit)
            }
        } finally {
            root.recycleSafely()
        }
    }

    override fun openNodeSession(
        expectedPackage: String?,
    ): AccessibilityResult<AccessibilityNodeSession> {
        when (val validation = validateTarget(expectedPackage)) {
            is AccessibilityResult.Failure -> return validation
            is AccessibilityResult.Success -> Unit
        }

        val root = service.rootInActiveWindow ?: return failure(
            code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
            message = "The active accessibility window changed before node lookup",
            retryable = true,
        )
        val currentPackage = root.packageName?.toString()
        val settings = settingsRepository.load()
        if (
            currentPackage !in settings.targetPackages ||
            (expectedPackage != null && currentPackage != expectedPackage)
        ) {
            root.recycleSafely()
            return failure(
                code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                message = "The active package changed before node lookup",
                retryable = true,
            )
        }

        return when (val snapshot = snapshotter.snapshot(root)) {
            is AccessibilityResult.Failure -> {
                root.recycleSafely()
                snapshot
            }

            is AccessibilityResult.Success -> AccessibilityResult.Success(
                AndroidNodeSession(root = root, rootSnapshot = snapshot.value),
            )
        }
    }

    override fun dispatch(gesture: Gesture): Boolean {
        val path = Path()
        val durationMs: Long
        when (gesture) {
            is Gesture.Tap -> {
                path.moveTo(gesture.point.x.toFloat(), gesture.point.y.toFloat())
                durationMs = gesture.durationMs
            }

            is Gesture.Swipe -> {
                path.moveTo(gesture.start.x.toFloat(), gesture.start.y.toFloat())
                path.lineTo(gesture.end.x.toFloat(), gesture.end.y.toFloat())
                durationMs = gesture.durationMs
            }
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(description, null, null)
    }

    override fun performGlobal(action: GlobalAction): Boolean =
        service.performGlobalAction(
            when (action) {
                GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            },
        )

    override fun screenBounds(): ScreenBounds {
        val bounds = service.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics
            .bounds
        return ScreenBounds(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )
    }

    private fun readinessFailure(
        settings: AccessibilitySettings,
    ): AccessibilityResult.Failure? = when {
        !settings.disclosureAccepted -> failure(
            code = AccessibilityErrorCode.DISCLOSURE_REQUIRED,
            message = "The in-app accessibility disclosure has not been accepted",
        )

        settings.targetPackages.isEmpty() -> failure(
            code = AccessibilityErrorCode.TARGET_PACKAGES_NOT_CONFIGURED,
            message = "At least one target package must be configured",
        )

        else -> null
    }

    private fun failure(
        code: AccessibilityErrorCode,
        message: String,
        retryable: Boolean = false,
    ): AccessibilityResult.Failure = AccessibilityResult.Failure(
        code = code,
        message = message,
        retryable = retryable,
    )

    private class AndroidNodeSession(
        private val root: AccessibilityNodeInfo,
        override val rootSnapshot: UiNodeSnapshot,
    ) : AccessibilityNodeSession {
        override fun perform(
            path: NodePath,
            action: NodeAction,
            text: String?,
        ): Boolean {
            val node = findNode(path) ?: return false
            return try {
                when (action) {
                    NodeAction.SET_TEXT -> {
                        if (text == null) {
                            false
                        } else {
                            val arguments = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    text,
                                )
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        }
                    }

                    else -> node.performAction(action.toPlatformAction())
                }
            } finally {
                if (node !== root) {
                    node.recycleSafely()
                }
            }
        }

        override fun close() {
            root.recycleSafely()
        }

        private fun findNode(path: NodePath): AccessibilityNodeInfo? {
            var current = root
            var currentOwned = false
            path.indices.forEach { index ->
                val child = current.getChild(index)
                if (currentOwned) {
                    current.recycleSafely()
                }
                if (child == null) {
                    return null
                }
                current = child
                currentOwned = true
            }
            return current
        }

        private fun NodeAction.toPlatformAction(): Int = when (this) {
            NodeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
            NodeAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            NodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
            NodeAction.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            NodeAction.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            NodeAction.SCROLL_UP ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id

            NodeAction.SCROLL_DOWN ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id

            NodeAction.SCROLL_LEFT ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id

            NodeAction.SCROLL_RIGHT ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
    }
}
