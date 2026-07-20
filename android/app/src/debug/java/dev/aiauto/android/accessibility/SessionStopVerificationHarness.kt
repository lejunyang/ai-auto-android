package dev.aiauto.android.accessibility

/**
 * 调试用途：提供 SessionStopVerificationHarness 的设备验收入口，仅用于 debug 变体且不进入 release 制品。
 */

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import dev.aiauto.android.automation.session.AutomationRiskPolicy
import dev.aiauto.android.automation.session.AutomationSessionEngine
import dev.aiauto.android.automation.session.AutomationSessionRuntime
import dev.aiauto.android.automation.session.SessionExecutionResult
import dev.aiauto.android.automation.session.SessionExecutor
import dev.aiauto.android.automation.session.SessionLimits
import dev.aiauto.android.automation.session.SessionObservation
import dev.aiauto.android.automation.session.SessionObserver
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.automation.session.SessionPlanner
import dev.aiauto.android.automation.session.SessionRequest
import dev.aiauto.android.provider.ProviderAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class SessionStopVerificationHarness(
    private val targetPackage: String,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val plannerGate = CompletableDeferred<ProviderAction>()
    private val plannerEntered = CompletableDeferred<Unit>()
    private val executorCalls = AtomicInteger()
    private val automationClickVerified = AtomicBoolean()
    private val engine = AutomationSessionEngine(
        observer = SessionObserver {
            SessionObservation(
                activePackage = targetPackage,
                uiSummary = "Debug-only session stop verification surface",
            )
        },
        planner = SessionPlanner {
            plannerEntered.complete(Unit)
            plannerGate.await()
        },
        executor = SessionExecutor { _, _ ->
            executorCalls.incrementAndGet()
            SessionExecutionResult("Unexpected debug executor invocation")
        },
        riskPolicy = AutomationRiskPolicy(setOf(targetPackage)),
        limits = SessionLimits(
            maxSteps = 2,
            stepTimeoutMs = MANUAL_SESSION_TIMEOUT_MS,
            totalTimeoutMs = MANUAL_SESSION_TIMEOUT_MS,
        ),
    )
    private var sessionJob: Job? = null

    suspend fun start(): Boolean {
        check(sessionJob == null) { "The verification session has already started." }
        sessionJob = scope.launch {
            engine.start(
                SessionRequest(
                    task = "Verify user touch emergency stop",
                    targetPackage = targetPackage,
                ),
            )
        }
        return withTimeoutOrNull(ASSERTION_TIMEOUT_MS) {
            plannerEntered.await()
            engine.state.first { it.phase == SessionPhase.Planning }
            true
        } == true
    }

    fun recordAutomationClick(performed: Boolean, userTouchNotifications: Int): Boolean {
        val passed = performed &&
            userTouchNotifications == 0 &&
            engine.state.value.phase == SessionPhase.Planning &&
            AutomationSessionRuntime.activeSessionId(targetPackage) != null
        automationClickVerified.set(passed)
        return passed
    }

    suspend fun verifyStopped(): String {
        val stopped = withTimeoutOrNull(ASSERTION_TIMEOUT_MS) {
            engine.state.first { it.phase == SessionPhase.Stopped }
            true
        } == true
        withTimeoutOrNull(ASSERTION_TIMEOUT_MS) {
            sessionJob?.join()
        }

        val state = engine.state.value
        val calls = executorCalls.get()
        val hasUserTouchAudit = state.audit.any {
            it.phase == SessionPhase.Stopped &&
                it.message == USER_TOUCH_STOP_MESSAGE
        }
        val runtimeCleared = AutomationSessionRuntime.activeSessionId(targetPackage) == null
        return if (
            stopped &&
            automationClickVerified.get() &&
            state.phase == SessionPhase.Stopped &&
            hasUserTouchAudit &&
            calls == 0 &&
            runtimeCleared
        ) {
            "SESSION_STOP_PASS phase=Stopped executorCalls=0"
        } else {
            "SESSION_STOP_FAIL phase=${state.phase} executorCalls=$calls " +
                "automationClick=${automationClickVerified.get()} " +
                "audit=$hasUserTouchAudit runtimeCleared=$runtimeCleared"
        }
    }

    override fun close() {
        engine.stop()
        plannerGate.cancel()
        plannerEntered.cancel()
        scope.cancel()
    }

    internal companion object {
        const val MANUAL_SESSION_TIMEOUT_MS = 120_000L
        const val ASSERTION_TIMEOUT_MS = 10_000L
        const val USER_TOUCH_STOP_MESSAGE =
            "Session stopped because the user touched the target app."
    }
}
