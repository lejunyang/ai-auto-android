package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely
import dev.aiauto.android.automation.recording.RecordingRuntime

internal class AndroidAccessibilityBackend(
    private val service: AccessibilityService,
    private val settingsRepository: AccessibilitySettingsRepository,
    private val snapshotter: AccessibilitySnapshotter,
    private val userTouchMonitor: UserTouchMonitor? = null,
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
                AndroidNodeSession(
                    root = root,
                    rootSnapshot = snapshot.value,
                    userTouchMonitor = userTouchMonitor,
                ),
            )
        }
    }

    override fun dispatch(
        gesture: Gesture,
        sourcePath: NodePath?,
        expectedEventBudgets: Map<Int, Int>,
        timeoutMs: Long,
    ): Boolean {
        val source = sourcePath?.let(::findNodeInActiveWindow)
            ?: findUniqueNodeAt(gesture)
        val requiresAttribution = userTouchMonitor?.requiresStrictAttribution() == true
        if (requiresAttribution && source == null) {
            return false
        }
        return try {
            val dispatchAction = {
                dispatchGesture(gesture)
            }
            if (source != null && requiresAttribution) {
                userTouchMonitor?.runAttributedAction(
                    source = source,
                    eventBudgets = expectedEventBudgets.ifEmpty {
                        gesture.expectedEventBudgets()
                    },
                    timeoutMs = timeoutMs,
                    action = dispatchAction,
                ) ?: false
            } else {
                dispatchAction()
            }
        } finally {
            source?.recycleSafely()
        }
    }

    private fun Gesture.anchorPoint(): ScreenPoint = when (this) {
        is Gesture.Tap -> point
        is Gesture.Swipe -> ScreenPoint(
            x = start.x + (end.x - start.x) / 2,
            y = start.y + (end.y - start.y) / 2,
        )
    }

    private fun Gesture.expectedEventBudgets(): Map<Int, Int> = when (this) {
        is Gesture.Tap -> mapOf(AccessibilityEvent.TYPE_VIEW_CLICKED to 1)
        is Gesture.Swipe -> mapOf(AccessibilityEvent.TYPE_VIEW_SCROLLED to GESTURE_EVENT_BUDGET)
    }

    private fun dispatchGesture(gesture: Gesture): Boolean {
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

    private fun findNodeInActiveWindow(path: NodePath): AccessibilityNodeInfo? {
        var current = service.rootInActiveWindow ?: return null
        var currentOwned = true
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

    private fun findUniqueNodeAt(gesture: Gesture): AccessibilityNodeInfo? {
        val point = gesture.anchorPoint()
        val root = service.rootInActiveWindow ?: return null
        val candidates = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var ambiguous = false
        while (candidates.isNotEmpty()) {
            val node = candidates.removeFirst()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val containsPoint = point.x in bounds.left until bounds.right &&
                point.y in bounds.top until bounds.bottom
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (containsPoint && node.isVisibleToUser && gesture.accepts(node) && area > 0) {
                when {
                    area < bestArea -> {
                        best?.recycleSafely()
                        best = AccessibilityNodeInfo(node)
                        bestArea = area
                        ambiguous = false
                    }

                    area == bestArea -> ambiguous = true
                }
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(candidates::addLast)
            }
            node.recycleSafely()
        }
        if (ambiguous) {
            best?.recycleSafely()
            return null
        }
        return best
    }

    private fun Gesture.accepts(node: AccessibilityNodeInfo): Boolean = when (this) {
        is Gesture.Tap -> node.isClickable || node.isLongClickable
        is Gesture.Swipe -> node.isScrollable
    }

    override fun performGlobal(action: GlobalAction): Boolean {
        val performed = service.performGlobalAction(
            when (action) {
                GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            },
        )
        if (performed) {
            RecordingRuntime.publishGlobalAction(action)
        }
        return performed
    }

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
        private val userTouchMonitor: UserTouchMonitor?,
    ) : AccessibilityNodeSession {
        override fun perform(
            path: NodePath,
            action: NodeAction,
            text: String?,
            expectedEventBudgets: Map<Int, Int>,
            timeoutMs: Long,
        ): Boolean {
            val node = findNode(path) ?: return false
            return try {
                val performAction = {
                    performNodeAction(node, action, text)
                }
                if (expectedEventBudgets.isNotEmpty()) {
                    userTouchMonitor?.runAttributedAction(
                        source = node,
                        eventBudgets = expectedEventBudgets,
                        timeoutMs = timeoutMs,
                        action = performAction,
                    ) ?: false
                } else {
                    performAction()
                }
            } finally {
                if (node !== root) {
                    node.recycleSafely()
                }
            }
        }

        private fun performNodeAction(
            node: AccessibilityNodeInfo,
            action: NodeAction,
            text: String?,
        ): Boolean = when (action) {
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

    private companion object {
        const val GESTURE_EVENT_BUDGET = 3
    }
}
