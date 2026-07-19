package dev.aiauto.android.accessibility

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

            service.resetUserTouchSignal()
            assertTrue(
                service.performAttributedClick(
                    ScreenshotTestActivity.AUTOMATION_TARGET_DESCRIPTION,
                ),
            )
            SystemClock.sleep(AUTOMATION_EVENT_SETTLE_MS)
            assertEquals(0, service.userTouchNotificationCount())

            service.resetUserTouchSignal()
            assertTrue(
                service.armClickExpectation(
                    ScreenshotTestActivity.AUTOMATION_TARGET_DESCRIPTION,
                ),
            )
            val (x, y) = testActivity.userTouchTargetCenter()
            injectTouch(automation, x, y)

            assertTrue(service.awaitUserTouch(TIMEOUT_SECONDS))
            assertEquals(1, service.userTouchNotificationCount())
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
        }
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
        const val AUTOMATION_EVENT_SETTLE_MS = 500L
        const val TOUCH_DURATION_MS = 50L
    }
}
