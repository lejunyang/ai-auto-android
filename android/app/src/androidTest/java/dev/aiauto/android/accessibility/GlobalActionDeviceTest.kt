package dev.aiauto.android.accessibility

/**
 * 测试用途：验证扩展全局动作（A10），按可中断性分档处理。
 *
 * 分档的理由：这些动作对测试会话的破坏性差别很大。
 * - 通知栏、快捷设置可恢复：展开后用 BACK 收起即可回到已知状态，正常验证；
 * - 电源菜单同属可恢复，但它是系统对话框，需确认真的出现再关掉；
 * - 锁屏会切断当前会话，必须在动作后主动解锁并重建观察，且放在独立用例；
 * - 截屏不改变界面状态，但会写入媒体库，需确认副作用可接受。
 *
 * 每个可恢复动作后都断言「回到了已知状态」，而不是只断言动作被接受：
 * performGlobalAction 只返回布尔值，不看窗口变化无法区分「系统接受了」
 * 与「界面真的变了」。
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

import dev.aiauto.android.accessibility.model.GlobalAction
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalActionDeviceTest {
    @Test
    fun expandsAndCollapsesTheNotificationShade() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            val baseline = service.visibleWindowKinds()

            val performed = service.performGlobalActionForTest(GlobalAction.NOTIFICATIONS)
            assertTrue("NOTIFICATIONS was refused by the platform", performed)
            SystemClock.sleep(TRANSITION_MS)

            // 通知栏展开会新增系统窗口。只断言返回 true 不足以证明界面变了。
            val expanded = service.visibleWindowKinds()
            assertTrue(
                "expected the notification shade to add a window; " +
                    "baseline=$baseline expanded=$expanded",
                expanded.size > baseline.size || expanded != baseline,
            )

            // 复位并确认回到已知状态：残留的通知栏会让后续用例在错误界面上执行。
            assertTrue(
                "failed to dismiss the notification shade",
                recoverToFixture(instrumentation, service),
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun opensAndDismissesQuickSettings() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            val baseline = service.visibleWindowKinds()

            val performed = service.performGlobalActionForTest(GlobalAction.QUICK_SETTINGS)
            assertTrue("QUICK_SETTINGS was refused by the platform", performed)
            SystemClock.sleep(TRANSITION_MS)

            val expanded = service.visibleWindowKinds()
            assertTrue(
                "expected quick settings to change the window set; " +
                    "baseline=$baseline expanded=$expanded",
                expanded != baseline,
            )

            assertTrue(
                "failed to dismiss quick settings",
                recoverToFixture(instrumentation, service),
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun opensAndDismissesThePowerDialog() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            val baseline = service.visibleWindowKinds()

            val performed = service.performGlobalActionForTest(GlobalAction.POWER_DIALOG)
            assertTrue("POWER_DIALOG was refused by the platform", performed)
            SystemClock.sleep(TRANSITION_MS)

            val opened = service.visibleWindowKinds()
            assertTrue(
                "expected the power dialog to change the window set; " +
                    "baseline=$baseline opened=$opened",
                opened != baseline,
            )

            // 电源菜单必须关掉：它盖住整屏，留着会让后续所有定位失败。
            assertTrue(
                "failed to dismiss the power dialog",
                recoverToFixture(instrumentation, service),
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun takesAScreenshotWithoutDisruptingTheSession() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        val activity = launchFixture(instrumentation)
        try {
            val baseline = service.visibleWindowKinds()

            // 截屏不改变界面状态，因此这一项的验证重点是「不破坏会话」：
            // 动作后界面仍应停在夹具上，否则说明它有意外副作用。
            val performed = service.performGlobalActionForTest(GlobalAction.TAKE_SCREENSHOT)
            assertTrue("TAKE_SCREENSHOT was refused by the platform", performed)
            SystemClock.sleep(SCREENSHOT_SETTLE_MS)

            val after = service.visibleWindowKinds()
            assertTrue(
                "the fixture window should still be present after a screenshot; after=$after",
                after.any { it.contains(instrumentation.targetContext.packageName) },
            )
            // 不断言截图文件落盘：那要读媒体库，需要额外运行时权限，
            // 而本项要证明的是「动作可用且不破坏会话」，文件落盘属系统职责。
            assertTrue(
                "baseline should also contain the fixture window; baseline=$baseline",
                baseline.any { it.contains(instrumentation.targetContext.packageName) },
            )
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun locksTheScreenAndRebuildsTheSession() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        try {
            // 锁屏会切断当前会话，因此本用例独立、且不依赖夹具处于前台。
            val performed = service.performGlobalActionForTest(GlobalAction.LOCK_SCREEN)
            assertTrue("LOCK_SCREEN was refused by the platform", performed)
            SystemClock.sleep(LOCK_SETTLE_MS)

            // 恢复：唤醒并解除无凭据锁屏。本设备无锁屏密码，
            // 因此 WAKEUP + MENU 足够；有凭据的设备上这一步会失败，
            // 那属于用户必须亲自完成的操作，不在自动化职责内。
            val automation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            SystemClock.sleep(TRANSITION_MS)
            automation.executeShellCommand("input keyevent KEYCODE_MENU").close()
            SystemClock.sleep(TRANSITION_MS)

            // 会话重建后必须能重新观察到窗口，否则说明锁屏之后服务不可用，
            // 那会让这个动作在实际自动化里无法安全使用。
            val rebuilt = service.visibleWindowKinds()
            assertTrue(
                "the accessibility service should still observe windows after unlocking; " +
                    "windows=$rebuilt",
                rebuilt.isNotEmpty(),
            )
        } finally {
            instrumentation.waitForIdleSync()
        }
    }

    /**
     * 用 BACK 把界面复位到夹具，并确认确实回到了它。
     *
     * 轮询而非单次：系统面板的收起有动画，立即读窗口可能仍看到它。
     * 返回是否成功，由调用方断言——静默失败会让后续用例在错误界面上跑。
     */
    private fun recoverToFixture(
        instrumentation: Instrumentation,
        service: ScreenshotTestAccessibilityService,
    ): Boolean {
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val expected = instrumentation.targetContext.packageName
        repeat(RECOVERY_ATTEMPTS) {
            automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            SystemClock.sleep(TRANSITION_MS)
            if (service.visibleWindowKinds().any { it.contains(expected) }) {
                return true
            }
        }
        return false
    }

    private fun requireManualAccessibility(): Instrumentation {
        assumeTrue(
            "Manual accessibility tests require manualAccessibility=true",
            InstrumentationRegistry.getArguments()
                .getString(MANUAL_ACCESSIBILITY_ARGUMENT) == "true",
        )
        return InstrumentationRegistry.getInstrumentation()
    }

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
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "",
            )
            SystemClock.sleep(TRANSITION_MS)
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
        SystemClock.sleep(WINDOW_SETTLE_MS)
        return activity
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
        const val WINDOW_SETTLE_MS = 1_500L

        /** 系统面板展开与收起的动画时长余量。 */
        const val TRANSITION_MS = 1_200L

        /** 截屏涉及媒体写入，留更长时间。 */
        const val SCREENSHOT_SETTLE_MS = 2_500L

        /** 锁屏切换需要更长时间，且要等锁屏界面稳定。 */
        const val LOCK_SETTLE_MS = 3_000L

        /**
         * 复位尝试次数。
         *
         * 取 3：单次 BACK 通常足够，但快捷设置从完全展开态需要两次
         * （先回通知栏再关闭）。3 次留一次余量。
         */
        const val RECOVERY_ATTEMPTS = 3
    }
}
