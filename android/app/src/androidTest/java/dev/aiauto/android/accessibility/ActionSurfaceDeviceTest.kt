package dev.aiauto.android.accessibility

/**
 * 测试用途：在真实 Android 运行时验证动作面扩展的多窗口枚举与复合手势派发，
 * 证明这些能力走的是生产 router 与 backend 而非测试专用简化通道。
 *
 * 为什么必须是设备测试：多窗口枚举读的是 AccessibilityService.windows，
 * 复合手势走的是平台 dispatchGesture 的多 stroke 续接。这两者在单测里
 * 只能被伪造——伪造出来的窗口列表与伪造出来的派发结果都不能证明
 * 真实设备上可用。
 */

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.DragAnchor
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSurfaceDeviceTest {
    @Test
    fun enumeratesMoreThanTheActiveWindowOnRealDevice() {
        val instrumentation = requireManualAccessibility()
        var activity: Activity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchTestActivity(instrumentation)

            val windows = service.enumerateWindows()

            // 真实设备上状态栏与导航栏都是独立窗口，因此可见窗口必然多于一个。
            // 这一条正是多窗口模型存在的理由：rootInActiveWindow 只能看到其中一个。
            assertTrue(
                "expected more than one visible window, got ${windows.size}",
                windows.size > 1,
            )
            // 必须至少有一个应用窗口，否则说明枚举拿到的是空壳。
            assertTrue(
                "expected at least one application window",
                windows.any { it.kind == WindowKind.APPLICATION },
            )
            // 被测 Activity 的窗口必须在列表里，且其根节点能提供子节点，
            // 证明 window.root 真的被读到而不是 null 被跳过。
            val expectedPackage = instrumentation.targetContext.packageName
            val ownWindow = windows.firstOrNull { it.root.packageName == expectedPackage }
            assertNotNull("expected the test activity window to be enumerated", ownWindow)
            assertTrue(
                "expected the test activity window to expose child nodes",
                ownWindow!!.root.children.isNotEmpty(),
            )
            // layer 降序是坐标动作命中最上层的前提，顺序错了会点到被盖住的元素。
            assertEquals(
                windows.map { it.layer }.sortedDescending(),
                windows.map { it.layer },
            )
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun dispatchesMultiStrokeDragThroughTheProductionRouter() {
        val instrumentation = requireManualAccessibility()
        var activity: Activity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchTestActivity(instrumentation)
            assumeTrue(
                "Composite gestures require CAPABILITY_CAN_PERFORM_GESTURES",
                service.canPerformGestures(),
            )

            val metrics = instrumentation.targetContext.resources.displayMetrics
            val centerX = metrics.widthPixels / 2
            // 起止都落在屏幕中部：避开顶部状态栏与底部系统手势区，
            // 那两处的手势会被系统截走，测出来的失败与实现无关。
            val start = ScreenPoint(x = centerX, y = metrics.heightPixels / 2)
            val end = ScreenPoint(x = centerX, y = metrics.heightPixels / 2 - DRAG_TRAVEL_PX)

            val result = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Drag(
                    start = DragAnchor(point = start),
                    end = DragAnchor(point = end),
                    expectedPackage = instrumentation.targetContext.packageName,
                ),
                expectedPackage = instrumentation.targetContext.packageName,
            )

            // 断言平台接受了这次多 stroke 续接派发。失败即说明 continueStroke
            // 的分批或等待逻辑有问题——这正是单测无法覆盖的部分。
            assertTrue("drag was rejected: $result", result is AccessibilityResult.Success)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun dispatchesMultiTapThroughTheProductionRouter() {
        val instrumentation = requireManualAccessibility()
        var activity: Activity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchTestActivity(instrumentation)
            assumeTrue(
                "Composite gestures require CAPABILITY_CAN_PERFORM_GESTURES",
                service.canPerformGestures(),
            )

            val metrics = instrumentation.targetContext.resources.displayMetrics
            val result = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.MultiTap(
                    point = ScreenPoint(
                        x = metrics.widthPixels / 2,
                        y = metrics.heightPixels / 2,
                    ),
                    taps = 2,
                    expectedPackage = instrumentation.targetContext.packageName,
                ),
                expectedPackage = instrumentation.targetContext.packageName,
            )

            assertTrue("double tap was rejected: $result", result is AccessibilityResult.Success)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun dispatchesTwoFingerPinchThroughTheProductionRouter() {
        val instrumentation = requireManualAccessibility()
        var activity: Activity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchTestActivity(instrumentation)
            assumeTrue(
                "Composite gestures require CAPABILITY_CAN_PERFORM_GESTURES",
                service.canPerformGestures(),
            )

            // 缩放是多指手势的代表：它同时检验「一个 GestureDescription 里
            // 两笔并行」这条平台能力。直连 ADB 通道无法做到这一点，
            // 因此这条断言是多指能力唯一的真实证据。
            val result = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Pinch(
                    scale = PINCH_SCALE,
                    expectedPackage = instrumentation.targetContext.packageName,
                ),
                expectedPackage = instrumentation.targetContext.packageName,
            )

            assertTrue("pinch was rejected: $result", result is AccessibilityResult.Success)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    /** 本类只在显式传入 manualAccessibility=true 时运行，避免默认 CI 误跑。 */
    private fun requireManualAccessibility(): Instrumentation {
        assumeTrue(
            "Manual accessibility tests require manualAccessibility=true",
            InstrumentationRegistry.getArguments()
                .getString(MANUAL_ACCESSIBILITY_ARGUMENT) == "true",
        )
        return InstrumentationRegistry.getInstrumentation()
    }

    /**
     * 取回已连接的调试无障碍服务。
     *
     * 两步都必不可少：
     *
     * 1. 先用 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES 取 UiAutomation。
     *    instrumentation 默认会在测试期间挂起所有第三方无障碍服务。
     * 2. 再从测试内部重新写一次 enabled_accessibility_services 触发绑定。
     *    仅做第 1 步不够：instrumentation 启动时会重建目标进程，
     *    此前在旧进程里完成的 onServiceConnected 不会在新进程重放，
     *    而 connectedService 是进程内静态字段，于是 awaitConnected 必然超时。
     *    必须在拿到不抑制的 UiAutomation 之后重新触发一次绑定。
     */
    private fun awaitService(
        instrumentation: Instrumentation,
    ): ScreenshotTestAccessibilityService {
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        if (ScreenshotTestAccessibilityService.connectedService == null) {
            rebindServices(instrumentation, automation)
        }
        return ScreenshotTestAccessibilityService.awaitConnected(TIMEOUT_SECONDS)
    }

    /**
     * 重新写入 secure 设置以触发服务绑定。
     *
     * 只在一次性模拟器上执行：真机的无障碍授权必须由用户本人在系统设置里完成。
     * 因此这里先校验 serial 前缀，拒绝在真机上自动授权。
     */
    private fun rebindServices(
        instrumentation: Instrumentation,
        automation: UiAutomation,
    ) {
        assumeTrue(
            "Automatic accessibility provisioning is only allowed on disposable emulators",
            Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.PRODUCT.contains("sdk"),
        )
        val resolver = instrumentation.targetContext.contentResolver
        val target = instrumentation.targetContext.packageName
        val services = listOf(
            "$target/dev.aiauto.android.accessibility.AiAutomationAccessibilityService",
            "$target/dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService",
        ).joinToString(":")
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            // 先清空再写入：值未变化时 SettingsProvider 不会发通知，
            // 于是 AccessibilityManagerService 不会重新评估绑定。
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "",
            )
            SystemClock.sleep(SETTINGS_SETTLE_MS)
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services,
            )
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1",
            )
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun launchTestActivity(instrumentation: Instrumentation): Activity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ScreenshotTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        // 等窗口真正加入窗口列表：startActivitySync 返回时 Activity 已创建，
        // 但 AccessibilityService.windows 的更新是异步的，立即枚举会漏掉它。
        SystemClock.sleep(WINDOW_SETTLE_MS)
        return activity
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
        const val WINDOW_SETTLE_MS = 1_500L
        const val SETTINGS_SETTLE_MS = 1_000L
        const val DRAG_TRAVEL_PX = 200
        const val PINCH_SCALE = 1.5
    }
}
