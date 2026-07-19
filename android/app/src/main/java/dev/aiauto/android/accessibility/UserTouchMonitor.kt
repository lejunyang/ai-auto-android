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
) {
    fun configuration(
        eventTypes: Int,
        flags: Int,
        motionEventSources: Int,
    ): TouchMonitoringConfiguration = TouchMonitoringConfiguration(
        eventTypes = eventTypes or AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
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

    fun onAccessibilityEvent(eventType: Int, packageName: String?): Boolean {
        if (
            eventType != AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            packageName.isNullOrBlank()
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
}
