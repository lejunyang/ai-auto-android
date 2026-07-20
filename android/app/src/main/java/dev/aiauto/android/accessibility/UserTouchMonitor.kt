package dev.aiauto.android.accessibility

// 功能用途：实现 UserTouchMonitor 对应的无障碍观察、截图、动作或用户触摸安全控制。

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.snapshot.recycleSafely

internal data class TouchMonitoringConfiguration(
    val eventTypes: Int,
    val flags: Int,
    val motionEventSources: Int,
)

internal data class AutomationNodeIdentity(
    val packageName: String,
    val windowId: Int,
    val uniqueId: String?,
    val nodeIdentityHash: Int,
    val resourceId: String?,
    val className: String?,
    val bounds: UiBounds,
) {
    fun isStrong(): Boolean =
        !uniqueId.isNullOrBlank() ||
            nodeIdentityHash != 0

    fun matches(other: AutomationNodeIdentity): Boolean {
        if (packageName != other.packageName || windowId != other.windowId) {
            return false
        }
        if (uniqueId != null || other.uniqueId != null) {
            return uniqueId != null && uniqueId == other.uniqueId
        }
        return nodeIdentityHash == other.nodeIdentityHash &&
            resourceId == other.resourceId &&
            className == other.className &&
            bounds == other.bounds
    }
}

internal data class LegacyInteractionSignal(
    val eventType: Int,
    val packageName: String,
    val eventTimeMs: Long,
    val sourceIdentity: AutomationNodeIdentity?,
)

internal data class AutomationActionToken(
    val value: Long,
)

