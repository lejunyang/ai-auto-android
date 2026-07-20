package dev.aiauto.android.ui.recording

/**
 * 界面用途：实现 RecordingDetailScreen 对应的录制、脚本预览、保存与回放交互。
 */

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.ReplayReport
import dev.aiauto.android.automation.recording.ReplayStepStatus
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun RecordingDetailScreen(
    script: AutomationScript,
    replayReport: ReplayReport?,
    busy: Boolean,
    errorMessage: String?,
    requiredSecretRefs: List<String>,
    secretValues: Map<String, String>,
    onSecretChanged: (String, String) -> Unit,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = script.name,
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = script.targetPackages.joinToString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.recording_step_count, script.steps.size),
                style = MaterialTheme.typography.titleMedium,
            )
            if (requiredSecretRefs.isNotEmpty()) {
                Card {
                    Text(
                        text = stringResource(R.string.recording_secret_required),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                requiredSecretRefs.forEach { alias ->
                    OutlinedTextField(
                        value = secretValues[alias].orEmpty(),
                        onValueChange = { value -> onSecretChanged(alias, value) },
                        label = {
                            Text(stringResource(R.string.recording_secret_label, alias))
                        },
                        supportingText = {
                            Text(stringResource(R.string.recording_secret_memory_notice))
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(RecordingTestTags.secretInput(alias)),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onReplay,
                    enabled = !busy && hasRequiredSecrets(
                        requiredRefs = requiredSecretRefs,
                        secretValues = secretValues,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RecordingTestTags.REPLAY),
                ) {
                    Text(stringResource(R.string.recording_replay))
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.recording_delete))
                }
            }
            errorMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
            replayReport?.let { report ->
                ReplayReportCard(report)
            }
            Text(
                text = stringResource(R.string.recording_all_steps),
                style = MaterialTheme.typography.titleMedium,
            )
            script.steps.forEachIndexed { index, step ->
                RecordingStepCard(number = index + 1, step = step)
            }
        }
    }
}

@Composable
private fun ReplayReportCard(report: ReplayReport) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingTestTags.REPLAY_RESULT),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (report.succeeded) {
                    stringResource(R.string.recording_replay_succeeded)
                } else {
                    stringResource(R.string.recording_replay_failed)
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (report.succeeded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (report.requiresIntervention) {
                Text(
                    text = stringResource(R.string.recording_replay_intervention),
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag(RecordingTestTags.REPLAY_INTERVENTION),
                )
            }
            report.steps.forEachIndexed { index, step ->
                val status = when (step.status) {
                    ReplayStepStatus.SUCCEEDED -> "OK"
                    ReplayStepStatus.FAILED -> "FAILED"
                    ReplayStepStatus.SKIPPED -> "SKIPPED"
                }
                Text(
                    text = "${index + 1}. $status · ${step.route.orEmpty()} · " +
                        "${step.errorCode.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
