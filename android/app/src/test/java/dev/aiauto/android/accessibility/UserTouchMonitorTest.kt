package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTouchMonitorTest {
    @Test
    fun `api 34 configuration adds motion events without touch exploration BitsUT`() {
        val monitor = UserTouchMonitor(notifyUserTouch = { true }, sdkInt = 34)

        val configuration = monitor.configuration(
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED,
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS,
            motionEventSources = 0,
        )

        assertTrue(
            configuration.eventTypes and AccessibilityEvent.TYPE_TOUCH_INTERACTION_START != 0,
        )
        assertTrue(
            configuration.flags and AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS != 0,
        )
        assertEquals(
            0,
            configuration.flags and
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE,
        )
        assertTrue(configuration.motionEventSources and InputDevice.SOURCE_TOUCHSCREEN != 0)
    }

    @Test
    fun `api 33 configuration adds legacy view events and leaves motion settings unchanged BitsUT`() {
        val flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        val monitor = UserTouchMonitor(notifyUserTouch = { true }, sdkInt = 33)

        val configuration = monitor.configuration(
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            flags = flags,
            motionEventSources = InputDevice.SOURCE_MOUSE,
        )

        assertEquals(flags, configuration.flags)
        assertEquals(InputDevice.SOURCE_MOUSE, configuration.motionEventSources)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    @Test
    fun `view events are not mistaken for user touch BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 34,
        )

        val handled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )

        assertFalse(handled)
        assertEquals(0, notifications)
    }

    @Test
    fun `api 34 touchscreen down notifies target touch BitsUT`() {
        var notifiedPackage: String? = null
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifiedPackage = it
                true
            },
            sdkInt = 34,
        )

        val handled = monitor.onMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            source = InputDevice.SOURCE_TOUCHSCREEN,
            packageName = TARGET_PACKAGE,
        )

        assertTrue(handled)
        assertEquals(TARGET_PACKAGE, notifiedPackage)
    }

    @Test
    fun `api 33 ignores raw motion and notifies for unattributed view event BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
        )

        val rawHandled = monitor.onMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            source = InputDevice.SOURCE_TOUCHSCREEN,
            packageName = TARGET_PACKAGE,
        )
        val compatibilityHandled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )

        assertFalse(rawHandled)
        assertTrue(compatibilityHandled)
        assertEquals(1, notifications)
    }

    @Test
    fun `matching legacy event is consumed only once BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        monitor.expectAutomationEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )

        val firstHandled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )
        val secondHandled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )

        assertFalse(firstHandled)
        assertTrue(secondHandled)
        assertEquals(1, notifications)
    }

    @Test
    fun `mismatched legacy event notifies user touch BitsUT`() {
        var notifiedPackage: String? = null
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifiedPackage = it
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        monitor.expectAutomationEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = TARGET_PACKAGE,
        )

        val handled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            packageName = TARGET_PACKAGE,
        )

        assertTrue(handled)
        assertEquals(TARGET_PACKAGE, notifiedPackage)
    }

    @Test
    fun `expired legacy event notifies user touch BitsUT`() {
        var nowMs = 100L
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { nowMs },
        )
        monitor.expectAutomationEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            packageName = TARGET_PACKAGE,
        )
        nowMs += 1_001L

        val handled = monitor.onAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            packageName = TARGET_PACKAGE,
        )

        assertTrue(handled)
        assertEquals(1, notifications)
    }

    private fun assertEventEnabled(
        configuration: TouchMonitoringConfiguration,
        eventType: Int,
    ) {
        assertTrue(configuration.eventTypes and eventType != 0)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
