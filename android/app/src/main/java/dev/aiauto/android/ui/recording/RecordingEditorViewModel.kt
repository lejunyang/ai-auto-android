package dev.aiauto.android.ui.recording

/**
 * 功能用途：适配 N40 不可变编辑事务为 Compose 状态，并管理原子表单、保存冲突和 dry-run。
 */

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RetryPolicy
import dev.aiauto.android.automation.recording.editor.CloseDecision
import dev.aiauto.android.automation.recording.editor.DeleteStep
import dev.aiauto.android.automation.recording.editor.DryRunReport
import dev.aiauto.android.automation.recording.editor.DryRunStepStatus
import dev.aiauto.android.automation.recording.editor.DuplicateStep
import dev.aiauto.android.automation.recording.editor.EditResult
import dev.aiauto.android.automation.recording.editor.EditStepAuthorizedVisualTarget
import dev.aiauto.android.automation.recording.editor.EditStepCoordinate
import dev.aiauto.android.automation.recording.editor.EditStepFailurePolicy
import dev.aiauto.android.automation.recording.editor.EditStepNotes
import dev.aiauto.android.automation.recording.editor.EditStepRetry
import dev.aiauto.android.automation.recording.editor.EditStepSelector
import dev.aiauto.android.automation.recording.editor.EditStepWait
import dev.aiauto.android.automation.recording.editor.EditorSavePort
import dev.aiauto.android.automation.recording.editor.EditorSaveResult
import dev.aiauto.android.automation.recording.editor.EditorPersistenceResult
import dev.aiauto.android.automation.recording.editor.RecordingScriptStoreSavePort
import dev.aiauto.android.automation.recording.editor.ReorderStep
import dev.aiauto.android.automation.recording.editor.ScriptDryRunEngine
import dev.aiauto.android.automation.recording.editor.ScriptEditorSession
import dev.aiauto.android.automation.recording.editor.SetStepEnabled
import dev.aiauto.android.automation.recording.editor.UpdateStep
import dev.aiauto.android.automation.recording.editor.copyAutomationScript
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class PredicateFormState(
    val enabled: Boolean = false,
    val kind: String = "node",
    val operator: String = "exists",
    val expected: String = "",
    val stableDurationMs: String = "",
    val timeoutMs: String = "5000",
)

data class RecordingStepFormState(
    val selectorStrategy: String = "resourceId",
    val selectorValue: String = "",
    val selectorWeight: String = "1.0",
    val selectorRequired: Boolean = false,
    val coordinateX: String = "",
    val coordinateY: String = "",
    val waitBefore: PredicateFormState = PredicateFormState(),
    val waitAfter: PredicateFormState = PredicateFormState(),
    val retryMaxAttempts: String = "2",
    val retryBackoffMs: String = "250",
    val retryBackoffMultiplier: String = "1.0",
    val retryMaxBackoffMs: String = "",
    val failurePolicy: String = "stop",
    val notes: String = "",
)

data class RecordingEditorUiState(
    val script: AutomationScript,
    val selectedStepId: String? = null,
    val stepForm: RecordingStepFormState? = null,
    val formError: String? = null,
    val showDiscardConfirmation: Boolean = false,
    val revisionConflict: EditorSaveResult.Conflict? = null,
    val dryRunReport: DryRunReport? = null,
    val focusedFailureStepId: String? = null,
    val copyCandidate: AutomationScript? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isDirty: Boolean = false,
)

