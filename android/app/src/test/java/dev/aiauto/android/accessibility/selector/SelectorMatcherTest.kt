package dev.aiauto.android.accessibility.selector

/**
 * 测试用途：验证 SelectorMatcher 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorMatchMode
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.accessibility.selector.NodeFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorMatcherTest {
    private val matcher = SelectorMatcher(
        minimumScore = 0.70,
        uniquenessMargin = 0.15,
    )

    @Test
    fun `selects the unique highest scoring node`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(
                    resourceId = "com.example:id/continue_button",
                    text = "Continue",
                    className = "android.widget.Button",
                    actions = setOf(NodeAction.CLICK),
                ),
                node(
                    resourceId = "com.example:id/cancel_button",
                    text = "Cancel",
                    className = "android.widget.Button",
                    actions = setOf(NodeAction.CLICK),
                ),
            ),
        )
        val target = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(
                candidate(SelectorStrategy.RESOURCE_ID, "com.example:id/continue_button", 0.6),
                candidate(SelectorStrategy.TEXT, "Continue", 0.25),
                candidate(SelectorStrategy.ROLE, "button", 0.15),
            ),
        )

        val result = matcher.match(root, target)

        assertTrue(result is SelectorMatch.Found)
        result as SelectorMatch.Found
        assertEquals(NodePath(listOf(0)), result.path)
        assertEquals(1.0, result.score, 0.0001)
    }

    @Test
    fun `rejects a node when a required selector does not match`() {
        val target = NodeTarget(
            selectorCandidates = listOf(
                candidate(
                    SelectorStrategy.RESOURCE_ID,
                    "com.example:id/missing",
                    weight = 0.2,
                    required = true,
                ),
                candidate(SelectorStrategy.TEXT, "Continue", weight = 0.8),
            ),
        )

        val result = matcher.match(node(text = "Continue"), target)

        assertTrue(result is SelectorMatch.NotFound)
    }

    @Test
    fun `rejects a best match below the minimum score`() {
        val target = NodeTarget(
            selectorCandidates = listOf(
                candidate(SelectorStrategy.TEXT, "Continue", weight = 0.6),
                candidate(SelectorStrategy.CONTENT_DESCRIPTION, "Primary action", weight = 0.4),
            ),
        )

        val result = matcher.match(node(text = "Continue"), target)

        assertTrue(result is SelectorMatch.NotFound)
        assertEquals(0.6, (result as SelectorMatch.NotFound).bestScore, 0.0001)
    }

    @Test
    fun `reports ambiguity when top candidates are not uniquely separated`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(text = "Continue", className = "android.widget.Button"),
                node(text = "Continue", className = "android.widget.Button"),
            ),
        )
        val target = NodeTarget(
            selectorCandidates = listOf(
                candidate(SelectorStrategy.TEXT, "Continue", weight = 0.8),
                candidate(SelectorStrategy.ROLE, "button", weight = 0.2),
            ),
        )

        val result = matcher.match(root, target)

        assertTrue(result is SelectorMatch.Ambiguous)
        assertEquals(2, (result as SelectorMatch.Ambiguous).candidateCount)
    }

    @Test
    fun `uses ancestor context and rejects a different package`() {
        val root = node(
            resourceId = "com.example:id/profile_section",
            className = "android.widget.LinearLayout",
            children = listOf(node(text = "Save", className = "android.widget.Button")),
        )
        val matchingTarget = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(
                candidate(SelectorStrategy.TEXT, "Save", weight = 0.7),
                candidate(
                    SelectorStrategy.ANCESTOR,
                    "com.example:id/profile_section",
                    weight = 0.3,
                ),
            ),
        )

        val matchingResult = matcher.match(root, matchingTarget)
        val wrongPackageResult = matcher.match(
            root,
            matchingTarget.copy(packageName = "com.other"),
        )

        assertTrue(matchingResult is SelectorMatch.Found)
        assertEquals(NodePath(listOf(0)), (matchingResult as SelectorMatch.Found).path)
        assertTrue(wrongPackageResult is SelectorMatch.NotFound)
    }

    @Test
    fun `matches toggle button with mixed selector candidates`() {
        val currentNode = node(
            resourceId = "dev.aiauto.fixture:id/local_toggle",
            text = "OFF",
            contentDescription = "Local test toggle",
            className = "android.widget.ToggleButton",
            actions = setOf(NodeAction.CLICK),
        )
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(currentNode),
        )

        val target = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(
                candidate(SelectorStrategy.RESOURCE_ID, "dev.aiauto.fixture:id/local_toggle", 0.45, required = true),
                candidate(SelectorStrategy.CONTENT_DESCRIPTION, "Local test toggle", 0.25),
                candidate(SelectorStrategy.TEXT, "ON", 0.20),
                candidate(SelectorStrategy.ROLE, "button", 0.10),
                candidate(SelectorStrategy.FINGERPRINT, NodeFingerprint.create(currentNode), 0.15),
            ),
        )

        val result = matcher.match(root, target)

        assertTrue(result is SelectorMatch.Found)
        result as SelectorMatch.Found
        assertEquals(NodePath(listOf(0)), result.path)
        assertEquals(0.95 / 1.15, result.score, 0.0001)
        assertTrue(result.score > 0.70)
    }

    // className 与 package 是新补的维度，与判据侧对齐，精确匹配（normalize 后）。
    @Test
    fun `matches by className and package dimensions`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(
                    resourceId = "com.example:id/ok",
                    text = "OK",
                    className = "android.widget.Button",
                    actions = setOf(NodeAction.CLICK),
                ),
            ),
        )
        val target = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(
                candidate(SelectorStrategy.CLASS_NAME, "android.widget.Button", 0.6),
                candidate(SelectorStrategy.PACKAGE, "com.example", 0.4),
            ),
        )

        val result = matcher.match(root, target)

        assertTrue(result is SelectorMatch.Found)
        result as SelectorMatch.Found
        assertEquals(NodePath(listOf(0)), result.path)
    }

    // className 精确匹配：前缀不命中，与 resourceId 口径一致。
    @Test
    fun `className matches exactly not by prefix`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(
                    resourceId = "com.example:id/ok",
                    className = "android.widget.Button",
                    actions = setOf(NodeAction.CLICK),
                ),
            ),
        )
        val target = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(
                candidate(SelectorStrategy.CLASS_NAME, "android.widget", 1.0, required = true),
            ),
        )

        assertTrue(matcher.match(root, target) is SelectorMatch.NotFound)
    }

    // 不传 match 时等价于 exact——现有脚本行为不变，这是 C 的兼容前提。
    @Test
    fun `default match is equivalent to exact`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(resourceId = "id/x", text = "Submit Order", actions = setOf(NodeAction.CLICK)),
            ),
        )
        val partial = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(candidate(SelectorStrategy.TEXT, "Submit", 1.0, required = true)),
        )
        assertTrue(matcher.match(root, partial) is SelectorMatch.NotFound)
        val exact = NodeTarget(
            packageName = "com.example",
            selectorCandidates = listOf(candidate(SelectorStrategy.TEXT, "Submit Order", 1.0, required = true)),
        )
        assertTrue(matcher.match(root, exact) is SelectorMatch.Found)
    }

    // match=contains / startsWith / endsWith 对 text 生效。
    @Test
    fun `text match modes locate partial text`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(resourceId = "id/x", text = "共 12 条，全部删除", actions = setOf(NodeAction.CLICK)),
            ),
        )
        val cases = listOf(
            SelectorMatchMode.CONTAINS to "全部删除",
            SelectorMatchMode.STARTS_WITH to "共 12",
            SelectorMatchMode.ENDS_WITH to "全部删除",
        )
        for (c in cases) {
            val target = NodeTarget(
                packageName = "com.example",
                selectorCandidates = listOf(
                    candidate(SelectorStrategy.TEXT, c.second, 1.0, required = true, match = c.first),
                ),
            )
            assertTrue(
                "mode ${c.first} value ${c.second} should match",
                matcher.match(root, target) is SelectorMatch.Found,
            )
        }
    }

    private fun candidate(
        strategy: SelectorStrategy,
        value: String,
        weight: Double,
        required: Boolean = false,
        match: SelectorMatchMode = SelectorMatchMode.EXACT,
    ) = SelectorCandidate(
        strategy = strategy,
        value = value,
        weight = weight,
        required = required,
        match = match,
    )

    private fun node(
        resourceId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        className: String = "android.widget.TextView",
        actions: Set<NodeAction> = emptySet(),
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = "com.example",
        className = className,
        resourceId = resourceId,
        text = text,
        contentDescription = contentDescription,
        bounds = UiBounds(left = 0, top = 0, right = 100, bottom = 50),
        actions = actions,
        state = UiNodeState(enabled = true, visibleToUser = true),
        children = children,
    )
}
