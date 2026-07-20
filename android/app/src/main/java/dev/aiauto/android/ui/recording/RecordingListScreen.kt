package dev.aiauto.android.ui.recording

// 界面用途：实现 RecordingListScreen 对应的录制、脚本预览、保存与回放交互。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun RecordingListScreen(
    scripts: List<AutomationScriptSummary>,
    busy: Boolean,
    errorMessage: String?,
    onStartRecording: () -> Unit,
    onOpenScript: (String) -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.recording_title),
        onBack = onBack,
    ) {
        LazyColumn(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(
                    onClick = onStartRecording,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recording_start))
                }
            }
            if (busy) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            errorMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!busy && scripts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.recording_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(
                items = scripts,
                key = AutomationScriptSummary::id,
            ) { script ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenScript(script.id) },
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = script.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.recording_step_count,
                                script.stepCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = script.targetPackages.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = script.createdAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
