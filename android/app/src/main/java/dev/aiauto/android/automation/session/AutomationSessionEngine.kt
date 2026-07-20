package dev.aiauto.android.automation.session

// 功能用途：实现 AutomationSessionEngine 对应的受控 AI 自动化会话、风险判断与生命周期管理。

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import dev.aiauto.android.provider.ProviderAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class AutomationSessionEngine(
    private val observer: SessionObserver,
    private val planner: SessionPlanner,
    private val executor: SessionExecutor,
    private val riskPolicy: AutomationRiskPolicy,
    private val limits: SessionLimits = SessionLimits(),
) {
    private val mutableState = MutableStateFlow(AutomationSessionState())
    private val control = MutableStateFlow(Control.Running)
    private val confirmation = MutableStateFlow<ConfirmationResponse?>(null)
    private val stopRequested = AtomicBoolean(false)
    private val executionState = AtomicReference(ExecutionState.Idle)
    private var auditSequence = 0
    private var confirmationSequence = 0
    @Volatile
    private var sessionJob: Job? = null
    private var started = false

    val state: StateFlow<AutomationSessionState> = mutableState.asStateFlow()

    suspend fun start(request: SessionRequest) {
        check(!started) { "An AutomationSessionEngine instance can only run once." }
        started = true
        require(request.task.isNotBlank()) { "Task must not be blank." }
        require(PACKAGE_NAME.matches(request.targetPackage)) {
            "Target package has an invalid Android package name."
        }
        sessionJob = currentCoroutineContext()[Job]
        val runtimeRegistration = AutomationSessionRuntime.register(
            targetPackage = request.targetPackage,
            screenshotsAllowed = request.screenshotsAllowed,
            stopHandler = UserTouchStopHandler(::stopForUserTouch),
        )

        try {
            transition(
                phase = SessionPhase.Observing,
                message = "Session started for the authorized target package.",
                request = request,
            )
            when (val taskRisk = riskPolicy.evaluateTask(request.task, request.targetPackage)) {
                is RiskDecision.Block -> {
                    fail(taskRisk.reason)
                    return
                }

                is RiskDecision.Allow -> Unit
                is RiskDecision.RequireConfirmation -> error("Task-level confirmation is unsupported.")
            }

            withTimeout(limits.totalTimeoutMs) {
                runLoop(request)
            }
        } catch (error: TimeoutCancellationException) {
            if (!stopRequested.get()) {
                fail("The automation session exceeded its total time limit.")
            }
        } catch (error: SessionStoppedException) {
            ensureStopped()
        } catch (error: CancellationException) {
            if (stopRequested.get()) {
                ensureStopped()
            } else {
                throw error
            }
        } catch (error: Exception) {
            if (!stopRequested.get()) {
                fail(error.message ?: "The automation session failed.")
            }
        } finally {
            runtimeRegistration.close()
            sessionJob = null
            confirmation.value = null
        }
    }

    fun pause() {
        if (!mutableState.value.canPause || stopRequested.get()) {
            return
        }
        control.value = Control.Paused
        transition(
            phase = SessionPhase.Paused,
            message = "Session paused by the user.",
        )
    }

    fun resume() {
        if (!mutableState.value.canResume || stopRequested.get()) {
            return
        }
        control.value = Control.Running
        transition(
            phase = SessionPhase.Observing,
            message = "Session resumed by the user.",
        )
    }

    fun confirmPendingAction(confirmationId: Int, approved: Boolean) {
        val pending = mutableState.value.pendingConfirmation
            ?.takeIf {
                mutableState.value.phase == SessionPhase.AwaitingConfirmation &&
                    it.id == confirmationId
            }
            ?: return
        confirmation.compareAndSet(
            expect = null,
            update = ConfirmationResponse(id = pending.id, approved = approved),
        )
    }

    fun stop() {
        requestStop("Session stopped by the user.")
    }

    internal fun stopForUserTouch() {
        requestStop("Session stopped because the user touched the target app.")
    }

    private fun requestStop(message: String) {
        if (mutableState.value.phase in TERMINAL_PHASES) {
            return
        }
        // 先同步封闭执行门，再发布 Stopped 状态并取消协程，确保停止后不会提交新动作。
        stopRequested.set(true)
        executionState.set(ExecutionState.Stopped)
        control.value = Control.Stopped
        confirmation.value = null
        transition(
            phase = SessionPhase.Stopped,
            message = message,
            currentActionType = null,
            pendingConfirmation = null,
            observationSummary = null,
            force = true,
        )
        sessionJob?.cancel(SessionStoppedException())
    }

    private suspend fun runLoop(request: SessionRequest) {
        var previousActionSummary: String? = null
        var previousFingerprint: ActionFingerprint? = null
        var repeatedActions = 0

        for (step in 1..limits.maxSteps) {
            awaitRunning()
            transition(
                phase = SessionPhase.Observing,
                message = "Observing the authorized target.",
                step = step,
                currentActionType = null,
            )
            val observation = timedStep("Observation") {
                observer.observe(request.targetPackage)
            }
            ensureTargetPackage(observation, request.targetPackage)

            awaitRunning()
            transition(
                phase = SessionPhase.Planning,
                message = "Requesting one validated action from the Provider.",
                step = step,
                observationSummary = observation.uiSummary.take(MAX_VISIBLE_SUMMARY),
            )
            val action = timedStep("Planning") {
                planner.plan(
                    SessionPlanRequest(
                        task = request.task,
                        targetPackage = request.targetPackage,
                        observation = observation,
                        previousActionSummary = previousActionSummary,
                        screenshotsAllowed = request.screenshotsAllowed,
                    ),
                )
            }
            if (action.type == "task.finish") {
                complete(action.finishSummary())
                return
            }

            val fingerprint = ActionFingerprint(action.type, action.params)
            repeatedActions = if (fingerprint == previousFingerprint) {
                repeatedActions + 1
            } else {
                1
            }
            previousFingerprint = fingerprint
            if (repeatedActions >= limits.repeatedActionLimit) {
                fail("The Provider repeated the same action too many times.")
                return
            }

            when (val decision = riskPolicy.evaluateAction(action, request.targetPackage)) {
                is RiskDecision.Block -> {
                    fail(decision.reason)
                    return
                }

                is RiskDecision.RequireConfirmation -> {
                    confirmationSequence += 1
                    val pending = PendingConfirmation(
                        id = confirmationSequence,
                        actionType = action.type,
                        reason = decision.reason,
                    )
                    confirmation.value = null
                    transition(
                        phase = SessionPhase.AwaitingConfirmation,
                        message = "Waiting for explicit user confirmation.",
                        step = step,
                        currentActionType = action.type,
                        pendingConfirmation = pending,
                    )
                    val approved = confirmation.first { it?.id == pending.id }!!.approved
                    confirmation.value = null
                    checkNotStopped()
                    if (!approved) {
                        fail("The user rejected the pending action.")
                        return
                    }
                }

                is RiskDecision.Allow -> Unit
            }

            awaitRunning()
            ensureTargetPackage(
                observation = timedStep("Pre-execution target check") {
                    observer.observe(request.targetPackage)
                },
                expectedPackage = request.targetPackage,
            )
            transition(
                phase = SessionPhase.Executing,
                message = "Executing one locally approved action.",
                step = step,
                currentActionType = action.type,
                pendingConfirmation = null,
            )
            checkNotStopped()
            val execution = executeApprovedAction(action, request.targetPackage)

            awaitRunning()
            transition(
                phase = SessionPhase.Verifying,
                message = "Re-observing the target after execution.",
                step = step,
                currentActionType = action.type,
            )
            val verified = timedStep("Verification") {
                observer.observe(request.targetPackage)
            }
            ensureTargetPackage(verified, request.targetPackage)
            previousActionSummary = execution.summary.take(MAX_ACTION_SUMMARY)
        }
        fail("The automation session reached its maximum step count.")
    }

    private suspend fun awaitRunning() {
        checkNotStopped()
        control.first { it != Control.Paused }
        checkNotStopped()
    }

    private suspend fun <T> timedStep(name: String, block: suspend () -> T): T {
        val result = withTimeoutOrNull(limits.stepTimeoutMs) {
            TimedStepResult(block())
        }
        return result?.value
            ?: throw SessionFailureException("$name exceeded the per-step time limit.")
    }

    private suspend fun executeApprovedAction(
        action: ProviderAction,
        targetPackage: String,
    ): SessionExecutionResult {
        checkNotStopped()
        // 原子执行门与停止路径共享，消除“已停止但动作刚开始提交”的竞态。
        if (!executionState.compareAndSet(ExecutionState.Idle, ExecutionState.Executing)) {
            throw SessionStoppedException()
        }
        return try {
            checkNotStopped()
            timedStep("Execution") {
                executor.execute(action, targetPackage)
            }
        } finally {
            executionState.compareAndSet(ExecutionState.Executing, ExecutionState.Idle)
        }
    }

    private fun ensureTargetPackage(
        observation: SessionObservation,
        expectedPackage: String,
    ) {
        if (observation.activePackage != expectedPackage) {
            throw SessionFailureException(
                "The active package changed outside the authorized target.",
            )
        }
    }

    private fun checkNotStopped() {
        if (stopRequested.get()) {
            throw SessionStoppedException()
        }
    }

    private fun complete(summary: String) {
        transition(
            phase = SessionPhase.Completed,
            message = "Provider marked the task as complete.",
            completionSummary = summary.take(MAX_ACTION_SUMMARY),
            currentActionType = "task.finish",
            pendingConfirmation = null,
        )
    }

    private fun fail(message: String) {
        transition(
            phase = SessionPhase.Failed,
            message = "Session failed.",
            failureMessage = message.take(MAX_FAILURE_MESSAGE),
            pendingConfirmation = null,
        )
    }

    private fun ensureStopped() {
        if (mutableState.value.phase != SessionPhase.Stopped) {
            transition(
                phase = SessionPhase.Stopped,
                message = "Session stopped.",
                force = true,
            )
        }
    }

    private fun transition(
        phase: SessionPhase,
        message: String,
        request: SessionRequest? = null,
        step: Int? = null,
        currentActionType: String? = mutableState.value.currentActionType,
        pendingConfirmation: PendingConfirmation? = mutableState.value.pendingConfirmation,
        observationSummary: String? = mutableState.value.observationSummary,
        failureMessage: String? = mutableState.value.failureMessage,
        completionSummary: String? = mutableState.value.completionSummary,
        force: Boolean = false,
    ) {
        if (stopRequested.get() && phase != SessionPhase.Stopped && !force) {
            return
        }
        auditSequence += 1
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = phase,
            task = request?.task ?: current.task,
            targetPackage = request?.targetPackage ?: current.targetPackage,
            step = step ?: current.step,
            currentActionType = currentActionType,
            pendingConfirmation = pendingConfirmation,
            observationSummary = observationSummary,
            failureMessage = failureMessage,
            completionSummary = completionSummary,
            audit = (current.audit + SessionAuditEntry(auditSequence, phase, message))
                .takeLast(MAX_AUDIT_ENTRIES),
        )
    }

    private fun ProviderAction.finishSummary(): String =
        params["summary"]?.jsonPrimitive?.contentOrNull ?: "Task completed."

    private enum class Control {
        Running,
        Paused,
        Stopped,
    }

    private enum class ExecutionState {
        Idle,
        Executing,
        Stopped,
    }

    private data class ActionFingerprint(
        val type: String,
        val params: JsonObject,
    )

    private data class ConfirmationResponse(
        val id: Int,
        val approved: Boolean,
    )

    private data class TimedStepResult<T>(
        val value: T,
    )

    private companion object {
        val TERMINAL_PHASES = setOf(
            SessionPhase.Completed,
            SessionPhase.Failed,
            SessionPhase.Stopped,
        )
        val PACKAGE_NAME =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        const val MAX_VISIBLE_SUMMARY = 2_000
        const val MAX_ACTION_SUMMARY = 500
        const val MAX_FAILURE_MESSAGE = 1_000
        const val MAX_AUDIT_ENTRIES = 100
    }
}
