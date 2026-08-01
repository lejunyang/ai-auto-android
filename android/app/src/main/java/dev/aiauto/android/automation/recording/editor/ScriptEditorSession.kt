package dev.aiauto.android.automation.recording.editor

/**
 * 功能用途：提供不依赖 UI 的不可变脚本编辑命令、撤销历史和 revision 保存事务。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.RecordingSaveResult
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.RetryPolicy
import dev.aiauto.android.automation.recording.VisualTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** 编辑命令失败时使用稳定原因，调用方无需解析异常消息。 */
enum class EditRejectionCode {
    INVALID_INDEX,
    STEP_NOT_FOUND,
    DUPLICATE_STEP_ID,
    STEP_ID_MISMATCH,
    INVALID_VALUE,
    UNSUPPORTED_TARGET,
}

/** 单次编辑结果显式区分已提交历史和原子拒绝。 */
sealed interface EditResult {
    data class Applied(val script: AutomationScript) : EditResult

    data class Rejected(
        val code: EditRejectionCode,
        val message: String,
    ) : EditResult
}

/** 所有命令只根据输入脚本计算新值，不持有会话或可变 UI 状态。 */
sealed interface ScriptEditCommand {
    fun edit(script: AutomationScript): EditResult
}

data class InsertStep(
    val index: Int,
    val step: RecordedStep,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (index !in 0..script.steps.size) {
            return rejected(EditRejectionCode.INVALID_INDEX, "The insertion index is invalid")
        }
        if (!STEP_ID_PATTERN.matches(step.id)) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The step id is invalid")
        }
        if (script.steps.any { it.id == step.id }) {
            return rejected(EditRejectionCode.DUPLICATE_STEP_ID, "The step id already exists")
        }
        return applied(script, script.steps.toMutableList().apply { add(index, step) })
    }
}

data class DeleteStep(
    val stepId: String,
    val expectedIndex: Int? = null,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        val index = script.indexOf(stepId)
        if (index < 0) {
            return rejected(EditRejectionCode.STEP_NOT_FOUND, "The step does not exist")
        }
        if (expectedIndex != null && expectedIndex != index) {
            return rejected(EditRejectionCode.INVALID_INDEX, "The step index changed")
        }
        if (script.steps.size == 1) {
            return rejected(EditRejectionCode.INVALID_VALUE, "A script must retain one step")
        }
        return applied(script, script.steps.toMutableList().apply { removeAt(index) })
    }
}

data class DuplicateStep(
    val sourceStepId: String,
    val newStepId: String,
    val insertIndex: Int,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        val source = script.steps.find { it.id == sourceStepId }
            ?: return rejected(EditRejectionCode.STEP_NOT_FOUND, "The source step does not exist")
        if (insertIndex !in 0..script.steps.size) {
            return rejected(EditRejectionCode.INVALID_INDEX, "The insertion index is invalid")
        }
        if (!STEP_ID_PATTERN.matches(newStepId)) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The new step id is invalid")
        }
        if (script.steps.any { it.id == newStepId }) {
            return rejected(EditRejectionCode.DUPLICATE_STEP_ID, "The new step id already exists")
        }
        return applied(
            script,
            script.steps.toMutableList().apply {
                add(insertIndex, source.copy(id = newStepId))
            },
        )
    }
}

data class ReorderStep(
    val stepId: String,
    val targetIndex: Int,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        val sourceIndex = script.indexOf(stepId)
        if (sourceIndex < 0) {
            return rejected(EditRejectionCode.STEP_NOT_FOUND, "The step does not exist")
        }
        if (targetIndex !in script.steps.indices) {
            return rejected(EditRejectionCode.INVALID_INDEX, "The target index is invalid")
        }
        val reordered = script.steps.toMutableList()
        val step = reordered.removeAt(sourceIndex)
        reordered.add(targetIndex, step)
        return applied(script, reordered)
    }
}

