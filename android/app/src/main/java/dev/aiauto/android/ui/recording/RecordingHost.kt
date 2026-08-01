package dev.aiauto.android.ui.recording

/**
 * 功能用途：实现 RecordingHost 对应的录制、脚本预览、保存与回放交互。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

import dev.aiauto.android.R
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun RecordingHost(
    viewModel: RecordingViewModel,
    onBack: () -> Unit,
    observationProvider: RecordingEditorObservationProvider? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recording = uiState.recording

    when (uiState.destination) {
        RecordingDestination.LIST -> RecordingListScreen(
            scripts = recording.scripts,
            busy = recording.busy,
            errorMessage = recording.errorMessage,
            onStartRecording = viewModel::openNewRecording,
            onOpenScript = viewModel::openScript,
            onBack = onBack,
        )

        RecordingDestination.SESSION -> RecordingSessionScreen(
            draft = recording.draft,
            name = uiState.nameInput,
            targetPackages = uiState.targetPackagesInput,
            errorMessage = recording.errorMessage,
            onNameChanged = viewModel::updateName,
            onTargetPackagesChanged = viewModel::updateTargetPackages,
            onStart = viewModel::start,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onFinish = viewModel::finish,
            onCancel = viewModel::cancel,
            onBack = viewModel::navigateBack,
        )

        RecordingDestination.DETAIL -> {
            val script = uiState.selectedScript
            if (script == null) {
                RecordingDetailLoadingScreen(
                    errorMessage = recording.errorMessage,
                    onBack = viewModel::navigateBack,
                )
            } else {
                key(script.id) {
                    val secretValues = remember { mutableStateMapOf<String, String>() }
                    RecordingDetailScreen(
                        script = script,
                        replayReport = recording.replayReport,
                        busy = recording.busy,
                        errorMessage = recording.errorMessage,
                        requiredSecretRefs = uiState.requiredSecretRefs,
                        secretValues = secretValues,
                        onSecretChanged = { alias, value ->
                            secretValues[alias] = value.take(MAX_SECRET_INPUT_LENGTH)
                        },
                        onReplay = { viewModel.replay(secretValues.toMap()) },
                        onEdit = viewModel::openEditor,
                        onDelete = viewModel::deleteSelected,
                        onBack = viewModel::navigateBack,
                    )
                }
            }
        }

        RecordingDestination.EDITOR -> {
            val script = uiState.selectedScript
            if (script == null) {
                RecordingDetailLoadingScreen(
                    errorMessage = recording.errorMessage,
                    onBack = viewModel::navigateBack,
                )
            } else {
                key(script.id) {
                    val context = LocalContext.current
                    val observationHolder = remember(observationProvider, script.id) {
                        observationProvider?.acquire()
                    }
                    val editor: RecordingEditorViewModel = viewModel(
                        key = "recording-editor-${script.id}",
                        factory = RecordingEditorViewModel.factory(context, script),
                    )
                    val editorState by editor.uiState.collectAsStateWithLifecycle()
                    RecordingEditorScreen(
                        state = editorState,
                        observationHolder = observationHolder,
                        onSelectStep = editor::selectStep,
                        onMoveStep = editor::moveStep,
                        onSetEnabled = editor::setStepEnabled,
                        onDeleteStep = editor::deleteStep,
                        onDuplicateStep = editor::duplicateStep,
                        onUpdateForm = editor::updateStepForm,
                        onSubmitForm = { editor.submitStepForm() },
                        onObservationSelection = editor::applyObservationSelection,
                        onUndo = editor::undo,
                        onRedo = editor::redo,
                        onSave = {
                            when (val result = editor.save()) {
                                is dev.aiauto.android.automation.recording.editor.EditorSaveResult.Saved ->
                                    viewModel.closeEditor(result.script.id)

                                else -> Unit
                            }
                        },
                        onCopy = {
                            when (
                                val result = editor.saveCopy(
                                    "${editorState.script.name} 副本",
                                )
                            ) {
                                is dev.aiauto.android.automation.recording.editor.EditorSaveResult.Saved ->
                                    viewModel.openSavedCopy(result.script.id)

                                else -> Unit
                            }
                        },
                        onDryRun = editor::runDryRun,
                        onBack = {
                            if (editor.requestClose()) {
                                viewModel.closeEditor(editorState.script.id)
                            }
                        },
                        onDiscard = {
                            editor.discardChanges()
                            viewModel.closeEditor(editorState.script.id)
                        },
                        onDismissDiscard = editor::dismissDiscardConfirmation,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingDetailLoadingScreen(
    errorMessage: String?,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.recording_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (errorMessage == null) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.recording_loading),
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val MAX_SECRET_INPUT_LENGTH = 4_000
