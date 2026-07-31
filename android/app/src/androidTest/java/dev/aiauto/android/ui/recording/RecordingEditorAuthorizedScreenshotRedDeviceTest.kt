package dev.aiauto.android.ui.recording

/**
 * 测试用途：锁定 N41 授权截图点选、框选、元数据保存和真实入口注入的预期 RED 契约。
 */

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.NormalizedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingControllerState
import dev.aiauto.android.automation.recording.RecordingCoordinator
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.ScriptEnvironmentProvider
import dev.aiauto.android.automation.recording.editor.EditorPersistenceResult
import dev.aiauto.android.automation.recording.editor.EditorSavePort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 测试用途：验证授权截图租约安全，并锁定入口注入、框选和视觉元数据保存的设备 RED 契约。 */
@RunWith(AndroidJUnit4::class)
class RecordingEditorAuthorizedScreenshotRedDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val providers = mutableListOf<DebugAuthorizedScreenshotProvider>()

    @After
    fun tearDown() {
        providers.forEach(DebugAuthorizedScreenshotProvider::close)
        providers.clear()
    }

    @Test
    fun debugProviderUsesSyntheticSingleLeaseAndExpiresClosed() {
        var nowMs = 1_000L
        val provider = provider(
            clock = { nowMs },
            ttlMs = 100L,
        )
        val first = provider.authorize()
        assertEquals(first.metadata.observationId, first.holder.currentObservationId)
        assertTrue(first.metadata.imageSha256.matches(Regex("^[0-9a-f]{64}$")))

        val second = provider.authorize()
        assertEquals(null, first.holder.currentObservationId)
        assertEquals(second.metadata.observationId, second.holder.currentObservationId)

        nowMs = second.metadata.expiresAtMs + 1
        assertEquals(
            ObservationSelection.Unavailable,
            second.holder.selectNormalized(0.5, 0.5),
        )
        assertEquals(null, second.holder.currentObservationId)
    }

    @Test
    fun redRealDetailEntryConsumesExplicitDebugAuthorization() {
        val script = script()
        val coordinator = SelectedScriptCoordinator(script)
        val viewModel = RecordingViewModel(
            coordinator = coordinator,
            environmentProvider = ScriptEnvironmentProvider { script.environment!! },
        )
        val provider = provider()
        val authorization = provider.authorize()

        composeRule.setContent {
            MaterialTheme {
                RecordingHost(viewModel = viewModel, onBack = {})
            }
        }
        composeRule.runOnIdle { viewModel.openScript(script.id) }
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText(script.name).assertIsDisplayed()

        // RED：生产 RecordingHost 当前固定传 null，debug 授权无法注入真实详情编辑入口。
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_OBSERVATION)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun redTapSavesPointWithAuthorizedObservationMetadata() {
        val savePort = CapturingSavePort()
        val editor = RecordingEditorViewModel(
            initialScript = script(),
            savePort = savePort,
        )
        editor.selectStep(STEP_ID)
        val provider = provider()
        val authorization = provider.authorize()
        setEditorContent(editor, authorization)

        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_OBSERVATION)
            .performScrollTo()
            .performTouchInput { click(center) }
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_FORM_SUBMIT)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_SAVE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        val target = requireNotNull(savePort.saved?.steps?.single()?.visualTarget)
        assertEquals(NormalizedPoint(0.5, 0.5), target.normalizedPoint)
        // RED：现有 point 保存没有把授权 provenance 与 image hash 交给原子持久化路径。
        assertEquals(
            authorization.metadata.observationId to authorization.metadata.imageSha256,
            target.observationId to target.imageSha256,
        )
    }

    @Test
    fun redDragSavesNormalizedBoundsWithAuthorizedObservationMetadata() {
        val savePort = CapturingSavePort()
        val editor = RecordingEditorViewModel(
            initialScript = script(),
            savePort = savePort,
        )
        editor.selectStep(STEP_ID)
        val provider = provider()
        val authorization = provider.authorize()
        setEditorContent(editor, authorization)

        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_OBSERVATION)
            .performScrollTo()
            .performTouchInput {
                swipe(
                    start = percentOffset(0.2f, 0.25f),
                    end = percentOffset(0.8f, 0.75f),
                    durationMillis = 500L,
                )
            }
        // RED：拖动应生成可保存的 bounds 编辑；当前 surface 只有 tap，保存仍不可用。
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_SAVE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        val target = savePort.saved?.steps?.single()?.visualTarget
        assertEquals(
            NormalizedBounds(0.2, 0.25, 0.8, 0.75),
            target?.normalizedBounds,
        )
        assertEquals(
            authorization.metadata.observationId to authorization.metadata.imageSha256,
            target?.observationId to target?.imageSha256,
        )
    }

    private fun provider(
        clock: () -> Long = System::currentTimeMillis,
        ttlMs: Long = 10_000L,
    ): DebugAuthorizedScreenshotProvider =
        DebugAuthorizedScreenshotProvider(clock = clock, ttlMs = ttlMs).also(providers::add)

    private fun setEditorContent(
        editor: RecordingEditorViewModel,
        authorization: DebugAuthorizedScreenshotAuthorization,
    ) {
        composeRule.setContent {
            val state by editor.uiState.collectAsState()
            MaterialTheme {
                RecordingEditorScreen(
                    state = state,
                    observationHolder = authorization.holder,
                    onSelectStep = editor::selectStep,
                    onMoveStep = editor::moveStep,
                    onSetEnabled = editor::setStepEnabled,
                    onDeleteStep = editor::deleteStep,
                    onDuplicateStep = editor::duplicateStep,
                    onUpdateForm = editor::updateStepForm,
                    onSubmitForm = { editor.submitStepForm() },
                    onUndo = editor::undo,
                    onRedo = editor::redo,
                    onSave = { editor.save() },
                    onCopy = {},
                    onDryRun = editor::runDryRun,
                    onBack = {},
                    onDiscard = editor::discardChanges,
                    onDismissDiscard = editor::dismissDiscardConfirmation,
                )
            }
        }
    }

    private fun script(): AutomationScript = AutomationScript(
        id = SCRIPT_ID,
        name = "N41 设备授权截图",
        targetPackages = listOf("dev.aiauto.android.debug"),
        createdAt = "2026-08-01T00:00:00Z",
        environment = ScriptEnvironment(
            apiLevel = 34,
            logicalWidth = 1080,
            logicalHeight = 2400,
        ),
        steps = listOf(
            RecordedStep(
                id = STEP_ID,
                action = RecordedAction(
                    type = "ui.click",
                    params = JsonObject(
                        mapOf("target" to JsonObject(emptyMap())),
                    ),
                ),
            ),
        ),
    )

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

    private class SelectedScriptCoordinator(
        private val script: AutomationScript,
    ) : RecordingCoordinator {
        private val mutableState = MutableStateFlow(
            RecordingControllerState(selectedScript = script),
        )
        override val state: StateFlow<RecordingControllerState> = mutableState

        override fun start(
            name: String,
            targetPackages: Set<String>,
            environment: ScriptEnvironment,
        ) = Unit

        override fun pause() = Unit

        override fun resume() = Unit

        override fun cancelRecording() = Unit

        override fun finish(name: String) = Unit

        override fun refresh() = Unit

        override fun select(id: String) {
            mutableState.value = mutableState.value.copy(selectedScript = script)
        }

        override fun delete(id: String) = Unit

        override fun replay(
            script: AutomationScript,
            secrets: Map<String, String>,
        ) = Unit

        override fun close() = Unit
    }

    private companion object {
        const val SCRIPT_ID = "41000000-0000-4000-8000-000000000021"
        const val STEP_ID = "41000000-0000-4000-8000-000000000022"
    }
}
