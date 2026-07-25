package dev.aiauto.android.automation.recording.editor

/**
 * 功能用途：通过仅可观察的端口预演脚本，确保编辑器 dry-run 不具备动作提交能力。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.RecordedStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** snapshot 只携带匹配和坐标预览所需元数据，不暴露设备控制句柄。 */
data class ReadOnlySnapshot(
    val id: String,
    val packageName: String?,
    val capturedAtMs: Long,
    val expiresAtMs: Long,
    val logicalWidth: Int,
    val logicalHeight: Int,
)

sealed interface SnapshotPreviewResult {
    data class Available(val snapshot: ReadOnlySnapshot) : SnapshotPreviewResult

    data class Unavailable(val message: String) : SnapshotPreviewResult
}

sealed interface SelectorPreviewResult {
    data class Unique(val matchSummary: String) : SelectorPreviewResult

    data class Ambiguous(val matchCount: Int) : SelectorPreviewResult

    data class Missing(val message: String) : SelectorPreviewResult
}

/** 坐标可以来自视觉元数据或 action target，端口负责映射当前屏幕并校验边界。 */
data class PreviewCoordinate(
    val normalizedPoint: NormalizedPoint?,
    val normalizedBounds: NormalizedBounds?,
)

sealed interface CoordinatePreviewResult {
    data class InBounds(
        val x: Int,
        val y: Int,
    ) : CoordinatePreviewResult

    data class OutOfBounds(val message: String) : CoordinatePreviewResult
}

sealed interface ConditionPreviewResult {
    data object Satisfied : ConditionPreviewResult

    data class Unsatisfied(val message: String) : ConditionPreviewResult
}

/** 四个端口均为只读查询；领域构造器刻意不存在 execute 或 commit 参数。 */
fun interface SnapshotPreviewPort {
    fun snapshot(targetPackages: Set<String>): SnapshotPreviewResult
}

fun interface SelectorPreviewPort {
    fun match(
        snapshot: ReadOnlySnapshot,
        target: JsonObject,
    ): SelectorPreviewResult
}

fun interface CoordinatePreviewPort {
    fun preview(
        snapshot: ReadOnlySnapshot,
        coordinate: PreviewCoordinate,
    ): CoordinatePreviewResult
}

fun interface ConditionPreviewPort {
    fun check(
        snapshot: ReadOnlySnapshot,
        predicate: RecordedPredicate,
    ): ConditionPreviewResult
}

enum class DryRunStepStatus {
    READY,
    FAILED,
    SKIPPED,
}

enum class DryRunErrorCode {
    SNAPSHOT_UNAVAILABLE,
    SNAPSHOT_STALE,
    PACKAGE_NOT_ALLOWED,
    SELECTOR_AMBIGUOUS,
    SELECTOR_NOT_FOUND,
    COORDINATE_OUT_OF_BOUNDS,
    CONDITION_UNSATISFIED,
}

data class DryRunStepResult(
    val stepId: String,
    val status: DryRunStepStatus,
    val errorCode: DryRunErrorCode? = null,
    val message: String? = null,
)

data class DryRunReport(
    val scriptId: String,
    val succeeded: Boolean,
    val snapshotId: String?,
    val steps: List<DryRunStepResult>,
)