class RecordingEditorViewModel(
    initialScript: AutomationScript,
    private val savePort: EditorSavePort? = null,
    private val dryRunEngine: ScriptDryRunEngine? = null,
    private val scriptIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val stepIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val createdAtFactory: () -> String = { java.time.Instant.now().toString() },
) : ViewModel() {
    private val session = ScriptEditorSession(initialScript, savePort)
    private val mutableUiState = MutableStateFlow(
        RecordingEditorUiState(script = initialScript),
    )

    val uiState: StateFlow<RecordingEditorUiState> = mutableUiState.asStateFlow()

    fun selectStep(stepId: String) {
        val step = session.script.steps.find { it.id == stepId } ?: return
        publish(
            selectedStepId = stepId,
            stepForm = step.toForm(),
            formError = null,
        )
    }

    fun updateStepForm(transform: (RecordingStepFormState) -> RecordingStepFormState) {
        val current = mutableUiState.value.stepForm ?: return
        publish(stepForm = transform(current), formError = null)
    }

    fun moveStep(stepId: String, targetIndex: Int) {
        apply(ReorderStep(stepId, targetIndex))
    }

    fun setStepEnabled(stepId: String, enabled: Boolean) {
        apply(SetStepEnabled(stepId, enabled))
    }

    fun deleteStep(stepId: String) {
        apply(DeleteStep(stepId))
    }

    fun duplicateStep(stepId: String) {
        val index = session.script.steps.indexOfFirst { it.id == stepId }
        if (index >= 0) {
            apply(
                DuplicateStep(
                    sourceStepId = stepId,
                    newStepId = stepIdFactory(),
                    insertIndex = index + 1,
                ),
            )
        }
    }

    fun copyScript(name: String): AutomationScript {
        val copied = copyAutomationScript(
            source = session.script,
            newId = scriptIdFactory(),
            newName = name.trim().take(128),
            createdAt = createdAtFactory(),
        )
        publish(copyCandidate = copied)
        return copied
    }

    fun saveCopy(name: String): EditorSaveResult {
        val port = savePort ?: return EditorSaveResult.Unavailable
        val copied = copyScript(name)
        return when (val result = port.save(copied, expectedRevision = 0)) {
            is EditorPersistenceResult.Saved -> {
                publish(copyCandidate = result.script, formError = null)
                EditorSaveResult.Saved(result.script)
            }

            is EditorPersistenceResult.Conflict -> {
                val conflict = EditorSaveResult.Conflict(
                    expectedRevision = result.expectedRevision,
                    attempted = result.attempted,
                    current = result.current,
                )
                publish(revisionConflict = conflict)
                conflict
            }
        }
    }

    fun submitStepForm(): Boolean {
        val state = mutableUiState.value
        val stepId = state.selectedStepId ?: return false
        val form = state.stepForm ?: return false
        val baselineStep = session.script.steps.find { it.id == stepId } ?: return false
        val temporary = ScriptEditorSession(session.script)
        val commands = runCatching { form.commands(stepId, baselineStep) }.getOrElse {
            publish(formError = it.message ?: "表单值无效")
            return false
        }
        for (command in commands) {
            val result = temporary.apply(command)
            if (result is EditResult.Rejected) {
                publish(formError = result.message)
                return false
            }
        }
        val replacement = temporary.script.steps.find { it.id == stepId } ?: baselineStep
        return when (val result = session.apply(UpdateStep(stepId, replacement))) {
            is EditResult.Rejected -> {
                publish(formError = result.message)
                false
            }

            is EditResult.Applied -> {
                publish(
                    selectedStepId = stepId,
                    stepForm = replacement.toForm(),
                    formError = null,
                )
                true
            }
        }
    }

    fun undo() {
        if (session.undo()) {
            refreshSelectedForm()
        }
    }

    fun redo() {
        if (session.redo()) {
            refreshSelectedForm()
        }
    }

    fun requestClose(): Boolean =
        when (session.closeDecision()) {
            CloseDecision.Safe -> true
            is CloseDecision.ConfirmDiscard -> {
                publish(showDiscardConfirmation = true)
                false
            }
        }

    fun dismissDiscardConfirmation() {
        publish(showDiscardConfirmation = false)
    }

    fun discardChanges() {
        session.discardUnsavedChanges()
        publish(
            showDiscardConfirmation = false,
            revisionConflict = null,
        )
        refreshSelectedForm()
    }

    fun save(): EditorSaveResult {
        val result = session.save()
        when (result) {
            is EditorSaveResult.Saved -> publish(
                revisionConflict = null,
                formError = null,
            )

            is EditorSaveResult.Conflict -> publish(revisionConflict = result)
            EditorSaveResult.Unavailable -> publish(formError = "保存端口不可用")
        }
        return result
    }

    fun runDryRun() {
        val report = dryRunEngine?.preview(session.script)
        if (report == null) {
            publish(formError = "Dry-run 端口不可用")
            return
        }
        val failure = report.steps.firstOrNull { it.status == DryRunStepStatus.FAILED }
        publish(
            dryRunReport = report,
            focusedFailureStepId = failure?.stepId,
            selectedStepId = failure?.stepId ?: mutableUiState.value.selectedStepId,
            stepForm = failure?.stepId
                ?.let { id -> session.script.steps.find { it.id == id }?.toForm() }
                ?: mutableUiState.value.stepForm,
        )
    }

    fun applyObservationSelection(selection: ObservationSelection) {
        val stepId = mutableUiState.value.selectedStepId ?: return
        val command = when (selection) {
            is ObservationSelection.Selected -> EditStepAuthorizedVisualTarget(
                stepId = stepId,
                normalizedPoint = NormalizedPoint(selection.x, selection.y),
                observationId = selection.observationId,
                imageSha256 = selection.imageSha256,
            )

            is ObservationSelection.BoundsSelected -> EditStepAuthorizedVisualTarget(
                stepId = stepId,
                normalizedBounds = selection.bounds,
                observationId = selection.observationId,
                imageSha256 = selection.imageSha256,
            )

            ObservationSelection.Unavailable -> return
        }
        apply(command)
    }

    private fun apply(command: dev.aiauto.android.automation.recording.editor.ScriptEditCommand) {
        when (val result = session.apply(command)) {
            is EditResult.Applied -> refreshSelectedForm()
            is EditResult.Rejected -> publish(formError = result.message)
        }
    }

    private fun refreshSelectedForm() {
        val selected = mutableUiState.value.selectedStepId
        val form = selected?.let { id -> session.script.steps.find { it.id == id }?.toForm() }
        publish(stepForm = form, formError = null)
    }

    private fun publish(
        selectedStepId: String? = mutableUiState.value.selectedStepId,
        stepForm: RecordingStepFormState? = mutableUiState.value.stepForm,
        formError: String? = mutableUiState.value.formError,
        showDiscardConfirmation: Boolean = mutableUiState.value.showDiscardConfirmation,
        revisionConflict: EditorSaveResult.Conflict? = mutableUiState.value.revisionConflict,
        dryRunReport: DryRunReport? = mutableUiState.value.dryRunReport,
        focusedFailureStepId: String? = mutableUiState.value.focusedFailureStepId,
        copyCandidate: AutomationScript? = mutableUiState.value.copyCandidate,
    ) {
        mutableUiState.value = RecordingEditorUiState(
            script = session.script,
            selectedStepId = selectedStepId,
            stepForm = stepForm,
            formError = formError,
            showDiscardConfirmation = showDiscardConfirmation,
            revisionConflict = revisionConflict,
            dryRunReport = dryRunReport,
            focusedFailureStepId = focusedFailureStepId,
            copyCandidate = copyCandidate,
            canUndo = session.undoDepth > 0,
            canRedo = session.redoDepth > 0,
            isDirty = session.isDirty,
        )
    }

    companion object {
        fun factory(
            context: Context,
            initialScript: AutomationScript,
        ): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(RecordingEditorViewModel::class.java))
                    val store = dev.aiauto.android.automation.recording.RecordingScriptStore
                        .from(applicationContext)
                    return RecordingEditorViewModel(
                        initialScript = initialScript,
                        savePort = RecordingScriptStoreSavePort(store),
                        dryRunEngine = AndroidRecordingEditorDryRun.create(),
                    ) as T
                }
            }
        }
    }
}

