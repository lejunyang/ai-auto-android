package dev.aiauto.android.accessibility.action

/**
 * 功能用途：实现 AccessibilityActionRouter 对应的无障碍动作路由与坐标规划，供受控设备操作复用。
 */

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.ClipboardOperation
import dev.aiauto.android.accessibility.model.DeviceKey
import dev.aiauto.android.accessibility.model.DragAnchor
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
import dev.aiauto.android.accessibility.model.UiWindowSnapshot
import dev.aiauto.android.accessibility.selector.SelectorMatch
import dev.aiauto.android.accessibility.selector.SelectorMatcher

interface AccessibilityNodeSession : AutoCloseable {
    val rootSnapshot: UiNodeSnapshot

    /**
     * 当前可见窗口的快照，按 z 序从上到下。
     *
     * 默认实现回退为「只有活动窗口」，让不支持多窗口的测试替身与既有实现
     * 无需改动即可编译。生产实现必须给出真实窗口列表，否则弹窗与输入法
     * 场景仍然定位不到节点。
     */
    val windows: List<UiWindowSnapshot>
        get() = emptyList()

    fun perform(
        path: NodePath,
        action: NodeAction,
        text: String? = null,
        arguments: Bundle? = null,
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
        expectedPackage: String? = null,
        expectedEventBudgets: Map<Int, Int> = emptyMap(),
        timeoutMs: Long = 1_000L,
    ): Boolean

    fun performGlobal(action: GlobalAction): Boolean

    /**
     * 发送一个按键。
     *
     * 与 [performGlobal] 分开：全局动作是平台定义的固定集合，
     * 按键则要靠 IME 通道或其它机制模拟，两者的可用性判据完全不同。
     * 默认返回 false 表示「本实现不支持按键」，调用方据此报
     * ACTION_NOT_SUPPORTED 而不是静默成功。
     */
    fun pressKey(key: DeviceKey): Boolean = false

    /**
     * 把输入焦点放到当前可编辑控件上，供 ime.show 使用。
     *
     * 刻意只做「聚焦」：Android 没有与 KEYCODE_BACK 收起键盘对称的
     * 「打开键盘」keycode，无障碍侧也无法查询键盘当前是否可见。系统通常会在
     * 输入控件获得焦点时顺带弹出键盘，但那取决于输入法实现与当前焦点状态。
     * 返回值只表示聚焦是否成功，不表示键盘已经显示。
     * 默认 false 表示本实现不支持，调用方据此报 ACTION_NOT_SUPPORTED。
     */
    fun focusInputControl(): Boolean = false

    fun screenBounds(): ScreenBounds
}

