package dev.aiauto.android.ui.session

/**
 * 界面用途：实现 AI 自动化会话的状态展示、确认、暂停与停止交互。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import dev.aiauto.android.R
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val session = uiState.session

    ScreenScaffold(
        title = stringResource(R.string.session_title),
        onBack = onBack,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SessionStatusCard(uiState)
            }
            if (!session.isRunning) {
                item {
                    OutlinedTextField(
                        value = uiState.taskInput,
                        onValueChange = viewModel::updateTask,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.session_task_label)) },
                        supportingText = {
                            Text(stringResource(R.string.session_task_supporting))
                        },
                        minLines = 3,
                        maxLines = 7,
                    )
                }
                item {
                    OutlinedTextField(
                        value = uiState.targetPackageInput,
                        onValueChange = viewModel::updateTargetPackage,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.session_target_label)) },
                        singleLine = true,
                    )
                }
                if (uiState.availableTargetPackages.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.session_authorized_targets),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            uiState.availableTargetPackages.forEach { targetPackage ->
                                FilterChip(
                                    selected = targetPackage == uiState.targetPackageInput,
                                    onClick = {
                                        viewModel.updateTargetPackage(targetPackage)
                                    },
                                    label = { Text(targetPackage) },
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.session_screenshot_label),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.session_screenshot_supporting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.screenshotsAllowed,
                            onCheckedChange = viewModel::updateScreenshotsAllowed,
                            enabled = !uiState.session.isRunning,
                        )
                    }
                }
                item {
                    Button(
                        onClick = viewModel::start,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.taskInput.isNotBlank() &&
                            uiState.targetPackageInput.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.session_start))
                    }
                }
            }

            session.pendingConfirmation?.let { confirmation ->
                item {
                    ConfirmationCard(
                        actionType = confirmation.actionType,
                        reason = confirmation.reason,
                        onConfirm = { viewModel.confirm(confirmation.id, true) },
                        onReject = { viewModel.confirm(confirmation.id, false) },
                    )
                }
            }

            if (session.isRunning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (session.canPause) {
                            OutlinedButton(
                                onClick = viewModel::pause,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.session_pause))
                            }
                        }
                        if (session.canResume) {
                            Button(
                                onClick = viewModel::resume,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.session_resume))
                            }
                        }
                        Button(
                            onClick = viewModel::stop,
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(stringResource(R.string.session_stop))
                        }
                    }
                }
            }

            if (session.audit.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.session_audit_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(session.audit.asReversed(), key = { it.sequence }) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Text(
                                text = phaseLabel(entry.phase),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = entry.message,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.session_back_home))
                }
            }
        }
    }
}

@Composable
private fun SessionStatusCard(uiState: SessionUiState) {
    val session = uiState.session
    val containerColor = when (session.phase) {
        SessionPhase.Failed -> MaterialTheme.colorScheme.errorContainer
        SessionPhase.Stopped -> MaterialTheme.colorScheme.surfaceVariant
        SessionPhase.Completed -> MaterialTheme.colorScheme.primaryContainer
        SessionPhase.AwaitingConfirmation -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = phaseLabel(session.phase),
                style = MaterialTheme.typography.titleLarge,
            )
            if (session.step > 0) {
                Text(stringResource(R.string.session_step, session.step))
            }
            session.currentActionType?.let {
                Text(stringResource(R.string.session_current_action, it))
            }
            session.observationSummary?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            session.completionSummary?.let {
                Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            session.failureMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            uiState.setupError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConfirmationCard(
    actionType: String,
    reason: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.session_confirmation_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(actionType, style = MaterialTheme.typography.labelLarge)
            Text(reason)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.session_reject))
                }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.session_confirm))
                }
            }
        }
    }
}

@Composable
private fun phaseLabel(phase: SessionPhase): String = stringResource(
    when (phase) {
        SessionPhase.Idle -> R.string.session_phase_idle
        SessionPhase.Observing -> R.string.session_phase_observing
        SessionPhase.Planning -> R.string.session_phase_planning
        SessionPhase.AwaitingConfirmation -> R.string.session_phase_confirmation
        SessionPhase.Executing -> R.string.session_phase_executing
        SessionPhase.Verifying -> R.string.session_phase_verifying
        SessionPhase.Paused -> R.string.session_phase_paused
        SessionPhase.Completed -> R.string.session_phase_completed
        SessionPhase.Failed -> R.string.session_phase_failed
        SessionPhase.Stopped -> R.string.session_phase_stopped
    },
)
