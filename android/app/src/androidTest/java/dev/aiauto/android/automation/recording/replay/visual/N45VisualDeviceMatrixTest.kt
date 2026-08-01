package dev.aiauto.android.automation.recording.replay.visual

/**
 * 测试用途：在 N31 owned emulator 上复用 production N45 executor 与类型化无障碍动作，
 * 验证 tap、long-click、swipe 的独立后置状态和全部设备侧失败关闭边界。
 */

import android.app.Activity
import android.content.Intent
import android.os.SystemClock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService
import dev.aiauto.android.accessibility.ScreenshotTestActivity
import dev.aiauto.android.testcontrol.DebugTestControlPlane
import dev.aiauto.android.testcontrol.InstrumentationTestIdentityProvider
import dev.aiauto.android.testcontrol.SecureAccessibilitySettings
import dev.aiauto.testcontrol.core.TestIdentity
import dev.aiauto.testcontrol.core.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class N45VisualDeviceMatrixTest {
    @Test
    fun runVisualActionMatrixCase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val identityProvider = InstrumentationTestIdentityProvider(
            targetContext = targetContext,
            instrumentationContext = instrumentation.context,
            arguments = arguments,
        )
        val identity = identityProvider.current()
        val control = DebugTestControlPlane(
            context = targetContext,
            identityProvider = identityProvider::current,
        )
        val harness = N45VisualDeviceHarness(
            context = targetContext,
            expectedIdentity = identity,
            currentIdentity = identityProvider::current,
            arguments = arguments,
        )
        var activity: Activity? = null
        try {
            val enable = control.accessibilityIntent(
                control.issueToken(TestScope.ACCESSIBILITY_CONTROL),
                enabled = true,
            )
            val disable = control.accessibilityIntent(
                control.issueToken(TestScope.ACCESSIBILITY_CONTROL),
                enabled = false,
            )
            SecureAccessibilitySettings(
                instrumentation = instrumentation,
                context = targetContext,
                identityProvider = identityProvider::current,
            ).withServiceEnabled(enable, disable) {
                val testActivity = instrumentation.startActivitySync(
                    Intent(targetContext, ScreenshotTestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as ScreenshotTestActivity
                activity = testActivity
                instrumentation.waitForIdleSync()
                ScreenshotTestAccessibilityService.awaitConnected(TIMEOUT_SECONDS)

                for (action in N45VisualTestAction.entries) {
                    instrumentation.runOnMainSync {
                        testActivity.resetVisualActionState()
                    }
                    val result = harness.execute(action)
                    assertTrue(result.succeeded)
                    assertEquals(1, result.actionCommitCount)
                    assertEquals(1, result.actionAttempts)
                    assertEquals(
                        "${action.name}_PASS",
                        awaitVisualStatus(testActivity, action),
                    )
                }

                for (mutation in N45VisualEvidenceMutation.entries) {
                    instrumentation.runOnMainSync {
                        testActivity.resetVisualActionState()
                    }
                    val result = harness.execute(
                        action = N45VisualTestAction.TAP,
                        mutation = mutation,
                    )
                    assertEquals(mutation.expectedCode, result.errorCode)
                    assertEquals(
                        if (mutation == N45VisualEvidenceMutation.POST_FAIL) 1 else 0,
                        result.actionCommitCount,
                    )
                    assertEquals(
                        if (mutation == N45VisualEvidenceMutation.POST_FAIL) 1 else 0,
                        result.actionAttempts,
                    )
                    SystemClock.sleep(STATUS_STABILITY_MS)
                    assertEquals(
                        if (mutation == N45VisualEvidenceMutation.POST_FAIL) {
                            "TAP_PASS"
                        } else {
                            "VISUAL_READY"
                        },
                        testActivity.currentVisualActionStatus(),
                    )
                }

                assertThrows(IllegalStateException::class.java) {
                    harness.assertIdentityUnchanged(
                        identity.copyWith(serial = "emulator-5556"),
                    )
                }
                assertThrows(IllegalStateException::class.java) {
                    harness.assertIdentityUnchanged(
                        identity.copyWith(serial = "physical-device"),
                    )
                }
                assertThrows(IllegalStateException::class.java) {
                    harness.assertIdentityUnchanged(
                        identity.copyWith(fingerprint = "f".repeat(64)),
                    )
                }
                assertThrows(IllegalStateException::class.java) {
                    harness.assertIdentityUnchanged(
                        identity.copyWith(marker = "WRONG_TEST_MARKER"),
                    )
                }
            }
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            harness.close()
            control.close()
        }
    }

    private fun awaitVisualStatus(
        activity: ScreenshotTestActivity,
        action: N45VisualTestAction,
    ): String {
        val expected = "${action.name}_PASS"
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_SECONDS * 1_000
        while (SystemClock.uptimeMillis() < deadline) {
            val current = activity.currentVisualActionStatus()
            if (current == expected) return current
            SystemClock.sleep(STATUS_POLL_MS)
        }
        return activity.currentVisualActionStatus()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val STATUS_POLL_MS = 100L
        const val STATUS_STABILITY_MS = 500L

        fun TestIdentity.copyWith(
            serial: String = emulatorSerial(),
            fingerprint: String = avdFingerprint(),
            marker: String = testOnlyMarker(),
        ) = TestIdentity(
            serial,
            fingerprint,
            appSigningDigest(),
            callerSigningDigest(),
            marker,
            buildVariant(),
        )
    }
}