data class SetStepEnabled(
    val stepId: String,
    val enabled: Boolean,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult =
        script.updateStep(stepId) { it.copy(enabled = enabled) }
}

data class UpdateStep(
    val stepId: String,
    val replacement: RecordedStep,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (replacement.id != stepId) {
            return rejected(
                EditRejectionCode.STEP_ID_MISMATCH,
                "The replacement must preserve the step id",
            )
        }
        return script.updateStep(stepId) { replacement }
    }
}

data class EditStepSelector(
    val stepId: String,
    val selectorCandidates: JsonArray,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (!selectorCandidates.isValidSelectorCandidates()) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The selectors are invalid")
        }
        return script.updateTarget(stepId) { target ->
            JsonObject(target + ("selectorCandidates" to selectorCandidates))
        }
    }
}

data class EditStepCoordinate(
    val stepId: String,
    val point: NormalizedPoint,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (point.x !in 0.0..1.0 || point.y !in 0.0..1.0) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The coordinate is out of bounds")
        }
        val index = script.indexOf(stepId)
        if (index < 0) {
            return rejected(EditRejectionCode.STEP_NOT_FOUND, "The step does not exist")
        }
        val step = script.steps[index]
        val coordinateJson = JsonObject(
            mapOf(
                "x" to JsonPrimitive(point.x),
                "y" to JsonPrimitive(point.y),
            ),
        )
        val action = when {
            step.action.params["target"] is JsonObject -> {
                val target = step.action.params.getValue("target").jsonObject
                step.action.copy(
                    params = JsonObject(
                        step.action.params + (
                            "target" to JsonObject(
                                target + ("normalizedScreenPoint" to coordinateJson),
                            )
                        ),
                    ),
                )
            }

            step.action.type == "ui.tap" -> {
                val environment = script.environment
                val width = environment?.logicalWidth
                val height = environment?.logicalHeight
                if (width == null || height == null) {
                    return rejected(
                        EditRejectionCode.UNSUPPORTED_TARGET,
                        "Absolute tap editing requires logical display dimensions",
                    )
                }
                step.action.copy(
                    params = JsonObject(
                        mapOf(
                            "x" to JsonPrimitive((point.x * width).roundToInt()),
                            "y" to JsonPrimitive((point.y * height).roundToInt()),
                        ),
                    ),
                )
            }

            else -> return rejected(
                EditRejectionCode.UNSUPPORTED_TARGET,
                "The action does not expose an editable coordinate",
            )
        }
        val visualTarget = step.visualTarget?.copy(
            normalizedPoint = point,
            normalizedBounds = null,
            source = RecordingProvenance.MANUAL,
            observationId = null,
            imageSha256 = null,
            screenshotBase64 = null,
            deviceLocalPath = null,
        ) ?: VisualTarget(
            normalizedPoint = point,
            confidence = 1.0,
            source = RecordingProvenance.MANUAL,
        )
        val replacement = step.copy(
            provenance = RecordingProvenance.COORDINATE,
            action = action,
            visualTarget = visualTarget,
        )
        return applied(
            script,
            script.steps.toMutableList().apply { this[index] = replacement },
        )
    }
}

/**
 * 授权视觉命令在同一次领域校验中绑定 geometry 与图片来源，拒绝任何部分更新。
 */
