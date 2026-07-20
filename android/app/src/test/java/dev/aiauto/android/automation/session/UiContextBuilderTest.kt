package dev.aiauto.android.automation.session

/**
 * 测试用途：验证 UiContextBuilder 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContextBuilderTest {
    @Test
    fun `build includes actionable semantics and package BitsUT`() {
        val summary = UiContextBuilder().build(
            node(
                text = null,
                children = listOf(
                    node(
                        text = "Continue",
                        resourceId = "com.example.app:id/continue",
                        state = UiNodeState(
                            clickable = true,
                            enabled = true,
                            visibleToUser = true,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(summary.contains("package=com.example.app"))
        assertTrue(summary.contains("text=Continue"))
        assertTrue(summary.contains("id=com.example.app:id/continue"))
        assertTrue(summary.contains("clickable"))
    }

    @Test
    fun `build removes sensitive text and control characters BitsUT`() {
        val summary = UiContextBuilder().build(
            node(
                text = "Account",
                children = listOf(
                    node(
                        text = "secret-value",
                        description = "password field",
                        state = UiNodeState(
                            password = true,
                            sensitive = true,
                            editable = true,
                        ),
                    ),
                    node(text = "Hello\u0000\nworld"),
                ),
            ),
        )

        assertFalse(summary.contains("secret-value"))
        assertFalse(summary.contains("password field"))
        assertFalse(summary.contains('\u0000'))
        assertTrue(summary.contains("Hello world"))
        assertTrue(summary.contains("sensitive"))
    }

    @Test
    fun `build enforces node and summary limits BitsUT`() {
        val children = (1..20).map { index -> node(text = "Node $index") }
        val summary = UiContextBuilder(
            maxNodes = 4,
            maxDepth = 2,
            maxTextLength = 20,
            maxSummaryLength = 256,
        ).build(node(children = children))

        assertTrue(summary.length <= 256)
        assertTrue(summary.contains("[node-limit-reached]"))
        assertFalse(summary.contains("Node 20"))
    }

    private fun node(
        text: String? = null,
        resourceId: String? = null,
        description: String? = null,
        state: UiNodeState = UiNodeState(enabled = true, visibleToUser = true),
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = "com.example.app",
        className = "android.widget.Button",
        resourceId = resourceId,
        text = text,
        contentDescription = description,
        bounds = UiBounds(0, 0, 100, 100),
        actions = emptySet(),
        state = state,
        children = children,
    )
}