/** dry-run 共用一次有时效的 snapshot，并在首个失败处停止后续只读检查。 */
class ScriptDryRunEngine(
    private val snapshots: SnapshotPreviewPort,
    private val selectors: SelectorPreviewPort,
    private val coordinates: CoordinatePreviewPort,
    private val conditions: ConditionPreviewPort,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun preview(script: AutomationScript): DryRunReport {
        val enabled = script.steps.filter(RecordedStep::enabled)
        if (enabled.isEmpty()) {
            return report(
                script = script,
                snapshotId = null,
                results = script.steps.map(::skipped),
            )
        }
        val snapshot = when (val result = snapshots.snapshot(script.targetPackages.toSet())) {
            is SnapshotPreviewResult.Unavailable -> {
                return failBeforeObservation(
                    script,
                    DryRunErrorCode.SNAPSHOT_UNAVAILABLE,
                    result.message,
                )
            }

            is SnapshotPreviewResult.Available -> result.snapshot
        }
        if (clock() > snapshot.expiresAtMs) {
            return failBeforeObservation(
                script,
                DryRunErrorCode.SNAPSHOT_STALE,
                "The snapshot expired before dry-run",
                snapshot.id,
            )
        }
        if (snapshot.packageName !in script.targetPackages) {
            return failBeforeObservation(
                script,
                DryRunErrorCode.PACKAGE_NOT_ALLOWED,
                "The snapshot package is not allowed by the script",
                snapshot.id,
            )
        }

        val results = script.steps.map { step ->
            if (!step.enabled) {
                skipped(step)
            } else {
                previewStep(snapshot, step)
            }
        }
        return report(script, snapshot.id, results)
    }

    private fun previewStep(
        snapshot: ReadOnlySnapshot,
        step: RecordedStep,
    ): DryRunStepResult {
        val target = step.action.params["target"] as? JsonObject
        if (target?.get("selectorCandidates") != null) {
            when (val result = selectors.match(snapshot, target)) {
                is SelectorPreviewResult.Unique -> Unit
                is SelectorPreviewResult.Ambiguous -> return failed(
                    step,
                    DryRunErrorCode.SELECTOR_AMBIGUOUS,
                    "The selector matched ${result.matchCount} nodes",
                )

                is SelectorPreviewResult.Missing -> return failed(
                    step,
                    DryRunErrorCode.SELECTOR_NOT_FOUND,
                    result.message,
                )
            }
        }

        val coordinate = step.previewCoordinate(target)
        if (coordinate != null) {
            when (val result = coordinates.preview(snapshot, coordinate)) {
                is CoordinatePreviewResult.InBounds -> Unit
                is CoordinatePreviewResult.OutOfBounds -> return failed(
                    step,
                    DryRunErrorCode.COORDINATE_OUT_OF_BOUNDS,
                    result.message,
                )
            }
        }

        listOfNotNull(step.waitBefore, step.waitAfter).forEach { predicate ->
            when (val result = conditions.check(snapshot, predicate)) {
                ConditionPreviewResult.Satisfied -> Unit
                is ConditionPreviewResult.Unsatisfied -> return failed(
                    step,
                    DryRunErrorCode.CONDITION_UNSATISFIED,
                    result.message,
                )
            }
        }
        return DryRunStepResult(step.id, DryRunStepStatus.READY)
    }

    private fun failBeforeObservation(
        script: AutomationScript,
        code: DryRunErrorCode,
        message: String,
        snapshotId: String? = null,
    ): DryRunReport {
        var failureAssigned = false
        val results = script.steps.map { step ->
            if (!step.enabled || failureAssigned) {
                skipped(step)
            } else {
                failureAssigned = true
                failed(step, code, message)
            }
        }
        return report(script, snapshotId, results)
    }

    private fun report(
        script: AutomationScript,
        snapshotId: String?,
        results: List<DryRunStepResult>,
    ): DryRunReport = DryRunReport(
        scriptId = script.id,
        succeeded = results.none { it.status == DryRunStepStatus.FAILED },
        snapshotId = snapshotId,
        steps = results,
    )

    private fun skipped(step: RecordedStep): DryRunStepResult =
        DryRunStepResult(step.id, DryRunStepStatus.SKIPPED)

    private fun failed(
        step: RecordedStep,
        code: DryRunErrorCode,
        message: String,
    ): DryRunStepResult = DryRunStepResult(
        stepId = step.id,
        status = DryRunStepStatus.FAILED,
        errorCode = code,
        message = message,
    )
}

private fun RecordedStep.previewCoordinate(target: JsonObject?): PreviewCoordinate? {
    visualTarget?.let {
        return PreviewCoordinate(
            normalizedPoint = it.normalizedPoint,
            normalizedBounds = it.normalizedBounds,
        )
    }
    val point = target?.get("normalizedScreenPoint") as? JsonObject ?: return null
    val x = point["x"]?.jsonPrimitive?.doubleOrNull ?: return null
    val y = point["y"]?.jsonPrimitive?.doubleOrNull ?: return null
    return PreviewCoordinate(
        normalizedPoint = NormalizedPoint(x, y),
        normalizedBounds = null,
    )
}