data class EditStepAuthorizedVisualTarget(
    val stepId: String,
    val normalizedPoint: NormalizedPoint? = null,
    val normalizedBounds: NormalizedBounds? = null,
    val observationId: String,
    val imageSha256: String,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if ((normalizedPoint == null) == (normalizedBounds == null)) {
            return rejected(
                EditRejectionCode.INVALID_VALUE,
                "Exactly one authorized visual geometry is required",
            )
        }
        if (normalizedPoint != null && !normalizedPoint.isValid()) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The visual point is invalid")
        }
        if (normalizedBounds != null && !normalizedBounds.isValid()) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The visual bounds are invalid")
        }
        if (!OBSERVATION_ID_PATTERN.matches(observationId)) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The observation id is invalid")
        }
        if (!LOWERCASE_SHA256.matches(imageSha256)) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The image SHA-256 is invalid")
        }
        val index = script.indexOf(stepId)
        if (index < 0) {
            return rejected(EditRejectionCode.STEP_NOT_FOUND, "The step does not exist")
        }
        val step = script.steps[index]
        val action = step.authorizedVisualAction(script, normalizedPoint)
            ?: return rejected(
                EditRejectionCode.UNSUPPORTED_TARGET,
                "The action does not expose an editable visual target",
            )
        val replacement = step.copy(
            provenance = RecordingProvenance.VISUAL,
            action = action,
            visualTarget = VisualTarget(
                normalizedPoint = normalizedPoint,
                normalizedBounds = normalizedBounds,
                confidence = 1.0,
                source = RecordingProvenance.VISUAL,
                observationId = observationId,
                imageSha256 = imageSha256,
            ),
        )
        return applied(
            script,
            script.steps.toMutableList().apply { this[index] = replacement },
        )
    }
}

data class EditStepWait(
    val stepId: String,
    val waitBefore: RecordedPredicate?,
    val waitAfter: RecordedPredicate?,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (!waitBefore.isValidPredicate() || !waitAfter.isValidPredicate()) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The wait predicate is invalid")
        }
        return script.updateStep(stepId) {
            it.copy(waitBefore = waitBefore, waitAfter = waitAfter)
        }
    }
}

data class EditStepRetry(
    val stepId: String,
    val retry: RetryPolicy,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult =
        script.updateStep(stepId) { it.copy(retry = retry) }
}

data class EditStepFailurePolicy(
    val stepId: String,
    val failurePolicy: String,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (failurePolicy !in FAILURE_POLICIES) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The failure policy is invalid")
        }
        return script.updateStep(stepId) { it.copy(failurePolicy = failurePolicy) }
    }
}

data class EditStepNotes(
    val stepId: String,
    val notes: String?,
) : ScriptEditCommand {
    override fun edit(script: AutomationScript): EditResult {
        if (notes != null && notes.length > MAX_NOTES_LENGTH) {
            return rejected(EditRejectionCode.INVALID_VALUE, "The notes are too long")
        }
        return script.updateStep(stepId) { it.copy(notes = notes) }
    }
}

/** 持久化端口只暴露 revision 条件写入，不让领域层依赖文件实现。 */
interface EditorSavePort {
    fun save(
        script: AutomationScript,
        expectedRevision: Long,
    ): EditorPersistenceResult
}

sealed interface EditorPersistenceResult {
    data class Saved(val script: AutomationScript) : EditorPersistenceResult

    data class Conflict(
        val expectedRevision: Long,
        val attempted: AutomationScript,
        val current: AutomationScript,
    ) : EditorPersistenceResult
}

/** N39 store 适配器保持冲突双方数据，不改变 core 保存实现。 */
class RecordingScriptStoreSavePort(
    private val store: RecordingScriptStore,
) : EditorSavePort {
    override fun save(
        script: AutomationScript,
        expectedRevision: Long,
    ): EditorPersistenceResult = when (
        val result = store.save(script, expectedRevision)
    ) {
        is RecordingSaveResult.Saved -> EditorPersistenceResult.Saved(result.script)
        is RecordingSaveResult.Conflict -> EditorPersistenceResult.Conflict(
            expectedRevision = result.expectedRevision,
            attempted = result.attempted,
            current = result.current,
        )
    }
}

sealed interface EditorSaveResult {
    data class Saved(val script: AutomationScript) : EditorSaveResult

    data class Conflict(
        val expectedRevision: Long,
        val attempted: AutomationScript,
        val current: AutomationScript,
    ) : EditorSaveResult

