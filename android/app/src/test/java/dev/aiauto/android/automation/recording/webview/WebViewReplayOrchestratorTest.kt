package dev.aiauto.android.automation.recording.webview

/**
 * 测试用途：验证跨页面流程保存、复位、逐步观察和确定性回放在上下文漂移时停止提交动作。
 */

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.ReplayGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewReplayOrchestratorTest {
    @Test
    fun `saved flow resets then replays navigation detail action and Back deterministically`() {
        val flow = multiPageFlow()
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(
                    snapshot("full", "Fixture state ready", "Open fixture detail"),
                    snapshot("detail", "Fixture detail state", "Detail page action"),
                    snapshot("detail", "Fixture detail state detail-clicked"),
                    snapshot("full", "Fixture state ready"),
                ),
            ),
        )
        val store = FakeFlowStore()
        val reset = FakeResetPort()
        val orchestrator = WebViewReplayOrchestrator(
            adapter = WebViewSemanticAdapter(),
            gateway = gateway,
            pageIdentity = FixturePageIdentity(),
            flowStore = store,
            resetPort = reset,
            verifyObservationBeforeDispatch = false,
        )

        val result = orchestrator.saveResetAndReplay(flow)

        assertTrue(result.succeeded)
        assertEquals(flow, store.saved)
        assertEquals(1, reset.calls)
        assertEquals(
            listOf("ui.click", "ui.click", "ui.back"),
            gateway.executed.map(RecordedAction::type),
        )
        assertEquals(
            listOf("navigate-detail", "detail-action", "back-full"),
            result.steps.map(WebViewReplayStepResult::stepId),
        )
        assertEquals(4, gateway.snapshotCalls)
    }

    @Test
    fun `save failure prevents reset snapshot and action submission`() {
        val gateway = FakeGateway()
        val reset = FakeResetPort()
        val orchestrator = WebViewReplayOrchestrator(
            adapter = WebViewSemanticAdapter(),
            gateway = gateway,
            pageIdentity = FixturePageIdentity(),
            flowStore = FakeFlowStore(saveSucceeds = false),
            resetPort = reset,
        )

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.SAVE_FAILED, result.errorCode)
        assertEquals(0, reset.calls)
        assertEquals(0, gateway.snapshotCalls)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `reset failure prevents observation and action submission`() {
        val gateway = FakeGateway()
        val reset = FakeResetPort(resetSucceeds = false)
        val orchestrator = WebViewReplayOrchestrator(
            adapter = WebViewSemanticAdapter(),
            gateway = gateway,
            pageIdentity = FixturePageIdentity(),
            flowStore = FakeFlowStore(),
            resetPort = reset,
        )

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.RESET_FAILED, result.errorCode)
        assertEquals(1, reset.calls)
        assertEquals(0, gateway.snapshotCalls)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `package drift at first step submits zero actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(snapshot("full", "Fixture state ready").copy(packageName = "other")),
            ),
        )
        val orchestrator = orchestrator(gateway)

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.PACKAGE_DRIFT, result.errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `page drift at first step submits zero actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(listOf(snapshot("detail", "Fixture state ready"))),
        )
        val orchestrator = orchestrator(gateway)

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.PAGE_DRIFT, result.errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `runtime drift at first step submits zero actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(snapshot("full", "Fixture state ready", "Open fixture detail")),
            ),
        )
        val orchestrator = WebViewReplayOrchestrator(
            adapter = WebViewSemanticAdapter(nowMs = { 1_000L }),
            gateway = gateway,
            pageIdentity = FixturePageIdentity(
                runtime = WebViewRuntime(33, "109.0.5414.123"),
            ),
            flowStore = FakeFlowStore(),
            resetPort = FakeResetPort(),
            nowMs = { 1_000L },
        )

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.RUNTIME_DRIFT, result.errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `missing node at first step is semantic unavailable with zero actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(listOf(snapshot("full", "Fixture state ready"))),
        )
        val orchestrator = orchestrator(gateway)

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.SEMANTIC_UNAVAILABLE, result.errorCode)
        assertEquals(WebViewCompatibilityLevel.HYBRID, result.level)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `ambiguous node at first step submits zero actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(
                        "full",
                        "Fixture state ready",
                        "Open fixture detail",
                        "Open fixture detail",
                    ),
                ),
            ),
        )
        val orchestrator = orchestrator(gateway)

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.SELECTOR_AMBIGUOUS, result.errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `observation change before dispatch fails closed with zero actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(
                    snapshot("full", "Fixture state ready", "Open fixture detail"),
                    snapshot("full", "Fixture state changed", "Open fixture detail"),
                ),
            ),
        )
        val orchestrator = WebViewReplayOrchestrator(
            adapter = WebViewSemanticAdapter(),
            gateway = gateway,
            pageIdentity = FixturePageIdentity(),
            flowStore = FakeFlowStore(),
            resetPort = FakeResetPort(),
            verifyObservationBeforeDispatch = true,
        )

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.OBSERVATION_STALE, result.errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `postcondition failure stops later steps`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(
                    snapshot("full", "Fixture state ready", "Open fixture detail"),
                    snapshot("full", "Fixture state ready"),
                ),
            ),
        )
        val orchestrator = orchestrator(gateway)

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.POSTCONDITION_FAILED, result.errorCode)
        assertEquals(1, gateway.executed.size)
    }

    @Test
    fun `gateway execution failure stops later actions`() {
        val gateway = FakeGateway(
            snapshots = ArrayDeque(
                listOf(snapshot("full", "Fixture state ready", "Open fixture detail")),
            ),
            executionFailure = true,
        )
        val orchestrator = orchestrator(gateway)

        val result = orchestrator.saveResetAndReplay(multiPageFlow())

        assertFalse(result.succeeded)
        assertEquals(WebViewSemanticErrorCode.ACTION_FAILED, result.errorCode)
        assertEquals(1, gateway.executed.size)
    }

    private fun orchestrator(gateway: FakeGateway) = WebViewReplayOrchestrator(
        adapter = WebViewSemanticAdapter(),
        gateway = gateway,
        pageIdentity = FixturePageIdentity(),
        flowStore = FakeFlowStore(),
        resetPort = FakeResetPort(),
        verifyObservationBeforeDispatch = false,
    )

    private fun multiPageFlow() = WebViewRecordedFlow(
        id = "fixture-multi-page",
        targetPackage = FIXTURE_PACKAGE,
        runtime = WebViewRuntime(34, "113.0.5672.136"),
        steps = listOf(
            WebViewRecordedStep(
                id = "navigate-detail",
                expectedPageId = "full",
                expectedState = "Fixture state ready",
                intent = WebViewSemanticIntent.Navigate(target("Open fixture detail")),
                postcondition = WebViewPostcondition(
                    pageId = "detail",
                    state = "Fixture detail state",
                ),
            ),
            WebViewRecordedStep(
                id = "detail-action",
                expectedPageId = "detail",
                expectedState = "Fixture detail state",
                intent = WebViewSemanticIntent.Click(target("Detail page action")),
                postcondition = WebViewPostcondition(
                    pageId = "detail",
                    state = "Fixture detail state detail-clicked",
                ),
            ),
            WebViewRecordedStep(
                id = "back-full",
                expectedPageId = "detail",
                expectedState = "Fixture detail state detail-clicked",
                intent = WebViewSemanticIntent.Back,
                postcondition = WebViewPostcondition(
                    pageId = "full",
                    state = "Fixture state ready",
                ),
            ),
        ),
    )

    private fun snapshot(page: String, vararg names: String): UiNodeSnapshot =
        UiNodeSnapshot(
            packageName = FIXTURE_PACKAGE,
            className = "android.webkit.WebView",
            resourceId = null,
            text = "PAGE:$page",
            contentDescription = null,
            bounds = UiBounds(0, 0, 1080, 1920),
            actions = emptySet(),
            state = UiNodeState(enabled = true, visibleToUser = true),
            children = names.map { name ->
                UiNodeSnapshot(
                    packageName = FIXTURE_PACKAGE,
                    className = "android.widget.Button",
                    resourceId = null,
                    text = null,
                    contentDescription = name,
                    bounds = UiBounds(20, 20, 400, 120),
                    actions = setOf(NodeAction.CLICK),
                    state = UiNodeState(
                        clickable = true,
                        enabled = true,
                        visibleToUser = true,
                    ),
                    children = emptyList(),
                )
            },
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

    private class FakeGateway(
        val snapshots: ArrayDeque<UiNodeSnapshot> = ArrayDeque(),
        private val executionFailure: Boolean = false,
    ) : ReplayGateway {
        val executed = mutableListOf<RecordedAction>()
        var snapshotCalls = 0

        override fun snapshot(expectedPackage: String?): AccessibilityResult<UiNodeSnapshot> {
            snapshotCalls += 1
            val next = snapshots.removeFirstOrNull() ?: return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.SNAPSHOT_FAILED,
                message = "No fixture snapshot",
            )
            return AccessibilityResult.Success(next)
        }

        override fun execute(action: RecordedAction): AccessibilityResult<ActionExecution> {
            executed += action
            if (executionFailure) {
                return AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.ACTION_FAILED,
                    message = "Fixture action failed",
                )
            }
            return AccessibilityResult.Success(ActionExecution(ActionRoute.NODE_ACTION))
        }
    }

    private class FakeFlowStore(
        private val saveSucceeds: Boolean = true,
    ) : WebViewFlowStore {
        var saved: WebViewRecordedFlow? = null

        override fun save(flow: WebViewRecordedFlow): Boolean {
            if (!saveSucceeds) {
                return false
            }
            saved = flow
            return true
        }
    }

    private class FakeResetPort(
        private val resetSucceeds: Boolean = true,
    ) : WebViewResetPort {
        var calls = 0

        override fun reset(targetPackage: String): Boolean {
            calls += 1
            return resetSucceeds
        }
    }

    private class FixturePageIdentity(
        private val runtime: WebViewRuntime = WebViewRuntime(34, "113.0.5672.136"),
    ) : WebViewPageIdentity {
        override fun identify(root: UiNodeSnapshot): String? =
            root.text?.removePrefix("PAGE:")

        override fun runtime(root: UiNodeSnapshot): WebViewRuntime = runtime

        override fun containsState(root: UiNodeSnapshot, expectedState: String): Boolean =
            root.text == expectedState ||
                root.contentDescription == expectedState ||
                root.children.any { child -> containsState(child, expectedState) }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "dev.aiauto.webfixture"
    }
}
