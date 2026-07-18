package dev.aiauto.android.automation.recording

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEngineTest {
    @Test
    fun `semantic action executes when exactly one node matches`() {
        val node = node(resourceId = "com.example:id/save")
        val gateway = FakeGateway(snapshots = mutableListOf(success(node)))
        val engine = engine(gateway)

        val report = engine.replay(script(action = clickAction(node)))

        assertTrue(report.succeeded)
        assertEquals(1, gateway.executed.size)
        assertEquals(ActionRoute.NODE_ACTION.name, report.steps.single().route)
    }

    @Test
    fun `ambiguous semantic selector fails before any action executes`() {
        val target = node(resourceId = "com.example:id/save")
        val root = node(
            resourceId = "com.example:id/root",
            className = "android.widget.FrameLayout",
            children = listOf(target, target),
        )
        val gateway = FakeGateway(snapshots = mutableListOf(success(root)))
        val engine = engine(gateway)

        val report = engine.replay(script(action = clickAction(target)))

        assertFalse(report.succeeded)
        assertEquals("SELECTOR_AMBIGUOUS", report.steps.single().errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `retryable action succeeds on the configured second attempt`() {
        val gateway = FakeGateway(
            executions = mutableListOf(
                AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.ACTION_FAILED,
                    message = "first attempt failed",
                    retryable = true,
                ),
                success(ActionExecution(route = ActionRoute.GLOBAL_ACTION)),
            ),
        )
        val engine = engine(gateway)
        val action = RecordedAction("ui.back", JsonObject(emptyMap()))

        val report = engine.replay(
            script(
                action = action,
                retry = RetryPolicy(maxAttempts = 2, backoffMs = 50),
            ),
        )

        assertTrue(report.succeeded)
        assertEquals(2, report.steps.single().attempts)
        assertEquals(listOf(50L), (engineTime as FakeTime).sleeps)
    }

    @Test
    fun `missing secret stops before execution and never exposes a literal`() {
        val gateway = FakeGateway()
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            time = FakeTime(),
        )
        val action = RecordedAction(
            type = "ui.setText",
            params = buildJsonObject {
                put("target", clickAction(node()).params.getValue("target"))
                put("secretRef", JsonPrimitive("password"))
            },
        )

        val report = engine.replay(script(action = action))

        assertFalse(report.succeeded)
        assertEquals("SECRET_REQUIRED", report.steps.single().errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `per replay secret is resolved without mutating the script BitsUT`() {
        val target = node(className = "android.widget.EditText")
        val gateway = FakeGateway(snapshots = mutableListOf(success(target)))
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            time = FakeTime(),
        )
        val action = RecordedAction(
            type = "ui.setText",
            params = buildJsonObject {
                put("target", clickAction(target).params.getValue("target"))
                put("secretRef", JsonPrimitive("account.password"))
            },
        )
        val script = script(action = action)

        val report = engine.replay(
            script = script,
            secrets = mapOf("account.password" to "run-only-value"),
        )

        assertTrue(report.succeeded)
        assertEquals(
            "run-only-value",
            gateway.executed.single().params.getValue("text").jsonPrimitive.content,
        )
        assertFalse("secretRef" in gateway.executed.single().params)
        assertTrue("secretRef" in script.steps.single().action.params)
        assertFalse("text" in script.steps.single().action.params)
    }

    @Test
    fun `recorded package wait is evaluated without dispatching an action`() {
        val gateway = FakeGateway(snapshots = mutableListOf(success(node())))
        val engine = engine(gateway)
        val action = RecordedAction(
            type = "ui.wait",
            params = buildJsonObject {
                put("kind", JsonPrimitive("package"))
                put("operator", JsonPrimitive("equals"))
                put("expected", JsonPrimitive("com.example"))
                put("timeoutMs", JsonPrimitive(500))
            },
        )

        val report = engine.replay(script(action = action))

        assertTrue(report.succeeded)
        assertTrue(gateway.executed.isEmpty())
    }

    private var engineTime: ReplayTime = FakeTime()

    private fun engine(gateway: FakeGateway): ReplayEngine {
        engineTime = FakeTime()
        return ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver("resolved-secret"),
            time = engineTime,
        )
    }

    private fun secretResolver(value: String?): SecretResolver =
        object : SecretResolver {
            override fun resolve(alias: String): String? = value
        }

    private fun script(
        action: RecordedAction,
        retry: RetryPolicy = RetryPolicy(maxAttempts = 1, backoffMs = 0),
    ) = AutomationScript(
        id = "script",
        name = "Replay",
        targetPackages = listOf("com.example"),
        createdAt = "2026-07-18T00:00:00Z",
        steps = listOf(
            RecordedStep(
                id = "step",
                recordedAtMs = 0,
                action = action,
                retry = retry,
            ),
        ),
    )

    private fun clickAction(node: UiNodeSnapshot): RecordedAction =
        requireNotNull(
            RecordingEventMapper().map(
                RecordingEvent(
                    type = RecordingEventType.CLICK,
                    eventTimeMs = 1,
                    packageName = "com.example",
                    source = node,
                ),
            ),
        ).action

    private fun node(
        resourceId: String = "com.example:id/action",
        className: String = "android.widget.Button",
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = "com.example",
        className = className,
        resourceId = resourceId,
        text = null,
        contentDescription = null,
        bounds = UiBounds(0, 0, 100, 50),
        actions = emptySet(),
        state = UiNodeState(
            enabled = true,
            clickable = true,
            visibleToUser = true,
        ),
        children = children,
    )

    private fun <T> success(value: T): AccessibilityResult.Success<T> =
        AccessibilityResult.Success(value)

    private class FakeGateway(
        val snapshots: MutableList<AccessibilityResult<UiNodeSnapshot>> = mutableListOf(),
        val executions: MutableList<AccessibilityResult<ActionExecution>> = mutableListOf(
            AccessibilityResult.Success(
                ActionExecution(
                    route = ActionRoute.NODE_ACTION,
                    matchScore = 1.0,
                ),
            ),
        ),
    ) : ReplayGateway {
        val executed = mutableListOf<RecordedAction>()

        override fun snapshot(expectedPackage: String?): AccessibilityResult<UiNodeSnapshot> =
            snapshots.removeFirstOrNull()
                ?: AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
                    message = "No snapshot",
                    retryable = true,
                )

        override fun execute(action: RecordedAction): AccessibilityResult<ActionExecution> {
            executed += action
            return executions.removeFirstOrNull()
                ?: AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.ACTION_FAILED,
                    message = "No execution result",
                )
        }
    }

    private class FakeTime : ReplayTime {
        var now = 1_000L
        val sleeps = mutableListOf<Long>()

        override fun nowMs(): Long = now

        override fun sleep(ms: Long) {
            sleeps += ms
            now += ms
        }
    }
}
