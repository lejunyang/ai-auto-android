package dev.aiauto.android.accessibility

/**
 * 测试用途：补齐动作面剩余能力的设备验收——多指手势（zoom / 旋转 / 双指滚动）、
 * 路径手势、剪切、三连击，以及节点动作里 FOCUS / CLEAR_FOCUS / SELECT /
 * CLEAR_SELECTION / SCROLL_TO_POSITION 五项。
 *
 * 断言分两类，刻意区别对待：
 * - 能读回控件状态的（剪切、焦点、选中）断言实际效果；
 * - 只能断言「平台接受了派发」的（多指手势、路径手势）就只断言这一点，
 *   并在注释里说明为什么无法更强——缩放与旋转由 View 内部处理，
 *   夹具没有响应它们的控件，强行断言视觉结果会写出一个假测试。
 */

import android.Manifest
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
import dev.aiauto.android.accessibility.model.ClipboardOperation
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSurfaceRemainingDeviceTest {
    @Test
    fun dispatchesZoomRotateAndTwoFingerScroll() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            assumeTrue(
                "Composite gestures require CAPABILITY_CAN_PERFORM_GESTURES",
                service.canPerformGestures(),
            )
            val expectedPackage = instrumentation.targetContext.packageName

            // zoom 与 pinch 是同一命令的两个方向：scale > 1 放大，< 1 捏合。
            // 上一轮只验证了 pinch（1.5），这里补 scale < 1 的方向。
            val zoomOut = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Pinch(
                    scale = ZOOM_OUT_SCALE,
                    expectedPackage = expectedPackage,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("zoom out was rejected: $zoomOut", zoomOut is AccessibilityResult.Success)

            val rotate = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Rotate(
                    degrees = ROTATE_DEGREES,
                    expectedPackage = expectedPackage,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("rotate was rejected: $rotate", rotate is AccessibilityResult.Success)

            val metrics = instrumentation.targetContext.resources.displayMetrics
            val twoFinger = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.TwoFingerScroll(
                    // 位移取视口高度的一小部分：过大时终点会落到屏幕外，
                    // 编排阶段会以 INVALID_COORDINATE 拒绝，那测的就不是双指能力。
                    deltaY = -(metrics.heightPixels / TWO_FINGER_DIVISOR),
                    deltaX = 0,
                    expectedPackage = expectedPackage,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue(
                "two-finger scroll was rejected: $twoFinger",
                twoFinger is AccessibilityResult.Success,
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun dispatchesAPathGestureAndTripleTap() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            assumeTrue(
                "Composite gestures require CAPABILITY_CAN_PERFORM_GESTURES",
                service.canPerformGestures(),
            )
            val expectedPackage = instrumentation.targetContext.packageName
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val centerX = metrics.widthPixels / 2
            val centerY = metrics.heightPixels / 2

            // 三点折线，模拟图案解锁那类必须精确经过中间点的轨迹。
            // 全部点收在屏幕中部，避开系统手势区。
            val path = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.PathGesture(
                    points = listOf(
                        ScreenPoint(centerX - PATH_SPAN, centerY - PATH_SPAN),
                        ScreenPoint(centerX + PATH_SPAN, centerY - PATH_SPAN),
                        ScreenPoint(centerX + PATH_SPAN, centerY + PATH_SPAN),
                    ),
                    expectedPackage = expectedPackage,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("path gesture was rejected: $path", path is AccessibilityResult.Success)

            // 三连击：上一轮只验证了双击，这里验证 taps > 2 的分支。
            val tripleTap = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.MultiTap(
                    point = ScreenPoint(centerX, centerY),
                    taps = TRIPLE_TAP,
                    expectedPackage = expectedPackage,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue(
                "triple tap was rejected: $tripleTap",
                tripleTap is AccessibilityResult.Success,
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun cutsSelectedTextOutOfTheField() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            val expectedPackage = instrumentation.targetContext.packageName

            val selected = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.SelectText(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    startIndex = 0,
                    endIndex = CUT_LENGTH,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("selectText was rejected: $selected", selected is AccessibilityResult.Success)
            SystemClock.sleep(SETTLE_MS)

            val cut = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Clipboard(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    operation = ClipboardOperation.CUT,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("cut was rejected: $cut", cut is AccessibilityResult.Success)

            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            // 剪切与复制的区别正是「原文被移除」，因此必须断言剩余文本，
            // 只断言动作成功无法区分二者。
            assertEquals(
                ActionSurfaceFixtureActivity.INITIAL_TEXT.substring(CUT_LENGTH),
                activity.editableText(),
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun performsFocusSelectionAndScrollToPositionNodeActions() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            val expectedPackage = instrumentation.targetContext.packageName

            // FOCUS 走无障碍焦点而非输入焦点，实测可用。
            val focused = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.PerformNodeAction(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    action = NodeAction.FOCUS,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("FOCUS was rejected: $focused", focused is AccessibilityResult.Success)
            SystemClock.sleep(SHORT_SETTLE_MS)

            // CLEAR_FOCUS 在本夹具上被平台拒绝，且拒绝方式是 ACTION_FAILED
            // 而非 ACTION_NOT_SUPPORTED——即节点声明了该动作、但 View 的
            // performAccessibilityAction 返回 false。实测（API 35）在 FOCUS
            // 成功之后立即执行仍然如此。
            //
            // 这里断言「明确失败」而不是「成功」：把平台真实行为写进断言，
            // 才能在未来某个版本行为变化时被测试发现。若改成跳过或不测，
            // 这个缺口就会被静默掩盖。
            // 该项在跟踪文档中记为未交付，原因即此。
            val cleared = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.PerformNodeAction(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    action = NodeAction.CLEAR_FOCUS,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue(
                "CLEAR_FOCUS is expected to be refused by the platform on this fixture; " +
                    "if this now succeeds, update the tracking document: $cleared",
                cleared is AccessibilityResult.Failure,
            )
            SystemClock.sleep(SHORT_SETTLE_MS)

            // SELECT 作用在 CheckBox 上：它是夹具里唯一声明了选中语义的控件。
            for (action in listOf(NodeAction.SELECT, NodeAction.CLEAR_SELECTION)) {
                val result = service.executeActionSurfaceCommand(
                    command = AccessibilityCommand.PerformNodeAction(
                        target = target(ActionSurfaceFixtureActivity.CHECKBOX_DESCRIPTION),
                        action = action,
                    ),
                    expectedPackage = expectedPackage,
                )
                assertTrue(
                    "${action.name} was rejected: $result",
                    result is AccessibilityResult.Success,
                )
                SystemClock.sleep(SHORT_SETTLE_MS)
            }

            // SCROLL_TO_POSITION 要求容器支持按行定位。ScrollView 不是
            // CollectionInfo 容器，因此这里预期它被明确拒绝为不支持——
            // 断言「明确失败」而不是跳过：静默通过会掩盖能力缺口。
            val scrollToPosition = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.ScrollToPosition(
                    target = target(
                        ActionSurfaceFixtureActivity.SCROLL_CONTAINER_DESCRIPTION,
                    ),
                    row = 1,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue(
                "ScrollView is not a collection container, so SCROLL_TO_POSITION " +
                    "must fail explicitly rather than silently succeed: $scrollToPosition",
                scrollToPosition is AccessibilityResult.Failure,
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    private fun target(contentDescription: String) = NodeTarget(
        packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        selectorCandidates = listOf(
            SelectorCandidate(
                strategy = SelectorStrategy.CONTENT_DESCRIPTION,
                value = contentDescription,
                weight = 1.0,
                required = true,
            ),
        ),
    )

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
     * 两步都必要：instrumentation 默认挂起第三方无障碍服务，且它会重建目标进程
     * 使 onServiceConnected 不重放，因此还要重新写 secure 设置触发绑定。
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

    /** 只在一次性模拟器上自动授权：真机的无障碍授权必须由用户本人完成。 */
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
            // 先清空再写入：值未变化时 SettingsProvider 不发通知，
            // AccessibilityManagerService 也就不会重新评估绑定。
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "",
            )
            SystemClock.sleep(SETTLE_MS)
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

    private fun launchFixture(
        instrumentation: Instrumentation,
    ): ActionSurfaceFixtureActivity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ActionSurfaceFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ActionSurfaceFixtureActivity
        instrumentation.waitForIdleSync()
        // 等窗口加入 AccessibilityService.windows：该更新是异步的。
        SystemClock.sleep(WINDOW_SETTLE_MS)
        return activity
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
        const val WINDOW_SETTLE_MS = 1_500L
        const val SETTLE_MS = 1_000L
        const val SHORT_SETTLE_MS = 400L
        const val ZOOM_OUT_SCALE = 0.5
        const val ROTATE_DEGREES = 45.0
        const val TWO_FINGER_DIVISOR = 6
        const val PATH_SPAN = 40
        const val TRIPLE_TAP = 3
        const val CUT_LENGTH = 4
    }
}
