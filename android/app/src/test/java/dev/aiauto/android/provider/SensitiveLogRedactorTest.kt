package dev.aiauto.android.provider

/**
 * 测试用途：验证 SensitiveLogRedactor 的功能契约、失败语义及自动化安全边界。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLogRedactorTest {
    @Test
    fun `redacts bearer tokens JSON secrets and explicit values`() {
        val secret = "sk-explicit-value"
        val message = """
            Authorization: Bearer header-token
            {"api_key":"json-token","password":"password-value"}
            provider rejected $secret
        """.trimIndent()

        val redacted = SensitiveLogRedactor.redact(message, secret)

        assertFalse(redacted.contains("header-token"))
        assertFalse(redacted.contains("json-token"))
        assertFalse(redacted.contains("password-value"))
        assertFalse(redacted.contains(secret))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `ignores blank explicit secrets`() {
        assertTrue(
            SensitiveLogRedactor.redact("connection failed", "", " ")
                .contains("connection failed"),
        )
    }

    @Test
    fun `redacts complete JSON secret values containing escaped quotes`() {
        val message = """{"secret":"prefix\"sensitive-suffix"}"""

        assertEquals(
            """{"secret":"[REDACTED]"}""",
            SensitiveLogRedactor.redact(message),
        )
    }
}
