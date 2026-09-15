package dev.aiauto.android.accessibility.selector

/**
 * 测试用途：验证跨窗口选择器匹配在弹窗、输入法与分屏场景下的定位与 z 序判定，
 * 并确认单窗口调用路径的既有语义未被改变。
 */

import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.accessibility.model.UiWindowSnapshot
import dev.aiauto.android.accessibility.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiWindowSelectorMatcherTest {
    @Test
    fun `finds a node that only exists in a non active dialog window BitsUT`() {
        // 这是多窗口模型要解决的核心场景：确认按钮只在对话框窗口里，
        // 而对话框通常不是活动窗口，只搜活动窗口必然 NotFound。
        val matcher = SelectorMatcher()
        val windows = listOf(
            window(
                windowId = 1,
                layer = 0,
                active = true,
                root = root(children = listOf(button("com.example:id/open", "打开"))),
            ),
            window(
                windowId = 2,
                layer = 5,
                active = false,
                kind = WindowKind.APPLICATION,
                root = root(children = listOf(button("com.example:id/confirm", "确认"))),
            ),
        )

        val match = matcher.match(windows, target("com.example:id/confirm"))

        assertTrue(match is SelectorMatch.Found)
        match as SelectorMatch.Found
        assertEquals(2, match.path.windowId)
        assertEquals(listOf(0), match.path.indices)
    }

    @Test
    fun `prefers the topmost window when the same selector matches in two windows BitsUT`() {
        // 对话框弹出时宿主 Activity 里的同名按钮仍会命中，但被盖住点不到。
        // 必须按 layer 选最上层，而不是报歧义。
        val matcher = SelectorMatcher()
        val windows = listOf(
            window(
                windowId = 1,
                layer = 0,
                active = true,
                root = root(children = listOf(button("com.example:id/ok", "确定"))),
            ),
            window(
                windowId = 2,
                layer = 9,
                active = false,
                root = root(children = listOf(button("com.example:id/ok", "确定"))),
            ),
        )

        val match = matcher.match(windows, target("com.example:id/ok"))

        assertTrue(match is SelectorMatch.Found)
        assertEquals(2, (match as SelectorMatch.Found).path.windowId)
    }

    @Test
    fun `keeps ambiguity for duplicate nodes inside one window BitsUT`() {
        // z 序只能区分不同窗口。同一窗口内的两个同名节点仍然无法决定，
        // 必须继续报歧义而不是被 layer 规则误判成有唯一解。
        val matcher = SelectorMatcher()
        val duplicate = button("com.example:id/ok", "确定")
        val windows = listOf(
            window(
                windowId = 1,
                layer = 3,
                active = true,
                root = root(children = listOf(duplicate, duplicate)),
            ),
        )

        val match = matcher.match(windows, target("com.example:id/ok"))

        assertTrue(match is SelectorMatch.Ambiguous)
        assertEquals(2, (match as SelectorMatch.Ambiguous).candidateCount)
    }

    @Test
    fun `finds a node inside the input method window BitsUT`() {
        val matcher = SelectorMatcher()
        val windows = listOf(
            window(
                windowId = 1,
                layer = 0,
                active = true,
                root = root(children = listOf(button("com.example:id/field", "输入"))),
            ),
            window(
                windowId = 7,
                layer = 12,
                active = false,
                kind = WindowKind.INPUT_METHOD,
                packageName = "com.example.ime",
                root = root(
                    packageName = "com.example.ime",
                    children = listOf(
                        button("com.example.ime:id/enter", "完成", "com.example.ime"),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            windows,
            NodeTarget(
                packageName = "com.example.ime",
                selectorCandidates = listOf(
                    SelectorCandidate(
                        strategy = SelectorStrategy.RESOURCE_ID,
                        value = "com.example.ime:id/enter",
                        weight = 1.0,
                        required = true,
                    ),
                ),
            ),
        )

        assertTrue(match is SelectorMatch.Found)
        assertEquals(7, (match as SelectorMatch.Found).path.windowId)
    }

    @Test
    fun `single window match keeps a null window id for legacy scripts BitsUT`() {
        // 多窗口模型引入前录制的脚本里 matchedPath 不带窗口标识。
        // 单根匹配必须继续返回 windowId=null，否则回放会去解析一个不存在的窗口。
        val matcher = SelectorMatcher()

        val match = matcher.match(
            root(children = listOf(button("com.example:id/ok", "确定"))),
            target("com.example:id/ok"),
        )

        assertTrue(match is SelectorMatch.Found)
        assertNull((match as SelectorMatch.Found).path.windowId)
        assertEquals(listOf(0), match.path.indices)
    }

    @Test
    fun `ignores invisible nodes in overlay windows BitsUT`() {
        val matcher = SelectorMatcher()
        val windows = listOf(
            window(
                windowId = 1,
                layer = 0,
                active = true,
                root = root(children = listOf(button("com.example:id/ok", "确定"))),
            ),
            window(
                windowId = 3,
                layer = 20,
                active = false,
                kind = WindowKind.ACCESSIBILITY_OVERLAY,
                root = root(
                    children = listOf(
                        button("com.example:id/ok", "确定").let { node ->
                            node.copy(state = node.state.copy(visibleToUser = false))
                        },
                    ),
                ),
            ),
        )

        val match = matcher.match(windows, target("com.example:id/ok"))

        assertTrue(match is SelectorMatch.Found)
        assertEquals(1, (match as SelectorMatch.Found).path.windowId)
    }

    private fun target(resourceId: String) = NodeTarget(
        packageName = "com.example",
        selectorCandidates = listOf(
            SelectorCandidate(
                strategy = SelectorStrategy.RESOURCE_ID,
                value = resourceId,
                weight = 1.0,
                required = true,
            ),
        ),
    )

    private fun window(
        windowId: Int,
        layer: Int,
        active: Boolean,
        root: UiNodeSnapshot,
        kind: WindowKind = WindowKind.APPLICATION,
        packageName: String = "com.example",
    ) = UiWindowSnapshot(
        windowId = windowId,
        kind = kind,
        layer = layer,
        active = active,
        focused = active,
        bounds = ScreenBounds(left = 0, top = 0, right = 1000, bottom = 2000),
        root = root.copy(packageName = packageName),
    )

    private fun root(
        packageName: String = "com.example",
        children: List<UiNodeSnapshot>,
    ) = UiNodeSnapshot(
        packageName = packageName,
        className = "android.widget.FrameLayout",
        resourceId = null,
        text = null,
        contentDescription = null,
        bounds = UiBounds(left = 0, top = 0, right = 1000, bottom = 2000),
        actions = emptySet(),
        state = UiNodeState(enabled = true, visibleToUser = true),
        children = children,
    )

    private fun button(
        resourceId: String,
        text: String,
        packageName: String = "com.example",
    ) = UiNodeSnapshot(
        packageName = packageName,
        className = "android.widget.Button",
        resourceId = resourceId,
        text = text,
        contentDescription = null,
        bounds = UiBounds(left = 100, top = 200, right = 300, bottom = 260),
        actions = setOf(dev.aiauto.android.accessibility.model.NodeAction.CLICK),
        state = UiNodeState(
            enabled = true,
            clickable = true,
            visibleToUser = true,
        ),
        children = emptyList(),
    )
}
