package dev.aiauto.android.accessibility

/**
 * 功能用途：实现 AndroidAccessibilityBackend 对应的无障碍观察、截图、动作或用户触摸安全控制。
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import dev.aiauto.android.accessibility.action.AccessibilityActionBackend
import dev.aiauto.android.accessibility.action.AccessibilityNodeSession
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.DeviceKey
import dev.aiauto.android.accessibility.model.Gesture
import dev.aiauto.android.accessibility.model.GestureStroke
import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiWindowSnapshot
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.WindowSnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely
import dev.aiauto.android.automation.recording.RecordingRuntime

internal class AndroidAccessibilityBackend(
    private val service: AccessibilityService,
    private val settingsRepository: AccessibilitySettingsRepository,
    private val snapshotter: AccessibilitySnapshotter,
    private val userTouchMonitor: UserTouchMonitor? = null,
    private val windowSnapshotter: WindowSnapshotter = WindowSnapshotter(snapshotter),
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

        // 目标包校验按「所有可见窗口的包集合」判定，而不是只看活动窗口。
        // 输入法窗口属于 IME 的包，系统对话框属于 android 包：只认活动窗口时，
        // 一旦键盘弹出或系统弹窗出现，活动窗口的包名就不再是被测应用，
        // 于是合法操作会被误判为 PACKAGE_NOT_ALLOWED。
        val visiblePackages = visiblePackageNames()
        if (visiblePackages.isEmpty()) {
            return failure(
                code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
                message = "No active accessibility window is available",
                retryable = true,
            )
        }
        return when {
            // 启动过渡：前台是 harness 自身时放行，等待 app.launch 生效。
            visiblePackages.none { it in settings.targetPackages || it == HARNESS_PACKAGE } -> AccessibilityResult.Success(Unit)

            expectedPackage != null && expectedPackage !in visiblePackages -> failure(
                code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                message = "No visible window matches the requested target package",
                retryable = true,
            )

            else -> AccessibilityResult.Success(Unit)
        }
    }

    /**
     * 收集所有可见窗口的包名。
     *
     * 优先走 service.windows；枚举不可用（部分 OEM 在服务刚绑定时返回空列表）
     * 时回退到 rootInActiveWindow，保证不会因为多窗口枚举失败而整体不可用。
     */
    private fun visiblePackageNames(): Set<String> {
        val fromWindows = runCatching { service.windows }
            .getOrNull()
            .orEmpty()
            .mapNotNullTo(mutableSetOf()) { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@mapNotNullTo null
                try {
                    root.packageName?.toString()
                } finally {
                    root.recycleSafely()
                }
            }
        if (fromWindows.isNotEmpty()) {
            return fromWindows
        }
        val root = service.rootInActiveWindow ?: return emptySet()
        return try {
            setOfNotNull(root.packageName?.toString())
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

        val settings = settingsRepository.load()
        // 先枚举多窗口：这是弹窗、输入法、分屏、画中画里的节点能进入候选集合的唯一途径。
        val windows = windowSnapshotter.snapshotWindows(service)
        val allowedWindows = windows.filter { window ->
            window.root.packageName in settings.targetPackages || window.root.packageName == HARNESS_PACKAGE
        }
        if (allowedWindows.isNotEmpty()) {
            // 活动窗口优先作为 rootSnapshot，既有只看单根的调用方行为不变。
            val primary = allowedWindows.firstOrNull { window ->
                expectedPackage == null || window.root.packageName == expectedPackage
            } ?: allowedWindows.first()
            return AccessibilityResult.Success(
                AndroidNodeSession(
                    root = null,
                    rootSnapshot = primary.root,
                    windows = allowedWindows,
                    userTouchMonitor = userTouchMonitor,
                    resolveRoot = { windowId ->
                        if (windowId == null) {
                            service.rootInActiveWindow
                        } else {
                            windowSnapshotter.rootOf(service, windowId)
                        }
                    },
                    defaultWindowId = primary.windowId,
                ),
            )
        }

        // 窗口枚举不可用时回退到活动窗口单根，保持多窗口模型引入前的行为。
        val root = service.rootInActiveWindow ?: return failure(
            code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
            message = "The active accessibility window changed before node lookup",
            retryable = true,
        )
        val currentPackage = root.packageName?.toString()
        if (
            currentPackage !in settings.targetPackages && currentPackage != HARNESS_PACKAGE ||
            (expectedPackage != null && currentPackage != expectedPackage && currentPackage != HARNESS_PACKAGE)
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
                    windows = emptyList(),
                    userTouchMonitor = userTouchMonitor,
                    resolveRoot = { service.rootInActiveWindow },
                    defaultWindowId = null,
                ),
            )
        }
    }

    override fun dispatch(
        gesture: Gesture,
        sourcePath: NodePath?,
        expectedPackage: String?,
        expectedEventBudgets: Map<Int, Int>,
        timeoutMs: Long,
    ): Boolean {
        val source = sourcePath?.let(::findNodeForGesture)
            ?: findUniqueNodeAt(gesture)
        val requiresAttribution = userTouchMonitor?.requiresStrictAttribution() == true
        if (
            requiresAttribution && source == null ||
            expectedPackage != null && source?.packageName?.toString() != expectedPackage
        ) {
            source?.recycleSafely()
            return false
        }
        return try {
            val dispatchAction = {
                dispatchGesture(gesture)
            }
            val budgets = expectedEventBudgets.ifEmpty { gesture.expectedEventBudgets() }
            // 只有预算里存在本监视器真正会观察的事件类型时才走归因路径。
            //
            // 判据是 hasMonitorableBudget 而不是「预算非空」：监视器只观察
            // CLICKED / LONG_CLICKED / SCROLLED / TEXT_CHANGED 四类，
            // 其余类型会在 arm 阶段被过滤掉，过滤后为空则 arm 失败、
            // runAttributedAction 返回 false，手势根本不会派发。
            //
            // 缩放与旋转由 View 内部处理，本就不发语义事件，预算为空；
            // 而「预算非空但无一项可监听」同样存在。两种情况都不该被当成
            // 归因失败——归因机制对无事件动作本就无从判断。此时直接派发：
            // 源节点已过 expectedPackage 校验，越界坐标已在编排阶段拒绝，
            // 安全边界仍然成立。
            val attributable = userTouchMonitor?.hasMonitorableBudget(budgets) == true
            if (source != null && requiresAttribution && attributable) {
                userTouchMonitor?.runAttributedAction(
                    source = source,
                    eventBudgets = budgets,
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
        // 复合手势取各指起点的质心作为归因锚点。
        //
        // 不取第一笔的起点：pinch 的两指对称分布在中心两侧，第一笔起点落在
        // 中心左侧一段距离处，可能不在目标控件内；而质心正好是缩放中心，
        // 也是用户认知里「这个手势作用在哪」的位置。drag 与路径手势只有一指时
        // 质心等于其起点，语义不变。
        is Gesture.MultiStroke -> {
            val origins = strokes.mapNotNull { it.path.firstOrNull() }
            if (origins.isEmpty()) {
                ScreenPoint(0, 0)
            } else {
                ScreenPoint(
                    x = origins.sumOf { it.x } / origins.size,
                    y = origins.sumOf { it.y } / origins.size,
                )
            }
        }
    }

    private fun Gesture.expectedEventBudgets(): Map<Int, Int> = when (this) {
        is Gesture.Tap -> mapOf(AccessibilityEvent.TYPE_VIEW_CLICKED to 1)
        is Gesture.Swipe -> mapOf(AccessibilityEvent.TYPE_VIEW_SCROLLED to GESTURE_EVENT_BUDGET)
        // 复合手势的语义由调用方决定（drag 产生长按+滚动，pinch 通常不产生任何
        // 语义事件），无法在这里推断出一份通用预算，因此交由路由层显式给出。
        is Gesture.MultiStroke -> emptyMap()
    }

    /**
     * 把手势派发给平台。
     *
     * 单笔的 Tap / Swipe 走原路径；复合手势按 pointerId 分组后逐组续接，
     * 这是多指与分段时序手势能成立的唯一途径——GestureDescription 允许多个
     * stroke 并行，同一手指的连续动作则要靠 StrokeDescription.continueStroke 拼接。
     */
    private fun dispatchGesture(gesture: Gesture): Boolean = when (gesture) {
        is Gesture.Tap -> dispatchSingleStroke(
            path = Path().apply {
                moveTo(gesture.point.x.toFloat(), gesture.point.y.toFloat())
            },
            durationMs = gesture.durationMs,
        )

        is Gesture.Swipe -> dispatchSingleStroke(
            path = Path().apply {
                moveTo(gesture.start.x.toFloat(), gesture.start.y.toFloat())
                lineTo(gesture.end.x.toFloat(), gesture.end.y.toFloat())
            },
            durationMs = gesture.durationMs,
        )

        is Gesture.MultiStroke -> dispatchMultiStroke(gesture)
    }

    private fun dispatchSingleStroke(path: Path, durationMs: Long): Boolean {
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(description, null, null)
    }

    /**
     * 派发复合手势。
     *
     * 平台约束决定了这里的分批逻辑：一个 GestureDescription 里同一根手指
     * 只能有一笔，续接必须通过下一个 GestureDescription 里的 continueStroke 完成。
     * 因此按「同指第几笔」把所有笔画切成若干批，每批各指最多一笔，逐批派发。
     *
     * 逐批同步等待：续接要求前一笔的 StrokeDescription 实例仍然有效且已被平台
     * 接受，异步并发派发会让 continueStroke 收到一个尚未生效或已失效的前驱。
     */
    private fun dispatchMultiStroke(gesture: Gesture.MultiStroke): Boolean {
        if (gesture.strokes.isEmpty()) {
            return false
        }
        // 按手指分组，组内按起始时间排序，保证续接顺序与调用方给出的时序一致。
        val byPointer = gesture.strokes
            .groupBy(GestureStroke::pointerId)
            .mapValues { (_, strokes) -> strokes.sortedBy(GestureStroke::startTimeMs) }
        val rounds = byPointer.values.maxOf(List<GestureStroke>::size)
        if (rounds > MAX_STROKE_ROUNDS) {
            return false
        }

        // 每根手指上一轮的 StrokeDescription，续接时作为 continueStroke 的前驱。
        val previous = HashMap<Int, GestureDescription.StrokeDescription>()
        for (round in 0 until rounds) {
            val builder = GestureDescription.Builder()
            var added = 0
            for ((pointerId, strokes) in byPointer) {
                val stroke = strokes.getOrNull(round) ?: continue
                val path = stroke.toPath() ?: return false
                val predecessor = previous[pointerId]
                val description = if (stroke.continuation && predecessor != null) {
                    predecessor.continueStroke(
                        path,
                        stroke.startTimeMs,
                        stroke.durationMs,
                        stroke.continued,
                    )
                } else {
                    GestureDescription.StrokeDescription(
                        path,
                        stroke.startTimeMs,
                        stroke.durationMs,
                        stroke.continued,
                    )
                }
                builder.addStroke(description)
                previous[pointerId] = description
                added += 1
            }
            if (added == 0) {
                continue
            }
            if (!dispatchAndAwait(builder.build())) {
                return false
            }
        }
        return true
    }

    /**
     * 派发一批手势并等待平台回调。
     *
     * 必须等待而不能 fire-and-forget：续接依赖前一批已经完成，
     * 不等待会让后续 continueStroke 因「前驱未结束」被平台拒绝。
     * 超时按失败处理，避免回调丢失时永久阻塞调用线程。
     */
    private fun dispatchAndAwait(description: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.set(false)
                latch.countDown()
            }
        }
        if (!service.dispatchGesture(description, callback, null)) {
            return false
        }
        val awaited = runCatching {
            latch.await(GESTURE_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        return awaited && completed.get()
    }

    /**
     * 把折线点列转成平台 Path。
     *
     * 少于一个点无法构成路径；单点是合法的（原地按住），
     * 平台要求这种情况仍然要有 moveTo。
     */
    private fun GestureStroke.toPath(): Path? {
        val first = path.firstOrNull() ?: return null
        return Path().apply {
            moveTo(first.x.toFloat(), first.y.toFloat())
            path.drop(1).forEach { point ->
                lineTo(point.x.toFloat(), point.y.toFloat())
            }
        }
    }

    /**
     * 按路径在指定窗口内找节点，供手势归因使用。
     *
     * 与 AndroidNodeSession.findNode 的差别是这里不复用会话根：手势派发时
     * 会话可能已关闭，必须从平台重新取根。
     */
    private fun findNodeForGesture(path: NodePath): AccessibilityNodeInfo? {
        var current = if (path.windowId == null) {
            service.rootInActiveWindow
        } else {
            windowSnapshotter.rootOf(service, path.windowId)
        } ?: return null
        path.indices.forEach { index ->
            val child = current.getChild(index)
            current.recycleSafely()
            if (child == null) {
                return null
            }
            current = child
        }
        return current
    }

    /**
     * 在所有可见窗口里找覆盖该点、面积最小且唯一的可接受节点。
     *
     * 必须跨窗口搜索：对话框上的按钮属于对话框窗口，只搜活动窗口时坐标手势
     * 找不到归因节点，于是在严格归因模式下整个手势会被拒绝执行。
     *
     * 窗口按 z 序从上到下遍历，一旦在某层找到唯一候选就停止：上层窗口盖住了
     * 下层，用户在该坐标上点到的必然是上层那个。继续往下搜只会引入
     * 用户点不到的节点并造成假歧义。
     */
    private fun findUniqueNodeAt(gesture: Gesture): AccessibilityNodeInfo? {
        val point = gesture.anchorPoint()
        val orderedRoots = orderedGestureRoots(point)
        if (orderedRoots.isEmpty()) {
            return null
        }
        try {
            orderedRoots.forEach { root ->
                when (val found = findUniqueNodeUnder(root, point, gesture)) {
                    is PointLookup.Unique -> return found.node
                    // 该层歧义时不再下探：下层节点被盖住，用户点不到，
                    // 继续搜只会把「这一层无法决定」变成「随便挑了下层一个」。
                    PointLookup.Ambiguous -> return null
                    PointLookup.Missing -> Unit
                }
            }
        } finally {
            orderedRoots.forEach(AccessibilityNodeInfo::recycleSafely)
        }
        return null
    }

    /**
     * 收集覆盖该点的窗口根，按 z 序从上到下。
     *
     * 只保留边界包含该点的窗口：状态栏窗口不该参与屏幕中央的坐标归因。
     * 窗口枚举不可用时回退到活动窗口根。
     */
    private fun orderedGestureRoots(point: ScreenPoint): List<AccessibilityNodeInfo> {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val roots = windows
            .filter { window ->
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                point.x in bounds.left until bounds.right &&
                    point.y in bounds.top until bounds.bottom
            }
            .sortedByDescending { it.layer }
            .mapNotNull { window -> runCatching { window.root }.getOrNull() }
        if (roots.isNotEmpty()) {
            return roots
        }
        return listOfNotNull(service.rootInActiveWindow)
    }

    /** 单个窗口内的点命中结果。 */
    private sealed interface PointLookup {
        class Unique(val node: AccessibilityNodeInfo) : PointLookup

        data object Ambiguous : PointLookup

        data object Missing : PointLookup
    }

    private fun findUniqueNodeUnder(
        root: AccessibilityNodeInfo,
        point: ScreenPoint,
        gesture: Gesture,
    ): PointLookup {
        val candidates = ArrayDeque<AccessibilityNodeInfo>().apply {
            add(AccessibilityNodeInfo(root))
        }
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
            return PointLookup.Ambiguous
        }
        return best?.let(PointLookup::Unique) ?: PointLookup.Missing
    }

    private fun Gesture.accepts(node: AccessibilityNodeInfo): Boolean = when (this) {
        is Gesture.Tap -> node.isClickable || node.isLongClickable
        is Gesture.Swipe -> node.isScrollable
        // 复合手势可能是拖拽（需要长按能力）、缩放（作用在容器上）或连击（可点击），
        // 无法用单一属性筛选，因此接受任何可交互节点——它只用于归因，
        // 判错的后果是归因失败而不是点错地方。
        is Gesture.MultiStroke ->
            node.isClickable || node.isLongClickable || node.isScrollable
    }

    /**
     * 执行全局动作。
     *
     * 每一项的最低 API 不同，因此按运行时版本判定：不支持时返回 false，
     * 由路由层报明确失败。若不判定而直接调用，平台会静默返回 false，
     * 调用方无法区分「这个系统不支持」与「系统拒绝了」。
     */
    override fun performGlobal(action: GlobalAction): Boolean {
        val platformAction = action.toPlatformAction() ?: return false
        val performed = service.performGlobalAction(platformAction)
        if (performed) {
            RecordingRuntime.publishGlobalAction(action)
        }
        return performed
    }

    private fun GlobalAction.toPlatformAction(): Int? = when (this) {
        GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        GlobalAction.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        // 锁屏与截屏自 API 28（P）起提供，更早版本上没有等价能力。
        GlobalAction.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        } else {
            null
        }

        GlobalAction.TAKE_SCREENSHOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        } else {
            null
        }

        // 收起通知栏自 API 34（U）起才有专门的全局动作。
        GlobalAction.DISMISS_NOTIFICATION_SHADE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            } else {
                null
            }

        // 快捷键帮助面板的全局动作常量在编译 SDK 上不可用（它是 @hide 的
        // GLOBAL_ACTION_KEYBOARD_SHORTCUTS，仅对系统应用开放）。
        // 硬编码常量值可以调通，但那是依赖未公开 API，会在后续版本静默失效。
        // 因此这里明确返回 null，让路由层报 ACTION_NOT_SUPPORTED——
        // 「暂时做不到」必须诚实上报，而不是伪装成可用。
        GlobalAction.KEYBOARD_SHORTCUTS -> null
    }

    /**
     * 通过无障碍通道发送按键。
     *
     * 无障碍服务没有通用按键 API。可用路径只有两条：
     * - 焦点节点上的语义动作（例如输入框上的 ACTION_SET_SELECTION 配合光标键）；
     * - 输入法连接（需要自身是输入法，本 App 不是）。
     *
     * 因此这里只实现能可靠映射到节点动作的键：光标移动与文本导航作用在
     * 当前焦点输入框上。其余键无法在不成为输入法的前提下从无障碍侧发出，
     * 明确返回 false 让调用方走 ADB direct 通道，而不是假装成功。
     */
    override fun pressKey(key: DeviceKey): Boolean {
        val focused = focusedInputNode() ?: return false
        return try {
            when (key) {
                DeviceKey.MOVE_HOME -> focused.setSelection(0)
                DeviceKey.MOVE_END -> focused.setSelection(focused.text?.length ?: 0)
                // 其余键在无障碍通道上没有可靠实现，交由 ADB direct 通道处理。
                else -> false
            }
        } finally {
            focused.recycleSafely()
        }
    }

    /** 把光标移动到指定位置（起止相同即为无选区的插入点）。 */
    private fun AccessibilityNodeInfo.setSelection(index: Int): Boolean {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, index)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, index)
        }
        return performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    /**
     * 聚焦当前可编辑控件，供 ime.show 使用。
     *
     * 只对已持有输入焦点的可编辑节点执行 ACTION_FOCUS。键盘通常会随之出现，
     * 但这里无法验证——无障碍服务拿不到键盘可见性。返回 true 只代表聚焦成功。
     * 没有可编辑焦点节点时返回 false，由调用方报 ACTION_NOT_SUPPORTED。
     */
    override fun focusInputControl(): Boolean {
        val focused = focusedInputNode() ?: return false
        return try {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } finally {
            focused.recycleSafely()
        }
    }

    /** 找当前获得输入焦点的可编辑节点，跨窗口查找。 */
    private fun focusedInputNode(): AccessibilityNodeInfo? {
        runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?.let { node ->
                if (node.isEditable) {
                    return node
                }
                node.recycleSafely()
            }
        return null
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
        private val root: AccessibilityNodeInfo?,
        override val rootSnapshot: UiNodeSnapshot,
        override val windows: List<UiWindowSnapshot>,
        private val userTouchMonitor: UserTouchMonitor?,
        private val resolveRoot: (Int?) -> AccessibilityNodeInfo?,
        private val defaultWindowId: Int?,
    ) : AccessibilityNodeSession {
        override fun perform(
            path: NodePath,
            action: NodeAction,
            text: String?,
            arguments: Bundle?,
            expectedEventBudgets: Map<Int, Int>,
            timeoutMs: Long,
        ): Boolean {
            val resolved = findNode(path) ?: return false
            val node = resolved.node
            return try {
                val performAction = {
                    performNodeAction(node, action, text, arguments)
                }
                // 与手势派发同一判据：预算里必须有监视器真正会观察的事件类型。
                // SET_SELECTION 只产生 TYPE_VIEW_TEXT_SELECTION_CHANGED，
                // 它不在监视范围内，按「预算非空」判断会让 arm 失败，
                // 于是动作根本不执行并报 ACTION_FAILED——已实测踩到。
                if (userTouchMonitor?.hasMonitorableBudget(expectedEventBudgets) == true) {
                    userTouchMonitor.runAttributedAction(
                        source = node,
                        eventBudgets = expectedEventBudgets,
                        timeoutMs = timeoutMs,
                        action = performAction,
                    )
                } else {
                    performAction()
                }
            } finally {
                resolved.release()
            }
        }

        private fun performNodeAction(
            node: AccessibilityNodeInfo,
            action: NodeAction,
            text: String?,
            arguments: Bundle?,
        ): Boolean = when (action) {
            NodeAction.SET_TEXT -> {
                if (text == null) {
                    false
                } else {
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text,
                            )
                        },
                    )
                }
            }

            // 这三个动作没有参数就没有意义：SET_SELECTION 缺少区间等价于清除选区，
            // SET_PROGRESS 缺少值无法确定目标，SCROLL_TO_POSITION 缺少行列无处可去。
            // 静默执行会让调用方以为设置成功，因此明确拒绝。
            NodeAction.SET_SELECTION,
            NodeAction.SET_PROGRESS,
            NodeAction.SCROLL_TO_POSITION,
            -> arguments != null && node.performAction(action.toPlatformAction(), arguments)

            else -> node.performAction(action.toPlatformAction())
        }

        override fun close() {
            root?.recycleSafely()
        }

        /**
         * 按路径解析节点。
         *
         * 路径带 windowId 时在该窗口内解析——这是弹窗、输入法里的节点能被操作的关键。
         * 不带 windowId 时沿用会话建立时的默认窗口，从而兼容多窗口模型引入前
         * 录制出的脚本（那些 matchedPath 里没有窗口标识）。
         *
         * 返回值带 release：从窗口重新取回的根是本次调用新持有的，必须回收；
         * 而单根模式下的会话根由 close 负责，不能在这里回收。
         */
        private fun findNode(path: NodePath): ResolvedNode? {
            val windowId = path.windowId ?: defaultWindowId
            val ownedRoot: AccessibilityNodeInfo?
            val start: AccessibilityNodeInfo
            if (root != null && path.windowId == null) {
                ownedRoot = null
                start = root
            } else {
                ownedRoot = resolveRoot(windowId) ?: return null
                start = ownedRoot
            }

            var current = start
            var currentOwned = false
            path.indices.forEach { index ->
                val child = current.getChild(index)
                if (currentOwned) {
                    current.recycleSafely()
                }
                if (child == null) {
                    if (!currentOwned) {
                        ownedRoot?.recycleSafely()
                    }
                    return null
                }
                current = child
                currentOwned = true
            }
            val leafOwned = currentOwned
            return ResolvedNode(
                node = current,
                release = {
                    if (leafOwned) {
                        current.recycleSafely()
                    }
                    if (current !== ownedRoot) {
                        ownedRoot?.recycleSafely()
                    }
                },
            )
        }

        /** 已解析节点及其回收动作，避免调用方猜测该不该回收。 */
        private class ResolvedNode(
            val node: AccessibilityNodeInfo,
            val release: () -> Unit,
        )

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

            NodeAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
            NodeAction.CLEAR_FOCUS -> AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
            NodeAction.SELECT -> AccessibilityNodeInfo.ACTION_SELECT
            NodeAction.CLEAR_SELECTION -> AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
            NodeAction.EXPAND -> AccessibilityNodeInfo.ACTION_EXPAND
            NodeAction.COLLAPSE -> AccessibilityNodeInfo.ACTION_COLLAPSE
            NodeAction.DISMISS -> AccessibilityNodeInfo.ACTION_DISMISS
            NodeAction.COPY -> AccessibilityNodeInfo.ACTION_COPY
            NodeAction.CUT -> AccessibilityNodeInfo.ACTION_CUT
            NodeAction.PASTE -> AccessibilityNodeInfo.ACTION_PASTE
            NodeAction.SET_SELECTION -> AccessibilityNodeInfo.ACTION_SET_SELECTION
            NodeAction.SET_PROGRESS ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id

            NodeAction.SCROLL_TO_POSITION ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
        }
    }

    private companion object {
        const val GESTURE_EVENT_BUDGET = 3
        // 启动过渡：harness 自身包名，前台是 harness 自身时放行等待 app.launch 生效。
        const val HARNESS_PACKAGE = "dev.aiauto.android"

        /**
         * 复合手势的最大续接轮数。
         *
         * 取 16：图案解锁最长 9 点（8 段），drag 是 3 段，五连击是 5 段，
         * 都在此之内。上限存在的意义是防止调用方构造出上千段的手势
         * 导致派发线程长时间阻塞。
         */
        const val MAX_STROKE_ROUNDS = 16

        /**
         * 单批手势回调的等待上限。
         *
         * 取 10 秒：与长按上限 10000ms 对齐，任何单批手势的时长都不应超过它。
         * 超时按失败处理而不是继续等待，避免回调丢失时永久占住调用线程。
         */
        const val GESTURE_CALLBACK_TIMEOUT_MS = 10_000L
    }
}
