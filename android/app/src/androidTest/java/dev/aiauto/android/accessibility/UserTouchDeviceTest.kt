package dev.aiauto.android.accessibility

/**
 * 设备测试用途：在真实 Android 运行时验证 UserTouchDevice 的设备能力、权限前提与生命周期边界。
 */

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserTouchDeviceTest {
    @Test
    fun strictAttributionIgnoresAutomationAndDetectsDifferentNodeTouch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Manual accessibility tests require manualAccessibility=true",
            InstrumentationRegistry.getArguments()
                .getString(MANUAL_ACCESSIBILITY_ARGUMENT) == "true",
        )
        val targetContext = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        var activity: Activity? = null

        try {
            val service = ScreenshotTestAccessibilityService.awaitConnected(TIMEOUT_SECONDS)
            val testActivity = instrumentation.startActivitySync(
                Intent(targetContext, ScreenshotTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ScreenshotTestActivity
            activity = testActivity
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                testActivity.startSessionStopVerification()
            }
            assertTrue(
                awaitStatus(testActivity) {
                    it == AUTOMATION_CLICK_READY_STATUS
                },
            )
            assertEquals(AUTOMATION_CLICK_READY_STATUS, testActivity.currentVerificationStatus())
            assertEquals(0, service.userTouchNotificationCount())

            val (x, y) = testActivity.userTouchTargetCenter()
            injectTouch(automation, x, y)

            assertTrue(service.awaitUserTouch(TIMEOUT_SECONDS))
            assertEquals(1, service.userTouchNotificationCount())
            assertTrue(service.runtimeStopHandled())
            assertTrue(
                awaitStatus(testActivity) {
                    it == SESSION_STOP_PASS_STATUS
                },
            )
            assertEquals(SESSION_STOP_PASS_STATUS, testActivity.currentVerificationStatus())
            SystemClock.sleep(STATUS_STABILITY_WINDOW_MS)
            assertEquals(SESSION_STOP_PASS_STATUS, testActivity.currentVerificationStatus())
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
    }

    private fun awaitStatus(
        activity: ScreenshotTestActivity,
        predicate: (String) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_SECONDS * 1_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(activity.currentVerificationStatus())) {
                return true
            }
            SystemClock.sleep(STATUS_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun injectTouch(
        automation: android.app.UiAutomation,
        x: Float,
        y: Float,
    ) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(
            downTime,
            downTime + TOUCH_DURATION_MS,
            MotionEvent.ACTION_UP,
            x,
            y,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
        const val STATUS_POLL_INTERVAL_MS = 100L
        const val STATUS_STABILITY_WINDOW_MS = 500L
        const val TOUCH_DURATION_MS = 50L
        const val AUTOMATION_CLICK_READY_STATUS = "AUTOMATION_CLICK_PASS TAP_USER_TARGET"
        const val SESSION_STOP_PASS_STATUS =
            "SESSION_STOP_PASS phase=Stopped executorCalls=0"
    }
}
