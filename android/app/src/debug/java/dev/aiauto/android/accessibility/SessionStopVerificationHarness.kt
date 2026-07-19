package dev.aiauto.android.accessibility

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject

internal class SessionStopVerificationHarness(
    private val targetPackage: String,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val plannerGate = CompletableDeferred<ProviderAction>()
    private val executorCalls = AtomicInteger()
    private val automationClickVerified = AtomicBoolean()
    private val engine = AutomationSessionEngine(
        observer = SessionObserver {
            SessionObservation(
                activePackage = targetPackage,
                uiSummary = "Debug-only session stop verification surface",
            )
        },
        planner = SessionPlanner { plannerGate.await() },
        executor = SessionExecutor { _, _ ->
            executorCalls.incrementAndGet()
            SessionExecutionResult("Unexpected debug executor invocation")
        },
        riskPolicy = AutomationRiskPolicy(setOf(targetPackage)),
        limits = SessionLimits(
            maxSteps = 2,
            stepTimeoutMs = SESSION_TIMEOUT_MS,
            totalTimeoutMs = SESSION_TIMEOUT_MS,
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
        return withTimeoutOrNull(SESSION_TIMEOUT_MS) {
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
        val stopped = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            engine.state.first { it.phase == SessionPhase.Stopped }
            true
        } == true
        plannerGate.complete(
            ProviderAction(
                type = "ui.back",
                params = buildJsonObject {},
            ),
        )
        withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            sessionJob?.join()
        }
        delay(POST_STOP_SETTLE_MS)

        val state = engine.state.value
        val calls = executorCalls.get()
        val hasUserTouchAudit = state.audit.any {
            it.message.contains("user touched the target app")
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
        scope.cancel()
    }

    private companion object {
        const val SESSION_TIMEOUT_MS = 10_000L
        const val POST_STOP_SETTLE_MS = 500L
    }
}
