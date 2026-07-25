package dev.aiauto.android.ui.recording

/**
 * 测试用途：在真实 Android 运行时验证 RecordingScreensInstrumented 的设备能力、权限前提与生命周期边界。
 */

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingDraft
import dev.aiauto.android.automation.recording.RecordingStatus
import dev.aiauto.android.automation.recording.ReplayReport
import dev.aiauto.android.automation.recording.ReplayStepResult
import dev.aiauto.android.automation.recording.ReplayStepStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingScreensInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordingControlsExposeCapturedStepAndFinishAction() {
        val capturedStep = recordedStep(secretRef = "account.password")
        var finishCalls = 0

        composeRule.setContent {
            var draft by remember { mutableStateOf(RecordingDraft()) }
            MaterialTheme {
                RecordingSessionScreen(
                    draft = draft,
                    name = "Login",
                    targetPackages = "com.example.target",
                    errorMessage = null,
                    onNameChanged = {},
                    onTargetPackagesChanged = {},
                    onStart = {
                        draft = RecordingDraft(
                            status = RecordingStatus.RECORDING,
                            steps = listOf(capturedStep),
                        )
                    },
                    onPause = {
                        draft = draft.copy(status = RecordingStatus.PAUSED)
                    },
                    onResume = {
                        draft = draft.copy(status = RecordingStatus.RECORDING)
                    },
                    onFinish = { finishCalls += 1 },
                    onCancel = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordingTestTags.START)
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(RecordingTestTags.step(1))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingTestTags.PAUSE).performClick()
        composeRule.onNodeWithTag(RecordingTestTags.RESUME)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(RecordingTestTags.FINISH)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, finishCalls)
        }
    }

    @Test
    fun secretInputEnablesReplayAndForwardsTheAction() {
        val alias = "account.password"
        val script = script(recordedStep(secretRef = alias))
        var replayCalls = 0

        composeRule.setContent {
            var secret by remember { mutableStateOf("") }
            MaterialTheme {
                RecordingDetailScreen(
                    script = script,
                    replayReport = null,
                    busy = false,
                    errorMessage = null,
                    requiredSecretRefs = listOf(alias),
                    secretValues = mapOf(alias to secret),
                    onSecretChanged = { changedAlias, value ->
                        if (changedAlias == alias) {
                            secret = value
                        }
                    },
                    onReplay = { replayCalls += 1 },
                    onEdit = {},
                    onDelete = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordingTestTags.REPLAY).assertIsNotEnabled()
        composeRule.onNodeWithTag(RecordingTestTags.secretInput(alias))
            .performTextInput("s3cr3t")
        composeRule.onNodeWithTag(RecordingTestTags.REPLAY)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, replayCalls)
        }
    }

    @Test
    fun failedReplayShowsInterventionFeedback() {
        val report = ReplayReport(
            scriptId = "script-1",
            startedAtMs = 100,
            finishedAtMs = 200,
            succeeded = false,
            requiresIntervention = true,
            steps = listOf(
                ReplayStepResult(
                    stepId = "step-1",
                    status = ReplayStepStatus.FAILED,
                    attempts = 2,
                    route = "semantic",
                    errorCode = "SELECTOR_AMBIGUOUS",
                ),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                RecordingDetailScreen(
                    script = script(recordedStep()),
                    replayReport = report,
                    busy = false,
                    errorMessage = null,
                    requiredSecretRefs = emptyList(),
                    secretValues = emptyMap(),
                    onSecretChanged = { _, _ -> },
                    onReplay = {},
                    onEdit = {},
                    onDelete = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordingTestTags.REPLAY_RESULT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            text = "回放失败",
            substring = true,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingTestTags.REPLAY_INTERVENTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            text = "SELECTOR_AMBIGUOUS",
            substring = true,
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private fun script(step: RecordedStep) = AutomationScript(
        id = "script-1",
        name = "Login",
        targetPackages = listOf("com.example.target"),
        createdAt = "2026-07-19T00:00:00Z",
        steps = listOf(step),
    )

    private fun recordedStep(secretRef: String? = null) = RecordedStep(
        id = "step-1",
        recordedAtMs = 120,
        action = RecordedAction(
            type = "ui.setText",
            params = buildJsonObject {
                secretRef?.let { put("secretRef", JsonPrimitive(it)) }
            },
        ),
    )
}