private fun RecordingStepFormState.commands(
    stepId: String,
    baselineStep: RecordedStep,
): List<dev.aiauto.android.automation.recording.editor.ScriptEditCommand> {
    val selectorWeightValue = selectorWeight.toDoubleOrNull()
        ?: error("selector weight 无效")
    val retry = RetryPolicy(
        maxAttempts = retryMaxAttempts.toIntOrNull() ?: error("retry attempts 无效"),
        backoffMs = retryBackoffMs.toLongOrNull() ?: error("retry backoff 无效"),
        backoffMultiplier = retryBackoffMultiplier.toDoubleOrNull()
            ?: error("retry multiplier 无效"),
        maxBackoffMs = retryMaxBackoffMs.takeIf(String::isNotBlank)?.let {
            it.toLongOrNull() ?: error("retry max backoff 无效")
        },
    )
    val commands =
        mutableListOf<dev.aiauto.android.automation.recording.editor.ScriptEditCommand>()
    if (selectorValue.isNotBlank()) {
        commands += EditStepSelector(
            stepId = stepId,
            selectorCandidates = JsonArray(
                listOf(
                    buildJsonObject {
                        put("strategy", JsonPrimitive(selectorStrategy))
                        put("value", JsonPrimitive(selectorValue.trim()))
                        put("weight", JsonPrimitive(selectorWeightValue))
                        put("required", JsonPrimitive(selectorRequired))
                    },
                ),
            ),
        )
    }
    val coordinate = if (coordinateX.isNotBlank() || coordinateY.isNotBlank()) {
        NormalizedPoint(
            coordinateX.toDoubleOrNull() ?: error("coordinate x 无效"),
            coordinateY.toDoubleOrNull() ?: error("coordinate y 无效"),
        )
    } else {
        null
    }
    if (coordinate != null && coordinate != baselineStep.visualTarget?.normalizedPoint) {
        commands += EditStepCoordinate(
            stepId,
            coordinate,
        )
    }
    commands += EditStepWait(stepId, waitBefore.toPredicate(), waitAfter.toPredicate())
    commands += EditStepRetry(stepId, retry)
    commands += EditStepFailurePolicy(stepId, failurePolicy)
    commands += EditStepNotes(stepId, notes.trim().ifEmpty { null })
    return commands
}

