package dev.aiauto.android.automation.recording

/**
 * 测试用途：验证 ReplayEngine 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualAction
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualReplayErrorCode
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualReplayResult
import dev.aiauto.android.accessibility.model.ScreenPoint
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
            snapshots = mutableListOf(success(node()), success(node())),
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

    @Test
    fun `ui wait condition timeout fails without dispatching an action BitsUT`() {
        val gateway = FakeGateway(
            snapshots = mutableListOf(
                success(node()),
                success(node()),
                success(node()),
            ),
        )
        val engine = engine(gateway)
        val action = RecordedAction(
            type = "ui.wait",
            params = buildJsonObject {
                put("kind", JsonPrimitive("package"))
                put("operator", JsonPrimitive("equals"))
                put("expected", JsonPrimitive("com.example.auth"))
                put("timeoutMs", JsonPrimitive(200))
            },
        )

        val report = engine.replay(script(action = action))

        assertFalse(report.succeeded)
        assertEquals("CONDITION_TIMEOUT", report.steps.single().errorCode)
        assertEquals(1, report.steps.single().attempts)
        assertEquals(listOf(100L, 100L), (engineTime as FakeTime).sleeps)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `snapshot failure does not satisfy a missing node condition BitsUT`() {
        val gateway = FakeGateway(
            snapshots = mutableListOf(
                AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.SNAPSHOT_FAILED,
                    message = "Snapshot failed",
                    retryable = true,
                ),
            ),
        )
        val engine = engine(gateway)
        val action = RecordedAction(
            type = "ui.wait",
            params = buildJsonObject {
                put("kind", JsonPrimitive("node"))
                put("operator", JsonPrimitive("notExists"))
                put("target", clickAction(node()).params.getValue("target"))
                put("timeoutMs", JsonPrimitive(0))
            },
        )

        val report = engine.replay(script(action = action))

        assertFalse(report.succeeded)
        assertEquals("SNAPSHOT_FAILED", report.steps.single().errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `multi target action rejects an active package outside the script BitsUT`() {
        val gateway = FakeGateway(
            snapshots = mutableListOf(success(node(packageName = "com.other"))),
        )
        val engine = engine(gateway)

        val report = engine.replay(
            script(
                action = RecordedAction("ui.back", JsonObject(emptyMap())),
                targetPackages = listOf("com.example", "com.example.auth"),
            ),
        )

        assertFalse(report.succeeded)
        assertEquals("PACKAGE_NOT_ALLOWED", report.steps.single().errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `multi target wait rejects an active package outside the script BitsUT`() {
        val gateway = FakeGateway(
            snapshots = mutableListOf(success(node(packageName = "com.other"))),
        )
        val engine = engine(gateway)
        val action = RecordedAction(
            type = "ui.wait",
            params = buildJsonObject {
                put("kind", JsonPrimitive("package"))
                put("operator", JsonPrimitive("equals"))
                put("expected", JsonPrimitive("com.example.auth"))
                put("timeoutMs", JsonPrimitive(500))
            },
        )

        val report = engine.replay(
            script(
                action = action,
                targetPackages = listOf("com.example", "com.example.auth"),
            ),
        )

        assertFalse(report.succeeded)
        assertEquals("PACKAGE_NOT_ALLOWED", report.steps.single().errorCode)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `visual provenance fails closed when explicit port is unavailable BitsUT`() {
        val gateway = FakeGateway()
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            time = FakeTime(),
        )

        val report = engine.replay(visualScript())

        assertFalse(report.succeeded)
        assertEquals(
            ExplicitVisualReplayErrorCode.VISUAL_REPLAY_UNAVAILABLE.name,
            report.steps.single().errorCode,
        )
        assertTrue(gateway.executed.isEmpty())
        assertTrue(gateway.snapshots.isEmpty())
    }

    @Test
    fun `visual provenance uses explicit port without semantic gateway BitsUT`() {
        val gateway = FakeGateway()
        var calls = 0
        val visualTime = FakeTime()
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            explicitVisualReplay = ExplicitVisualReplayPort {
                calls += 1
                ExplicitVisualReplayResult.Success(
                    action = ExplicitVisualAction.Tap(ScreenPoint(100, 200)),
                    execution = ActionExecution(route = ActionRoute.COORDINATE_GESTURE),
                )
            },
            time = visualTime,
        )

        val report = engine.replay(visualScript())

        assertTrue(report.succeeded)
        assertEquals(1, calls)
        assertEquals(ActionRoute.COORDINATE_GESTURE.name, report.steps.single().route)
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `post verification failure is not retried after one visual commit BitsUT`() {
        val gateway = FakeGateway()
        var calls = 0
        val visualTime = FakeTime()
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            explicitVisualReplay = ExplicitVisualReplayPort {
                calls += 1
                ExplicitVisualReplayResult.Failure(
                    code = ExplicitVisualReplayErrorCode.POST_ACTION_VERIFICATION_FAILED,
                    message = "verification failed",
                    actionCommitCount = 1,
                )
            },
            time = visualTime,
        )

        val report = engine.replay(
            visualScript(
                retry = RetryPolicy(maxAttempts = 5, backoffMs = 100),
            ),
        )

        assertFalse(report.succeeded)
        assertEquals(1, calls)
        assertEquals(1, report.steps.single().attempts)
        assertTrue(visualTime.sleeps.isEmpty())
        assertTrue(gateway.executed.isEmpty())
    }

    @Test
    fun `visual precondition failure prevents explicit port call BitsUT`() {
        val gateway = FakeGateway(
            snapshots = mutableListOf(success(node(packageName = "com.example"))),
        )
        var calls = 0
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            explicitVisualReplay = ExplicitVisualReplayPort {
                calls += 1
                error("visual port must not run")
            },
            time = FakeTime(),
        )
        val script = visualScript().let { source ->
            source.copy(
                steps = source.steps.map { step ->
                    step.copy(
                        waitBefore = RecordedPredicate(
                            kind = "package",
                            operator = "equals",
                            expected = JsonPrimitive("com.other"),
                            timeoutMs = 0,
                        ),
                    )
                },
            )
        }

        val report = engine.replay(script)

        assertFalse(report.succeeded)
        assertEquals("CONDITION_TIMEOUT", report.steps.single().errorCode)
        assertEquals(0, report.steps.single().attempts)
        assertEquals(0, calls)
    }

    @Test
    fun `visual postcondition timeout does not repeat committed action BitsUT`() {
        val gateway = FakeGateway(
            snapshots = mutableListOf(success(node(packageName = "com.example"))),
        )
        var calls = 0
        val engine = ReplayEngine(
            gateway = gateway,
            secretResolver = secretResolver(null),
            explicitVisualReplay = ExplicitVisualReplayPort {
                calls += 1
                ExplicitVisualReplayResult.Success(
                    action = ExplicitVisualAction.Tap(ScreenPoint(100, 200)),
                    execution = ActionExecution(route = ActionRoute.COORDINATE_GESTURE),
                )
            },
            time = FakeTime(),
        )
        val script = visualScript(
            retry = RetryPolicy(maxAttempts = 5, backoffMs = 100),
        ).let { source ->
            source.copy(
                steps = source.steps.map { step ->
                    step.copy(
                        waitAfter = RecordedPredicate(
                            kind = "package",
                            operator = "equals",
                            expected = JsonPrimitive("com.other"),
                            timeoutMs = 0,
                        ),
                    )
                },
            )
        }

        val report = engine.replay(script)

        assertFalse(report.succeeded)
        assertEquals("CONDITION_TIMEOUT", report.steps.single().errorCode)
        assertEquals(1, report.steps.single().attempts)
        assertEquals(1, calls)
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
        targetPackages: List<String> = listOf("com.example"),
    ) = AutomationScript(
        id = "script",
        name = "Replay",
        targetPackages = targetPackages,
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

    private fun visualScript(
        retry: RetryPolicy = RetryPolicy(maxAttempts = 1, backoffMs = 0),
    ) = AutomationScript(
        id = "visual-script",
        name = "Visual replay",
        targetPackages = listOf("com.example"),
        createdAt = "2026-07-18T00:00:00Z",
        environment = ScriptEnvironment(
            logicalWidth = 1_080,
            logicalHeight = 2_400,
            densityDpi = 420,
            rotation = 0,
        ),
        steps = listOf(
            RecordedStep(
                id = "visual-step",
                provenance = RecordingProvenance.VISUAL,
                action = RecordedAction(
                    type = "ui.tap",
                    params = buildJsonObject {
                        put("x", JsonPrimitive(540))
                        put("y", JsonPrimitive(1_200))
                    },
                ),
                visualTarget = VisualTarget(
                    normalizedPoint = NormalizedPoint(0.5, 0.5),
                    confidence = 0.95,
                    source = RecordingProvenance.VISUAL,
                    observationId = "123e4567-e89b-42d3-a456-426614174045",
                    imageSha256 = "a".repeat(64),
                ),
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
        packageName: String = "com.example",
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = packageName,
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
