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

    @Test
    fun `parses clipboard set into the write command`() {
        val command = parser.parse(
            action("""{"type":"clipboard.set","params":{"text":"hello"}}"""),
        )

        assertEquals(AccessibilityCommand.ClipboardSet("hello"), command)
    }

    /**
     * 锁定：clipboard.get 没有桥侧出口，且这是**有意**的。
     *
     * Android 不存在 READ_CLIPBOARD 权限。API 29+ 对读取的限制靠「应用必须持有
     * 输入焦点或是默认输入法」实施——而自动化要读的正是别的 App 的剪贴板，
     * 那一刻本 App 不可能在前台。两个条件互斥，因此读取不是「补个权限就行」：
     * 要么让用户把本 App 设为默认输入法（权限档位高于无障碍，能看到用户在任何
     * 应用里的每一次按键），要么前台夺焦（会打断被自动化的界面）。
     * 两条都需要独立产品决策。
     *
     * 这条测试防止日后有人看到「写有读没有」以为是遗漏而顺手接上。
     * ClipboardGateway.read() 与 ClipboardReadResult 的三态保留不动——
     * 那是正确设计，只是本轮不给它出口。
     */
    @Test
    fun `clipboard get has no bridge entry point`() {
        val error = runCatching {
            parser.parse(action("""{"type":"clipboard.get","params":{}}"""))
        }.exceptionOrNull()

        assertTrue(error is BridgeException)
        assertEquals(
            BridgeErrorCode.CAPABILITY_UNAVAILABLE,
            (error as BridgeException).code,
        )
    }

    // 不传 match 时选择器候选默认精确匹配——现有脚本行为不变。
    @Test
    fun `selector candidate defaults to exact match`() {
        val command = parser.parse(
            action(
                """{"type":"ui.click","params":{"target":{"selectorCandidates":""" +
                    """[{"strategy":"text","value":"OK","weight":1.0}]}}}""",
            ),
        )

        val click = command as AccessibilityCommand.Click
        assertEquals(
            dev.aiauto.android.accessibility.model.SelectorMatchMode.EXACT,
            click.target.selectorCandidates.first().match,
        )
    }

    // match 只对 text/contentDescription 生效；标识符维度上用非精确匹配必须被拒。
    @Test
    fun `rejects match mode on identifier dimensions`() {
        for (strategy in listOf("resourceId", "className", "package")) {
            val error = runCatching {
                parser.parse(
                    action(
                        """{"type":"ui.click","params":{"target":{"selectorCandidates":""" +
                            """[{"strategy":"$strategy","value":"x","weight":1.0,""" +
                            """"match":"contains"}]}}}""",
                    ),
                )
            }.exceptionOrNull()

            assertTrue("$strategy should reject non-exact match", error is BridgeException)
            assertEquals(
                BridgeErrorCode.INVALID_ARGUMENT,
                (error as BridgeException).code,
            )
        }
    }

    private fun action(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject
}
