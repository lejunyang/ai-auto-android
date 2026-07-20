package dev.aiauto.android.automation.recording

/**
 * 功能用途：实现 RecordingStateMachine 对应的语义录制、脚本持久化或确定性回放能力。
 */

import java.time.Instant
import java.util.UUID

import dev.aiauto.android.accessibility.model.GlobalAction
import kotlinx.serialization.json.JsonObject

class RecordingStateMachine(
    private val eventMapper: RecordingEventMapper = RecordingEventMapper(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private var draft = RecordingDraft()
    private var environment: ScriptEnvironment? = null
    private var lastAcceptedKey: String? = null
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE

    @Synchronized
    fun current(): RecordingDraft = draft

    @Synchronized
    fun start(
        name: String,
        targetPackages: Set<String>,
        environment: ScriptEnvironment = ScriptEnvironment(),
    ): RecordingDraft {
        check(draft.status == RecordingStatus.IDLE) {
            "A recording session is already active"
        }
        require(targetPackages.isNotEmpty()) {
            "At least one target package is required"
        }
        require(targetPackages.size <= MAX_TARGET_PACKAGES)
        val now = clockMs()
        this.environment = environment
        lastAcceptedKey = null
        lastAcceptedAtMs = Long.MIN_VALUE
        draft = RecordingDraft(
            status = RecordingStatus.RECORDING,
            name = name.take(MAX_SCRIPT_NAME_LENGTH),
            targetPackages = targetPackages,
            startedAtMs = now,
        )
        return draft
    }

    @Synchronized
    fun pause(): RecordingDraft {
        check(draft.status == RecordingStatus.RECORDING) {
            "Only an active recording can be paused"
        }
        draft = draft.copy(status = RecordingStatus.PAUSED)
        return draft
    }

    @Synchronized
    fun resume(): RecordingDraft {
        check(draft.status == RecordingStatus.PAUSED) {
            "Only a paused recording can be resumed"
        }
        draft = draft.copy(status = RecordingStatus.RECORDING)
        return draft
    }

    @Synchronized
    fun accept(event: RecordingEvent): RecordingDraft {
        if (
            draft.status != RecordingStatus.RECORDING ||
            event.packageName !in draft.targetPackages ||
            event.eventTimeMs < (draft.startedAtMs ?: Long.MAX_VALUE) ||
            draft.steps.size >= MAX_STEPS
        ) {
            return draft
        }
        val mapped = eventMapper.map(event) ?: return draft
        return append(mapped, event.eventTimeMs)
    }

    @Synchronized
    fun recordGlobalAction(action: GlobalAction): RecordingDraft {
        if (draft.status != RecordingStatus.RECORDING || draft.steps.size >= MAX_STEPS) {
            return draft
        }
        val type = when (action) {
            GlobalAction.BACK -> "ui.back"
            GlobalAction.HOME -> "ui.home"
            GlobalAction.RECENTS -> "ui.recents"
        }
        val now = clockMs()
        return append(
            mapped = MappedRecordingAction(
                action = RecordedAction(
                    type = type,
                    params = JsonObject(emptyMap()),
                ),
            ),
            eventTimeMs = now,
        )
    }

    @Synchronized
    fun finish(name: String = draft.name): AutomationScript {
        check(draft.status != RecordingStatus.IDLE) {
            "No recording session is active"
        }
        require(draft.steps.isNotEmpty()) {
            "A script must contain at least one recorded step"
        }
        val finalName = name.trim().take(MAX_SCRIPT_NAME_LENGTH)
        require(finalName.isNotEmpty()) {
            "A script name is required"
        }
        val startedAt = requireNotNull(draft.startedAtMs)
        val script = AutomationScript(
            id = idFactory(),
            name = finalName,
            targetPackages = draft.targetPackages.sorted(),
            createdAt = Instant.ofEpochMilli(startedAt).toString(),
            environment = environment,
            variables = draft.variables,
            steps = draft.steps,
        )
        reset()
        return script
    }

    @Synchronized
    fun cancel(): RecordingDraft {
        reset()
        return draft
    }

    private fun shouldCoalesceText(step: RecordedStep, eventTimeMs: Long): Boolean {
        if (step.action.type != "ui.setText" || draft.steps.isEmpty()) {
            return false
        }
        val previous = draft.steps.last()
        return previous.action.type == "ui.setText" &&
            previous.action.params["target"] == step.action.params["target"] &&
            eventTimeMs - lastAcceptedAtMs <= TEXT_COALESCE_WINDOW_MS
    }

    private fun append(
        mapped: MappedRecordingAction,
        eventTimeMs: Long,
    ): RecordingDraft {
        val key = "${mapped.action.type}:${mapped.action.params}"
        if (key == lastAcceptedKey && eventTimeMs - lastAcceptedAtMs <= DEDUP_WINDOW_MS) {
            return draft
        }

        val nextStep = RecordedStep(
            id = idFactory(),
            recordedAtMs = eventTimeMs - requireNotNull(draft.startedAtMs),
            action = mapped.action,
            waitAfter = mapped.action.defaultWaitAfter(),
        )
        val nextSteps = if (shouldCoalesceText(nextStep, eventTimeMs)) {
            draft.steps.dropLast(1) + nextStep
        } else {
            draft.steps + nextStep
        }
        val variables = mapped.secretVariable?.let { variable ->
            (draft.variables + variable).distinctBy(ScriptVariable::name)
        } ?: draft.variables
        draft = draft.copy(steps = nextSteps, variables = variables)
        lastAcceptedKey = key
        lastAcceptedAtMs = eventTimeMs
        return draft
    }

    private fun RecordedAction.defaultWaitAfter(): RecordedPredicate? =
        if (type == "ui.wait") null else stableWait()

    private fun stableWait(): RecordedPredicate = RecordedPredicate(
        kind = "uiStable",
        operator = "stable",
        stableDurationMs = DEFAULT_STABLE_DURATION_MS,
        timeoutMs = DEFAULT_WAIT_TIMEOUT_MS,
    )

    private fun reset() {
        draft = RecordingDraft()
        environment = null
        lastAcceptedKey = null
        lastAcceptedAtMs = Long.MIN_VALUE
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 350L
        const val TEXT_COALESCE_WINDOW_MS = 1_000L
        const val DEFAULT_STABLE_DURATION_MS = 300L
        const val DEFAULT_WAIT_TIMEOUT_MS = 5_000L
        const val MAX_STEPS = 10_000
        const val MAX_TARGET_PACKAGES = 32
        const val MAX_SCRIPT_NAME_LENGTH = 128
    }
}
