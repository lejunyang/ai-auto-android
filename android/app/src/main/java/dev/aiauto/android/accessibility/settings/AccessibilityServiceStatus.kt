package dev.aiauto.android.accessibility.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

import dev.aiauto.android.accessibility.AiAutomationAccessibilityService

object AccessibilityServiceStatus {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val expectedName = AiAutomationAccessibilityService::class.java.name
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val info = service.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == expectedName
            }
    }
}
