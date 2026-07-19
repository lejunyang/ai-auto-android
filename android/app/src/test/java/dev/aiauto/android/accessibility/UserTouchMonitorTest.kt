package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.InputDevice
import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.model.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTouchMonitorTest {
    @Test
    fun `api 34 configuration uses view events without observing raw touchscreen BitsUT`() {
        val monitor = UserTouchMonitor(notifyUserTouch = { true }, sdkInt = 34)
        val flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS or
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        val motionSources = InputDevice.SOURCE_MOUSE

        val configuration = monitor.configuration(
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            flags = flags,
            motionEventSources = motionSources,
        )

        assertEquals(AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS, configuration.flags)
        assertEquals(0, configuration.motionEventSources)
        assertEquals(
            0,
            configuration.flags and
                AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS,
        )
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    @Test
    fun `api 33 configuration adds view events and clears raw motion sources BitsUT`() {
        val flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        val monitor = UserTouchMonitor(notifyUserTouch = { true }, sdkInt = 33)

        val configuration = monitor.configuration(
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            flags = flags,
            motionEventSources = InputDevice.SOURCE_MOUSE,
        )

        assertEquals(flags, configuration.flags)
        assertEquals(0, configuration.motionEventSources)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        assertEventEnabled(configuration, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    @Test
    fun `api 33 notifies for unattributed view event BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
        )

        val compatibilityHandled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = null,
            ),
        )

        assertTrue(compatibilityHandled)
        assertEquals(1, notifications)
    }

    @Test
    fun `package and event type alone cannot suppress legacy user interaction BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(token))

        val handled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = null,
            ),
        )

        assertTrue(handled)
        assertEquals(1, notifications)
    }

    @Test
    fun `matching node event is consumed only within its event budget BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(token))

        val firstHandled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = identity(),
            ),
        )
        val secondHandled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = identity(),
            ),
        )

        assertFalse(firstHandled)
        assertTrue(secondHandled)
        assertEquals(1, notifications)
    }

    @Test
    fun `event set remains attributed until each expected type is consumed BitsUT`() {
        val monitor = UserTouchMonitor(
            notifyUserTouch = { true },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = mapOf(
                    AccessibilityEvent.TYPE_VIEW_CLICKED to 1,
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED to 1,
                ),
            ),
        )
        assertTrue(monitor.commitAutomationAction(token))

        assertFalse(
            monitor.onLegacyInteraction(
                signal(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    sourceIdentity = identity(),
                ),
            ),
        )
        assertFalse(
            monitor.onLegacyInteraction(
                signal(
                    eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                    sourceIdentity = identity(),
                ),
            ),
        )
    }

    @Test
    fun `weak node identity cannot arm legacy attribution BitsUT`() {
        val monitor = UserTouchMonitor(notifyUserTouch = { true }, sdkInt = 33)

        val token = monitor.armAutomationAction(
            sourceIdentity = identity(resourceId = "").copy(
                uniqueId = null,
                nodeIdentityHash = 0,
            ),
            eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
        )

        assertEquals(null, token)
    }

    @Test
    fun `different window event cannot consume automation expectation BitsUT`() {
        assertMismatchedSourceNotifies(identity(windowId = 8))
    }

    @Test
    fun `different source event cannot consume automation expectation BitsUT`() {
        assertMismatchedSourceNotifies(
            identity(
                resourceId = "com.example.app:id/other",
                bounds = UiBounds(20, 20, 30, 30),
            ),
        )
    }

    @Test
    fun `stale token cannot abort a newer automation action BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val firstToken = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(firstToken))
        assertFalse(
            monitor.onLegacyInteraction(
                signal(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    sourceIdentity = identity(),
                ),
            ),
        )
        val secondToken = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_SCROLLED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(secondToken))

        assertFalse(monitor.abortAutomationAction(firstToken))
        val handled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                sourceIdentity = identity(),
            ),
        )

        assertFalse(handled)
        assertEquals(0, notifications)
    }

    @Test
    fun `new session cannot consume previous session automation event BitsUT`() {
        var sessionId = 1L
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            currentSessionId = { sessionId },
            sdkInt = 34,
            nowMs = { 100L },
        )
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(token))
        sessionId = 2L

        val handled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = identity(),
            ),
        )

        assertTrue(handled)
        assertEquals(1, notifications)
    }

    @Test
    fun `failed action attributes pending matching signal to user BitsUT`() {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
            ),
        )
        val pendingHandled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = identity(),
            ),
        )

        assertFalse(pendingHandled)
        assertTrue(monitor.abortAutomationAction(token))
        assertEquals(1, notifications)
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
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(token))
        nowMs += 1_001L

        val handled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                sourceIdentity = identity(),
                eventTimeMs = nowMs,
            ),
        )

        assertTrue(handled)
        assertEquals(1, notifications)
    }

    private fun assertMismatchedSourceNotifies(signalIdentity: AutomationNodeIdentity) {
        var notifications = 0
        val monitor = UserTouchMonitor(
            notifyUserTouch = {
                notifications += 1
                true
            },
            sdkInt = 33,
            nowMs = { 100L },
        )
        val token = checkNotNull(
            monitor.armAutomationAction(
                sourceIdentity = identity(),
                eventBudgets = eventBudgets(AccessibilityEvent.TYPE_VIEW_CLICKED),
            ),
        )
        assertTrue(monitor.commitAutomationAction(token))

        val handled = monitor.onLegacyInteraction(
            signal(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                sourceIdentity = signalIdentity,
            ),
        )

        assertTrue(handled)
        assertEquals(1, notifications)
    }

    private fun eventBudgets(eventType: Int): Map<Int, Int> = mapOf(eventType to 1)

    private fun signal(
        eventType: Int,
        sourceIdentity: AutomationNodeIdentity?,
        eventTimeMs: Long = 100L,
    ) = LegacyInteractionSignal(
        eventType = eventType,
        packageName = TARGET_PACKAGE,
        eventTimeMs = eventTimeMs,
        sourceIdentity = sourceIdentity,
    )

    private fun identity(
        windowId: Int = 7,
        resourceId: String = "com.example.app:id/target",
        bounds: UiBounds = UiBounds(0, 0, 10, 10),
    ) = AutomationNodeIdentity(
        packageName = TARGET_PACKAGE,
        windowId = windowId,
        uniqueId = null,
        nodeIdentityHash = resourceId.hashCode(),
        resourceId = resourceId,
        className = "android.widget.Button",
        bounds = bounds,
    )

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