internal class UserTouchMonitor(
    private val notifyUserTouch: (String) -> Boolean,
    private val currentSessionId: (String) -> Long? = { null },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    private var expectedAction: ExpectedAutomationAction? = null
    private var nextToken = 1L

    fun configuration(
        eventTypes: Int,
        flags: Int,
        motionEventSources: Int,
    ): TouchMonitoringConfiguration = TouchMonitoringConfiguration(
        // 只观察普通 View 语义事件；触摸探索和原始 MotionEvent 会改变部分设备的正常触控语义。
        eventTypes = eventTypes or LEGACY_INTERACTION_EVENTS,
        flags = flags and
            AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS.inv() and
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv(),
        motionEventSources = 0,
    )

    @Synchronized
    fun armAutomationAction(
        sourceIdentity: AutomationNodeIdentity,
        eventBudgets: Map<Int, Int>,
        timeoutMs: Long = EXPECTED_EVENT_TIMEOUT_MS,
    ): AutomationActionToken? {
        if (
            !sourceIdentity.isStrong() ||
            timeoutMs <= 0L
        ) {
            return null
        }
        clearExpiredActionLocked()
        if (expectedAction != null) {
            return null
        }
        val monitoredBudgets = eventBudgets
            .filterKeys { it in LEGACY_INTERACTION_EVENT_TYPES }
            .filterValues { it > 0 }
        if (monitoredBudgets.isEmpty()) {
            return null
        }
        val startedAtMs = nowMs()
        val token = AutomationActionToken(nextToken++)
        expectedAction = ExpectedAutomationAction(
            token = token,
            sourceIdentity = sourceIdentity,
            sessionId = currentSessionId(sourceIdentity.packageName),
            remainingEventBudgets = monitoredBudgets.toMutableMap(),
            startedAtMs = startedAtMs,
            expiresAtMs = startedAtMs + timeoutMs,
        )
        return token
    }

    fun runAttributedAction(
        source: AccessibilityNodeInfo,
        eventBudgets: Map<Int, Int>,
        timeoutMs: Long = EXPECTED_EVENT_TIMEOUT_MS,
        action: () -> Boolean,
    ): Boolean {
        val identity = source.toAutomationNodeIdentity() ?: return false
        val token = armAutomationAction(identity, eventBudgets, timeoutMs) ?: return false
        return try {
            action().also { accepted ->
                if (accepted) {
                    commitAutomationAction(token)
                } else {
                    abortAutomationAction(token)
                }
            }
        } catch (error: RuntimeException) {
            abortAutomationAction(token)
            throw error
        }
    }

    fun requiresStrictAttribution(): Boolean = true

    @Synchronized
    fun commitAutomationAction(token: AutomationActionToken): Boolean {
        val expected = expectedAction?.takeIf { it.token == token } ?: return false
        expected.lifecycle = AutomationActionLifecycle.COMMITTED
        expected.pendingSignals.forEach(expected::consume)
        expected.pendingSignals.clear()
        if (expected.remainingEventBudgets.isEmpty()) {
            expectedAction = null
        }
        return true
    }

    fun abortAutomationAction(token: AutomationActionToken): Boolean {
        val pendingPackage = synchronized(this) {
            val expected = expectedAction?.takeIf { it.token == token } ?: return false
            expectedAction = null
            expected.pendingSignals.firstOrNull()?.packageName
        }
        if (pendingPackage != null) {
            notifyUserTouch(pendingPackage)
        }
        return true
    }

    @Synchronized
    fun clearAutomationActions() {
        expectedAction = null
    }

    fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString()
        if (packageName.isNullOrBlank() || event.eventType !in monitoredEventTypes()) {
            return false
        }
        val source = event.source
        val identity = try {
            source?.toAutomationNodeIdentity()
        } finally {
            source?.recycleSafely()
        }
        return onLegacyInteraction(
            LegacyInteractionSignal(
                eventType = event.eventType,
                packageName = packageName,
                eventTimeMs = event.eventTime,
                sourceIdentity = identity,
            ),
        )
    }

    fun onLegacyInteraction(signal: LegacyInteractionSignal): Boolean {
        // 只有会话、窗口、节点身份、事件预算和生命周期全部匹配时，事件才归因给自动化动作。
        val automationEvent = synchronized(this) {
            clearExpiredActionLocked()
            val expected = expectedAction
            if (
                expected == null ||
                !expected.matches(signal, currentSessionId(signal.packageName))
            ) {
                false
            } else {
                when (expected.lifecycle) {
                    AutomationActionLifecycle.ARMED -> expected.pendingSignals += signal
                    AutomationActionLifecycle.COMMITTED -> {
                        expected.consume(signal)
                        if (expected.remainingEventBudgets.isEmpty()) {
                            expectedAction = null
                        }
                    }
                }
                true
            }
        }
        if (automationEvent) {
            return false
        }
        val handled = notifyUserTouch(signal.packageName)
        clearAutomationActions()
        return handled
    }

    private fun AccessibilityNodeInfo.toAutomationNodeIdentity(): AutomationNodeIdentity? {
        val packageName = packageName?.toString()?.takeIf(String::isNotBlank) ?: return null
        val screenBounds = Rect()
        getBoundsInScreen(screenBounds)
        return AutomationNodeIdentity(
            packageName = packageName,
            windowId = windowId,
            uniqueId = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                sdkInt >= Build.VERSION_CODES.TIRAMISU
            ) {
                uniqueId
            } else {
                null
            },
            nodeIdentityHash = hashCode(),
            resourceId = viewIdResourceName,
            className = className?.toString(),
            bounds = UiBounds(
                left = screenBounds.left,
                top = screenBounds.top,
                right = screenBounds.right,
                bottom = screenBounds.bottom,
            ),
        )
    }

    private fun monitoredEventTypes(): Set<Int> = LEGACY_INTERACTION_EVENT_TYPES

    @Synchronized
    private fun clearExpiredActionLocked() {
        if (expectedAction?.expiresAtMs?.let { it < nowMs() } == true) {
            expectedAction = null
        }
    }

    private data class ExpectedAutomationAction(
        val token: AutomationActionToken,
        val sourceIdentity: AutomationNodeIdentity,
        val sessionId: Long?,
        val remainingEventBudgets: MutableMap<Int, Int>,
        val startedAtMs: Long,
        val expiresAtMs: Long,
        var lifecycle: AutomationActionLifecycle = AutomationActionLifecycle.ARMED,
        val pendingSignals: MutableList<LegacyInteractionSignal> = mutableListOf(),
    ) {
        fun matches(signal: LegacyInteractionSignal, currentSessionId: Long?): Boolean =
            sessionId == currentSessionId &&
                signal.eventType in remainingEventBudgets &&
                signal.eventTimeMs in startedAtMs..expiresAtMs &&
                signal.sourceIdentity?.let(sourceIdentity::matches) == true

        fun consume(signal: LegacyInteractionSignal) {
            val remaining = remainingEventBudgets[signal.eventType] ?: return
            if (remaining <= 1) {
                remainingEventBudgets.remove(signal.eventType)
            } else {
                remainingEventBudgets[signal.eventType] = remaining - 1
            }
        }
    }

    private enum class AutomationActionLifecycle {
        ARMED,
        COMMITTED,
    }

    private companion object {
        const val EXPECTED_EVENT_TIMEOUT_MS = 1_000L
        val LEGACY_INTERACTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
        val LEGACY_INTERACTION_EVENTS = LEGACY_INTERACTION_EVENT_TYPES
            .fold(0) { result, eventType -> result or eventType }
    }
}
