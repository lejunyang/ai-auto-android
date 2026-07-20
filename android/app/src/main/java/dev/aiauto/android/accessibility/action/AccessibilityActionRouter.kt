package dev.aiauto.android.accessibility.action

// 功能用途：实现 AccessibilityActionRouter 对应的无障碍动作路由与坐标规划，供受控设备操作复用。

import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.Gesture
import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.selector.SelectorMatch
import dev.aiauto.android.accessibility.selector.SelectorMatcher

interface AccessibilityNodeSession : AutoCloseable {
    val rootSnapshot: UiNodeSnapshot

    fun perform(
        path: NodePath,
        action: NodeAction,
        text: String? = null,
        expectedEventBudgets: Map<Int, Int> = emptyMap(),
        timeoutMs: Long = 1_000L,
    ): Boolean
}

interface AccessibilityActionBackend {
    fun validateTarget(expectedPackage: String?): AccessibilityResult<Unit>

    fun openNodeSession(expectedPackage: String?): AccessibilityResult<AccessibilityNodeSession>

    fun dispatch(
        gesture: Gesture,
        sourcePath: NodePath? = null,
        expectedEventBudgets: Map<Int, Int> = emptyMap(),
        timeoutMs: Long = 1_000L,
    ): Boolean

    fun performGlobal(action: GlobalAction): Boolean

    fun screenBounds(): ScreenBounds
}

