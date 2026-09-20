package dev.aiauto.android.bridge

/**
 * 测试用途：验证 AccessibilityCommandJsonParser 把协议动作映射为无障碍命令的契约，
 * 重点覆盖 ime.show 的聚焦语义与参数约束。
 */

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCommandJsonParserTest {
    private val parser = AccessibilityCommandJsonParser()

    @Test
    fun `parses ime show into the focus command`() {
        val command = parser.parse(action("""{"type":"ime.show","params":{}}"""))

        // ImeShow 是无参数的 data object：聚焦的是系统当前输入焦点，不接受选择器。
        assertEquals(AccessibilityCommand.ImeShow, command)
    }

    @Test
    fun `rejects ime show with parameters`() {
        // ime.show 不接受任何参数。放行未知参数会让调用方以为可以指定聚焦目标，
        // 而实际聚焦的始终是系统当前焦点节点。
        val error = runCatching {
            parser.parse(action("""{"type":"ime.show","params":{"target":"field"}}"""))
        }.exceptionOrNull()

        assertTrue(error is BridgeException)
        assertEquals(BridgeErrorCode.INVALID_ARGUMENT, (error as BridgeException).code)
    }

    private fun action(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject
}
