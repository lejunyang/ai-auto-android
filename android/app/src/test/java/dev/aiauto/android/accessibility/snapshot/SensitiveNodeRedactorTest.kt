package dev.aiauto.android.accessibility.snapshot

/**
 * 测试用途：验证 SensitiveNodeRedactor 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveNodeRedactorTest {
    private val redactor = SensitiveNodeRedactor()

    @Test
    fun `redacts password values and marks the node as sensitive`() {
        val node = node(
            resourceId = "com.example:id/password",
            text = "correct horse battery staple",
            contentDescription = "Account password",
            state = UiNodeState(password = true),
        )

        val result = redactor.redact(node)

        assertNull(result.text)
        assertNull(result.contentDescription)
        assertTrue(result.state.sensitive)
        assertEquals(node.resourceId, result.resourceId)
    }

    @Test
    fun `redacts verification code fields identified by metadata`() {
        val node = node(
            resourceId = "com.example:id/otp_input",
            text = "482901",
            contentDescription = "Verification code",
        )

        val result = redactor.redact(node)

        assertNull(result.text)
        assertNull(result.contentDescription)
        assertTrue(result.state.sensitive)
    }

    @Test
    fun `redacts every descendant of a payment container`() {
        val node = node(
            text = "Payment details",
            children = listOf(
                node(
                    resourceId = "com.example:id/card_number",
                    text = "4111 1111 1111 1111",
                ),
                node(text = "12/30"),
            ),
        )

        val result = redactor.redact(node)

        assertTrue(result.state.sensitive)
        assertTrue(result.children.all { it.state.sensitive })
        assertTrue(result.children.all { it.text == null })
    }

    @Test
    fun `preserves ordinary interface content`() {
        val node = node(
            resourceId = "com.example:id/continue_button",
            text = "Continue",
            contentDescription = "Continue to profile",
        )

        val result = redactor.redact(node)

        assertEquals(node, result)
        assertFalse(result.state.sensitive)
    }

    private fun node(
        resourceId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        state: UiNodeState = UiNodeState(),
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = "com.example",
        className = "android.widget.EditText",
        resourceId = resourceId,
        text = text,
        contentDescription = contentDescription,
        bounds = UiBounds(left = 0, top = 0, right = 100, bottom = 50),
        actions = emptySet(),
        state = state,
        children = children,
    )
}
