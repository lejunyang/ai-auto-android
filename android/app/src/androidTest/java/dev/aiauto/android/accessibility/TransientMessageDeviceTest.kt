package dev.aiauto.android.accessibility

/**
 * 测试用途：在真实 Android 运行时验证 Toast 捕获与剪贴板读写。
 *
 * 为什么必须是设备测试：Toast 捕获依赖平台真的把
 * TYPE_NOTIFICATION_STATE_CHANGED 事件投递给无障碍服务，而事件是否投递取决于
 * manifest 里的订阅声明与运行时 serviceInfo——这两处在单测里都是伪造的。
 * 剪贴板读取则受 API 29+ 的前台限制约束，只有在真实设备上才能确认
 * 当前上下文能否读到。
 */

import android.Manifest
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.bridge.ClipboardGateway
import dev.aiauto.android.bridge.ClipboardReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransientMessageDeviceTest {
    @Test
    fun capturesAToastShownByTheAppUnderTest() {
        val instrumentation = requireManualAccessibility()
        val service = awaitService(instrumentation)
        // Toast 必须由前台应用发出：自 Android 11 起后台应用的 Toast 会被平台
        // 抑制，不显示也就不产生无障碍事件。先把夹具推到前台再触发。
        val activity = instrumentation.startActivitySync(
            android.content.Intent(
                instrumentation.targetContext,
                ActionSurfaceFixtureActivity::class.java,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(FOREGROUND_SETTLE_MS)
            // 清空缓冲让断言只覆盖本次触发的提示，避免历史通知造成假通过。
            service.clearTransientMessageBuffer()

            val message = "aactl toast probe ${SystemClock.uptimeMillis()}"
            instrumentation.runOnMainSync {
                Toast.makeText(
                    instrumentation.targetContext,
                    message,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            instrumentation.waitForIdleSync()

            // Toast 事件投递是异步的，需要轮询而非单次读取。
            // 单次读取会在事件尚未到达时误判为「没捕获到」。
            val captured = awaitMessage(service) { messages ->
                messages.any { it.text.contains(message) }
            }

            assertTrue(
                "expected the toast to be captured, got ${captured.map { it.text }}",
                captured.any { it.text.contains(message) },
            )
            val toast = captured.first { it.text.contains(message) }
            // className 必须被识别为 Toast，否则 toastsOnly 过滤会漏掉它。
            assertTrue("captured message should be marked as a toast", toast.toast)
            assertEquals(instrumentation.targetContext.packageName, toast.packageName)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun writesAndReadsClipboardTextInTheForeground() {
        val instrumentation = requireManualAccessibility()
        // 剪贴板读取在 API 29+ 要求应用持有输入焦点，因此先把夹具推到前台。
        // 不这样做时读取会被平台拒绝，那是隐私保护而非缺陷。
        val activity = instrumentation.startActivitySync(
            android.content.Intent(
                instrumentation.targetContext,
                ActionSurfaceFixtureActivity::class.java,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(FOREGROUND_SETTLE_MS)

            val gateway = ClipboardGateway(instrumentation.targetContext)
            val payload = "aactl clipboard probe ${SystemClock.uptimeMillis()}"

            assertTrue("clipboard write should succeed", gateway.write(payload))

            when (val result = gateway.read()) {
                is ClipboardReadResult.Text -> assertEquals(payload, result.value)
                // Unavailable 是合法结果而非测试失败：某些运行上下文下平台
                // 确实拒绝读取。此时如实跳过，而不是把「读不到」伪装成通过。
                is ClipboardReadResult.Unavailable -> assumeTrue(
                    "clipboard read is unavailable in this context: ${result.reason}",
                    false,
                )

                ClipboardReadResult.Empty -> throw AssertionError(
                    "clipboard reported empty right after a successful write",
                )
            }
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    private fun awaitMessage(
        service: ScreenshotTestAccessibilityService,
        predicate: (List<TransientMessage>) -> Boolean,
    ): List<TransientMessage> {
        val deadline = SystemClock.uptimeMillis() + CAPTURE_TIMEOUT_MS
        var latest = emptyList<TransientMessage>()
        while (SystemClock.uptimeMillis() < deadline) {
            latest = service.capturedTransientMessages()
            if (predicate(latest)) {
                return latest
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return latest
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
     * 与其它动作面设备测试同样需要两步：先解除 instrumentation 对第三方
     * 无障碍服务的挂起，再重新触发绑定——instrumentation 会重建目标进程，
     * 旧进程里的 onServiceConnected 不会重放，而 connectedService 是进程内
     * 静态字段。
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

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
        const val SETTINGS_SETTLE_MS = 1_000L
        const val FOREGROUND_SETTLE_MS = 1_500L

        /**
         * Toast 捕获的轮询上限与间隔。
         *
         * 取 8 秒：Toast 事件投递通常在数百毫秒内完成，但冷启动后的首个事件
         * 可能更慢。间隔 200ms 在响应性与轮询开销之间取平衡。
         */
        const val CAPTURE_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 200L
    }
}
