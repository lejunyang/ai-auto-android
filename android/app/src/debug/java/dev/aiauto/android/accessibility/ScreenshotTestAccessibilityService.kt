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
