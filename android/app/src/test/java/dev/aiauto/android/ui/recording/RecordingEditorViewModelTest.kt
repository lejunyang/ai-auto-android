package dev.aiauto.android.ui.recording

/**
 * 测试用途：验证 N41 编辑状态只适配 N40 公共事务，并保持原子表单、冲突和 dry-run 语义。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.editor.ConditionPreviewPort
import dev.aiauto.android.automation.recording.editor.ConditionPreviewResult
import dev.aiauto.android.automation.recording.editor.CoordinatePreviewPort
import dev.aiauto.android.automation.recording.editor.CoordinatePreviewResult
import dev.aiauto.android.automation.recording.editor.DryRunErrorCode
import dev.aiauto.android.automation.recording.editor.DryRunStepStatus
import dev.aiauto.android.automation.recording.editor.EditorPersistenceResult
import dev.aiauto.android.automation.recording.editor.EditorSavePort
import dev.aiauto.android.automation.recording.editor.PreviewCoordinate
import dev.aiauto.android.automation.recording.editor.ReadOnlySnapshot
import dev.aiauto.android.automation.recording.editor.ScriptDryRunEngine
import dev.aiauto.android.automation.recording.editor.SelectorPreviewPort
import dev.aiauto.android.automation.recording.editor.SelectorPreviewResult
import dev.aiauto.android.automation.recording.editor.SnapshotPreviewPort
import dev.aiauto.android.automation.recording.editor.SnapshotPreviewResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEditorViewModelTest {
    @Test
    fun `step list operations and copy entry consume immutable editor commands BitsUT`() {
        val viewModel = viewModel()
        val first = FIRST_STEP_ID

        viewModel.moveStep(first, 1)
        viewModel.setStepEnabled(first, false)
        viewModel.duplicateStep(first)

        val edited = viewModel.uiState.value.script
        assertEquals(3, edited.steps.size)
        assertEquals(SECOND_STEP_ID, edited.steps.first().id)
        assertFalse(edited.steps[1].enabled)
        assertEquals(DUPLICATE_STEP_ID, edited.steps[2].id)

        viewModel.deleteStep(SECOND_STEP_ID)
        assertEquals(2, viewModel.uiState.value.script.steps.size)

        val copied = viewModel.copyScript("可编辑副本")
        assertEquals(COPIED_SCRIPT_ID, copied.id)
        assertEquals("可编辑副本", copied.name)
        assertEquals(1L, copied.revision)
        assertEquals(copied, viewModel.uiState.value.copyCandidate)
    }

    @Test
    fun `complete form applies as one undoable edit and invalid input stays atomic BitsUT`() {
        val viewModel = viewModel()
        viewModel.selectStep(FIRST_STEP_ID)
        viewModel.updateStepForm {
            it.copy(
                selectorStrategy = "contentDescription",
                selectorValue = "Save note",
                selectorWeight = "0.9",
                selectorRequired = true,
                coordinateX = "0.25",
                coordinateY = "0.75",
                waitBefore = PredicateFormState(
                    enabled = true,
                    kind = "package",
                    operator = "equals",
                    expected = "com.example.notes",
                    timeoutMs = "3000",
                ),
                waitAfter = PredicateFormState(
                    enabled = true,
                    kind = "uiStable",
                    operator = "stable",
                    stableDurationMs = "500",
                    timeoutMs = "4000",
                ),
                retryMaxAttempts = "4",
                retryBackoffMs = "600",
                retryBackoffMultiplier = "2.0",
                retryMaxBackoffMs = "5000",
                failurePolicy = "requestIntervention",
                notes = "提交前检查内容",
            )
        }

        assertTrue(viewModel.submitStepForm())

        val state = viewModel.uiState.value
        val step = state.script.steps.first()
        val target = step.action.params.getValue("target").jsonObject
        val selectors = target.getValue("selectorCandidates") as JsonArray
        assertEquals("contentDescription", selectors.first().jsonObject["strategy"]?.jsonPrimitive?.content)
        assertEquals(NormalizedPoint(0.25, 0.75), step.visualTarget?.normalizedPoint)
        assertEquals("package", step.waitBefore?.kind)
        assertEquals("uiStable", step.waitAfter?.kind)
        assertEquals(4, step.retry.maxAttempts)
        assertEquals("requestIntervention", step.failurePolicy)
        assertEquals("提交前检查内容", step.notes)
        assertTrue(state.canUndo)

        viewModel.undo()
        assertEquals(script().copy(revision = 7), viewModel.uiState.value.script)
        viewModel.redo()
        assertEquals(step, viewModel.uiState.value.script.steps.first())

        val beforeInvalid = viewModel.uiState.value.script
        viewModel.updateStepForm { it.copy(retryMaxAttempts = "99") }
        assertFalse(viewModel.submitStepForm())
        assertEquals(beforeInvalid, viewModel.uiState.value.script)
        assertNotNull(viewModel.uiState.value.formError)
    }

    @Test
    fun `dirty close save and revision conflict remain explicit BitsUT`() {
        val diskCurrent = script().copy(revision = 8, name = "磁盘版本")
        val savePort = object : EditorSavePort {
            override fun save(
                script: AutomationScript,
                expectedRevision: Long,
            ): EditorPersistenceResult = EditorPersistenceResult.Conflict(
                expectedRevision = expectedRevision,
                attempted = script,
                current = diskCurrent,
            )
        }
        val viewModel = viewModel(savePort = savePort)
        viewModel.selectStep(FIRST_STEP_ID)
        viewModel.updateStepForm { it.copy(notes = "本地版本") }
        assertTrue(viewModel.submitStepForm())

        assertFalse(viewModel.requestClose())
        assertTrue(viewModel.uiState.value.showDiscardConfirmation)

        viewModel.dismissDiscardConfirmation()
        viewModel.save()

        val conflict = requireNotNull(viewModel.uiState.value.revisionConflict)
        assertEquals(7L, conflict.expectedRevision)
        assertEquals("本地版本", conflict.attempted.steps.first().notes)
        assertEquals(diskCurrent, conflict.current)
        assertTrue(viewModel.uiState.value.isDirty)

        viewModel.discardChanges()
        assertFalse(viewModel.uiState.value.isDirty)
        assertTrue(viewModel.requestClose())
    }

    @Test
    fun `authorized point and bounds selections persist complete metadata atomically BitsUT`() {
        val pointSavePort = CapturingSavePort()
        val pointViewModel = viewModel(savePort = pointSavePort)
        pointViewModel.selectStep(FIRST_STEP_ID)

        pointViewModel.applyObservationSelection(
            ObservationSelection.Selected(
                x = 0.25,
                y = 0.75,
                observationId = OBSERVATION_ID,
                imageSha256 = IMAGE_SHA256,
            ),
        )
        assertTrue(pointViewModel.uiState.value.isDirty)
        pointViewModel.save()
        val point = requireNotNull(pointSavePort.saved?.steps?.first()?.visualTarget)
        assertEquals(NormalizedPoint(0.25, 0.75), point.normalizedPoint)
        assertEquals(null, point.normalizedBounds)
        assertEquals(OBSERVATION_ID, point.observationId)
        assertEquals(IMAGE_SHA256, point.imageSha256)

        val boundsSavePort = CapturingSavePort()
        val boundsViewModel = viewModel(savePort = boundsSavePort)
        boundsViewModel.selectStep(FIRST_STEP_ID)
        boundsViewModel.applyObservationSelection(
            ObservationSelection.BoundsSelected(
                bounds = NormalizedBounds(0.2, 0.25, 0.8, 0.75),
                observationId = OBSERVATION_ID,
                imageSha256 = IMAGE_SHA256,
            ),
        )
        boundsViewModel.save()
        val bounds = requireNotNull(boundsSavePort.saved?.steps?.first()?.visualTarget)
        assertEquals(null, bounds.normalizedPoint)
        assertEquals(NormalizedBounds(0.2, 0.25, 0.8, 0.75), bounds.normalizedBounds)
        assertEquals(OBSERVATION_ID, bounds.observationId)
        assertEquals(IMAGE_SHA256, bounds.imageSha256)
    }

    @Test
    fun `dry run report focuses the first failed step without replay capability BitsUT`() {
        val ports = FailingPreviewPorts()
        val viewModel = viewModel(
            dryRunEngine = ScriptDryRunEngine(
                snapshots = ports,
                selectors = ports,
                coordinates = ports,
                conditions = ports,
                clock = { 1_500 },
            ),
        )

        viewModel.runDryRun()

        val state = viewModel.uiState.value
        assertFalse(requireNotNull(state.dryRunReport).succeeded)
        assertEquals(FIRST_STEP_ID, state.focusedFailureStepId)
        assertEquals(FIRST_STEP_ID, state.selectedStepId)
        assertEquals(
            DryRunErrorCode.SELECTOR_AMBIGUOUS,
            state.dryRunReport?.steps?.first()?.errorCode,
        )
        assertEquals(DryRunStepStatus.FAILED, state.dryRunReport?.steps?.first()?.status)
        assertEquals(2, ports.selectorCalls)
    }

    private fun viewModel(
        savePort: EditorSavePort? = null,
        dryRunEngine: ScriptDryRunEngine? = null,
    ): RecordingEditorViewModel = RecordingEditorViewModel(
        initialScript = script().copy(revision = 7),
        savePort = savePort,
        dryRunEngine = dryRunEngine,
        scriptIdFactory = { COPIED_SCRIPT_ID },
        stepIdFactory = { DUPLICATE_STEP_ID },
        createdAtFactory = { "2026-07-26T00:00:00Z" },
    )

    private class FailingPreviewPorts :
        SnapshotPreviewPort,
        SelectorPreviewPort,
        CoordinatePreviewPort,
        ConditionPreviewPort {
        var selectorCalls = 0

        override fun snapshot(targetPackages: Set<String>): SnapshotPreviewResult =
            SnapshotPreviewResult.Available(
                ReadOnlySnapshot(
                    id = "snapshot",
                    packageName = "com.example.notes",
                    capturedAtMs = 1_000,
                    expiresAtMs = 2_000,
                    logicalWidth = 1080,
                    logicalHeight = 2400,
                ),
            )

        override fun match(
            snapshot: ReadOnlySnapshot,
            target: JsonObject,
        ): SelectorPreviewResult {
            selectorCalls += 1
            return SelectorPreviewResult.Ambiguous(2)
        }

        override fun preview(
            snapshot: ReadOnlySnapshot,
            coordinate: PreviewCoordinate,
        ): CoordinatePreviewResult = CoordinatePreviewResult.InBounds(540, 1200)

        override fun check(
            snapshot: ReadOnlySnapshot,
            predicate: dev.aiauto.android.automation.recording.RecordedPredicate,
        ): ConditionPreviewResult = ConditionPreviewResult.Satisfied
    }

    private class CapturingSavePort : EditorSavePort {
        var saved: AutomationScript? = null

        override fun save(
            script: AutomationScript,
            expectedRevision: Long,
        ): EditorPersistenceResult {
            val persisted = script.copy(revision = expectedRevision + 1)
            saved = persisted
            return EditorPersistenceResult.Saved(persisted)
        }
    }

    private fun script(): AutomationScript = AutomationScript(
        id = SCRIPT_ID,
        revision = 1,
        name = "Editor UI",
        targetPackages = listOf("com.example.notes"),
        createdAt = "2026-07-25T00:00:00Z",
        environment = ScriptEnvironment(logicalWidth = 1080, logicalHeight = 2400),
        steps = listOf(step(FIRST_STEP_ID), step(SECOND_STEP_ID)),
    )

    private fun step(id: String): RecordedStep = RecordedStep(
        id = id,
        action = RecordedAction(
            type = "ui.click",
            params = buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
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
                    },
                )
            },
        ),
    )

    private companion object {
        const val SCRIPT_ID = "10000000-0000-4000-8000-000000000001"
        const val COPIED_SCRIPT_ID = "10000000-0000-4000-8000-000000000002"
        const val FIRST_STEP_ID = "20000000-0000-4000-8000-000000000001"
        const val SECOND_STEP_ID = "20000000-0000-4000-8000-000000000002"
        const val DUPLICATE_STEP_ID = "20000000-0000-4000-8000-000000000003"
        const val OBSERVATION_ID = "n41-authorized-observation"
        const val IMAGE_SHA256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
