package dev.aiauto.android.ui.recording

/**
 * 测试用途：在 Compose instrumentation 中验证 N41 工具栏、步骤表单和未保存确认交互。
 */

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingEditorScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorToolbarStepFormAndDiscardDialogAreAutomatable() {
        val first = step("step-one")
        val second = step("step-two")
        var selected: String? = null
        var submitCalls = 0
        var discardCalls = 0
        val script = AutomationScript(
            id = "script",
            name = "Editor",
            targetPackages = listOf("com.example"),
            createdAt = "2026-07-26T00:00:00Z",
            steps = listOf(first, second),
        )

        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    RecordingEditorUiState(
                        script = script,
                        selectedStepId = first.id,
                        stepForm = RecordingStepFormState(selectorValue = "Save"),
                        showDiscardConfirmation = true,
                        canUndo = true,
                        isDirty = true,
                    ),
                )
            }
            MaterialTheme {
                RecordingEditorScreen(
                    state = state,
                    observationHolder = null,
                    onSelectStep = {
                        selected = it
                        state = state.copy(selectedStepId = it)
                    },
                    onMoveStep = { _, _ -> },
                    onSetEnabled = { _, _ -> },
                    onDeleteStep = {},
                    onDuplicateStep = {},
                    onUpdateForm = { transform ->
                        state = state.copy(stepForm = state.stepForm?.let(transform))
                    },
                    onSubmitForm = { submitCalls += 1 },
                    onObservationSelection = {},
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onCopy = {},
                    onDryRun = {},
                    onBack = {},
                    onDiscard = { discardCalls += 1 },
                    onDismissDiscard = {
                        state = state.copy(showDiscardConfirmation = false)
                    },
                )
            }
        }

        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_UNDO)
            .assertIsEnabled()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingTestTags.editorStep(second.id))
            .performClick()
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_FORM_SUBMIT)
            .performClick()
        composeRule.onNodeWithText("丢弃").performClick()

        composeRule.runOnIdle {
            assertEquals(second.id, selected)
            assertEquals(1, submitCalls)
            assertEquals(1, discardCalls)
        }
    }

    private fun step(id: String) = RecordedStep(
        id = id,
        action = RecordedAction("ui.click", JsonObject(emptyMap())),
    )
}
