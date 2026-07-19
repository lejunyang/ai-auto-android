package dev.aiauto.android.automation.session

import dev.aiauto.android.provider.ProviderAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationSessionEngineTest {
    @Test
    fun `start completes after one action and post execution observation BitsUT`() = runTest {
        val observer = FakeObserver()
        val planner = QueuePlanner(
            mutableListOf(
                action("ui.back"),
                finishAction("done"),
            ),
        )
        val executor = FakeExecutor()
        val engine = engine(observer, planner, executor)

        engine.start(request())

        assertEquals(SessionPhase.Completed, engine.state.value.phase)
        assertEquals("done", engine.state.value.completionSummary)
        assertEquals(4, observer.calls)
        assertEquals(listOf("ui.back"), executor.actionTypes)
        assertTrue(engine.state.value.audit.none { it.message.contains("params") })
    }

    @Test
    fun `start blocks unsupported provider action before execution BitsUT`() = runTest {
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(mutableListOf(action("shell.exec"))),
            executor = executor,
        )

        engine.start(request())

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("unsupported"))
        assertTrue(executor.actionTypes.isEmpty())
    }

    @Test
    fun `start waits for explicit confirmation before send action BitsUT`() = runTest {
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(
                mutableListOf(
                    semanticClick("Send message"),
                    finishAction("sent"),
                ),
            ),
            executor = executor,
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        assertEquals(SessionPhase.AwaitingConfirmation, engine.state.value.phase)
        assertTrue(executor.actionTypes.isEmpty())

        engine.confirmPendingAction(engine.pendingConfirmationId(), approved = true)
        advanceUntilIdle()

        assertEquals(SessionPhase.Completed, engine.state.value.phase)
        assertEquals(listOf("ui.click"), executor.actionTypes)
        job.join()
    }

    @Test
    fun `start stops when user rejects pending action BitsUT`() = runTest {
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(mutableListOf(semanticClick("Delete item"))),
            executor = executor,
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        engine.confirmPendingAction(engine.pendingConfirmationId(), approved = false)
        advanceUntilIdle()

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("rejected"))
        assertTrue(executor.actionTypes.isEmpty())
        job.join()
    }

    @Test
    fun `duplicate confirmation cannot approve the next risky action BitsUT`() = runTest {
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(
                mutableListOf(
                    semanticClick("Send message"),
                    semanticClick("Delete item"),
                    finishAction("done"),
                ),
            ),
            executor = executor,
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        val firstConfirmationId = engine.pendingConfirmationId()
        engine.confirmPendingAction(firstConfirmationId, approved = true)
        engine.confirmPendingAction(firstConfirmationId, approved = true)
        runCurrent()

        assertEquals(SessionPhase.AwaitingConfirmation, engine.state.value.phase)
        assertEquals(2, engine.state.value.step)
        assertEquals(listOf("ui.click"), executor.actionTypes)

        engine.confirmPendingAction(firstConfirmationId, approved = true)
        runCurrent()
        assertEquals(SessionPhase.AwaitingConfirmation, engine.state.value.phase)

        engine.confirmPendingAction(engine.pendingConfirmationId(), approved = true)
        advanceUntilIdle()

        assertEquals(SessionPhase.Completed, engine.state.value.phase)
        assertEquals(listOf("ui.click", "ui.click"), executor.actionTypes)
        job.join()
    }

    @Test
    fun `start blocks payment task before observation planning or execution BitsUT`() = runTest {
        val observer = FakeObserver()
        val planner = QueuePlanner(mutableListOf(finishAction("unexpected")))
        val executor = FakeExecutor()
        val engine = engine(observer, planner, executor)

        engine.start(request(task = "Pay the outstanding invoice"))

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertEquals(0, observer.calls)
        assertEquals(0, planner.calls)
        assertTrue(executor.actionTypes.isEmpty())
    }

    @Test
    fun `start fails when observer reports a different active package BitsUT`() = runTest {
        val observer = FakeObserver(activePackage = "com.other.app")
        val planner = QueuePlanner(mutableListOf(finishAction("unexpected")))
        val engine = engine(observer, planner, FakeExecutor())

        engine.start(request())

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("active package"))
        assertEquals(0, planner.calls)
    }

    @Test
    fun `start rechecks target package immediately before execution BitsUT`() = runTest {
        var observations = 0
        val observer = SessionObserver {
            observations += 1
            SessionObservation(
                activePackage = if (observations == 1) TARGET_PACKAGE else "com.other.app",
                uiSummary = "package=$TARGET_PACKAGE",
            )
        }
        val executor = FakeExecutor()
        val engine = engine(
            observer = observer,
            planner = QueuePlanner(mutableListOf(action("ui.back"))),
            executor = executor,
        )

        engine.start(request())

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("active package"))
        assertTrue(executor.actionTypes.isEmpty())
    }

    @Test
    fun `start stops repeated actions before another execution BitsUT`() = runTest {
        val repeated = action("ui.back")
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(
                mutableListOf(repeated, repeated, repeated, finishAction("unexpected")),
            ),
            executor = executor,
            limits = limits(repeatedActionLimit = 3),
        )

        engine.start(request())

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("repeated"))
        assertEquals(listOf("ui.back", "ui.back"), executor.actionTypes)
    }

    @Test
    fun `start detects structurally identical actions with different key order BitsUT`() = runTest {
        val first = ProviderAction(
            type = "ui.scroll",
            params = buildJsonObject {
                put("direction", "down")
                put("amount", 0.5)
            },
        )
        val second = ProviderAction(
            type = "ui.scroll",
            params = buildJsonObject {
                put("amount", 0.5)
                put("direction", "down")
            },
        )
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(mutableListOf(first, second)),
            executor = executor,
            limits = limits(repeatedActionLimit = 2),
        )

        engine.start(request())

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("repeated"))
        assertEquals(listOf("ui.scroll"), executor.actionTypes)
    }

    @Test
    fun `start converts per step timeout into failed state BitsUT`() = runTest {
        val planner = SessionPlanner {
            kotlinx.coroutines.delay(5_000)
            finishAction("late")
        }
        val engine = engine(
            observer = FakeObserver(),
            planner = planner,
            executor = FakeExecutor(),
            limits = limits(stepTimeoutMs = 500, totalTimeoutMs = 5_000),
        )

        engine.start(request())

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("per-step"))
    }

    @Test
    fun `start times out while awaiting confirmation BitsUT`() = runTest {
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(mutableListOf(semanticClick("Send message"))),
            executor = FakeExecutor(),
            limits = limits(stepTimeoutMs = 500, totalTimeoutMs = 1_000),
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        assertEquals(SessionPhase.AwaitingConfirmation, engine.state.value.phase)
        advanceUntilIdle()

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertTrue(engine.state.value.failureMessage!!.contains("total time"))
        job.join()
    }

    @Test
    fun `pause prevents execution until resume BitsUT`() = runTest {
        val plannedAction = CompletableDeferred<ProviderAction>()
        val planner = QueuePlanner(
            mutableListOf(action("ui.back"), finishAction("done")),
            firstActionGate = plannedAction,
        )
        val executor = FakeExecutor()
        val engine = engine(FakeObserver(), planner, executor)
        val job = launch { engine.start(request()) }

        runCurrent()
        engine.pause()
        plannedAction.complete(action("ui.back"))
        runCurrent()

        assertEquals(SessionPhase.Paused, engine.state.value.phase)
        assertTrue(executor.actionTypes.isEmpty())

        engine.resume()
        advanceUntilIdle()
        assertEquals(listOf("ui.back"), executor.actionTypes)
        assertEquals(SessionPhase.Completed, engine.state.value.phase)
        job.join()
    }

    @Test
    fun `stop changes state synchronously and prevents execution BitsUT`() = runTest {
        val planner = SessionPlanner {
            awaitCancellation()
        }
        val executor = FakeExecutor()
        val engine = engine(FakeObserver(), planner, executor)
        val job = launch { engine.start(request()) }

        runCurrent()
        engine.stop()

        assertEquals(SessionPhase.Stopped, engine.state.value.phase)
        assertFalse(engine.state.value.isRunning)
        assertEquals(null, engine.state.value.pendingConfirmation)
        assertEquals(null, engine.state.value.observationSummary)
        assertTrue(executor.actionTypes.isEmpty())
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `stop cancels confirmation wait and clears pending action BitsUT`() = runTest {
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(mutableListOf(semanticClick("Send message"))),
            executor = executor,
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        assertEquals(SessionPhase.AwaitingConfirmation, engine.state.value.phase)
        assertTrue(engine.state.value.pendingConfirmation != null)

        engine.stop()
        job.join()

        assertEquals(SessionPhase.Stopped, engine.state.value.phase)
        assertEquals(null, engine.state.value.pendingConfirmation)
        assertTrue(executor.actionTypes.isEmpty())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `target app touch stops active session before executor submission BitsUT`() = runTest {
        val plannedAction = CompletableDeferred<ProviderAction>()
        val executor = FakeExecutor()
        val engine = engine(
            observer = FakeObserver(),
            planner = QueuePlanner(
                actions = mutableListOf(action("ui.back")),
                firstActionGate = plannedAction,
            ),
            executor = executor,
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        assertEquals(SessionPhase.Planning, engine.state.value.phase)

        assertTrue(AutomationSessionRuntime.notifyUserTouch(TARGET_PACKAGE))
        plannedAction.complete(action("ui.back"))
        advanceUntilIdle()

        assertEquals(SessionPhase.Stopped, engine.state.value.phase)
        assertTrue(
            engine.state.value.audit.last().message.contains("touched the target app"),
        )
        assertTrue(executor.actionTypes.isEmpty())
        assertFalse(AutomationSessionRuntime.isScreenshotAuthorized(TARGET_PACKAGE))
        job.join()
    }

    @Test
    fun `external cancellation propagates without becoming failed state BitsUT`() = runTest {
        val engine = engine(
            observer = FakeObserver(),
            planner = SessionPlanner { awaitCancellation() },
            executor = FakeExecutor(),
        )
        val job = launch { engine.start(request()) }

        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(SessionPhase.Planning, engine.state.value.phase)
    }

    private fun engine(
        observer: SessionObserver,
        planner: SessionPlanner,
        executor: SessionExecutor,
        limits: SessionLimits = limits(),
    ) = AutomationSessionEngine(
        observer = observer,
        planner = planner,
        executor = executor,
        riskPolicy = AutomationRiskPolicy(setOf(TARGET_PACKAGE)),
        limits = limits,
    )

    private fun request(
        task: String = "Open the inbox",
        targetPackage: String = TARGET_PACKAGE,
    ) = SessionRequest(task = task, targetPackage = targetPackage)

    private fun AutomationSessionEngine.pendingConfirmationId(): Int =
        requireNotNull(state.value.pendingConfirmation).id

    private fun limits(
        stepTimeoutMs: Long = 1_000,
        totalTimeoutMs: Long = 10_000,
        repeatedActionLimit: Int = 3,
    ) = SessionLimits(
        maxSteps = 10,
        stepTimeoutMs = stepTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        repeatedActionLimit = repeatedActionLimit,
    )

    private fun action(type: String) = ProviderAction(type, buildJsonObject {})

    private fun finishAction(summary: String) = ProviderAction(
        type = "task.finish",
        params = buildJsonObject { put("summary", summary) },
    )

    private fun semanticClick(value: String) = ProviderAction(
        type = "ui.click",
        params = buildJsonObject {
            put(
                "target",
                buildJsonObject {
                    put("packageName", TARGET_PACKAGE)
                    put(
                        "fingerprint",
                        buildJsonObject {
                            put("text", JsonPrimitive(value))
                        },
                    )
                },
            )
        },
    )

    private class FakeObserver(
        private val activePackage: String = TARGET_PACKAGE,
    ) : SessionObserver {
        var calls = 0

        override suspend fun observe(targetPackage: String): SessionObservation {
            calls += 1
            return SessionObservation(
                activePackage = activePackage,
                uiSummary = "package=$activePackage\n- role=Button text=Continue",
            )
        }
    }

    private class QueuePlanner(
        private val actions: MutableList<ProviderAction>,
        private val firstActionGate: CompletableDeferred<ProviderAction>? = null,
    ) : SessionPlanner {
        var calls = 0

        override suspend fun plan(request: SessionPlanRequest): ProviderAction {
            calls += 1
            if (calls == 1 && firstActionGate != null) {
                firstActionGate.await()
            }
            return actions.removeFirst()
        }
    }

    private class FakeExecutor : SessionExecutor {
        val actionTypes = mutableListOf<String>()

        override suspend fun execute(
            action: ProviderAction,
            targetPackage: String,
        ): SessionExecutionResult {
            actionTypes += action.type
            return SessionExecutionResult("executed ${action.type}")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