class AccessibilityActionRouter(
    private val backend: AccessibilityActionBackend,
    private val selectorMatcher: SelectorMatcher,
) {
    fun execute(command: AccessibilityCommand): AccessibilityResult<ActionExecution> {
        val expectedPackage = when (command) {
            is AccessibilityCommand.Click -> command.target.packageName
            is AccessibilityCommand.LongClick -> command.target.packageName
            is AccessibilityCommand.SetText -> command.target.packageName
            is AccessibilityCommand.Scroll -> command.target?.packageName
            is AccessibilityCommand.Tap,
            is AccessibilityCommand.Swipe,
            is AccessibilityCommand.Navigate,
            -> null
        }
        when (val validation = backend.validateTarget(expectedPackage)) {
            is AccessibilityResult.Failure -> return validation
            is AccessibilityResult.Success -> Unit
        }

        return when (command) {
            is AccessibilityCommand.Click -> executeClick(command.target)
            is AccessibilityCommand.LongClick -> executeLongClick(command)
            is AccessibilityCommand.SetText -> executeSetText(command)
            is AccessibilityCommand.Scroll -> executeScroll(command)
            is AccessibilityCommand.Tap -> dispatchTap(
                point = command.point,
                durationMs = TAP_DURATION_MS,
                route = ActionRoute.SCREEN_GESTURE,
                expectedEventBudgets = emptyMap(),
            )

            is AccessibilityCommand.Swipe -> executeSwipe(command)
            is AccessibilityCommand.Navigate -> executeGlobal(command.action)
        }
    }

    private fun executeClick(target: NodeTarget): AccessibilityResult<ActionExecution> {
        // 每次执行都从最新快照重新匹配节点；失败时按节点手势、坐标回退的受控顺序降级。
        val attempt = performNodeAction(target, NodeAction.CLICK)
        when (attempt) {
            is NodeAttempt.Performed -> return attempt.execution(ActionRoute.NODE_ACTION)
            is NodeAttempt.Ambiguous -> return attempt.failure()
            is NodeAttempt.BackendFailure -> return attempt.failure
            is NodeAttempt.Found -> {
                val nodeGesture = dispatchTap(
                    point = attempt.match.node.bounds.center(),
                    durationMs = TAP_DURATION_MS,
                    route = ActionRoute.NODE_GESTURE,
                    match = attempt.match,
                    expectedEventBudgets = eventBudgets(
                        AccessibilityEvent.TYPE_VIEW_CLICKED to 1,
                    ),
                )
                if (nodeGesture is AccessibilityResult.Success) {
                    return nodeGesture
                }
            }

            is NodeAttempt.NotFound, NodeAttempt.NoSelector -> Unit
        }

        return dispatchCoordinateFallback(
            target = target,
            durationMs = TAP_DURATION_MS,
            noFallback = when (attempt) {
                is NodeAttempt.NotFound, NodeAttempt.NoSelector ->
                    failure(
                        code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                        message = "No selector matched and no coordinate fallback was available",
                        retryable = true,
                    )

                else -> failure(
                    code = AccessibilityErrorCode.GESTURE_FAILED,
                    message = "The node action and node-center gesture were rejected",
                    retryable = true,
                )
            },
        )
    }

    private fun executeLongClick(
        command: AccessibilityCommand.LongClick,
    ): AccessibilityResult<ActionExecution> {
        if (command.durationMs !in MIN_LONG_CLICK_MS..MAX_LONG_CLICK_MS) {
            return invalidAction("Long-click duration must be between 300 and 10000 ms")
        }

        val attempt = performNodeAction(command.target, NodeAction.LONG_CLICK)
        when (attempt) {
            is NodeAttempt.Performed -> return attempt.execution(ActionRoute.NODE_ACTION)
            is NodeAttempt.Ambiguous -> return attempt.failure()
            is NodeAttempt.BackendFailure -> return attempt.failure
            is NodeAttempt.Found -> {
                val nodeGesture = dispatchTap(
                    point = attempt.match.node.bounds.center(),
                    durationMs = command.durationMs,
                    route = ActionRoute.NODE_GESTURE,
                    match = attempt.match,
                    expectedEventBudgets = eventBudgets(
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED to 1,
                    ),
                )
                if (nodeGesture is AccessibilityResult.Success) {
                    return nodeGesture
                }
            }

            is NodeAttempt.NotFound, NodeAttempt.NoSelector -> Unit
        }

        return dispatchCoordinateFallback(
            target = command.target,
            durationMs = command.durationMs,
            noFallback = when (attempt) {
                is NodeAttempt.NotFound, NodeAttempt.NoSelector ->
                    failure(
                        code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                        message = "No selector matched and no coordinate fallback was available",
                        retryable = true,
                    )

                else -> failure(
                    code = AccessibilityErrorCode.GESTURE_FAILED,
                    message = "The long-click action and gesture were rejected",
                    retryable = true,
                )
            },
        )
    }

    private fun executeSetText(
        command: AccessibilityCommand.SetText,
    ): AccessibilityResult<ActionExecution> {
        if (command.text.length > MAX_TEXT_LENGTH) {
            return invalidAction("Text exceeds 10000 characters")
        }
        return when (
            val attempt = performNodeAction(
                target = command.target,
                action = NodeAction.SET_TEXT,
                text = command.text,
            )
        ) {
            is NodeAttempt.Performed -> attempt.execution(ActionRoute.NODE_ACTION)
            is NodeAttempt.Ambiguous -> attempt.failure()
            is NodeAttempt.BackendFailure -> attempt.failure
            is NodeAttempt.NotFound, NodeAttempt.NoSelector -> failure(
                code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                message = "Text input requires a unique semantic node",
                retryable = true,
            )

            is NodeAttempt.Found -> failure(
                code = if (NodeAction.SET_TEXT in attempt.match.node.actions) {
                    AccessibilityErrorCode.ACTION_FAILED
                } else {
                    AccessibilityErrorCode.ACTION_NOT_SUPPORTED
                },
                message = "The matched node did not accept text input",
                retryable = true,
            )
        }
    }

    private fun executeScroll(
        command: AccessibilityCommand.Scroll,
    ): AccessibilityResult<ActionExecution> {
        if (!command.amount.isFinite() || command.amount <= 0.0 || command.amount > 1.0) {
            return invalidAction("Scroll amount must be greater than 0 and at most 1")
        }

        val target = command.target
        val actions = command.direction.toNodeActions()
        val attempt = target?.let { performNodeAction(it, actions) }
        when (attempt) {
            is NodeAttempt.Performed -> return attempt.execution(ActionRoute.NODE_ACTION)
            is NodeAttempt.Ambiguous -> return attempt.failure()
            is NodeAttempt.BackendFailure -> return attempt.failure
            else -> Unit
        }

        val screen = backend.screenBounds()
        val (region, route, match) = when (attempt) {
            is NodeAttempt.Found -> Triple(
                attempt.match.node.bounds.intersect(screen),
                ActionRoute.NODE_GESTURE,
                attempt.match,
            )

            is NodeAttempt.NotFound, is NodeAttempt.NoSelector -> {
                val recorded = target?.recordedBounds?.intersect(screen)
                if (recorded == null) {
                    return failure(
                        code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                        message = "The scroll target was not found",
                        retryable = true,
                    )
                }
                Triple(recorded ?: screen, ActionRoute.COORDINATE_GESTURE, null)
            }

            null -> Triple(screen, ActionRoute.SCREEN_GESTURE, null)
            is NodeAttempt.Performed,
            is NodeAttempt.Ambiguous,
            is NodeAttempt.BackendFailure,
            -> error("Handled before gesture routing")
        }

        if (region == null) {
            return failure(
                code = AccessibilityErrorCode.INVALID_COORDINATE,
                message = "The scroll region is outside the current screen",
            )
        }
        return when (val planned = GesturePlanner.scroll(region, command.direction, command.amount)) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchSwipe(
                gesture = planned.value,
                route = route,
                match = match,
            )
        }
    }

    private fun executeSwipe(
        command: AccessibilityCommand.Swipe,
    ): AccessibilityResult<ActionExecution> {
        if (command.durationMs !in MIN_SWIPE_MS..MAX_SWIPE_MS) {
            return invalidAction("Swipe duration must be between 1 and 60000 ms")
        }
        return dispatchSwipe(
            gesture = Gesture.Swipe(
                start = command.start,
                end = command.end,
                durationMs = command.durationMs,
            ),
            route = ActionRoute.SCREEN_GESTURE,
        )
    }

    private fun executeGlobal(action: GlobalAction): AccessibilityResult<ActionExecution> =
        if (backend.performGlobal(action)) {
            AccessibilityResult.Success(ActionExecution(route = ActionRoute.GLOBAL_ACTION))
        } else {
            failure(
                code = AccessibilityErrorCode.GLOBAL_ACTION_FAILED,
                message = "The system rejected the global action",
                retryable = true,
            )
        }

    private fun performNodeAction(
        target: NodeTarget,
        action: NodeAction,
        text: String? = null,
    ): NodeAttempt = performNodeAction(
        target = target,
        actions = listOf(action),
        text = text,
    )

    private fun performNodeAction(
        target: NodeTarget,
        actions: List<NodeAction>,
        text: String? = null,
    ): NodeAttempt {
        if (target.selectorCandidates.isEmpty()) {
            return NodeAttempt.NoSelector
        }
        val session = when (val opened = backend.openNodeSession(target.packageName)) {
            is AccessibilityResult.Failure -> return NodeAttempt.BackendFailure(opened)
            is AccessibilityResult.Success -> opened.value
        }
        return session.use {
            when (val match = selectorMatcher.match(session.rootSnapshot, target)) {
                is SelectorMatch.Found -> {
                    val supportedAction = actions.firstOrNull { it in match.node.actions }
                    if (
                        supportedAction != null &&
                        session.perform(
                            path = match.path,
                            action = supportedAction,
                            text = text,
                            expectedEventBudgets = supportedAction.expectedEventBudgets(),
                        )
                    ) {
                        NodeAttempt.Performed(match)
                    } else {
                        NodeAttempt.Found(match)
                    }
                }

                is SelectorMatch.NotFound -> NodeAttempt.NotFound(match.bestScore)
                is SelectorMatch.Ambiguous -> NodeAttempt.Ambiguous(
                    score = match.score,
                    candidateCount = match.candidateCount,
                )
            }
        }
    }

    private fun dispatchCoordinateFallback(
        target: NodeTarget,
        durationMs: Long,
        noFallback: AccessibilityResult.Failure,
    ): AccessibilityResult<ActionExecution> {
        val point = fallbackPoint(target) ?: return noFallback
        return when (point) {
            is AccessibilityResult.Failure -> point
            is AccessibilityResult.Success -> dispatchTap(
                point = point.value,
                durationMs = durationMs,
                route = ActionRoute.COORDINATE_GESTURE,
                expectedEventBudgets = emptyMap(),
            )
        }
    }

    private fun fallbackPoint(target: NodeTarget): AccessibilityResult<ScreenPoint>? {
        val screen = backend.screenBounds()
        if (target.recordedBounds != null && target.relativePoint != null) {
            val relative = CoordinateTransformer.fromRelative(
                point = target.relativePoint,
                recordedBounds = target.recordedBounds,
                screenBounds = screen,
            )
            if (relative is AccessibilityResult.Success) {
                return relative
            }
        }
        return target.normalizedScreenPoint?.let {
            CoordinateTransformer.fromNormalized(it, screen)
        }
    }

    private fun dispatchTap(
        point: ScreenPoint,
        durationMs: Long,
        route: ActionRoute,
        match: SelectorMatch.Found? = null,
        expectedEventBudgets: Map<Int, Int>,
    ): AccessibilityResult<ActionExecution> {
        when (val validated = CoordinateTransformer.validate(point, backend.screenBounds())) {
            is AccessibilityResult.Failure -> return validated
            is AccessibilityResult.Success -> Unit
        }
        return if (
            backend.dispatch(
                gesture = Gesture.Tap(point, durationMs),
                sourcePath = match?.path,
                expectedEventBudgets = expectedEventBudgets,
                timeoutMs = durationMs + EVENT_TIMEOUT_GRACE_MS,
            )
        ) {
            AccessibilityResult.Success(
                ActionExecution(
                    route = route,
                    matchedPath = match?.path,
                    matchScore = match?.score,
                ),
            )
        } else {
            failure(
                code = AccessibilityErrorCode.GESTURE_FAILED,
                message = "The system rejected the tap gesture",
                retryable = true,
            )
        }
    }

    private fun dispatchSwipe(
        gesture: Gesture.Swipe,
        route: ActionRoute,
        match: SelectorMatch.Found? = null,
    ): AccessibilityResult<ActionExecution> {
        val screen = backend.screenBounds()
        val validStart = CoordinateTransformer.validate(gesture.start, screen)
        val validEnd = CoordinateTransformer.validate(gesture.end, screen)
        if (validStart is AccessibilityResult.Failure) {
            return validStart
        }
        if (validEnd is AccessibilityResult.Failure) {
            return validEnd
        }
        return if (
            backend.dispatch(
                gesture = gesture,
                sourcePath = match?.path,
                expectedEventBudgets = if (match == null) {
                    emptyMap()
                } else {
                    eventBudgets(AccessibilityEvent.TYPE_VIEW_SCROLLED to SCROLL_EVENT_BUDGET)
                },
                timeoutMs = gesture.durationMs + EVENT_TIMEOUT_GRACE_MS,
            )
        ) {
            AccessibilityResult.Success(
                ActionExecution(
                    route = route,
                    matchedPath = match?.path,
                    matchScore = match?.score,
                ),
            )
        } else {
            failure(
                code = AccessibilityErrorCode.GESTURE_FAILED,
                message = "The system rejected the swipe gesture",
                retryable = true,
            )
        }
    }

    private fun NodeAttempt.Performed.execution(
        route: ActionRoute,
    ): AccessibilityResult.Success<ActionExecution> =
        AccessibilityResult.Success(
            ActionExecution(
                route = route,
                matchedPath = match.path,
                matchScore = match.score,
            ),
        )

    private fun NodeAttempt.Ambiguous.failure(): AccessibilityResult.Failure =
        failure(
            code = AccessibilityErrorCode.SELECTOR_AMBIGUOUS,
            message = "Selector matched multiple nodes without a unique winner",
            retryable = true,
            details = mapOf(
                "score" to score.toString(),
                "candidateCount" to candidateCount.toString(),
            ),
        )

    private fun ScrollDirection.toNodeActions(): List<NodeAction> = when (this) {
        ScrollDirection.UP -> listOf(
            NodeAction.SCROLL_UP,
            NodeAction.SCROLL_BACKWARD,
        )

        ScrollDirection.DOWN -> listOf(
            NodeAction.SCROLL_DOWN,
            NodeAction.SCROLL_FORWARD,
        )

        ScrollDirection.LEFT -> listOf(
            NodeAction.SCROLL_LEFT,
            NodeAction.SCROLL_BACKWARD,
        )

        ScrollDirection.RIGHT -> listOf(
            NodeAction.SCROLL_RIGHT,
            NodeAction.SCROLL_FORWARD,
        )

        ScrollDirection.FORWARD -> listOf(NodeAction.SCROLL_FORWARD)
        ScrollDirection.BACKWARD -> listOf(NodeAction.SCROLL_BACKWARD)
    }

    private fun NodeAction.expectedEventBudgets(): Map<Int, Int> = when (this) {
        NodeAction.CLICK -> eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED to 1)
        NodeAction.LONG_CLICK -> eventBudgets(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED to 1)
        NodeAction.SET_TEXT ->
            eventBudgets(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED to TEXT_EVENT_BUDGET)

        NodeAction.SCROLL_FORWARD,
        NodeAction.SCROLL_BACKWARD,
        NodeAction.SCROLL_UP,
        NodeAction.SCROLL_DOWN,
        NodeAction.SCROLL_LEFT,
        NodeAction.SCROLL_RIGHT,
        -> eventBudgets(AccessibilityEvent.TYPE_VIEW_SCROLLED to SCROLL_EVENT_BUDGET)
    }

    private fun eventBudgets(
        vararg budgets: Pair<Int, Int>,
    ): Map<Int, Int> = mapOf(*budgets)

    private fun UiBounds.intersect(screen: ScreenBounds): ScreenBounds? {
        val intersection = ScreenBounds(
            left = maxOf(left, screen.left),
            top = maxOf(top, screen.top),
            right = minOf(right, screen.right),
            bottom = minOf(bottom, screen.bottom),
        )
        return intersection.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun invalidAction(message: String): AccessibilityResult.Failure =
        failure(
            code = AccessibilityErrorCode.INVALID_ACTION,
            message = message,
        )

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

    private sealed interface NodeAttempt {
        data class Performed(val match: SelectorMatch.Found) : NodeAttempt

        data class Found(val match: SelectorMatch.Found) : NodeAttempt

        data class NotFound(val bestScore: Double) : NodeAttempt

        data class Ambiguous(
            val score: Double,
            val candidateCount: Int,
        ) : NodeAttempt

        data class BackendFailure(
            val failure: AccessibilityResult.Failure,
        ) : NodeAttempt

        data object NoSelector : NodeAttempt
    }

    private companion object {
        const val TAP_DURATION_MS = 100L
        const val MIN_LONG_CLICK_MS = 300L
        const val EVENT_TIMEOUT_GRACE_MS = 1_000L
        const val TEXT_EVENT_BUDGET = 2
        const val SCROLL_EVENT_BUDGET = 3
        const val MAX_LONG_CLICK_MS = 10_000L
        const val MIN_SWIPE_MS = 1L
        const val MAX_SWIPE_MS = 60_000L
        const val MAX_TEXT_LENGTH = 10_000
    }
}
