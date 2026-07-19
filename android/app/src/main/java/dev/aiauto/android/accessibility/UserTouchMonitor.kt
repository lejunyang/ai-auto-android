package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

internal data class TouchMonitoringConfiguration(
    val eventTypes: Int,
    val flags: Int,
    val motionEventSources: Int,
)

internal class UserTouchMonitor(
    private val notifyUserTouch: (String) -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var expectedEvent: ExpectedAutomationEvent? = null

    fun configuration(
        eventTypes: Int,
        flags: Int,
        motionEventSources: Int,
    ): TouchMonitoringConfiguration = TouchMonitoringConfiguration(
        eventTypes = eventTypes or if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
        } else {
            LEGACY_INTERACTION_EVENTS
        },
        flags = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
        } else {
            flags
        },
        motionEventSources = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            motionEventSources or InputDevice.SOURCE_TOUCHSCREEN
        } else {
            motionEventSources
        },
    )

    @Synchronized
    fun expectAutomationEvent(
        eventType: Int,
        packageName: String,
    ) {
        expectedEvent = ExpectedAutomationEvent(
            eventType = eventType,
            packageName = packageName,
            expiresAtMs = nowMs() + EXPECTED_EVENT_TIMEOUT_MS,
        )
    }

    @Synchronized
    fun clearExpectedAutomationEvent() {
        expectedEvent = null
    }

    @Synchronized
    fun onAccessibilityEvent(eventType: Int, packageName: String?): Boolean {
        if (packageName.isNullOrBlank() || eventType !in monitoredEventTypes()) {
            return false
        }
        val expected = expectedEvent
        expectedEvent = null
        if (
            expected != null &&
            expected.expiresAtMs >= nowMs() &&
            expected.eventType == eventType &&
            expected.packageName == packageName
        ) {
            return false
        }
        return notifyUserTouch(packageName)
    }

    fun onMotionEvent(
        action: Int,
        source: Int,
        packageName: String?,
    ): Boolean {
        if (
            sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            action != MotionEvent.ACTION_DOWN ||
            source and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN ||
            packageName.isNullOrBlank()
        ) {
            return false
        }
        return notifyUserTouch(packageName)
    }

    private fun monitoredEventTypes(): Set<Int> =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setOf(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        } else {
            LEGACY_INTERACTION_EVENT_TYPES
        }

    private data class ExpectedAutomationEvent(
        val eventType: Int,
        val packageName: String,
        val expiresAtMs: Long,
    )

    private companion object {
        const val EXPECTED_EVENT_TIMEOUT_MS = 1_000L
        val LEGACY_INTERACTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
        val LEGACY_INTERACTION_EVENTS = LEGACY_INTERACTION_EVENT_TYPES
            .fold(0) { result, eventType -> result or eventType }
    }
}
