package dev.aiauto.android.automation.recording.editor

/**
 * 测试用途：验证 dry-run 只有只读能力，且导入导出预览严格、脱敏并保持零文件副作用。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.ScriptVariable
import dev.aiauto.android.automation.recording.VisualTarget
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptEditorPreviewTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `dry run calls only snapshot selector coordinate and condition ports`() {
        val ports = FakeReadOnlyPorts()
        val engine = ScriptDryRunEngine(
            snapshots = ports,
            selectors = ports,
            coordinates = ports,
            conditions = ports,
            clock = { 1_500 },
        )
        val script = script(
            step().copy(
                visualTarget = visualTarget(NormalizedPoint(0.5, 0.75)),
                waitBefore = predicate("package", "equals"),
                waitAfter = predicate("uiStable", "stable"),
            ),
        )

        val report = engine.preview(script)

        assertTrue(report.succeeded)
        assertEquals(DryRunStepStatus.READY, report.steps.single().status)
        assertEquals(1, ports.snapshotCalls)
        assertEquals(1, ports.selectorCalls)
        assertEquals(1, ports.coordinateCalls)
        assertEquals(2, ports.conditionCalls)
        assertEquals(0, ports.actionCommitCount)
    }

    @Test
    fun `ambiguous selector fails closed before coordinate and conditions`() {
        val ports = FakeReadOnlyPorts().apply {
            selectorResult = SelectorPreviewResult.Ambiguous(matchCount = 2)
        }
        val report = engine(ports).preview(
            script(
                step().copy(
                    visualTarget = visualTarget(NormalizedPoint(0.5, 0.75)),
                    waitBefore = predicate("package", "equals"),
                ),
            ),
        )

        assertEquals(DryRunStepStatus.FAILED, report.steps.single().status)
        assertEquals(DryRunErrorCode.SELECTOR_AMBIGUOUS, report.steps.single().errorCode)
        assertEquals(1, ports.selectorCalls)
        assertEquals(0, ports.coordinateCalls)
        assertEquals(0, ports.conditionCalls)
        assertEquals(0, ports.actionCommitCount)
    }

    @Test
    fun `stale snapshot fails before all derived checks`() {
        val ports = FakeReadOnlyPorts().apply {
            snapshotResult = SnapshotPreviewResult.Available(
                ReadOnlySnapshot(
                    id = "snapshot-stale",
                    packageName = "com.example.notes",
                    capturedAtMs = 100,
                    expiresAtMs = 200,
                    logicalWidth = 1080,
                    logicalHeight = 2400,
                ),
            )
        }

        val report = engine(ports, nowMs = 201).preview(script(step()))

        assertEquals(DryRunErrorCode.SNAPSHOT_STALE, report.steps.single().errorCode)
        assertEquals(0, ports.selectorCalls)
        assertEquals(0, ports.coordinateCalls)
        assertEquals(0, ports.conditionCalls)
        assertEquals(0, ports.actionCommitCount)
    }

    @Test
    fun `out of bounds coordinate returns explicit failure without condition checks`() {
        val ports = FakeReadOnlyPorts().apply {
            coordinateResult = CoordinatePreviewResult.OutOfBounds(
                message = "The normalized point is outside the current display",
            )
        }
        val report = engine(ports).preview(
            script(
                step().copy(
                    visualTarget = visualTarget(NormalizedPoint(0.5, 0.75)),
                    waitAfter = predicate("uiStable", "stable"),
                ),
            ),
        )

        assertEquals(DryRunErrorCode.COORDINATE_OUT_OF_BOUNDS, report.steps.single().errorCode)
        assertEquals(1, ports.coordinateCalls)
        assertEquals(0, ports.conditionCalls)
        assertEquals(0, ports.actionCommitCount)
    }

    @Test
    fun `unsatisfied condition fails explicitly and disabled steps do not observe`() {
        val ports = FakeReadOnlyPorts().apply {
            conditionResults += ConditionPreviewResult.Unsatisfied("The package does not match")
        }
        val enabled = step().copy(waitBefore = predicate("package", "equals"))
        val disabled = step(SECOND_STEP_ID).copy(enabled = false)

        val report = engine(ports).preview(script(enabled, disabled))

        assertFalse(report.succeeded)
        assertEquals(DryRunErrorCode.CONDITION_UNSATISFIED, report.steps.first().errorCode)
        assertEquals(DryRunStepStatus.SKIPPED, report.steps.last().status)
        assertEquals(1, ports.snapshotCalls)
        assertEquals(1, ports.conditionCalls)
        assertEquals(0, ports.actionCommitCount)
    }

    @Test
    fun `strict import preview returns parsed model without creating files`() {
        val directory = temporaryFolder.newFolder("import-preview")
        val previewer = RecordingScriptPreviewer(RecordingScriptStore(directory))
        val content = RecordingScriptStore(temporaryFolder.newFolder("source"))
            .exportScript(script(step()), includeSensitiveArtifacts = true)
        val before = directory.fileNames()

        val result = previewer.importPreview(content)

        assertTrue(result is ImportPreviewResult.Valid)
        assertEquals(script(step()), (result as ImportPreviewResult.Valid).script)
        assertEquals(before, directory.fileNames())
    }

    @Test
    fun `strict import preview rejects unknown data without creating files`() {
        val directory = temporaryFolder.newFolder("invalid-import-preview")
        val previewer = RecordingScriptPreviewer(RecordingScriptStore(directory))
        val valid = RecordingScriptStore(temporaryFolder.newFolder("invalid-source"))
            .exportScript(script(step()), includeSensitiveArtifacts = true)
        val unknown = JsonObject(
            Json.parseToJsonElement(valid).jsonObject + (
                "unknownField" to JsonPrimitive(true)
            ),
        )

        val result = previewer.importPreview(unknown.toString())

        assertTrue(result is ImportPreviewResult.Invalid)
        assertEquals(emptySet<String>(), directory.fileNames())
    }

    @Test
    fun `export preview removes secret screenshot and local path without creating files`() {
        val directory = temporaryFolder.newFolder("export-preview")
        val previewer = RecordingScriptPreviewer(RecordingScriptStore(directory))
        val sensitive = script(
            step().copy(
                action = RecordedAction(
                    type = "ui.click",
                    params = buildJsonObject {
                        put("target", selectorTarget())
                        put("credential", JsonPrimitive("nested-secret"))
                    },
                ),
                visualTarget = visualTarget(NormalizedPoint(0.5, 0.75)).copy(
                    screenshotBase64 = "c2Vuc2l0aXZlLXNjcmVlbnNob3Q=",
                    deviceLocalPath = "/data/user/0/dev.aiauto.android/cache/private.png",
                ),
            ),
        ).copy(
            variables = listOf(
                ScriptVariable(
                    name = "password",
                    type = "secret",
                    sensitive = true,
                    defaultValue = JsonPrimitive("plain-secret"),
                ),
            ),
        )

        // 使用合法参数中的嵌套字段验证递归脱敏，避免 strict export 在敏感数据前先拒绝。
        val validSensitive = sensitive.copy(
            steps = listOf(
                sensitive.steps.single().copy(
                    action = RecordedAction(
                        type = "ui.click",
                        params = buildJsonObject {
                            put(
                                "target",
                                JsonObject(
                                    selectorTarget() + (
                                        "fingerprint" to buildJsonObject {
                                            put("credential", JsonPrimitive("nested-secret"))
                                            put(
                                                "localPath",
                                                JsonPrimitive("/sdcard/private/screen.png"),
                                            )
                                            put("role", JsonPrimitive("button"))
                                        }
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )

        val result = previewer.exportPreview(validSensitive)

        assertFalse(result.content.contains("plain-secret"))
        assertFalse(result.content.contains("nested-secret"))
        assertFalse(result.content.contains("c2Vuc2l0aXZlLXNjcmVlbnNob3Q="))
        assertFalse(result.content.contains("/data/user/0/"))
        assertFalse(result.content.contains("/sdcard/private/"))
        assertEquals(emptySet<String>(), directory.fileNames())
    }

    private fun engine(
        ports: FakeReadOnlyPorts,
        nowMs: Long = 1_500,
    ): ScriptDryRunEngine = ScriptDryRunEngine(
        snapshots = ports,
        selectors = ports,
        coordinates = ports,
        conditions = ports,
        clock = { nowMs },
    )

    private class FakeReadOnlyPorts :
        SnapshotPreviewPort,
        SelectorPreviewPort,
        CoordinatePreviewPort,
        ConditionPreviewPort {
        var snapshotCalls = 0
        var selectorCalls = 0
        var coordinateCalls = 0
        var conditionCalls = 0
        var actionCommitCount = 0
        var snapshotResult: SnapshotPreviewResult = SnapshotPreviewResult.Available(
            ReadOnlySnapshot(
                id = "snapshot-current",
                packageName = "com.example.notes",
                capturedAtMs = 1_000,
                expiresAtMs = 2_000,
                logicalWidth = 1080,
                logicalHeight = 2400,
            ),
        )
        var selectorResult: SelectorPreviewResult =
            SelectorPreviewResult.Unique(matchSummary = "save button")
        var coordinateResult: CoordinatePreviewResult =
            CoordinatePreviewResult.InBounds(x = 540, y = 1800)
        val conditionResults = ArrayDeque<ConditionPreviewResult>()

        override fun snapshot(targetPackages: Set<String>): SnapshotPreviewResult {
            snapshotCalls += 1
            return snapshotResult
        }

        override fun match(
            snapshot: ReadOnlySnapshot,
            target: JsonObject,
        ): SelectorPreviewResult {
            selectorCalls += 1
            return selectorResult
        }

        override fun preview(
            snapshot: ReadOnlySnapshot,
            coordinate: PreviewCoordinate,
        ): CoordinatePreviewResult {
            coordinateCalls += 1
            return coordinateResult
        }

        override fun check(
            snapshot: ReadOnlySnapshot,
            predicate: RecordedPredicate,
        ): ConditionPreviewResult {
            conditionCalls += 1
            return if (conditionResults.isEmpty()) {
                ConditionPreviewResult.Satisfied
            } else {
                conditionResults.removeFirst()
            }
        }
    }

    private fun script(vararg steps: RecordedStep): AutomationScript = AutomationScript(
        id = SCRIPT_ID,
        revision = 1,
        name = "Preview test",
        targetPackages = listOf("com.example.notes"),
        createdAt = "2026-07-25T10:00:00Z",
        steps = steps.toList(),
    )

    private fun step(id: String = STEP_ID): RecordedStep = RecordedStep(
        id = id,
        action = RecordedAction(
            type = "ui.click",
            params = buildJsonObject { put("target", selectorTarget()) },
        ),
    )

    private fun selectorTarget(): JsonObject = buildJsonObject {
        put("packageName", JsonPrimitive("com.example.notes"))
        put(
            "selectorCandidates",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("strategy", JsonPrimitive("resourceId"))
                        put("value", JsonPrimitive("com.example.notes:id/save"))
                        put("weight", JsonPrimitive(1.0))
                    },
                ),
            ),
        )
    }

    private fun visualTarget(point: NormalizedPoint): VisualTarget = VisualTarget(
        normalizedPoint = point,
        confidence = 1.0,
        source = RecordingProvenance.MANUAL,
    )

    private fun predicate(
        kind: String,
        operator: String,
    ): RecordedPredicate = RecordedPredicate(
        kind = kind,
        operator = operator,
        expected = if (kind == "package") JsonPrimitive("com.example.notes") else null,
        stableDurationMs = if (kind == "uiStable") 300 else null,
    )

    private fun File.fileNames(): Set<String> =
        listFiles().orEmpty().mapTo(linkedSetOf(), File::getName)

    private companion object {
        const val SCRIPT_ID = "30000000-0000-4000-8000-000000000001"
        const val STEP_ID = "40000000-0000-4000-8000-000000000001"
        const val SECOND_STEP_ID = "40000000-0000-4000-8000-000000000002"
    }
}
