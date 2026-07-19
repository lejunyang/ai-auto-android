package dev.aiauto.android.accessibility

import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAutomationAccessibilityServiceTest {
    @Test
    fun `typed commands register matching event before execution BitsUT`() {
        val commandEvents = listOf(
            mockk<AccessibilityCommand.Click>() to AccessibilityEvent.TYPE_VIEW_CLICKED,
            mockk<AccessibilityCommand.LongClick>() to AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            mockk<AccessibilityCommand.Scroll>() to AccessibilityEvent.TYPE_VIEW_SCROLLED,
            mockk<AccessibilityCommand.SetText>() to AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )

        commandEvents.forEach { (command, eventType) ->
            var notifications = 0
            val monitor = UserTouchMonitor(
                notifyUserTouch = {
                    notifications += 1
                    true
                },
                sdkInt = 33,
                nowMs = { 100L },
            )
            val attributor = AccessibilityCommandAttributor(
                userTouchMonitor = monitor,
                currentPackageName = { TARGET_PACKAGE },
            )

            val result = attributor.execute(command) {
                val handled = monitor.onAccessibilityEvent(
                    eventType = eventType,
                    packageName = TARGET_PACKAGE,
                )
                assertFalse(handled)
                AccessibilityResult.Success(mockk<ActionExecution>())
            }

            assertTrue(result is AccessibilityResult.Success)
            assertEquals(0, notifications)
        }
    }

    @Test
    fun `failed command clears expected event immediately BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val attributor = AccessibilityCommandAttributor(
            userTouchMonitor = monitor,
            currentPackageName = { TARGET_PACKAGE },
        )
        val failure = AccessibilityResult.Failure(
            code = AccessibilityErrorCode.SERVICE_DISABLED,
            message = "Action failed",
            retryable = false,
        )

        val result = attributor.execute(mockk<AccessibilityCommand.Click>()) { failure }
        val handled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )

        assertSame(failure, result)
        assertTrue(handled)
        assertEquals(1, notifications)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
