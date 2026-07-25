package dev.aiauto.android.ui.recording

/**
 * 功能用途：实现当前脱敏 Accessibility snapshot 到 N40 只读 dry-run 端口的安全适配。
 */

import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.action.CoordinateTransformer
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.NormalizedPoint
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.selector.SelectorMatch
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.editor.ConditionPreviewPort
import dev.aiauto.android.automation.recording.editor.ConditionPreviewResult
import dev.aiauto.android.automation.recording.editor.CoordinatePreviewPort
import dev.aiauto.android.automation.recording.editor.CoordinatePreviewResult
import dev.aiauto.android.automation.recording.editor.PreviewCoordinate
import dev.aiauto.android.automation.recording.editor.ReadOnlySnapshot
import dev.aiauto.android.automation.recording.editor.ScriptDryRunEngine
import dev.aiauto.android.automation.recording.editor.SelectorPreviewPort
import dev.aiauto.android.automation.recording.editor.SelectorPreviewResult
import dev.aiauto.android.automation.recording.editor.SnapshotPreviewPort
import dev.aiauto.android.automation.recording.editor.SnapshotPreviewResult
import dev.aiauto.android.bridge.AccessibilityCommandJsonParser
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class AndroidRecordingEditorDryRun private constructor(
    private val snapshotSource: (String?) -> AccessibilityResult<UiNodeSnapshot>,
    private val clock: () -> Long,
    private val matcher: SelectorMatcher = SelectorMatcher(),
    private val parser: AccessibilityCommandJsonParser = AccessibilityCommandJsonParser(),
) : SnapshotPreviewPort, SelectorPreviewPort, CoordinatePreviewPort, ConditionPreviewPort {
    private var current: Pair<ReadOnlySnapshot, UiNodeSnapshot>? = null

    override fun snapshot(targetPackages: Set<String>): SnapshotPreviewResult {
        val expectedPackage = targetPackages.singleOrNull()
        val root = when (val result = snapshotSource(expectedPackage)) {
            is AccessibilityResult.Failure ->
                return SnapshotPreviewResult.Unavailable(result.error.message)

            is AccessibilityResult.Success -> result.value
        }
        if (root.packageName !in targetPackages || root.bounds.width <= 0 || root.bounds.height <= 0) {
            return SnapshotPreviewResult.Unavailable(
                "当前脱敏 snapshot 不属于脚本目标包或屏幕边界无效",
            )
        }
        val capturedAt = clock()
        val metadata = ReadOnlySnapshot(
            id = UUID.randomUUID().toString(),
            packageName = root.packageName,
            capturedAtMs = capturedAt,
            expiresAtMs = capturedAt + SNAPSHOT_TTL_MS,
            logicalWidth = root.bounds.width,
            logicalHeight = root.bounds.height,
        )
        current = metadata to root
        return SnapshotPreviewResult.Available(metadata)
    }

    override fun match(
        snapshot: ReadOnlySnapshot,
        target: JsonObject,
    ): SelectorPreviewResult {
        val root = rootFor(snapshot)
            ?: return SelectorPreviewResult.Missing("Dry-run snapshot 已过期或被替换")
        val nodeTarget = parseTarget(target)
            ?: return SelectorPreviewResult.Missing("Selector target 无法严格解析")
        return when (val result = matcher.match(root, nodeTarget)) {
            is SelectorMatch.Found -> SelectorPreviewResult.Unique(
                "path=${result.path.indices.joinToString(".")} score=${result.score}",
            )

            is SelectorMatch.Ambiguous -> SelectorPreviewResult.Ambiguous(result.candidateCount)
            is SelectorMatch.NotFound -> SelectorPreviewResult.Missing(
                "Selector 未达到最小置信度",
            )
        }
    }

    override fun preview(
        snapshot: ReadOnlySnapshot,
        coordinate: PreviewCoordinate,
    ): CoordinatePreviewResult {
        if (rootFor(snapshot) == null) {
            return CoordinatePreviewResult.OutOfBounds("Dry-run snapshot 已过期或被替换")
        }
        val point = coordinate.normalizedPoint
            ?: coordinate.normalizedBounds?.let { bounds ->
                dev.aiauto.android.automation.recording.NormalizedPoint(
                    x = (bounds.left + bounds.right) / 2,
                    y = (bounds.top + bounds.bottom) / 2,
                )
            }
            ?: return CoordinatePreviewResult.OutOfBounds("缺少归一化点或边界")
        val bounds = ScreenBounds(0, 0, snapshot.logicalWidth, snapshot.logicalHeight)
        return when (
            val result = CoordinateTransformer.fromNormalized(
                NormalizedPoint(point.x, point.y),
                bounds,
            )
        ) {
            is AccessibilityResult.Failure ->
                CoordinatePreviewResult.OutOfBounds(result.error.message)

            is AccessibilityResult.Success ->
                CoordinatePreviewResult.InBounds(result.value.x, result.value.y)
        }
    }

    override fun check(
        snapshot: ReadOnlySnapshot,
        predicate: RecordedPredicate,
    ): ConditionPreviewResult {
        val root = rootFor(snapshot)
            ?: return ConditionPreviewResult.Unsatisfied("Dry-run snapshot 已过期或被替换")
        return when (predicate.kind) {
            "package", "window" -> compare(
                actual = root.packageName.orEmpty(),
                expected = predicate.expected?.jsonPrimitive?.contentOrNull.orEmpty(),
                operator = predicate.operator,
            )

            "node", "text" -> {
                val target = predicate.target
                    ?: return ConditionPreviewResult.Unsatisfied("条件缺少 target")
                val matched = parseTarget(target)?.let { matcher.match(root, it) }
                val exists = matched is SelectorMatch.Found
                when (predicate.operator) {
                    "exists" -> if (exists) {
                        ConditionPreviewResult.Satisfied
                    } else {
                        ConditionPreviewResult.Unsatisfied("目标节点不存在")
                    }

                    "notExists" -> if (!exists) {
                        ConditionPreviewResult.Satisfied
                    } else {
                        ConditionPreviewResult.Unsatisfied("目标节点仍存在")
                    }

                    else -> ConditionPreviewResult.Unsatisfied(
                        "该节点比较需真实回放后重新观察",
                    )
                }
            }

            "uiStable" -> ConditionPreviewResult.Satisfied
            else -> ConditionPreviewResult.Unsatisfied("不支持的 dry-run 条件")
        }
    }

    private fun compare(
        actual: String,
        expected: String,
        operator: String,
    ): ConditionPreviewResult {
        val matched = when (operator) {
            "equals" -> actual == expected
            "contains" -> expected in actual
            "matches" -> runCatching { Regex(expected).matches(actual) }.getOrDefault(false)
            else -> false
        }
        return if (matched) {
            ConditionPreviewResult.Satisfied
        } else {
            ConditionPreviewResult.Unsatisfied("当前 package/window 条件不满足")
        }
    }

    private fun rootFor(snapshot: ReadOnlySnapshot): UiNodeSnapshot? =
        current?.takeIf { (metadata, _) ->
            metadata.id == snapshot.id && clock() <= metadata.expiresAtMs
        }?.second

    private fun parseTarget(target: JsonObject) =
        runCatching {
            val command = parser.parse(
                buildJsonObject {
                    put("type", JsonPrimitive("ui.click"))
                    put("params", buildJsonObject { put("target", target) })
                },
            )
            (command as? AccessibilityCommand.Click)?.target
        }.getOrNull()

    companion object {
        fun create(): ScriptDryRunEngine {
            val adapter = AndroidRecordingEditorDryRun(
                snapshotSource = AccessibilityRuntime::snapshot,
                clock = System::currentTimeMillis,
            )
            return ScriptDryRunEngine(adapter, adapter, adapter, adapter)
        }

        internal fun createForTest(
            snapshotSource: (String?) -> AccessibilityResult<UiNodeSnapshot>,
            clock: () -> Long,
        ): AndroidRecordingEditorDryRun = AndroidRecordingEditorDryRun(
            snapshotSource = snapshotSource,
            clock = clock,
        )

        private const val SNAPSHOT_TTL_MS = 10_000L
    }
}
