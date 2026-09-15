package dev.aiauto.android.accessibility

/**
 * 功能用途：提供 ScreenshotTestAccessibilityService 的设备验收入口，仅用于 debug 变体且不进入 release 制品。
 */

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.action.AccessibilityActionRouter
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualAction
import dev.aiauto.android.automation.session.AutomationSessionRuntime
import dev.aiauto.android.automation.session.toAccessibilityCommand

class ScreenshotTestAccessibilityService : AccessibilityService() {
    private lateinit var userTouchMonitor: UserTouchMonitor
    private lateinit var n45ActionRouter: AccessibilityActionRouter

    /**
     * 生产 backend 实例，供全局动作验收直接调用 performGlobal。
     *
     * 复用 router 内部那一个而非另建：全局动作的 API 分级判定写在 backend 里，
     * 另建实例可能与生产走不同代码路径，那样测过的就不是被交付的实现。
     */
    private lateinit var globalActionBackend: AndroidAccessibilityBackend
    private lateinit var n45Preferences:
        android.content.SharedPreferences
    private val userTouchCount = AtomicInteger()
    private val runtimeStopHandled = AtomicBoolean()

    /**
     * Toast 与通知捕获缓冲。
     *
     * 在字段声明处初始化：事件可能在 onServiceConnected 完成之前就到达。
     */
    private val transientMessages = TransientMessageCapture()
    @Volatile
    private var userTouchLatch = CountDownLatch(1)

