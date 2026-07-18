package dev.aiauto.android.ui.recording

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import dev.aiauto.android.R
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun RecordingHost(
    viewModel: RecordingViewModel,
    onBack: () -> Unit,
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
                        onDelete = viewModel::deleteSelected,
                        onBack = viewModel::navigateBack,
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
