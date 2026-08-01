package dev.aiauto.android.automation.recording.editor

/**
 * 测试用途：验证录制编辑命令可逆、长历史确定性以及保存冲突不会丢失任一版本。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.RetryPolicy
import dev.aiauto.android.automation.recording.ScriptEnvironment
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEditorSessionTest {
    @Test
    fun `every editor command supports undo and redo`() {
        val original = script()
        val selector = JsonArray(
            listOf(
                buildJsonObject {
                    put("strategy", JsonPrimitive("contentDescription"))
                    put("value", JsonPrimitive("Submit"))
                    put("weight", JsonPrimitive(1.0))
                    put("required", JsonPrimitive(true))
                },
            ),
        )
        val replacement = original.steps.first().copy(
            action = RecordedAction("ui.back", JsonObject(emptyMap())),
        )
        val commands = listOf(
            InsertStep(1, step(INSERTED_STEP_ID)),
            DeleteStep(SECOND_STEP_ID),
            DuplicateStep(FIRST_STEP_ID, DUPLICATED_STEP_ID, 1),
            ReorderStep(FIRST_STEP_ID, 1),
            SetStepEnabled(FIRST_STEP_ID, false),
            UpdateStep(FIRST_STEP_ID, replacement),
            EditStepSelector(FIRST_STEP_ID, selector),
            EditStepCoordinate(FIRST_STEP_ID, NormalizedPoint(0.25, 0.75)),
            EditStepAuthorizedVisualTarget(
                stepId = FIRST_STEP_ID,
                normalizedPoint = NormalizedPoint(0.4, 0.6),
                observationId = OBSERVATION_ID,
                imageSha256 = IMAGE_SHA256,
            ),
            EditStepWait(
                FIRST_STEP_ID,
                waitBefore = predicate("package", "equals"),
                waitAfter = predicate("uiStable", "stable"),
            ),
            EditStepRetry(FIRST_STEP_ID, RetryPolicy(maxAttempts = 4, backoffMs = 500)),
            EditStepFailurePolicy(FIRST_STEP_ID, "continue"),
            EditStepNotes(FIRST_STEP_ID, "人工确认后继续"),
        )

        commands.forEach { command ->
            val editor = ScriptEditorSession(original)

            assertTrue(command::class.simpleName.orEmpty(), editor.apply(command) is EditResult.Applied)
            val edited = editor.script
            assertNotEquals(command::class.simpleName.orEmpty(), original, edited)
            assertTrue(editor.undo())
            assertEquals(command::class.simpleName.orEmpty(), original, editor.script)
            assertTrue(editor.redo())
            assertEquals(command::class.simpleName.orEmpty(), edited, editor.script)
        }
    }

    @Test
    fun `copy creates an independent revision one script without mutating source`() {
        val original = script().copy(revision = 9)

        val copied = copyAutomationScript(
            source = original,
            newId = COPIED_SCRIPT_ID,
            newName = "可编辑副本",
            createdAt = "2026-07-25T12:00:00Z",
        )

        assertEquals(COPIED_SCRIPT_ID, copied.id)
        assertEquals("可编辑副本", copied.name)
        assertEquals(1L, copied.revision)
        assertEquals(RecordingProvenance.MANUAL, copied.provenance)
        assertEquals(copied.createdAt, copied.updatedAt)
        assertEquals(original.steps, copied.steps)
        assertEquals(9L, original.revision)
    }

    @Test
    fun `one hundred deterministic edits undo to original and redo to final state`() {
        val original = script(stepCount = 5)
        val editor = ScriptEditorSession(original)
        val random = Random(40)
        var nextId = 100

        repeat(100) { index ->
            val steps = editor.script.steps
            val selected = steps[random.nextInt(steps.size)]
            val command = when (random.nextInt(7)) {
                0 -> SetStepEnabled(selected.id, !selected.enabled)
                1 -> EditStepNotes(selected.id, "edit-$index")
                2 -> ReorderStep(selected.id, random.nextInt(steps.size))
                3 -> DuplicateStep(
                    sourceStepId = selected.id,
                    newStepId = uuid(nextId++),
                    insertIndex = random.nextInt(steps.size + 1),
                )

                4 -> if (steps.size > 1) {
                    DeleteStep(selected.id)
                } else {
                    SetStepEnabled(selected.id, !selected.enabled)
                }

                5 -> EditStepFailurePolicy(
                    selected.id,
                    if (selected.failurePolicy == "stop") "continue" else "stop",
                )

                else -> EditStepWait(
                    selected.id,
                    waitBefore = predicate("package", "equals", 1_000L + index),
                    waitAfter = null,
                )
            }
            assertTrue("edit $index", editor.apply(command) is EditResult.Applied)
        }

        val finalScript = editor.script
        assertEquals(100, editor.undoDepth)
        repeat(100) { assertTrue("undo $it", editor.undo()) }
        assertEquals(original, editor.script)
        assertEquals(100, editor.redoDepth)
        repeat(100) { assertTrue("redo $it", editor.redo()) }
        assertEquals(finalScript, editor.script)

        assertTrue(editor.undo())
        assertTrue(editor.apply(EditStepNotes(editor.script.steps.first().id, "branched")) is EditResult.Applied)
        assertEquals(0, editor.redoDepth)
        assertFalse(editor.redo())
    }

    @Test
    fun `invalid index id and duplicate id fail atomically`() {
        val editor = ScriptEditorSession(script())
        val original = editor.script
        val invalidCommands = listOf(
            InsertStep(-1, step(INSERTED_STEP_ID)),
            InsertStep(99, step(INSERTED_STEP_ID)),
            DeleteStep(MISSING_STEP_ID),
            DeleteStep(FIRST_STEP_ID, expectedIndex = 1),
            DuplicateStep(FIRST_STEP_ID, SECOND_STEP_ID, 1),
            ReorderStep(MISSING_STEP_ID, 0),
            ReorderStep(FIRST_STEP_ID, 99),
            SetStepEnabled(MISSING_STEP_ID, false),
            UpdateStep(FIRST_STEP_ID, step(INSERTED_STEP_ID)),
            EditStepSelector(MISSING_STEP_ID, JsonArray(emptyList())),
            EditStepCoordinate(FIRST_STEP_ID, NormalizedPoint(1.5, 0.5)),
        )

        invalidCommands.forEach { command ->
            assertTrue(command::class.simpleName.orEmpty(), editor.apply(command) is EditResult.Rejected)
            assertEquals(original, editor.script)
            assertEquals(0, editor.undoDepth)
            assertEquals(0, editor.redoDepth)
        }
    }

    @Test
    fun `authorized visual point and bounds commit complete metadata as one undoable command`() {
        val original = script()
        val editor = ScriptEditorSession(original)

        assertTrue(
            editor.apply(
                EditStepAuthorizedVisualTarget(
                    stepId = FIRST_STEP_ID,
                    normalizedPoint = NormalizedPoint(0.25, 0.75),
                    observationId = OBSERVATION_ID,
                    imageSha256 = IMAGE_SHA256,
                ),
            ) is EditResult.Applied,
        )
        val pointStep = editor.script.steps.first()
        assertEquals(RecordingProvenance.VISUAL, pointStep.provenance)
        assertEquals(NormalizedPoint(0.25, 0.75), pointStep.visualTarget?.normalizedPoint)
        assertEquals(null, pointStep.visualTarget?.normalizedBounds)
        assertEquals(OBSERVATION_ID, pointStep.visualTarget?.observationId)
        assertEquals(IMAGE_SHA256, pointStep.visualTarget?.imageSha256)
        assertEquals(1, editor.undoDepth)

        assertTrue(editor.undo())
        assertEquals(original, editor.script)
        assertTrue(
            editor.apply(
                EditStepAuthorizedVisualTarget(
                    stepId = FIRST_STEP_ID,
                    normalizedBounds = NormalizedBounds(0.2, 0.25, 0.8, 0.75),
                    observationId = OBSERVATION_ID,
                    imageSha256 = IMAGE_SHA256,
                ),
            ) is EditResult.Applied,
        )
        val boundsStep = editor.script.steps.first()
        assertEquals(null, boundsStep.visualTarget?.normalizedPoint)
        assertEquals(
            NormalizedBounds(0.2, 0.25, 0.8, 0.75),
            boundsStep.visualTarget?.normalizedBounds,
        )
        assertEquals(OBSERVATION_ID, boundsStep.visualTarget?.observationId)
        assertEquals(IMAGE_SHA256, boundsStep.visualTarget?.imageSha256)
        assertEquals(1, editor.undoDepth)
    }

    @Test
    fun `authorized visual metadata rejects partial or invalid values without history`() {
        val original = script()
        val invalidCommands = listOf(
            EditStepAuthorizedVisualTarget(
                stepId = FIRST_STEP_ID,
                normalizedPoint = NormalizedPoint(0.5, 0.5),
                normalizedBounds = NormalizedBounds(0.2, 0.2, 0.8, 0.8),
                observationId = OBSERVATION_ID,
                imageSha256 = IMAGE_SHA256,
            ),
            EditStepAuthorizedVisualTarget(
                stepId = FIRST_STEP_ID,
                normalizedBounds = NormalizedBounds(0.8, 0.2, 0.2, 0.8),
                observationId = OBSERVATION_ID,
                imageSha256 = IMAGE_SHA256,
            ),
            EditStepAuthorizedVisualTarget(
                stepId = FIRST_STEP_ID,
                normalizedPoint = NormalizedPoint(0.5, 0.5),
                observationId = "invalid observation",
                imageSha256 = IMAGE_SHA256,
            ),
            EditStepAuthorizedVisualTarget(
                stepId = FIRST_STEP_ID,
                normalizedPoint = NormalizedPoint(0.5, 0.5),
                observationId = OBSERVATION_ID,
                imageSha256 = "invalid",
            ),
        )

        invalidCommands.forEach { command ->
            val editor = ScriptEditorSession(original)
            assertTrue(editor.apply(command) is EditResult.Rejected)
            assertEquals(original, editor.script)
            assertEquals(0, editor.undoDepth)
        }
    }

    @Test
    fun `manual coordinate edit clears stale authorized metadata atomically`() {
        val authorized = script().copy(
            steps = script().steps.mapIndexed { index, step ->
                if (index == 0) {
                    step.copy(
                        visualTarget = dev.aiauto.android.automation.recording.VisualTarget(
                            normalizedPoint = NormalizedPoint(0.25, 0.75),
                            confidence = 1.0,
                            source = RecordingProvenance.VISUAL,
                            observationId = OBSERVATION_ID,
                            imageSha256 = IMAGE_SHA256,
                        ),
                    )
                } else {
                    step
                }
            },
        )
        val editor = ScriptEditorSession(authorized)

        assertTrue(
            editor.apply(
                EditStepCoordinate(FIRST_STEP_ID, NormalizedPoint(0.5, 0.5)),
            ) is EditResult.Applied,
        )

        val target = requireNotNull(editor.script.steps.first().visualTarget)
        assertEquals(NormalizedPoint(0.5, 0.5), target.normalizedPoint)
        assertEquals(null, target.observationId)
        assertEquals(null, target.imageSha256)
    }

    @Test
    fun `dirty state protects close and discard restores saved baseline`() {
        val original = script()
        val editor = ScriptEditorSession(original)

        assertFalse(editor.isDirty)
        assertEquals(CloseDecision.Safe, editor.closeDecision())
        assertTrue(editor.apply(EditStepNotes(FIRST_STEP_ID, "unsaved")) is EditResult.Applied)
        assertTrue(editor.isDirty)
        assertEquals(
            CloseDecision.ConfirmDiscard(
                baselineRevision = original.revision,
                attempted = editor.script,
            ),
            editor.closeDecision(),
        )

        editor.discardUnsavedChanges()

        assertEquals(original, editor.script)
        assertFalse(editor.isDirty)
        assertEquals(0, editor.undoDepth)
    }

    @Test
    fun `successful save advances revision baseline and clears dirty history`() {
        val original = script().copy(revision = 7)
        val savePort = FakeSavePort { attempted, expectedRevision ->
            assertEquals(7L, expectedRevision)
            EditorPersistenceResult.Saved(attempted.copy(revision = 8))
        }
        val editor = ScriptEditorSession(original, savePort)
        assertTrue(editor.apply(EditStepNotes(FIRST_STEP_ID, "saved")) is EditResult.Applied)

        val result = editor.save()

        assertEquals(EditorSaveResult.Saved(editor.script), result)
        assertEquals(8L, editor.baselineRevision)
        assertFalse(editor.isDirty)
        assertEquals(0, editor.undoDepth)
        assertEquals(1, savePort.calls)
    }

    @Test
    fun `revision conflict preserves attempted and current versions while editor stays dirty`() {
        val original = script().copy(revision = 7)
        val current = original.copy(revision = 8, name = "磁盘新版本")
        lateinit var attempted: AutomationScript
        val savePort = FakeSavePort { value, expectedRevision ->
            attempted = value
            EditorPersistenceResult.Conflict(
                expectedRevision = expectedRevision,
                attempted = value,
                current = current,
            )
        }
        val editor = ScriptEditorSession(original, savePort)
        assertTrue(editor.apply(EditStepNotes(FIRST_STEP_ID, "本地修改")) is EditResult.Applied)

        val result = editor.save()

        assertEquals(
            EditorSaveResult.Conflict(
                expectedRevision = 7,
                attempted = attempted,
                current = current,
            ),
            result,
        )
        assertEquals(attempted, editor.script)
        assertEquals(current, (result as EditorSaveResult.Conflict).current)
        assertTrue(editor.isDirty)
        assertEquals(7L, editor.baselineRevision)
    }

    private class FakeSavePort(
        private val answer: (AutomationScript, Long) -> EditorPersistenceResult,
    ) : EditorSavePort {
        var calls = 0

        override fun save(
            script: AutomationScript,
            expectedRevision: Long,
        ): EditorPersistenceResult {
            calls += 1
            return answer(script, expectedRevision)
        }
    }

    private fun script(stepCount: Int = 2): AutomationScript = AutomationScript(
        id = SCRIPT_ID,
        revision = 1,
        name = "Editor test",
        targetPackages = listOf("com.example.notes"),
        createdAt = "2026-07-25T10:00:00Z",
        environment = ScriptEnvironment(logicalWidth = 1080, logicalHeight = 2400),
        steps = List(stepCount) { index ->
            step(if (index == 0) FIRST_STEP_ID else uuid(index + 1))
        },
    )

    private fun step(id: String): RecordedStep = RecordedStep(
        id = id,
        recordedAtMs = 100,
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

    private fun predicate(
        kind: String,
        operator: String,
        timeoutMs: Long = 2_000,
    ): RecordedPredicate = RecordedPredicate(
        kind = kind,
        operator = operator,
        expected = if (kind == "package") JsonPrimitive("com.example.notes") else null,
        stableDurationMs = if (kind == "uiStable") 300 else null,
        timeoutMs = timeoutMs,
    )

    private fun uuid(value: Int): String = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private companion object {
        const val SCRIPT_ID = "10000000-0000-4000-8000-000000000001"
        const val COPIED_SCRIPT_ID = "10000000-0000-4000-8000-000000000002"
        const val FIRST_STEP_ID = "20000000-0000-4000-8000-000000000001"
        const val SECOND_STEP_ID = "00000000-0000-4000-8000-000000000002"
        const val INSERTED_STEP_ID = "20000000-0000-4000-8000-000000000003"
        const val DUPLICATED_STEP_ID = "20000000-0000-4000-8000-000000000004"
        const val MISSING_STEP_ID = "20000000-0000-4000-8000-000000000099"
        const val OBSERVATION_ID = "n41-authorized-observation"
        const val IMAGE_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
