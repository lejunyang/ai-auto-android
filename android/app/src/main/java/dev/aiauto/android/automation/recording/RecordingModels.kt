package dev.aiauto.android.automation.recording

// 功能用途：实现 RecordingModels 对应的语义录制、脚本持久化或确定性回放能力。

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val RECORDING_SCHEMA_VERSION = "1.0"

@Serializable
data class AutomationScript(
    val id: String,
    val schemaVersion: String = RECORDING_SCHEMA_VERSION,
    val name: String,
    val targetPackages: List<String>,
    val createdAt: String,
    val requirements: ScriptRequirements = ScriptRequirements(),
    val environment: ScriptEnvironment? = null,
    val variables: List<ScriptVariable> = emptyList(),
    val steps: List<RecordedStep>,
)

@Serializable
data class ScriptRequirements(
    val minApiLevel: Int = 30,
    val capabilities: List<String> = listOf(
        "accessibility.snapshot",
        "accessibility.action",
    ),
)

@Serializable
data class ScriptEnvironment(
    val apiLevel: Int? = null,
    val logicalWidth: Int? = null,
    val logicalHeight: Int? = null,
    val densityDpi: Int? = null,
    val rotation: Int? = null,
    val locale: String? = null,
    val fontScale: Float? = null,
    val appVersion: String? = null,
)

@Serializable
data class ScriptVariable(
    val name: String,
    val type: String,
    val scope: String = "run",
    val sensitive: Boolean,
)

@Serializable
data class RecordedStep(
    val id: String,
    val recordedAtMs: Long,
    val action: RecordedAction,
    val waitAfter: RecordedPredicate? = null,
    val retry: RetryPolicy = RetryPolicy(),
    val failurePolicy: String = "stop",
)

@Serializable
data class RecordedAction(
    val type: String,
    val params: JsonObject,
)

@Serializable
data class RecordedPredicate(
    val kind: String,
    val operator: String,
    val target: JsonObject? = null,
    val expected: JsonElement? = null,
    val stableDurationMs: Long? = null,
    val timeoutMs: Long = 5_000,
)

@Serializable
data class RetryPolicy(
    val maxAttempts: Int = 2,
    val backoffMs: Long = 250,
) {
    init {
        require(maxAttempts in 1..5)
        require(backoffMs in 0..30_000)
    }
}

data class AutomationScriptSummary(
    val id: String,
    val name: String,
    val targetPackages: List<String>,
    val createdAt: String,
    val stepCount: Int,
    val requirements: ScriptRequirements,
)

enum class RecordingStatus {
    IDLE,
    RECORDING,
    PAUSED,
}

data class RecordingDraft(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val name: String = "",
    val targetPackages: Set<String> = emptySet(),
    val startedAtMs: Long? = null,
    val steps: List<RecordedStep> = emptyList(),
    val variables: List<ScriptVariable> = emptyList(),
    val errorMessage: String? = null,
)

enum class ReplayStepStatus {
    SUCCEEDED,
    FAILED,
    SKIPPED,
}

data class ReplayStepResult(
    val stepId: String,
    val status: ReplayStepStatus,
    val attempts: Int,
    val route: String? = null,
    val matchScore: Double? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

data class ReplayReport(
    val scriptId: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val succeeded: Boolean,
    val requiresIntervention: Boolean,
    val steps: List<ReplayStepResult>,
)
