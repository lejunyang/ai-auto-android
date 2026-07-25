package dev.aiauto.android.automation.recording.webview

/**
 * 测试用途：验证 WebView 虚拟节点的四级能力分类、类型化动作规划与失败关闭边界。
 */

import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSemanticAdapterTest {
    private val adapter = WebViewSemanticAdapter(nowMs = { 1_000L })
    private val evaluator = WebViewCapabilityEvaluator()

    @Test
    fun `API and WebView matrix records full semantic support`() {
        val runtimes = listOf(
            WebViewRuntime(apiLevel = 30, webViewVersion = "91.0.4472.114"),
            WebViewRuntime(apiLevel = 33, webViewVersion = "109.0.5414.123"),
            WebViewRuntime(apiLevel = 34, webViewVersion = "113.0.5672.136"),
        )

        val entries = runtimes.map { runtime ->
            evaluator.evaluate(
                observation = fullObservation(runtime = runtime),
                contract = fullContract(),
            )
        }

        assertEquals(listOf(30, 33, 34), entries.map { it.runtime.apiLevel })
        assertEquals(
            runtimes.map(WebViewRuntime::webViewVersion),
            entries.map { it.runtime.webViewVersion },
        )
        assertTrue(entries.all { it.level == WebViewCompatibilityLevel.FULL_SEMANTIC })
        assertTrue(entries.all { it.missingCapabilities.isEmpty() })
    }

    @Test
    fun `partial and canvas pages report explicit downgrade levels`() {
        val partial = evaluator.evaluate(
            observation = observation(
                pageId = "partial",
                children = listOf(
                    node("Visible semantic action", NodeAction.CLICK),
                    node("Visible semantic input", NodeAction.SET_TEXT),
                ),
                visualSurfaceAvailable = true,
            ),
            contract = WebViewPageContract(
                pageId = "partial",
                requirements = listOf(
                    requirement(WebViewSemanticCapability.CLICK, "Visible semantic action"),
                    requirement(
                        WebViewSemanticCapability.INPUT,
                        "Visible semantic input",
                        NodeAction.SET_TEXT,
                    ),
                ),
                visualRegionCount = 1,
            ),
        )
        val canvas = evaluator.evaluate(
            observation = observation(
                pageId = "canvas",
                children = emptyList(),
                visualSurfaceAvailable = true,
            ),
            contract = WebViewPageContract(
                pageId = "canvas",
                requirements = emptyList(),
                visualRegionCount = 1,
            ),
        )

        assertEquals(WebViewCompatibilityLevel.HYBRID, partial.level)
        assertEquals(WebViewCompatibilityLevel.VISUAL_ONLY, canvas.level)
    }

    @Test
    fun `unverifiable or restricted page is unsupported`() {
        val entry = evaluator.evaluate(
            observation = observation(
                pageId = "secure",
                children = emptyList(),
                visualSurfaceAvailable = false,
                postconditionsVerifiable = false,
                restricted = true,
            ),
            contract = WebViewPageContract(
                pageId = "secure",
                requirements = listOf(
                    requirement(WebViewSemanticCapability.CLICK, "Blocked action"),
                ),
            ),
        )

        assertEquals(WebViewCompatibilityLevel.UNSUPPORTED, entry.level)
        assertTrue("POSTCONDITION_UNVERIFIABLE" in entry.reasons)
        assertTrue("RESTRICTED_CONTENT" in entry.reasons)
    }

    @Test
    fun `click input long press scroll navigation and Back use typed actions`() {
        val observation = fullObservation()
        val intents = listOf(
            WebViewSemanticIntent.Click(target("Fixture click button")) to "ui.click",
            WebViewSemanticIntent.SetText(
                target("Fixture text input"),
                "fixture-value",
            ) to "ui.setText",
            WebViewSemanticIntent.LongClick(target("Fixture long press target")) to
                "ui.longClick",
            WebViewSemanticIntent.Scroll(
                target("Fixture scroll container"),
                ScrollDirection.DOWN,
            ) to "ui.scroll",
            WebViewSemanticIntent.Navigate(target("Open fixture detail")) to "ui.click",
            WebViewSemanticIntent.Back to "ui.back",
        )

        val plans = intents.map { (intent, expectedType) ->
            val result = adapter.plan(
                observation = observation,
                expectedPackage = FIXTURE_PACKAGE,
                expectedPageId = "full",
                intent = intent,
            )
            val ready = assertType<WebViewSemanticPlanResult.Ready>(result)
            assertEquals(expectedType, ready.plan.action.type)
            assertEquals("obs-full-1", ready.plan.observationId)
            ready.plan
        }

        assertTrue(
            plans.filter { it.action.type != "ui.back" }
                .all { "target" in it.action.params },
        )
        assertTrue(plans.none { plan ->
            plan.action.type == "ui.tap" || plan.action.type == "ui.swipe"
        })
    }

    @Test
    fun `missing dynamic DOM and iframe return stable semantic unavailable`() {
        val observation = fullObservation(
            children = fullNodes().filterNot {
                it.contentDescription in setOf(
                    "Dynamic result button",
                    "Offline frame button",
                )
            },
        )

        listOf("Dynamic result button", "Offline frame button").forEach { name ->
            val result = adapter.plan(
                observation = observation,
                expectedPackage = FIXTURE_PACKAGE,
                expectedPageId = "full",
                intent = WebViewSemanticIntent.Click(target(name)),
            )

            val rejected = assertType<WebViewSemanticPlanResult.Rejected>(result)
            assertEquals(WebViewSemanticErrorCode.SEMANTIC_UNAVAILABLE, rejected.code)
            assertEquals(WebViewCompatibilityLevel.HYBRID, rejected.level)
        }
    }

    @Test
    fun `missing required node action returns semantic unavailable`() {
        val observation = fullObservation(
            children = fullNodes().map { node ->
                if (node.contentDescription == "Fixture text input") {
                    node.copy(actions = emptySet(), state = node.state.copy(editable = false))
                } else {
                    node
                }
            },
        )

        val result = adapter.plan(
            observation = observation,
            expectedPackage = FIXTURE_PACKAGE,
            expectedPageId = "full",
            intent = WebViewSemanticIntent.SetText(
                target("Fixture text input"),
                "blocked",
            ),
        )

        val rejected = assertType<WebViewSemanticPlanResult.Rejected>(result)
        assertEquals(WebViewSemanticErrorCode.SEMANTIC_UNAVAILABLE, rejected.code)
    }

    @Test
    fun `ambiguous virtual nodes fail without selecting either candidate`() {
        val duplicate = node("Fixture click button", NodeAction.CLICK)
        val observation = fullObservation(children = fullNodes() + duplicate)

        val result = adapter.plan(
            observation = observation,
            expectedPackage = FIXTURE_PACKAGE,
            expectedPageId = "full",
            intent = WebViewSemanticIntent.Click(target("Fixture click button")),
        )

        val rejected = assertType<WebViewSemanticPlanResult.Rejected>(result)
        assertEquals(WebViewSemanticErrorCode.SELECTOR_AMBIGUOUS, rejected.code)
    }

    @Test
    fun `package and page drift fail before a plan is produced`() {
        val packageDrift = adapter.plan(
            observation = fullObservation().copy(packageName = "dev.example.other"),
            expectedPackage = FIXTURE_PACKAGE,
            expectedPageId = "full",
            intent = WebViewSemanticIntent.Click(target("Fixture click button")),
        )
        val pageDrift = adapter.plan(
            observation = fullObservation().copy(pageId = "detail"),
            expectedPackage = FIXTURE_PACKAGE,
            expectedPageId = "full",
            intent = WebViewSemanticIntent.Click(target("Fixture click button")),
        )

        assertEquals(
            WebViewSemanticErrorCode.PACKAGE_DRIFT,
            assertType<WebViewSemanticPlanResult.Rejected>(packageDrift).code,
        )
        assertEquals(
            WebViewSemanticErrorCode.PAGE_DRIFT,
            assertType<WebViewSemanticPlanResult.Rejected>(pageDrift).code,
        )
    }

    @Test
    fun `expired observation fails before semantic planning`() {
        val result = adapter.plan(
            observation = fullObservation().copy(
                capturedAtMs = 100L,
                expiresAtMs = 999L,
            ),
            expectedPackage = FIXTURE_PACKAGE,
            expectedPageId = "full",
            intent = WebViewSemanticIntent.Click(target("Fixture click button")),
        )

        val rejected = assertType<WebViewSemanticPlanResult.Rejected>(result)
        assertEquals(WebViewSemanticErrorCode.OBSERVATION_EXPIRED, rejected.code)
    }

    private fun fullObservation(
        runtime: WebViewRuntime = WebViewRuntime(34, "113.0.5672.136"),
        children: List<UiNodeSnapshot> = fullNodes(),
    ): WebViewSemanticObservation = observation(
        pageId = "full",
        runtime = runtime,
        children = children,
        visualSurfaceAvailable = true,
    )

    private fun observation(
        pageId: String,
        children: List<UiNodeSnapshot>,
        runtime: WebViewRuntime = WebViewRuntime(34, "113.0.5672.136"),
        visualSurfaceAvailable: Boolean,
        postconditionsVerifiable: Boolean = true,
        restricted: Boolean = false,
    ) = WebViewSemanticObservation(
        observationId = "obs-$pageId-1",
        runtime = runtime,
        packageName = FIXTURE_PACKAGE,
        pageId = pageId,
        root = root(children),
        visualSurfaceAvailable = visualSurfaceAvailable,
        postconditionsVerifiable = postconditionsVerifiable,
        restricted = restricted,
        capturedAtMs = 900L,
        expiresAtMs = 1_100L,
    )

    private fun fullContract() = WebViewPageContract(
        pageId = "full",
        requirements = listOf(
            requirement(WebViewSemanticCapability.CLICK, "Fixture click button"),
            requirement(
                WebViewSemanticCapability.INPUT,
                "Fixture text input",
                NodeAction.SET_TEXT,
            ),
            requirement(
                WebViewSemanticCapability.LONG_CLICK,
                "Fixture long press target",
                NodeAction.LONG_CLICK,
            ),
            requirement(
                WebViewSemanticCapability.SCROLL,
                "Fixture scroll container",
                NodeAction.SCROLL_DOWN,
            ),
            requirement(WebViewSemanticCapability.NAVIGATE, "Open fixture detail"),
            requirement(WebViewSemanticCapability.DYNAMIC_DOM, "Dynamic result button"),
            requirement(WebViewSemanticCapability.IFRAME, "Offline frame button"),
        ),
    )

    private fun requirement(
        capability: WebViewSemanticCapability,
        name: String,
        action: NodeAction = NodeAction.CLICK,
    ) = WebViewSemanticRequirement(
        capability = capability,
        target = target(name),
        requiredAction = action,
    )

    private fun fullNodes() = listOf(
        node("Fixture click button", NodeAction.CLICK),
        node("Fixture text input", NodeAction.SET_TEXT),
        node("Fixture long press target", NodeAction.LONG_CLICK),
        node("Fixture scroll container", NodeAction.SCROLL_DOWN),
        node("Open fixture detail", NodeAction.CLICK),
        node("Dynamic result button", NodeAction.CLICK),
        node("Offline frame button", NodeAction.CLICK),
    )

    private fun root(children: List<UiNodeSnapshot>) = UiNodeSnapshot(
        packageName = FIXTURE_PACKAGE,
        className = "android.webkit.WebView",
        resourceId = null,
        text = null,
        contentDescription = null,
        bounds = UiBounds(0, 0, 1080, 1920),
        actions = emptySet(),
        state = UiNodeState(enabled = true, visibleToUser = true),
        children = children,
    )

    private fun node(name: String, action: NodeAction) = UiNodeSnapshot(
        packageName = FIXTURE_PACKAGE,
        className = when (action) {
            NodeAction.SET_TEXT -> "android.widget.EditText"
            else -> "android.widget.Button"
        },
        resourceId = null,
        text = null,
        contentDescription = name,
        bounds = UiBounds(20, 20, 400, 120),
        actions = setOf(action),
        state = UiNodeState(
            clickable = action == NodeAction.CLICK,
            enabled = true,
            editable = action == NodeAction.SET_TEXT,
            longClickable = action == NodeAction.LONG_CLICK,
            scrollable = action.name.startsWith("SCROLL"),
            visibleToUser = true,
        ),
        children = emptyList(),
    )

    private fun target(name: String) = NodeTarget(
        packageName = FIXTURE_PACKAGE,
        selectorCandidates = listOf(
            SelectorCandidate(
                strategy = SelectorStrategy.CONTENT_DESCRIPTION,
                value = name,
                weight = 1.0,
                required = true,
            ),
        ),
    )

    private companion object {
        const val FIXTURE_PACKAGE = "dev.aiauto.webfixture"
    }
}

private inline fun <reified T> assertType(value: Any?): T {
    assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
    return value as T
}
