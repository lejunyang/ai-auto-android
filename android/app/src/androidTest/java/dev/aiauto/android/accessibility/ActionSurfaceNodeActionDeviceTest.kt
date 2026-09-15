package dev.aiauto.android.accessibility

/**
 * 测试用途：在真实 Android 运行时验证文本选区、复制粘贴、进度设值、展开折叠、
 * 焦点与滚动到可见这几组节点级能力，断言的是控件实际状态而非「动作被接受」。
 *
 * 与 ActionSurfaceDeviceTest 的分工：那里验证多窗口与手势派发，
 * 这里验证节点动作对被测控件产生了预期效果。两者共用同一套授权与绑定前提，
 * 但断言对象完全不同——手势只能断言平台是否接受，节点动作可以断言结果。
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
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSurfaceNodeActionDeviceTest {
    @Test
    fun setsTextSelectionOnTheEditableField() {
        val instrumentation = requireManualAccessibility()
        var activity: ActionSurfaceFixtureActivity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchFixture(instrumentation)

            val result = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.SelectText(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    startIndex = SELECTION_START,
                    endIndex = SELECTION_END,
                ),
                expectedPackage = instrumentation.targetContext.packageName,
            )

            assertTrue("selectText was rejected: $result", result is AccessibilityResult.Success)
            // 断言控件真实选区而非只看动作返回值：ACTION_SET_SELECTION 在
            // 参数越界时也可能返回 true，只有读回选区才能证明区间正确落地。
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            assertEquals(SELECTION_START to SELECTION_END, activity.selection())
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun copiesTheSelectedTextAndPastesItIntoAnotherField() {
        val instrumentation = requireManualAccessibility()
        var activity: ActionSurfaceFixtureActivity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchFixture(instrumentation)
            val expectedPackage = instrumentation.targetContext.packageName

            // 复制要求已有选区，因此先划区间。这条依赖是平台语义而非实现细节：
            // ACTION_COPY 复制的就是当前选区，无选区时无事可做。
            val selected = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.SelectText(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    startIndex = SELECTION_START,
                    endIndex = SELECTION_END,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("selectText was rejected: $selected", selected is AccessibilityResult.Success)
            SystemClock.sleep(SETTLE_MS)

            val copied = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Clipboard(
                    target = target(ActionSurfaceFixtureActivity.EDITABLE_DESCRIPTION),
                    operation = ClipboardOperation.COPY,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("copy was rejected: $copied", copied is AccessibilityResult.Success)
            SystemClock.sleep(SETTLE_MS)

            // 粘贴到另一个输入框：粘贴回源框无法区分「粘贴成功」
            // 与「内容本来就在那」。
            val pasted = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.Clipboard(
                    target = target(ActionSurfaceFixtureActivity.PASTE_TARGET_DESCRIPTION),
                    operation = ClipboardOperation.PASTE,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("paste was rejected: $pasted", pasted is AccessibilityResult.Success)

            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            val expectedFragment = ActionSurfaceFixtureActivity.INITIAL_TEXT
                .substring(SELECTION_START, SELECTION_END)
            assertEquals(expectedFragment, activity.pasteTargetText())
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun setsSeekBarProgress() {
        val instrumentation = requireManualAccessibility()
        var activity: ActionSurfaceFixtureActivity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchFixture(instrumentation)

            val result = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.SetProgress(
                    target = target(ActionSurfaceFixtureActivity.SEEK_BAR_DESCRIPTION),
                    value = TARGET_PROGRESS.toFloat(),
                ),
                expectedPackage = instrumentation.targetContext.packageName,
            )

            assertTrue("setProgress was rejected: $result", result is AccessibilityResult.Success)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            assertEquals(TARGET_PROGRESS, activity.progress())
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun expandsAndCollapsesTheSection() {
        val instrumentation = requireManualAccessibility()
        var activity: ActionSurfaceFixtureActivity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchFixture(instrumentation)
            val expectedPackage = instrumentation.targetContext.packageName

            val expanded = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.PerformNodeAction(
                    target = target(ActionSurfaceFixtureActivity.EXPANDABLE_DESCRIPTION),
                    action = NodeAction.EXPAND,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("expand was rejected: $expanded", expanded is AccessibilityResult.Success)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            assertEquals("EXPANDED", activity.status())

            // 折叠必须单独验证：夹具按当前状态只暴露一个动作，
            // 若展开未真正改变状态，这一步会因 ACTION_COLLAPSE 不存在而失败。
            val collapsed = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.PerformNodeAction(
                    target = target(ActionSurfaceFixtureActivity.EXPANDABLE_DESCRIPTION),
                    action = NodeAction.COLLAPSE,
                ),
                expectedPackage = expectedPackage,
            )
            assertTrue("collapse was rejected: $collapsed", collapsed is AccessibilityResult.Success)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            assertEquals("COLLAPSED", activity.status())
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun scrollsAnOffscreenTargetIntoView() {
        val instrumentation = requireManualAccessibility()
        var activity: ActionSurfaceFixtureActivity? = null
        try {
            val service = awaitService(instrumentation)
            activity = launchFixture(instrumentation)
            val expectedPackage = instrumentation.targetContext.packageName

            // 前置观察：目标初始必须不可见，否则这条测试证明不了滚动逻辑。
            // 夹具填了 24 行 × 48dp，在 320x640 上远超一屏。
            assertTrue(
                "the scroll target must start offscreen for this test to be meaningful",
                !service.isTargetVisible(ActionSurfaceFixtureActivity.SCROLL_TARGET_DESCRIPTION),
            )

            val result = service.executeActionSurfaceCommand(
                command = AccessibilityCommand.ScrollIntoView(
                    target = target(ActionSurfaceFixtureActivity.SCROLL_TARGET_DESCRIPTION),
                    container = target(
                        ActionSurfaceFixtureActivity.SCROLL_CONTAINER_DESCRIPTION,
                    ),
                    direction = ScrollDirection.FORWARD,
                ),
                expectedPackage = expectedPackage,
            )

            assertTrue(
                "scrollIntoView was rejected: $result",
                result is AccessibilityResult.Success,
            )
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            // 后置验证：目标必须真的进入视口。仅断言动作成功不够——
            // 实现可能在没滚动的情况下就返回成功。
            assertTrue(
                "the scroll target should be visible after scrollIntoView",
                service.isTargetVisible(
                    ActionSurfaceFixtureActivity.SCROLL_TARGET_DESCRIPTION,
                ),
            )
        } finally {
            activity?.finish()
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
     * 与 ActionSurfaceDeviceTest 同样需要两步：先用
     * FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES 解除 instrumentation 对
     * 第三方无障碍服务的挂起，再重新触发一次绑定——instrumentation 会重建
     * 目标进程，旧进程里的 onServiceConnected 不会重放，而 connectedService
     * 是进程内静态字段。
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
     * 只在一次性模拟器上执行：真机的无障碍授权必须由用户本人在系统设置里完成，
     * 因此先校验设备指纹，拒绝在真机上自动授权。
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
            // 先清空再写入：值未变化时 SettingsProvider 不发通知，
            // AccessibilityManagerService 也就不会重新评估绑定。
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

    private fun launchFixture(
        instrumentation: Instrumentation,
    ): ActionSurfaceFixtureActivity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ActionSurfaceFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ActionSurfaceFixtureActivity
        instrumentation.waitForIdleSync()
        // 等窗口加入窗口列表：startActivitySync 返回时 Activity 已创建，
        // 但 AccessibilityService.windows 的更新是异步的。
        SystemClock.sleep(WINDOW_SETTLE_MS)
        return activity
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
        const val WINDOW_SETTLE_MS = 1_500L
        const val SETTINGS_SETTLE_MS = 1_000L
        const val SETTLE_MS = 600L
        const val SELECTION_START = 2
        const val SELECTION_END = 6
        const val TARGET_PROGRESS = 42
    }
}
