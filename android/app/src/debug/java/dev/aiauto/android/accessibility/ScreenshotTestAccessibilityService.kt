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
    private lateinit var n45Preferences:
        android.content.SharedPreferences
    private val userTouchCount = AtomicInteger()
    private val runtimeStopHandled = AtomicBoolean()
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
            ),
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
     */
    fun isTargetVisible(contentDescription: String): Boolean {
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