private fun PredicateFormState.toPredicate(): RecordedPredicate? {
    if (!enabled) return null
    return RecordedPredicate(
        kind = kind,
        operator = operator,
        expected = expected.takeIf(String::isNotBlank)?.let(::JsonPrimitive),
        stableDurationMs = stableDurationMs.takeIf(String::isNotBlank)?.let {
            it.toLongOrNull() ?: error("predicate stable duration 无效")
        },
        timeoutMs = timeoutMs.toLongOrNull() ?: error("predicate timeout 无效"),
    )
}

private fun RecordedStep.toForm(): RecordingStepFormState {
    val selector = (action.params["target"] as? kotlinx.serialization.json.JsonObject)
        ?.get("selectorCandidates")
        ?.let { it as? JsonArray }
        ?.firstOrNull() as? kotlinx.serialization.json.JsonObject
    val point = visualTarget?.normalizedPoint
    return RecordingStepFormState(
        selectorStrategy = selector?.get("strategy")?.let { (it as JsonPrimitive).content }
            ?: "resourceId",
        selectorValue = selector?.get("value")?.let { (it as JsonPrimitive).content }.orEmpty(),
        selectorWeight = selector?.get("weight")?.toString() ?: "1.0",
        selectorRequired = selector?.get("required")?.toString()?.toBooleanStrictOrNull() ?: false,
        coordinateX = point?.x?.toString().orEmpty(),
        coordinateY = point?.y?.toString().orEmpty(),
        waitBefore = waitBefore.toForm(),
        waitAfter = waitAfter.toForm(),
        retryMaxAttempts = retry.maxAttempts.toString(),
        retryBackoffMs = retry.backoffMs.toString(),
        retryBackoffMultiplier = retry.backoffMultiplier.toString(),
        retryMaxBackoffMs = retry.maxBackoffMs?.toString().orEmpty(),
        failurePolicy = failurePolicy,
        notes = notes.orEmpty(),
    )
}

private fun RecordedPredicate?.toForm(): PredicateFormState =
    if (this == null) {
        PredicateFormState()
    } else {
        PredicateFormState(
            enabled = true,
            kind = kind,
            operator = operator,
            expected = expected?.let { (it as? JsonPrimitive)?.content }.orEmpty(),
            stableDurationMs = stableDurationMs?.toString().orEmpty(),
            timeoutMs = timeoutMs.toString(),
        )
    }
