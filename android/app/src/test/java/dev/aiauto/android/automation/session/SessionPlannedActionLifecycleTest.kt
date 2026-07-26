package dev.aiauto.android.automation.session

/**
 * 测试用途：验证 session-only action context 在执行前后观察间存活，并在全部路径关闭。
 */

import dev.aiauto.android.provider.ProviderAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPlannedActionLifecycleTest {
    @Test
    fun `engine validates executes verifies and closes planned visual context BitsUT`() = runTest {
        val events = mutableListOf<String>()
        val context = object : SessionActionContext {
            override suspend fun validateBefore(
                observation: SessionObservation,
                targetPackage: String,
            ) {
                events += "pre:${observation.activePackage}:$targetPackage"
            }

            override suspend fun execute(targetPackage: String): SessionExecutionResult {
                events += "execute:$targetPackage"
                return SessionExecutionResult("visual action committed")
            }

            override suspend fun verifyAfter(
                observation: SessionObservation,
                targetPackage: String,
            ) {
                events += "post:${observation.activePackage}:$targetPackage"
            }

            override fun close() {
                events += "close"
            }
        }
        var planCalls = 0
        val engine = AutomationSessionEngine(
            observer = SessionObserver {
                SessionObservation(TARGET_PACKAGE, "state-${events.size}")
            },
            planner = SessionPlanner {
                planCalls += 1
                if (planCalls == 1) {
                    SessionPlannedAction(
                        ProviderAction("ui.back", buildJsonObject {}),
                        context,
                    )
                } else {
                    SessionPlannedAction(
                        ProviderAction("task.finish", buildJsonObject {}),
                    )
                }
            },
            executor = SessionExecutor { _, _ -> SessionExecutionResult("finished") },
            riskPolicy = AutomationRiskPolicy(setOf(TARGET_PACKAGE)),
            limits = SessionLimits(maxSteps = 2, stepTimeoutMs = 1_000, totalTimeoutMs = 5_000),
        )

        engine.start(SessionRequest("Inspect", TARGET_PACKAGE))

        assertEquals(
            listOf(
                "pre:$TARGET_PACKAGE:$TARGET_PACKAGE",
                "execute:$TARGET_PACKAGE",
                "post:$TARGET_PACKAGE:$TARGET_PACKAGE",
                "close",
            ),
            events,
        )
        assertTrue(engine.state.value.phase == SessionPhase.Completed)
    }

    @Test
    fun `risk block and preflight failure close context before action commit BitsUT`() = runTest {
        val cases = listOf(
            ProviderAction("shell.exec", buildJsonObject {}) to false,
            ProviderAction("ui.back", buildJsonObject {}) to true,
        )
        for ((action, failPreflight) in cases) {
            var closed = 0
            var executions = 0
            val context = fakeContext(
                failPreflight = failPreflight,
                onExecute = { executions += 1 },
                onClose = { closed += 1 },
            )
            val engine = engine(SessionPlannedAction(action, context))

            engine.start(SessionRequest("Inspect", TARGET_PACKAGE))

            assertEquals(SessionPhase.Failed, engine.state.value.phase)
            assertEquals(0, executions)
            assertEquals(1, closed)
        }
    }

    @Test
    fun `confirmation rejection and stop close context without execution BitsUT`() = runTest {
        for (stop in listOf(false, true)) {
            var closed = 0
            var executions = 0
            val planned = SessionPlannedAction(
                riskyClick(),
                fakeContext(
                    onExecute = { executions += 1 },
                    onClose = { closed += 1 },
                ),
            )
            val engine = engine(planned)
            val job = launch { engine.start(SessionRequest("Inspect", TARGET_PACKAGE)) }
            runCurrent()

            if (stop) {
                engine.stop()
            } else {
                val confirmationId = requireNotNull(
                    engine.state.value.pendingConfirmation,
                ).id
                engine.confirmPendingAction(confirmationId, approved = false)
            }
            advanceUntilIdle()
            job.join()

            assertEquals(0, executions)
            assertEquals(1, closed)
        }
    }

    @Test
    fun `post verification failure closes once after one context execution BitsUT`() = runTest {
        var closed = 0
        var executions = 0
        val context = fakeContext(
            failVerification = true,
            onExecute = { executions += 1 },
            onClose = { closed += 1 },
        )
        val engine = engine(
            SessionPlannedAction(
                ProviderAction("ui.back", buildJsonObject {}),
                context,
            ),
        )

        engine.start(SessionRequest("Inspect", TARGET_PACKAGE))

        assertEquals(SessionPhase.Failed, engine.state.value.phase)
        assertEquals(1, executions)
        assertEquals(1, closed)
    }

    @Test
    fun `context close exception does not replace verified execution state BitsUT`() = runTest {
        val context = fakeContext(
            onClose = { throw IllegalStateException("private cleanup failure") },
        )
        var planCalls = 0
        val engine = AutomationSessionEngine(
            observer = SessionObserver {
                SessionObservation(TARGET_PACKAGE, "state")
            },
            planner = SessionPlanner {
                planCalls += 1
                if (planCalls == 1) {
                    SessionPlannedAction(
                        ProviderAction("ui.back", buildJsonObject {}),
                        context,
                    )
                } else {
                    SessionPlannedAction(
                        ProviderAction("task.finish", buildJsonObject {}),
                    )
                }
            },
            executor = SessionExecutor { _, _ -> error("generic executor must not run") },
            riskPolicy = AutomationRiskPolicy(setOf(TARGET_PACKAGE)),
            limits = SessionLimits(maxSteps = 2, stepTimeoutMs = 1_000, totalTimeoutMs = 5_000),
        )

        engine.start(SessionRequest("Inspect", TARGET_PACKAGE))

        assertEquals(SessionPhase.Completed, engine.state.value.phase)
        assertEquals(null, engine.state.value.failureMessage)
    }

    private fun engine(planned: SessionPlannedAction) = AutomationSessionEngine(
        observer = SessionObserver {
            SessionObservation(TARGET_PACKAGE, "state")
        },
        planner = SessionPlanner { planned },
        executor = SessionExecutor { _, _ -> error("generic executor must not run") },
        riskPolicy = AutomationRiskPolicy(setOf(TARGET_PACKAGE)),
        limits = SessionLimits(maxSteps = 1, stepTimeoutMs = 1_000, totalTimeoutMs = 5_000),
    )

    private fun fakeContext(
        failPreflight: Boolean = false,
        failVerification: Boolean = false,
        onExecute: () -> Unit = {},
        onClose: () -> Unit = {},
    ) = object : SessionActionContext {
        override suspend fun validateBefore(
            observation: SessionObservation,
            targetPackage: String,
        ) {
            if (failPreflight) throw SessionFailureException("preflight failed")
        }

        override suspend fun execute(targetPackage: String): SessionExecutionResult {
            onExecute()
            return SessionExecutionResult("executed")
        }

        override suspend fun verifyAfter(
            observation: SessionObservation,
            targetPackage: String,
        ) {
            if (failVerification) throw SessionFailureException("after one commit")
        }

        override fun close() = onClose()
    }

    private fun riskyClick() = ProviderAction(
        "ui.click",
        buildJsonObject {
            put(
                "target",
                buildJsonObject {
                    put("packageName", TARGET_PACKAGE)
                    put(
                        "fingerprint",
                        buildJsonObject { put("text", "Delete item") },
                    )
                },
            )
        },
    )

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
