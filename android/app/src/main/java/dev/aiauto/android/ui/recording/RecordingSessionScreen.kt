package dev.aiauto.android.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingDraft
import dev.aiauto.android.automation.recording.RecordingStatus
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun RecordingSessionScreen(
    draft: RecordingDraft,
    name: String,
    targetPackages: String,
    errorMessage: String?,
    onNameChanged: (String) -> Unit,
    onTargetPackagesChanged: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.recording_session_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RecordingStatusCard(draft.status)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.recording_name)) },
                enabled = draft.status == RecordingStatus.IDLE,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = targetPackages,
                onValueChange = onTargetPackagesChanged,
                label = { Text(stringResource(R.string.recording_target_packages)) },
                supportingText = {
                    Text(stringResource(R.string.recording_target_packages_help))
                },
                enabled = draft.status == RecordingStatus.IDLE,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Card {
                Text(
                    text = stringResource(R.string.recording_sensitive_notice),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (draft.status) {
                RecordingStatus.IDLE -> Button(
                    onClick = onStart,
                    enabled = name.isNotBlank() && targetPackages.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recording_start))
                }

                RecordingStatus.RECORDING -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.recording_pause))
                    }
                    Button(
                        onClick = onFinish,
                        enabled = draft.steps.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.recording_finish))
                    }
                }

                RecordingStatus.PAUSED -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onResume,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.recording_resume))
                    }
                    Button(
                        onClick = onFinish,
                        enabled = draft.steps.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.recording_finish))
                    }
                }
            }
            if (draft.status != RecordingStatus.IDLE) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recording_cancel))
                }
            }
            Text(
                text = stringResource(R.string.recording_step_preview),
                style = MaterialTheme.typography.titleMedium,
            )
            if (draft.steps.isEmpty()) {
                Text(
                    text = stringResource(R.string.recording_no_steps),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                draft.steps.takeLast(MAX_PREVIEW_STEPS).forEachIndexed { index, step ->
                    RecordingStepCard(
                        number = draft.steps.size - draft.steps
                            .takeLast(MAX_PREVIEW_STEPS).size + index + 1,
                        step = step,
                    )
                }
            }
            (draft.errorMessage ?: errorMessage)?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecordingStatusCard(status: RecordingStatus) {
    val text = when (status) {
        RecordingStatus.IDLE -> stringResource(R.string.recording_status_idle)
        RecordingStatus.RECORDING -> stringResource(R.string.recording_status_recording)
        RecordingStatus.PAUSED -> stringResource(R.string.recording_status_paused)
    }
    Card {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (status == RecordingStatus.RECORDING) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
internal fun RecordingStepCard(
    number: Int,
    step: RecordedStep,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$number. ${step.action.type}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.recording_step_time,
                    step.recordedAtMs,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if ("secretRef" in step.action.params) {
                Text(
                    text = stringResource(R.string.recording_secret_step),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private const val MAX_PREVIEW_STEPS = 20
