package dev.aiauto.android.accessibility

// 测试用途：验证 AiAutomationAccessibilityService 的功能契约、失败语义及自动化安全边界。

import dev.aiauto.android.accessibility.model.UiBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAutomationAccessibilityServiceTest {
    @Test
    fun `node identity requires package window and source match BitsUT`() {
        val expected = identity()

        assertTrue(expected.matches(identity()))
        assertFalse(expected.matches(identity(packageName = "com.other.app")))
        assertFalse(expected.matches(identity(windowId = 8)))
        assertFalse(expected.matches(identity(resourceId = "com.example.app:id/other")))
    }

    @Test
    fun `unique node id takes precedence over fallback fields BitsUT`() {
        val expected = identity(uniqueId = "node-1")
        val sameUniqueId = identity(
            uniqueId = "node-1",
            resourceId = "com.example.app:id/rebound",
        )

        assertTrue(expected.matches(sameUniqueId))
        assertFalse(expected.matches(identity(uniqueId = "node-2")))
    }

    private fun identity(
        packageName: String = "com.example.app",
        windowId: Int = 7,
        uniqueId: String? = null,
        resourceId: String = "com.example.app:id/target",
    ) = AutomationNodeIdentity(
        packageName = packageName,
        windowId = windowId,
        uniqueId = uniqueId,
        nodeIdentityHash = resourceId.hashCode(),
        resourceId = resourceId,
        className = "android.widget.Button",
        bounds = UiBounds(0, 0, 10, 10),
    )
}