class AccessibilityActionRouter(
    private val backend: AccessibilityActionBackend,
    private val selectorMatcher: SelectorMatcher,
    // 可注入的等待函数。滚动到可见需要在两轮之间等动画落定，
    // 但单测不应真的睡——多轮累加会让测试耗时到秒级。
    private val sleeper: (Long) -> Unit = { millis ->
        runCatching { Thread.sleep(millis) }
    },
) {
    fun execute(command: AccessibilityCommand): AccessibilityResult<ActionExecution> {
        val expectedPackage = when (command) {
            is AccessibilityCommand.Click -> command.target.packageName
            is AccessibilityCommand.LongClick -> command.target.packageName
            is AccessibilityCommand.SetText -> command.target.packageName
            is AccessibilityCommand.Scroll -> command.target?.packageName
            is AccessibilityCommand.Tap -> command.expectedPackage
            is AccessibilityCommand.Swipe -> command.expectedPackage
            is AccessibilityCommand.Navigate -> null
            is AccessibilityCommand.Drag -> command.expectedPackage
                ?: command.start.target?.packageName
            is AccessibilityCommand.MultiTap -> command.expectedPackage
                ?: command.target?.packageName
            is AccessibilityCommand.Pinch -> command.expectedPackage
                ?: command.target?.packageName
            is AccessibilityCommand.Rotate -> command.expectedPackage
                ?: command.target?.packageName
            is AccessibilityCommand.TwoFingerScroll -> command.expectedPackage
                ?: command.target?.packageName
            is AccessibilityCommand.PathGesture -> command.expectedPackage
            is AccessibilityCommand.ScrollIntoView -> command.target.packageName
            is AccessibilityCommand.SelectText -> command.target.packageName
            is AccessibilityCommand.Clipboard -> command.target.packageName
            is AccessibilityCommand.PerformNodeAction -> command.target.packageName
            is AccessibilityCommand.SetProgress -> command.target.packageName
            is AccessibilityCommand.ScrollToPosition -> command.target.packageName
            is AccessibilityCommand.PressKey -> null
            is AccessibilityCommand.ImeShow -> null
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
            is AccessibilityCommand.Tap -> if (
                command.durationMs !in MIN_TAP_MS..MAX_LONG_CLICK_MS
            ) {
                invalidAction("Tap duration must be between 1 and 10000 ms")
            } else {
                dispatchTap(
                    point = command.point,
                    durationMs = command.durationMs,
                    route = ActionRoute.SCREEN_GESTURE,
                    expectedPackage = command.expectedPackage,
                    expectedEventBudgets = emptyMap(),
                )
            }

            is AccessibilityCommand.Swipe -> executeSwipe(command)
            is AccessibilityCommand.Navigate -> executeGlobal(command.action)
            is AccessibilityCommand.Drag -> executeDrag(command)
            is AccessibilityCommand.MultiTap -> executeMultiTap(command)
            is AccessibilityCommand.Pinch -> executePinch(command)
            is AccessibilityCommand.Rotate -> executeRotate(command)
            is AccessibilityCommand.TwoFingerScroll -> executeTwoFingerScroll(command)
            is AccessibilityCommand.PathGesture -> executePathGesture(command)
            is AccessibilityCommand.ScrollIntoView -> executeScrollIntoView(command)
            is AccessibilityCommand.SelectText -> executeSelectText(command)
            is AccessibilityCommand.Clipboard -> executeClipboard(command)
            is AccessibilityCommand.PerformNodeAction -> executeSimpleNodeAction(command)
            is AccessibilityCommand.SetProgress -> executeSetProgress(command)
            is AccessibilityCommand.ScrollToPosition -> executeScrollToPosition(command)
            is AccessibilityCommand.PressKey -> executePressKey(command)
            is AccessibilityCommand.ImeShow -> executeImeShow()
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
        requireScrollEvent: Boolean = true,
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
                requireScrollEvent = requireScrollEvent,
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
            expectedPackage = command.expectedPackage,
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

    /**
     * 执行拖拽。
     *
     * 两端都要先解析成屏幕坐标：拖拽在平台上没有节点级动作，只能靠手势，
     * 因此选择器目标必须先降级为其可见中心点。这与 click 的降级链方向相反——
     * click 先试节点动作，drag 只有手势一条路。
     */
    private fun executeDrag(
        command: AccessibilityCommand.Drag,
    ): AccessibilityResult<ActionExecution> {
        val screen = backend.screenBounds()
        val start = resolveAnchor(command.start, screen)
        if (start is AccessibilityResult.Failure) {
            return start
        }
        val end = resolveAnchor(command.end, screen)
        if (end is AccessibilityResult.Failure) {
            return end
        }
        val startPoint = (start as AccessibilityResult.Success).value
        val endPoint = (end as AccessibilityResult.Success).value
        return when (
            val planned = CompositeGesturePlanner.drag(
                start = startPoint,
                end = endPoint,
                holdMs = command.holdMs,
                moveMs = command.moveMs,
            )
        ) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchComposite(
                gesture = planned.value,
                expectedPackage = command.expectedPackage,
                // 拖拽会先触发长按再产生滚动：预算两者都留，
                // 否则严格归因模式下会把自己发出的事件当成用户触摸。
                expectedEventBudgets = eventBudgets(
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED to 1,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED to SCROLL_EVENT_BUDGET,
                ),
                timeoutMs = command.holdMs + command.moveMs + EVENT_TIMEOUT_GRACE_MS,
            )
        }
    }

    private fun executeMultiTap(
        command: AccessibilityCommand.MultiTap,
    ): AccessibilityResult<ActionExecution> {
        val screen = backend.screenBounds()
        val resolved = resolvePoint(command.point, command.target, screen)
        if (resolved is AccessibilityResult.Failure) {
            return resolved
        }
        return when (
            val planned = CompositeGesturePlanner.multiTap(
                point = (resolved as AccessibilityResult.Success).value,
                taps = command.taps,
            )
        ) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchComposite(
                gesture = planned.value,
                expectedPackage = command.expectedPackage,
                // 每次点击都可能产生一个 CLICKED 事件；双击控件通常只产生一个，
                // 因此按点击次数给上限而不是精确值。
                expectedEventBudgets = eventBudgets(
                    AccessibilityEvent.TYPE_VIEW_CLICKED to command.taps,
                ),
                timeoutMs = MULTI_TAP_TIMEOUT_MS,
            )
        }
    }

    private fun executePinch(
        command: AccessibilityCommand.Pinch,
    ): AccessibilityResult<ActionExecution> {
        val screen = backend.screenBounds()
        val resolved = resolvePoint(command.center, command.target, screen, allowScreenCenter = true)
        if (resolved is AccessibilityResult.Failure) {
            return resolved
        }
        return when (
            val planned = CompositeGesturePlanner.pinch(
                center = (resolved as AccessibilityResult.Success).value,
                scale = command.scale,
                bounds = screen,
                durationMs = command.durationMs,
            )
        ) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchComposite(
                gesture = planned.value,
                expectedPackage = command.expectedPackage,
                // 缩放通常不产生 View 语义事件（它由 View 内部处理），
                // 因此不设预算：设了反而会因等不到事件而误判失败。
                expectedEventBudgets = emptyMap(),
                timeoutMs = command.durationMs + EVENT_TIMEOUT_GRACE_MS,
            )
        }
    }

    private fun executeRotate(
        command: AccessibilityCommand.Rotate,
    ): AccessibilityResult<ActionExecution> {
        val screen = backend.screenBounds()
        val resolved = resolvePoint(command.center, command.target, screen, allowScreenCenter = true)
        if (resolved is AccessibilityResult.Failure) {
            return resolved
        }
        return when (
            val planned = CompositeGesturePlanner.rotate(
                center = (resolved as AccessibilityResult.Success).value,
                degrees = command.degrees,
                bounds = screen,
                durationMs = command.durationMs,
            )
        ) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchComposite(
                gesture = planned.value,
                expectedPackage = command.expectedPackage,
                expectedEventBudgets = emptyMap(),
                timeoutMs = command.durationMs + EVENT_TIMEOUT_GRACE_MS,
            )
        }
    }

    private fun executeTwoFingerScroll(
        command: AccessibilityCommand.TwoFingerScroll,
    ): AccessibilityResult<ActionExecution> {
        val screen = backend.screenBounds()
        val resolved = resolvePoint(command.center, command.target, screen, allowScreenCenter = true)
        if (resolved is AccessibilityResult.Failure) {
            return resolved
        }
        return when (
            val planned = CompositeGesturePlanner.twoFingerScroll(
                center = (resolved as AccessibilityResult.Success).value,
                deltaX = command.deltaX,
                deltaY = command.deltaY,
                bounds = screen,
                durationMs = command.durationMs,
            )
        ) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchComposite(
                gesture = planned.value,
                expectedPackage = command.expectedPackage,
                expectedEventBudgets = eventBudgets(
                    AccessibilityEvent.TYPE_VIEW_SCROLLED to SCROLL_EVENT_BUDGET,
                ),
                timeoutMs = command.durationMs + EVENT_TIMEOUT_GRACE_MS,
            )
        }
    }

    private fun executePathGesture(
        command: AccessibilityCommand.PathGesture,
    ): AccessibilityResult<ActionExecution> =
        when (
            val planned = CompositeGesturePlanner.path(
                points = command.points,
                bounds = backend.screenBounds(),
                durationMs = command.durationMs,
            )
        ) {
            is AccessibilityResult.Failure -> planned
            is AccessibilityResult.Success -> dispatchComposite(
                gesture = planned.value,
                expectedPackage = command.expectedPackage,
                expectedEventBudgets = emptyMap(),
                timeoutMs = command.durationMs + EVENT_TIMEOUT_GRACE_MS,
            )
        }

    /**
     * 反复滚动直到目标可见。
     *
     * 三个终止条件都必要：
     * - 目标出现：成功；
     * - 达到 maxScrolls：列表可能比预期长，或目标根本不在这个列表里；
     * - 无进展：连续两轮快照的容器内容完全一致说明已经滚到尽头，
     *   继续滚只是空转。没有这一条时，短列表上会白等满 maxScrolls 轮。
     */
    private fun executeScrollIntoView(
        command: AccessibilityCommand.ScrollIntoView,
    ): AccessibilityResult<ActionExecution> {
        if (command.maxScrolls < 1 || command.maxScrolls > MAX_SCROLL_ROUNDS) {
            return invalidAction("maxScrolls must be between 1 and $MAX_SCROLL_ROUNDS")
        }
        if (!command.amount.isFinite() || command.amount <= 0.0 || command.amount > 1.0) {
            return invalidAction("Scroll amount must be greater than 0 and at most 1")
        }

        var previousSignature: String? = null
        // 连续无变化的轮数。单轮无变化不足以判定滚到尽头：
        // 实测（API 35，320x640 夹具）确认容器仍可滚动、且相邻轮次签名确实会变，
        // 但某一轮的快照仍可能与上一轮相同——滚动动画与快照采集是两条独立时序，
        // 快照恰好落在动画未推进的窗口内就会得到相同结果。
        // 单轮即判定会把这种时序巧合报成「滚不动了」，因此要求连续多轮无变化。
        var stalledRounds = 0
        repeat(command.maxScrolls + 1) { round ->
            // 每轮都重新开会话取最新快照：滚动后节点树必然变化，
            // 复用上一轮的快照会一直看到目标不存在。
            val session = when (val opened = backend.openNodeSession(command.target.packageName)) {
                is AccessibilityResult.Failure -> return opened
                is AccessibilityResult.Success -> opened.value
            }
            val (match, signature) = session.use {
                val windows = session.windows
                val found = if (windows.isEmpty()) {
                    selectorMatcher.match(session.rootSnapshot, command.target)
                } else {
                    selectorMatcher.match(windows, command.target)
                }
                found to contentSignature(session)
            }
            if (match is SelectorMatch.Found) {
                return AccessibilityResult.Success(
                    ActionExecution(
                        route = if (round == 0) {
                            // 第 0 轮命中说明本来就可见，没有真正滚动过。
                            ActionRoute.NODE_ACTION
                        } else {
                            ActionRoute.NODE_GESTURE
                        },
                        matchedPath = match.path,
                        matchScore = match.score,
                    ),
                )
            }
            if (match is SelectorMatch.Ambiguous) {
                return NodeAttempt.Ambiguous(
                    score = match.score,
                    candidateCount = match.candidateCount,
                ).failure()
            }
            if (round == command.maxScrolls) {
                return failure(
                    code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                    message = "The target did not become visible within ${command.maxScrolls} scrolls",
                    retryable = true,
                    details = mapOf("scrolls" to command.maxScrolls.toString()),
                )
            }
            if (signature == previousSignature) {
                stalledRounds += 1
                if (stalledRounds >= MAX_STALLED_ROUNDS) {
                    return failure(
                        code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                        message = "Scrolling made no progress before the target became visible",
                        retryable = false,
                        details = mapOf(
                            "scrolls" to round.toString(),
                            "stalledRounds" to stalledRounds.toString(),
                        ),
                    )
                }
            } else {
                // 一旦出现变化就重置：连续性是判据，累计次数不是。
                stalledRounds = 0
            }
            previousSignature = signature

            when (
                val scrolled = executeScroll(
                    command = AccessibilityCommand.Scroll(
                        direction = command.direction,
                        target = command.container,
                        amount = command.amount,
                    ),
                    // 逐轮滚动不要求产生滚动事件：容器可能已到尽头。
                    // 见 dispatchSwipe 的 requireScrollEvent 说明。
                    requireScrollEvent = false,
                )
            ) {
                // 滚动被拒不等于 scrollIntoView 失败。
                //
                // 列表滚到尽头后不再提供 ACTION_SCROLL_FORWARD，于是 executeScroll
                // 降级到手势路径；而手势路径带着 match 去 arm 归因，等一个
                // 「已经到底、不会再发生」的 TYPE_VIEW_SCROLLED，必然超时并报
                // GESTURE_FAILED。这是「滚不动了」被误报成「手势被系统拒绝」。
                //
                // 正确做法是把它当成一次无进展：下一轮重新取快照，若目标仍未出现
                // 就由无进展检测或轮次上限给出准确的 SELECTOR_NOT_FOUND，
                // 那才是调用方真正需要知道的结论。
                is AccessibilityResult.Failure -> {
                    if (scrolled.error.code != AccessibilityErrorCode.GESTURE_FAILED) {
                        return scrolled
                    }
                }

                is AccessibilityResult.Success -> Unit
            }
            // 滚动是动画过程，必须等它落定再取下一轮快照。
            // 不等待时下一轮看到的仍是滚动前的内容，签名不变，
            // 于是被无进展检测误判为「已滚到尽头」——实测（API 35）
            // 表现为第 1 轮就返回「Scrolling made no progress」。
            settleAfterScroll()
        }
        return failure(
            code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
            message = "The target did not become visible",
            retryable = true,
        )
    }

    /**
     * 为容器内容生成签名，用于无进展检测。
     *
     * 同时取节点标识（资源 id 与文本）与其屏幕位置。
     *
     * 只取标识不行：ScrollView 这类容器把全部子节点都保留在树里，滚动只改变
     * 它们的坐标，标识集合始终不变，于是首轮就被误判为「已滚到尽头」——
     * 实测（API 35）表现为 scrolls=2 时报 no progress，而列表实际还能滚。
     *
     * 只取坐标也不行：带惯性回弹的列表在尽头仍有微小位移，会被误判为「还有进展」
     * 而白跑满 maxScrolls 轮。
     *
     * 两者一起取才能同时避开这两种误判。
     */
    private fun contentSignature(session: AccessibilityNodeSession): String {
        val roots = session.windows.map { it.root }.ifEmpty { listOf(session.rootSnapshot) }
        val builder = StringBuilder()
        roots.forEach { root -> appendSignature(root, builder) }
        return builder.toString().hashCode().toString()
    }

    private fun appendSignature(node: UiNodeSnapshot, builder: StringBuilder) {
        if (node.state.visibleToUser) {
            builder.append(node.resourceId.orEmpty())
                .append('\u001f')
                .append(node.text.orEmpty())
                .append('\u001f')
                // 坐标必须进签名：容器滚动时子节点标识不变、只有位置变化。
                .append(node.bounds.top)
                .append(',')
                .append(node.bounds.left)
                .append('\u001e')
        }
        node.children.forEach { child -> appendSignature(child, builder) }
    }

    /**
     * 设置文本选区。
     *
     * endIndex 为 null 时选到文本末尾——调用方通常不知道当前文本长度，
     * 让它先查一次快照再算长度既啰嗦又有竞态。这里从匹配到的节点快照取长度。
     *
     * 会先对目标节点做一次 FOCUS。这不是多余步骤：实测（API 35）确认
     * ACTION_SET_SELECTION 在节点未获得输入焦点时被拒绝并返回 false，
     * 报 ACTION_FAILED。平台把选区视为输入状态的一部分，没有焦点就没有插入点。
     * 由调用方自己记得先聚焦是不合理的负担，且漏掉时的错误信息
     * （「节点拒绝了 SET_SELECTION」）完全指不出真实原因。
     */
    private fun executeSelectText(
        command: AccessibilityCommand.SelectText,
    ): AccessibilityResult<ActionExecution> {
        if (command.startIndex < 0) {
            return invalidAction("startIndex must not be negative")
        }
        if (command.endIndex != null && command.endIndex < command.startIndex) {
            return invalidAction("endIndex must not be smaller than startIndex")
        }
        // 聚焦失败不直接终止：部分实现即使没有 ACTION_FOCUS 也允许设置选区，
        // 此时应让 SET_SELECTION 自己去判断，而不是提前拒绝一个可能成功的操作。
        ensureFocused(command.target)
        return performArgumentNodeAction(
            target = command.target,
            action = NodeAction.SET_SELECTION,
            eventBudgets = eventBudgets(
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED to 1,
            ),
        ) { node ->
            val length = node.text?.length ?: 0
            val end = command.endIndex ?: length
            if (command.startIndex > length || end > length) {
                null
            } else {
                Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        command.startIndex,
                    )
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                }
            }
        }
    }

    /**
     * 尽力让目标节点获得输入焦点，供依赖焦点的动作使用。
     *
     * 返回值故意忽略：这是前置准备而非动作本身，失败时应让真正的动作去报错，
     * 那样错误信息才指向调用方实际请求的操作。
     */
    private fun ensureFocused(target: NodeTarget) {
        performNodeAction(
            target = target,
            actions = listOf(NodeAction.FOCUS),
            eventBudgets = emptyMap(),
        )
    }

    private fun executeClipboard(
        command: AccessibilityCommand.Clipboard,
    ): AccessibilityResult<ActionExecution> {
        val action = when (command.operation) {
            ClipboardOperation.COPY -> NodeAction.COPY
            ClipboardOperation.CUT -> NodeAction.CUT
            ClipboardOperation.PASTE -> NodeAction.PASTE
        }
        // 粘贴要求目标已获得输入焦点：平台把插入点视为输入状态的一部分，
        // 没有焦点就没有插入点可写。复制与剪切依赖已有选区，而选区本身
        // 由 SelectText 建立（那里也会先聚焦），故不必在此重复聚焦。
        if (command.operation == ClipboardOperation.PASTE) {
            ensureFocused(command.target)
        }
        return executeNodeOnlyAction(
            target = command.target,
            action = action,
            eventBudgets = if (command.operation == ClipboardOperation.PASTE) {
                eventBudgets(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED to TEXT_EVENT_BUDGET)
            } else {
                emptyMap()
            },
        )
    }

    private fun executeSimpleNodeAction(
        command: AccessibilityCommand.PerformNodeAction,
    ): AccessibilityResult<ActionExecution> {
        if (command.action in ARGUMENT_REQUIRED_ACTIONS) {
            return invalidAction(
                "${command.action.name} requires arguments and must use its dedicated command",
            )
        }
        return executeNodeOnlyAction(
            target = command.target,
            action = command.action,
            eventBudgets = emptyMap(),
        )
    }

    private fun executeSetProgress(
        command: AccessibilityCommand.SetProgress,
    ): AccessibilityResult<ActionExecution> {
        if (!command.value.isFinite()) {
            return invalidAction("Progress value must be finite")
        }
        return performArgumentNodeAction(
            target = command.target,
            action = NodeAction.SET_PROGRESS,
            eventBudgets = emptyMap(),
        ) {
            Bundle().apply {
                putFloat(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                    command.value,
                )
            }
        }
    }

    private fun executeScrollToPosition(
        command: AccessibilityCommand.ScrollToPosition,
    ): AccessibilityResult<ActionExecution> {
        if (command.row < 0 || command.column < 0) {
            return invalidAction("Row and column must not be negative")
        }
        return performArgumentNodeAction(
            target = command.target,
            action = NodeAction.SCROLL_TO_POSITION,
            eventBudgets = eventBudgets(
                AccessibilityEvent.TYPE_VIEW_SCROLLED to SCROLL_EVENT_BUDGET,
            ),
        ) {
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, command.row)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, command.column)
            }
        }
    }

    /**
     * 按键。
     *
     * 无障碍服务没有通用按键 API，只能分两条路：
     * - BACK / HOME / RECENTS 走全局动作，这是平台唯一支持的方式；
     * - 其余键走 backend 的按键通道（由具体实现决定用 IME 还是其它机制）。
     *
     * 不支持的键必须明确失败：静默忽略会让「输入完按回车提交」这类流程
     * 看起来成功但实际什么都没发生。
     */
    private fun executePressKey(
        command: AccessibilityCommand.PressKey,
    ): AccessibilityResult<ActionExecution> {
        if (command.repeatCount < 1 || command.repeatCount > MAX_KEY_REPEAT) {
            return invalidAction("repeatCount must be between 1 and $MAX_KEY_REPEAT")
        }
        val globalEquivalent = when (command.key) {
            DeviceKey.BACK -> GlobalAction.BACK
            DeviceKey.HOME -> GlobalAction.HOME
            DeviceKey.RECENTS -> GlobalAction.RECENTS
            else -> null
        }
        repeat(command.repeatCount) {
            val performed = if (globalEquivalent != null) {
                backend.performGlobal(globalEquivalent)
            } else {
                backend.pressKey(command.key)
            }
            if (!performed) {
                return failure(
                    code = AccessibilityErrorCode.ACTION_NOT_SUPPORTED,
                    message = "The key ${command.key.name} could not be delivered",
                    retryable = true,
                    details = mapOf("key" to command.key.name),
                )
            }
        }
        return AccessibilityResult.Success(ActionExecution(route = ActionRoute.GLOBAL_ACTION))
    }

    /**
     * 执行 ime.show：把焦点放到当前可编辑控件上。
     *
     * 能力上限：这不是「显示键盘」的确定开关。Android 没有与 ime.hide 所用
     * KEYCODE_BACK 对称的打开键盘 keycode，无障碍通道也无法查询键盘可见性。
     * 本实现只保证「已聚焦输入控件」，键盘是否随之弹出由输入法决定。
     * 因此成功结果的语义是「已聚焦」，调用方不应据此断定键盘已经可见。
     *
     * 聚焦失败时明确返回 ACTION_NOT_SUPPORTED，不返回假成功——否则调用方会
     * 以为焦点已就绪而继续写文本，文本会落到不可预期的位置。
     */
    private fun executeImeShow(): AccessibilityResult<ActionExecution> {
        if (!backend.focusInputControl()) {
            return failure(
                code = AccessibilityErrorCode.ACTION_NOT_SUPPORTED,
                message = "No editable control could be focused for ime.show",
                retryable = true,
            )
        }
        // route 记为节点动作：实际做的是对焦点节点执行 ACTION_FOCUS。
        // 不带 matchedPath/matchScore——聚焦的是当前焦点节点，没有选择器匹配证据。
        return AccessibilityResult.Success(ActionExecution(route = ActionRoute.NODE_ACTION))
    }

    /** 只走节点动作、无坐标降级的能力（复制、聚焦、展开等）。 */
    private fun executeNodeOnlyAction(
        target: NodeTarget,
        action: NodeAction,
        eventBudgets: Map<Int, Int>,
    ): AccessibilityResult<ActionExecution> = when (
        val attempt = performNodeAction(
            target = target,
            actions = listOf(action),
            eventBudgets = eventBudgets,
        )
    ) {
        is NodeAttempt.Performed -> attempt.execution(ActionRoute.NODE_ACTION)
        is NodeAttempt.Ambiguous -> attempt.failure()
        is NodeAttempt.BackendFailure -> attempt.failure
        is NodeAttempt.NotFound, NodeAttempt.NoSelector -> failure(
            code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
            message = "${action.name} requires a unique semantic node",
            retryable = true,
        )

        is NodeAttempt.Found -> failure(
            code = if (action in attempt.match.node.actions) {
                AccessibilityErrorCode.ACTION_FAILED
            } else {
                AccessibilityErrorCode.ACTION_NOT_SUPPORTED
            },
            message = "The matched node did not accept ${action.name}",
            retryable = true,
            details = mapOf("action" to action.name),
        )
    }

    /**
     * 执行需要参数的节点动作。
     *
     * 参数由 [buildArguments] 基于匹配到的节点快照生成——SET_SELECTION 需要
     * 知道文本长度才能校验区间，这个信息只有匹配之后才有。返回 null 表示
     * 参数不合法（如区间越界），此时按 INVALID_ACTION 失败而不是发一个
     * 会被平台静默忽略的动作。
     */
    private fun performArgumentNodeAction(
        target: NodeTarget,
        action: NodeAction,
        eventBudgets: Map<Int, Int>,
        buildArguments: (UiNodeSnapshot) -> Bundle?,
    ): AccessibilityResult<ActionExecution> {
        if (target.selectorCandidates.isEmpty()) {
            return failure(
                code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                message = "${action.name} requires a semantic selector",
                retryable = false,
            )
        }
        val session = when (val opened = backend.openNodeSession(target.packageName)) {
            is AccessibilityResult.Failure -> return opened
            is AccessibilityResult.Success -> opened.value
        }
        return session.use {
            val windows = session.windows
            val match = if (windows.isEmpty()) {
                selectorMatcher.match(session.rootSnapshot, target)
            } else {
                selectorMatcher.match(windows, target)
            }
            when (match) {
                is SelectorMatch.NotFound -> failure(
                    code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
                    message = "${action.name} requires a unique semantic node",
                    retryable = true,
                )

                is SelectorMatch.Ambiguous -> NodeAttempt.Ambiguous(
                    score = match.score,
                    candidateCount = match.candidateCount,
                ).failure()

                is SelectorMatch.Found -> {
                    val arguments = buildArguments(match.node)
                    when {
                        arguments == null -> invalidAction(
                            "${action.name} arguments are outside the node's valid range",
                        )

                        action !in match.node.actions -> failure(
                            code = AccessibilityErrorCode.ACTION_NOT_SUPPORTED,
                            message = "The matched node does not support ${action.name}",
                            retryable = false,
                            details = mapOf("action" to action.name),
                        )

                        session.perform(
                            path = match.path,
                            action = action,
                            arguments = arguments,
                            expectedEventBudgets = eventBudgets,
                        ) -> AccessibilityResult.Success(
                            ActionExecution(
                                route = ActionRoute.NODE_ACTION,
                                matchedPath = match.path,
                                matchScore = match.score,
                            ),
                        )

                        else -> failure(
                            code = AccessibilityErrorCode.ACTION_FAILED,
                            message = "The matched node rejected ${action.name}",
                            retryable = true,
                            details = mapOf("action" to action.name),
                        )
                    }
                }
            }
        }
    }

    /**
     * 等待滚动动画落定。
     *
     * 通过可注入的 sleeper 而不是直接 Thread.sleep：单测不应真的等待，
     * 否则 scrollIntoView 的多轮测试会把耗时累加到秒级。
     */
    private fun settleAfterScroll() {
        sleeper(SCROLL_SETTLE_MS)
    }

    /** 把选择器目标解析成屏幕坐标。 */
    private fun resolveAnchor(
        anchor: DragAnchor,
        screen: ScreenBounds,
    ): AccessibilityResult<ScreenPoint> {
        anchor.point?.let { point ->
            return CoordinateTransformer.validate(point, screen)
        }
        val target = anchor.target ?: return AccessibilityResult.Failure(
            code = AccessibilityErrorCode.INVALID_ACTION,
            message = "A drag anchor requires either a target or a point",
        )
        return resolveTargetCenter(target, screen)
    }

    /**
     * 解析手势中心点。
     *
     * 三级优先：显式坐标 → 选择器目标的可见中心 → 屏幕中心。
     * 屏幕中心只在 [allowScreenCenter] 时可用：缩放与旋转作用于整个视图，
     * 不给目标是合理的；连击不给目标则是调用方漏了参数，必须报错。
     */
    private fun resolvePoint(
        point: ScreenPoint?,
        target: NodeTarget?,
        screen: ScreenBounds,
        allowScreenCenter: Boolean = false,
    ): AccessibilityResult<ScreenPoint> {
        point?.let { return CoordinateTransformer.validate(it, screen) }
        target?.let { return resolveTargetCenter(it, screen) }
        if (!allowScreenCenter) {
            return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.INVALID_ACTION,
                message = "This gesture requires either a target or an explicit point",
            )
        }
        return AccessibilityResult.Success(
            ScreenPoint(
                x = screen.left + screen.width / 2,
                y = screen.top + screen.height / 2,
            ),
        )
    }

    /** 把选择器目标解析为其可见中心点，失败时退回坐标回退信息。 */
    private fun resolveTargetCenter(
        target: NodeTarget,
        screen: ScreenBounds,
    ): AccessibilityResult<ScreenPoint> {
        val session = when (val opened = backend.openNodeSession(target.packageName)) {
            is AccessibilityResult.Failure -> return opened
            is AccessibilityResult.Success -> opened.value
        }
        val matched = session.use {
            val windows = session.windows
            if (windows.isEmpty()) {
                selectorMatcher.match(session.rootSnapshot, target)
            } else {
                selectorMatcher.match(windows, target)
            }
        }
        if (matched is SelectorMatch.Found) {
            return CoordinateTransformer.validate(matched.node.bounds.center(), screen)
        }
        if (matched is SelectorMatch.Ambiguous) {
            return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.SELECTOR_AMBIGUOUS,
                message = "The gesture anchor matched multiple nodes",
                retryable = true,
            )
        }
        // 选择器未命中时才用录制坐标：这是最后手段，因为坐标在不同尺寸设备上
        // 未必对应同一元素。
        return fallbackPoint(target) ?: AccessibilityResult.Failure(
            code = AccessibilityErrorCode.SELECTOR_NOT_FOUND,
            message = "The gesture anchor was not found and has no coordinate fallback",
            retryable = true,
        )
    }

    /** 派发复合手势并统一失败语义。 */
    private fun dispatchComposite(
        gesture: Gesture,
        expectedPackage: String?,
        expectedEventBudgets: Map<Int, Int>,
        timeoutMs: Long,
    ): AccessibilityResult<ActionExecution> = if (
        backend.dispatch(
            gesture = gesture,
            sourcePath = null,
            expectedPackage = expectedPackage,
            expectedEventBudgets = expectedEventBudgets,
            timeoutMs = timeoutMs,
        )
    ) {
        AccessibilityResult.Success(ActionExecution(route = ActionRoute.SCREEN_GESTURE))
    } else {
        failure(
            code = AccessibilityErrorCode.GESTURE_FAILED,
            message = "The system rejected the composite gesture",
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
        eventBudgets: Map<Int, Int>? = null,
    ): NodeAttempt {
        if (target.selectorCandidates.isEmpty()) {
            return NodeAttempt.NoSelector
        }
        val session = when (val opened = backend.openNodeSession(target.packageName)) {
            is AccessibilityResult.Failure -> return NodeAttempt.BackendFailure(opened)
            is AccessibilityResult.Success -> opened.value
        }
        return session.use {
            // 优先跨窗口匹配：弹窗与输入法通常不是活动窗口，只搜活动窗口会漏掉它们。
            // 窗口列表为空（不支持多窗口的实现或枚举失败）时回退到活动窗口根，
            // 保持与多窗口模型引入前完全一致的行为。
            val windows = session.windows
            val match = if (windows.isEmpty()) {
                selectorMatcher.match(session.rootSnapshot, target)
            } else {
                selectorMatcher.match(windows, target)
            }
            when (match) {
                is SelectorMatch.Found -> {
                    val supportedAction = actions.firstOrNull { it in match.node.actions }
                    if (
                        supportedAction != null &&
                        session.perform(
                            path = match.path,
                            action = supportedAction,
                            text = text,
                            expectedEventBudgets = eventBudgets
                                ?: supportedAction.expectedEventBudgets(),
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
        expectedPackage: String? = null,
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
                expectedPackage = expectedPackage,
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

    /**
     * 派发滑动手势。
     *
     * [requireScrollEvent] 决定是否要求这次滑动必须产生 TYPE_VIEW_SCROLLED。
     * 默认 true：由选择器命中节点后发出的滑动应当真的滚动了内容，
     * 收不到事件说明打在了错误的地方，此时报失败是正确的。
     *
     * scrollIntoView 的逐轮滚动传 false：容器可能已经滚到尽头，
     * 此时滑动不会产生滚动事件，但那是「滚不动了」而不是「手势打错了」。
     * 让它按成功返回、交由外层的无进展检测得出准确结论，
     * 比在这里报 GESTURE_FAILED 更贴近事实。
     */
    private fun dispatchSwipe(
        gesture: Gesture.Swipe,
        route: ActionRoute,
        expectedPackage: String? = null,
        match: SelectorMatch.Found? = null,
        requireScrollEvent: Boolean = true,
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
                expectedPackage = expectedPackage,
                expectedEventBudgets = if (match == null || !requireScrollEvent) {
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
        NodeAction.SCROLL_TO_POSITION,
        -> eventBudgets(AccessibilityEvent.TYPE_VIEW_SCROLLED to SCROLL_EVENT_BUDGET)

        NodeAction.SET_SELECTION ->
            eventBudgets(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED to 1)

        NodeAction.PASTE, NodeAction.CUT ->
            eventBudgets(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED to TEXT_EVENT_BUDGET)

        NodeAction.SELECT -> eventBudgets(AccessibilityEvent.TYPE_VIEW_SELECTED to 1)

        // 其余动作不产生可归因的 View 语义事件：聚焦走 FOCUSED 事件但并非所有
        // 实现都发，复制与展开折叠通常静默完成。给空预算意味着不做严格归因，
        // 这是正确的——等一个不会到来的事件只会把成功判成失败。
        NodeAction.FOCUS,
        NodeAction.CLEAR_FOCUS,
        NodeAction.CLEAR_SELECTION,
        NodeAction.EXPAND,
        NodeAction.COLLAPSE,
        NodeAction.DISMISS,
        NodeAction.COPY,
        NodeAction.SET_PROGRESS,
        -> emptyMap()
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
        const val MIN_TAP_MS = 1L
        const val TAP_DURATION_MS = 100L
        const val MIN_LONG_CLICK_MS = 300L
        const val EVENT_TIMEOUT_GRACE_MS = 1_000L
        const val TEXT_EVENT_BUDGET = 2
        const val SCROLL_EVENT_BUDGET = 3
        const val MAX_LONG_CLICK_MS = 10_000L
        const val MIN_SWIPE_MS = 1L
        const val MAX_SWIPE_MS = 60_000L
        const val MAX_TEXT_LENGTH = 10_000

        /**
         * scrollIntoView 的最大轮次上限。
         *
         * 取 50：按每轮滚动 60% 视口算，足以走过约 30 屏内容。
         * 上限存在的意义是防止在无限流列表上永久滚动。
         */
        const val MAX_SCROLL_ROUNDS = 50

        /**
         * 两轮滚动之间的等待时长。
         *
         * 取 350ms：滚动手势本身 400ms，加上 ListView / RecyclerView 的
         * 惯性收敛，实测 350ms 足以让内容稳定。过短会让无进展检测误判，
         * 过长则乘以最大轮次后总耗时不可接受。
         */
        const val SCROLL_SETTLE_MS = 350L

        /**
         * 判定「滚到尽头」所需的连续无变化轮数。
         *
         * 取 3：滚动动画与快照采集是两条独立时序，快照恰好落在动画未推进的窗口内
         * 就会与上一轮相同。实测（API 35）中容器明确仍可滚动却出现过单轮签名相同，
         * 若单轮即判定就会误报「无进展」。连续 3 轮相同才足以说明真的到底了，
         * 代价是多花约 1 秒（3 × SCROLL_SETTLE_MS），远小于误判导致整个动作失败。
         */
        const val MAX_STALLED_ROUNDS = 3

        /**
         * 连击的归因等待上限。
         *
         * 五连击按默认参数约 700ms，加上宽限期取 3 秒。
         */
        const val MULTI_TAP_TIMEOUT_MS = 3_000L

        /** 按键重复上限，防止一次调用打出上千次按键。 */
        const val MAX_KEY_REPEAT = 64

        /** 必须走专用命令携带参数的动作，不允许通过通用节点动作入口执行。 */
        val ARGUMENT_REQUIRED_ACTIONS = setOf(
            NodeAction.SET_TEXT,
            NodeAction.SET_SELECTION,
            NodeAction.SET_PROGRESS,
            NodeAction.SCROLL_TO_POSITION,
        )
    }
}