    override fun onServiceConnected() {
        super.onServiceConnected()
        userTouchMonitor = UserTouchMonitor(
            notifyUserTouch = { packageName ->
                userTouchCount.incrementAndGet()
                userTouchLatch.countDown()
                val handled = AutomationSessionRuntime.notifyUserTouch(packageName)
                runtimeStopHandled.set(handled)
                handled
            },
            currentSessionId = AutomationSessionRuntime::activeSessionId,
        )
        val currentServiceInfo = serviceInfo
        val touchConfiguration = userTouchMonitor.configuration(
            eventTypes = currentServiceInfo.eventTypes,
            flags = currentServiceInfo.flags,
            motionEventSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                currentServiceInfo.motionEventSources
            } else {
                0
            },
        )
        serviceInfo = currentServiceInfo.apply {
            eventTypes = touchConfiguration.eventTypes
            flags = touchConfiguration.flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                motionEventSources = touchConfiguration.motionEventSources
            }
        }
        n45Preferences = getSharedPreferences(
            N45_SETTINGS_NAME,
            MODE_PRIVATE,
        )
        val n45Settings = AccessibilitySettingsRepository(n45Preferences)
        n45Settings.save(
            AccessibilitySettings(
                disclosureAccepted = true,
                targetPackages = setOf(packageName),
            ),
        )
        n45ActionRouter = AccessibilityActionRouter(
            backend = AndroidAccessibilityBackend(
                service = this,
                settingsRepository = n45Settings,
                snapshotter = AccessibilitySnapshotter(),
                userTouchMonitor = userTouchMonitor,
            ).also { globalActionBackend = it },
            selectorMatcher = SelectorMatcher(),
        )
        connectedService = this
        connectedLatch.countDown()
    }

    override fun onDestroy() {
        if (connectedService === this) {
            connectedService = null
        }
        if (::n45Preferences.isInitialized) {
            n45Preferences.edit().clear().commit()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && ::userTouchMonitor.isInitialized) {
            userTouchMonitor.onAccessibilityEvent(event)
        }
        // 与生产服务一致地捕获 Toast 与通知，供设备测试断言平台确实投递了
        // TYPE_NOTIFICATION_STATE_CHANGED——这一点单测无法验证。
        if (event != null) {
            transientMessages.onAccessibilityEvent(event)
        }
    }

    override fun onInterrupt() = Unit

    fun snapshot(expectedPackage: String): AccessibilityResult<UiNodeSnapshot> {
        val root = rootInActiveWindow ?: return AccessibilityResult.Failure(
            code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
            message = "The test activity has no active accessibility window",
            retryable = true,
        )
        return try {
            if (root.packageName?.toString() != expectedPackage) {
                AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                    message = "The active test window does not match the requested package",
                    retryable = true,
                )
            } else {
                AccessibilitySnapshotter().snapshot(root)
            }
        } finally {
            root.recycleSafely()
        }
    }

    fun performAttributedClick(contentDescription: String): Boolean =
        withNode(contentDescription) { node ->
            userTouchMonitor.runAttributedAction(
                source = node,
                eventBudgets = mapOf(AccessibilityEvent.TYPE_VIEW_CLICKED to 1),
            ) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

    fun armClickExpectation(contentDescription: String): Boolean =
        withNode(contentDescription) { node ->
            userTouchMonitor.runAttributedAction(
                source = node,
                eventBudgets = mapOf(AccessibilityEvent.TYPE_VIEW_CLICKED to 1),
            ) {
                true
            }
        }

    fun resetUserTouchSignal() {
        userTouchMonitor.clearAutomationActions()
        userTouchCount.set(0)
        runtimeStopHandled.set(false)
        userTouchLatch = CountDownLatch(1)
    }

    fun userTouchNotificationCount(): Int = userTouchCount.get()

    fun runtimeStopHandled(): Boolean = runtimeStopHandled.get()

    fun awaitUserTouch(timeoutSeconds: Long): Boolean =
        userTouchLatch.await(timeoutSeconds, TimeUnit.SECONDS)

    /** 仅供 debug N45 harness 调用生产 command 映射与真实 Accessibility router/backend。 */
    fun executeN45VisualAction(
        action: ExplicitVisualAction,
        expectedPackage: String,
    ): AccessibilityResult<ActionExecution> {
        if (!::n45ActionRouter.isInitialized || expectedPackage != packageName) {
            return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                message = "The N45 debug fixture only accepts its own package",
            )
        }
        return n45ActionRouter.execute(action.toAccessibilityCommand(expectedPackage))
    }

    /**
     * 仅供 debug 动作面 harness 执行任意生产命令。
     *
     * 直接走与生产完全相同的 router 与 backend：动作面扩展的验收要证明
     * 「生产路径可用」，若这里另建一条简化通道，测过的就不是被交付的代码。
     */
    fun executeActionSurfaceCommand(
        command: dev.aiauto.android.accessibility.model.AccessibilityCommand,
        expectedPackage: String,
    ): AccessibilityResult<ActionExecution> {
        if (!::n45ActionRouter.isInitialized || expectedPackage != packageName) {
            return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                message = "The action surface debug fixture only accepts its own package",
            )
        }
        return n45ActionRouter.execute(command)
    }

    /**
     * 用生产 WindowSnapshotter 枚举当前可见窗口。
     *
     * 这是多窗口模型唯一可验证的出口：它必须在真实设备上返回多于一个窗口
     * （状态栏、导航栏、输入法都是独立窗口），否则说明 service.windows
     * 没有真正被读到，而这一点无法用单测证明——单测里窗口列表是伪造的。
     */
    fun enumerateWindows(): List<dev.aiauto.android.accessibility.model.UiWindowSnapshot> =
        dev.aiauto.android.accessibility.snapshot.WindowSnapshotter().snapshotWindows(this)

    /** 供 harness 断言平台是否声明了手势派发能力。 */
    fun canPerformGestures(): Boolean =
        serviceInfo.capabilities and
            android.accessibilityservice.AccessibilityServiceInfo
                .CAPABILITY_CAN_PERFORM_GESTURES != 0

    /**
     * 判断指定 contentDescription 的节点当前是否在视口内可见。
     *
     * 用于 scrollIntoView 的前置与后置验证：仅断言动作返回成功不够——
     * 实现完全可能在没有真正滚动的情况下返回成功。这里读的是节点的
     * isVisibleToUser，即平台对「用户此刻能否看到它」的判断。
     *
     * 跨窗口查找，与生产的多窗口模型保持一致。
     */    fun isTargetVisible(contentDescription: String): Boolean {
        val windows = dev.aiauto.android.accessibility.snapshot.WindowSnapshotter()
            .snapshotWindows(this)
        val roots = if (windows.isEmpty()) {
            listOfNotNull(
                rootInActiveWindow?.let { root ->
                    try {
                        AccessibilitySnapshotter().snapshot(root)
                    } finally {
                        root.recycleSafely()
                    }
                },
            ).filterIsInstance<AccessibilityResult.Success<UiNodeSnapshot>>()
                .map { it.value }
        } else {
            windows.map { it.root }
        }
        return roots.any { root -> containsVisible(root, contentDescription) }
    }

    private fun containsVisible(
        node: UiNodeSnapshot,
        contentDescription: String,
    ): Boolean {
        if (
            node.contentDescription == contentDescription &&
            node.state.visibleToUser
        ) {
            return true
        }
        return node.children.any { child -> containsVisible(child, contentDescription) }
    }

    /**
     * 读取本服务捕获到的 Toast 与通知。
     *
     * 走本服务自己的缓冲而非生产服务的：两个服务各自独立接收事件，
     * 读生产服务的缓冲需要跨服务引用，而那条路径在测试里不可靠
     * （生产服务可能尚未绑定）。此处验证的是「平台确实投递了该事件类型」
     * 与「捕获逻辑正确分类」，这两点与哪个服务实例接收无关。
     */
    fun capturedTransientMessages(): List<TransientMessage> = transientMessages.messages()

    /** 清空提示缓冲，让断言只覆盖本次触发的提示。 */
    fun clearTransientMessageBuffer() {
        transientMessages.clear()
    }

    /**
     * 返回当前快照里可见节点的 contentDescription 与顶边坐标。
     *
     * 用于 B1 的根因定位：scrollIntoView 的无进展检测比较的是同一份快照数据，
     * 若这里两轮返回相同内容，说明快照没反映滚动；若不同而检测仍报无进展，
     * 说明签名算法有问题。没有这个观测点只能反复猜。
     */
    fun visibleNodeFingerprint(): List<String> {
        val windows = dev.aiauto.android.accessibility.snapshot.WindowSnapshotter()
            .snapshotWindows(this)
        val roots = if (windows.isEmpty()) {
            emptyList()
        } else {
            windows.filter { it.root.packageName == packageName }.map { it.root }
        }
        val collected = mutableListOf<String>()
        roots.forEach { root -> collectFingerprint(root, collected) }
        return collected
    }

    private fun collectFingerprint(node: UiNodeSnapshot, into: MutableList<String>) {
        if (node.state.visibleToUser) {
            val label = node.contentDescription ?: node.text
            if (!label.isNullOrBlank()) {
                into += "$label@${node.bounds.top}"
            }
        }
        node.children.forEach { child -> collectFingerprint(child, into) }
    }

    /**
     * 返回当前可见窗口的类别集合。
     *
     * 用于全局动作验收：通知栏与快捷设置展开时会新增 SYSTEM 类窗口并夺取焦点，
     * 这是「动作真的生效了」在无障碍侧唯一可观测的信号——
     * performGlobalAction 只返回布尔值，不看窗口无法区分「系统接受了」
     * 与「界面真的变了」。
     */
    fun visibleWindowKinds(): List<String> =
        dev.aiauto.android.accessibility.snapshot.WindowSnapshotter()
            .snapshotWindows(this)
            .map { "${it.kind}:${it.root.packageName}" }

    /** 供全局动作验收执行生产 performGlobalAction 并回报结果。 */
    fun performGlobalActionForTest(
        action: dev.aiauto.android.accessibility.model.GlobalAction,
    ): Boolean = globalActionBackend.performGlobal(action)

    /**
     * 直接向平台派发一次滚动 swipe，绕过 router 与归因层。
     *
     * 用于 B1 的根因定位：若同一组坐标经此方法能派发成功、而经 router 失败，
     * 说明问题在 router/归因链而非坐标或平台；反之说明坐标本身被系统拒绝。
     * 没有这个对照，只能反复猜测哪一层出了问题。
     */
    fun dispatchRawSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val description = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs,
                ),
            )
            .build()
        return dispatchGesture(description, null, null)
    }

    private fun withNode(
        contentDescription: String,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.contentDescription?.toString() == contentDescription) {
                return try {
                    action(node)
                } finally {
                    node.recycleSafely()
                    nodes.forEach { it.recycleSafely() }
                }
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(nodes::addLast)
            }
            node.recycleSafely()
        }
        return false
    }

    private fun activePackageName(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycleSafely()
        }
    }

    companion object {
        @Volatile
        var connectedService: ScreenshotTestAccessibilityService? = null
            private set

        private var connectedLatch = CountDownLatch(1)
        private const val N45_SETTINGS_NAME = "n45-visual-device-matrix"

        fun awaitConnected(timeoutSeconds: Long): ScreenshotTestAccessibilityService {
            check(connectedLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "Enable Accessibility device test manually in Android accessibility settings"
            }
            return checkNotNull(connectedService)
        }
    }
}