    data object Unavailable : EditorSaveResult
}

sealed interface CloseDecision {
    data object Safe : CloseDecision

    data class ConfirmDiscard(
        val baselineRevision: Long,
        val attempted: AutomationScript,
    ) : CloseDecision
}

/**
 * 会话保存完整脚本快照作为历史，确保复合字段编辑和失败路径均可精确恢复。
 */
class ScriptEditorSession(
    initialScript: AutomationScript,
    private val savePort: EditorSavePort? = null,
) {
    private var baseline = initialScript
    private val undoHistory = ArrayDeque<AutomationScript>()
    private val redoHistory = ArrayDeque<AutomationScript>()

    var script: AutomationScript = initialScript
        private set

    val baselineRevision: Long
        get() = baseline.revision

    val isDirty: Boolean
        get() = script != baseline

    val undoDepth: Int
        get() = undoHistory.size

    val redoDepth: Int
        get() = redoHistory.size

    fun apply(command: ScriptEditCommand): EditResult =
        when (val result = command.edit(script)) {
            is EditResult.Rejected -> result
            is EditResult.Applied -> {
                undoHistory.addLast(script)
                script = result.script
                redoHistory.clear()
                result
            }
        }

    fun undo(): Boolean {
        val previous = undoHistory.removeLastOrNull() ?: return false
        redoHistory.addLast(script)
        script = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoHistory.removeLastOrNull() ?: return false
        undoHistory.addLast(script)
        script = next
        return true
    }

    fun closeDecision(): CloseDecision =
        if (isDirty) {
            CloseDecision.ConfirmDiscard(
                baselineRevision = baseline.revision,
                attempted = script,
            )
        } else {
            CloseDecision.Safe
        }

    fun discardUnsavedChanges() {
        script = baseline
        undoHistory.clear()
        redoHistory.clear()
    }

    fun save(): EditorSaveResult {
        val port = savePort ?: return EditorSaveResult.Unavailable
        return when (val result = port.save(script, baseline.revision)) {
            is EditorPersistenceResult.Saved -> {
                script = result.script
                baseline = result.script
                undoHistory.clear()
                redoHistory.clear()
                EditorSaveResult.Saved(result.script)
            }

            is EditorPersistenceResult.Conflict -> EditorSaveResult.Conflict(
                expectedRevision = result.expectedRevision,
                attempted = result.attempted,
                current = result.current,
            )
        }
    }
}

/** 脚本复制重置身份与 revision，但复用不可变步骤内容并保留原脚本不变。 */
fun copyAutomationScript(
    source: AutomationScript,
    newId: String,
    newName: String,
    createdAt: String,
): AutomationScript = source.copy(
    id = newId,
    revision = 1,
    name = newName,
    createdAt = createdAt,
    updatedAt = createdAt,
    provenance = RecordingProvenance.MANUAL,
)

private fun AutomationScript.indexOf(stepId: String): Int =
    steps.indexOfFirst { it.id == stepId }

private fun AutomationScript.updateStep(
    stepId: String,
    transform: (RecordedStep) -> RecordedStep,
): EditResult {
    val index = indexOf(stepId)
    if (index < 0) {
        return rejected(EditRejectionCode.STEP_NOT_FOUND, "The step does not exist")
    }
    val replacement = transform(steps[index])
    if (replacement.id != stepId) {
        return rejected(EditRejectionCode.STEP_ID_MISMATCH, "The step id cannot change")
    }
    return applied(this, steps.toMutableList().apply { this[index] = replacement })
}

private fun AutomationScript.updateTarget(
    stepId: String,
    transform: (JsonObject) -> JsonObject,
): EditResult {
    val index = indexOf(stepId)
    if (index < 0) {
        return rejected(EditRejectionCode.STEP_NOT_FOUND, "The step does not exist")
    }
    val step = steps[index]
    val target = step.action.params["target"] as? JsonObject
        ?: return rejected(
            EditRejectionCode.UNSUPPORTED_TARGET,
            "The step does not expose an editable target",
        )
    val replacement = step.copy(
        action = step.action.copy(
            params = JsonObject(step.action.params + ("target" to transform(target))),
        ),
    )
    return applied(this, steps.toMutableList().apply { this[index] = replacement })
}

