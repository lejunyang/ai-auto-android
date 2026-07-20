package dev.aiauto.android.accessibility.selector

// 测试用途：验证 SelectorMatcher 的功能契约、失败语义及自动化安全边界。

import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
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

    private fun candidate(
        strategy: SelectorStrategy,
        value: String,
        weight: Double,
        required: Boolean = false,
    ) = SelectorCandidate(
        strategy = strategy,
        value = value,
        weight = weight,
        required = required,
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
