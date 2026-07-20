package dev.aiauto.android.automation.session

// 功能用途：实现 SessionModels 对应的受控 AI 自动化会话、风险判断与生命周期管理。

import dev.aiauto.android.provider.ProviderAction
import kotlinx.coroutines.CancellationException

enum class SessionPhase {
    Idle,
    Observing,
    Planning,
    AwaitingConfirmation,
    Executing,
    Verifying,
    Paused,
    Completed,
    Failed,
    Stopped,
}

data class SessionLimits(
    val maxSteps: Int = 30,
    val stepTimeoutMs: Long = 20_000,
    val totalTimeoutMs: Long = 5 * 60_000,
    val repeatedActionLimit: Int = 3,
) {
    init {
        require(maxSteps in 1..100)
        require(stepTimeoutMs in 100..300_000)
        require(totalTimeoutMs >= stepTimeoutMs)
        require(repeatedActionLimit in 2..10)
    }
}

data class SessionRequest(
    val task: String,
    val targetPackage: String,
    val screenshotsAllowed: Boolean = false,
)

data class SessionObservation(
    val activePackage: String,
    val uiSummary: String,
)

data class SessionPlanRequest(
    val task: String,
    val targetPackage: String,
    val observation: SessionObservation,
    val previousActionSummary: String?,
    val screenshotsAllowed: Boolean = false,
)

data class SessionExecutionResult(
    val summary: String,
)

data class PendingConfirmation(
    val id: Int,
    val actionType: String,
    val reason: String,
)

data class SessionAuditEntry(
    val sequence: Int,
    val phase: SessionPhase,
    val message: String,
)

data class AutomationSessionState(
    val phase: SessionPhase = SessionPhase.Idle,
    val task: String = "",
    val targetPackage: String = "",
    val step: Int = 0,
    val currentActionType: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val observationSummary: String? = null,
    val failureMessage: String? = null,
    val completionSummary: String? = null,
    val audit: List<SessionAuditEntry> = emptyList(),
) {
    val isRunning: Boolean
        get() = phase in RUNNING_PHASES

    val canPause: Boolean
        get() = phase in setOf(
            SessionPhase.Observing,
            SessionPhase.Planning,
            SessionPhase.Executing,
            SessionPhase.Verifying,
        )

    val canResume: Boolean
        get() = phase == SessionPhase.Paused

    private companion object {
        val RUNNING_PHASES = setOf(
            SessionPhase.Observing,
            SessionPhase.Planning,
            SessionPhase.AwaitingConfirmation,
            SessionPhase.Executing,
            SessionPhase.Verifying,
            SessionPhase.Paused,
        )
    }
}

sealed interface RiskDecision {
    data object Allow : RiskDecision

    data class RequireConfirmation(val reason: String) : RiskDecision

    data class Block(val reason: String) : RiskDecision
}

sealed interface SessionExecutionDecision {
    data class Execute(val action: ProviderAction) : SessionExecutionDecision

    data class Complete(val summary: String) : SessionExecutionDecision
}

class SessionFailureException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SessionStoppedException : CancellationException("Automation session was stopped")