private fun RecordedStep.authorizedVisualAction(
    script: AutomationScript,
    point: NormalizedPoint?,
): dev.aiauto.android.automation.recording.RecordedAction? {
    val target = action.params["target"] as? JsonObject
    if (target != null) {
        val updatedTarget = if (point == null) {
            JsonObject(target - "normalizedScreenPoint")
        } else {
            JsonObject(target + ("normalizedScreenPoint" to point.toJson()))
        }
        return action.copy(
            params = JsonObject(action.params + ("target" to updatedTarget)),
        )
    }
    if (point == null || action.type != "ui.tap") {
        return null
    }
    val width = script.environment?.logicalWidth ?: return null
    val height = script.environment.logicalHeight ?: return null
    return action.copy(
        params = JsonObject(
            mapOf(
                "x" to JsonPrimitive((point.x * width).roundToInt()),
                "y" to JsonPrimitive((point.y * height).roundToInt()),
            ),
        ),
    )
}

private fun NormalizedPoint.toJson(): JsonObject = JsonObject(
    mapOf(
        "x" to JsonPrimitive(x),
        "y" to JsonPrimitive(y),
    ),
)

private fun NormalizedPoint.isValid(): Boolean =
    x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0

private fun NormalizedBounds.isValid(): Boolean =
    left.isFinite() &&
        top.isFinite() &&
        right.isFinite() &&
        bottom.isFinite() &&
        left in 0.0..1.0 &&
        top in 0.0..1.0 &&
        right in 0.0..1.0 &&
        bottom in 0.0..1.0 &&
        right > left &&
        bottom > top

private fun applied(
    script: AutomationScript,
    steps: List<RecordedStep>,
): EditResult = EditResult.Applied(script.copy(steps = steps))

private fun rejected(
    code: EditRejectionCode,
    message: String,
): EditResult = EditResult.Rejected(code, message)

private fun JsonArray.isValidSelectorCandidates(): Boolean {
    if (size !in 1..16) {
        return false
    }
    return all { element ->
        runCatching {
            val candidate = element as? JsonObject ?: return@runCatching false
            val strategy = candidate["strategy"]?.jsonPrimitive?.content
            val value = candidate["value"]?.jsonPrimitive?.content
            val weight = candidate["weight"]?.jsonPrimitive?.doubleOrNull
            candidate.keys.all(SELECTOR_KEYS::contains) &&
                strategy in SELECTOR_STRATEGIES &&
                !value.isNullOrBlank() &&
                weight != null &&
                weight in 0.0..1.0
        }.getOrDefault(false)
    }
}

private fun RecordedPredicate?.isValidPredicate(): Boolean {
    this ?: return true
    return kind in PREDICATE_KINDS &&
        operator in PREDICATE_OPERATORS &&
        timeoutMs in 1..300_000 &&
        (stableDurationMs == null || stableDurationMs in 100..30_000)
}

private const val MAX_NOTES_LENGTH = 4_096
private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
private val OBSERVATION_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val FAILURE_POLICIES = setOf("stop", "continue", "requestIntervention")
private val SELECTOR_KEYS = setOf("strategy", "value", "weight", "required")
private val SELECTOR_STRATEGIES =
    setOf("resourceId", "contentDescription", "text", "role", "ancestor", "fingerprint")
private val PREDICATE_KINDS = setOf("node", "text", "package", "window", "uiStable")
private val PREDICATE_OPERATORS =
    setOf("exists", "notExists", "equals", "contains", "matches", "stable")
private val STEP_ID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
        "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)
