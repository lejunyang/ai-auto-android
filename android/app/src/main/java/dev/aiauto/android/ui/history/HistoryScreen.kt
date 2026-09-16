package dev.aiauto.android.ui.history

/**
 * 功能用途：实现 C 端"记录"主页面，展示真实会话运行历史。
 *
 * 数据来源：SessionHistoryStore（append-only、有界、损坏文件明确反馈）。
 * 筛选基于真实 phase 字段：全部 / 已完成（Completed）/ 需处理（Failed）。
 * 安全策略：不保存用户输入的原始任务描述，仅保存固定摘要「自动化任务」+ 结构字段。
 * 自由文本可能包含密码/验证码/API key，无法可靠识别任意自由文本中的秘密。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.R
import dev.aiauto.android.automation.session.history.HistoryListResult
import dev.aiauto.android.automation.session.history.SessionHistoryRecord
import dev.aiauto.android.automation.session.history.SessionHistoryStore
import dev.aiauto.android.ui.components.HistoryLoadState
import dev.aiauto.android.ui.components.SkillCard
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.components.produceHistoryRecords
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 记录页筛选选项，基于真实 SessionHistoryRecord.phase。 */
private enum class HistoryFilter(val labelRes: Int) {
    ALL(R.string.history_filter_all),
    DONE(R.string.history_filter_done),
    ATTENTION(R.string.history_filter_attention),
}

@Composable
fun HistoryScreen(
    historyStore: SessionHistoryStore,
) {
    // 异步加载历史记录，IO dispatcher + ON_RESUME 刷新 + 错误态。
    val loadState by produceHistoryRecords(store = historyStore)
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selectedRecord by remember { mutableStateOf<SessionHistoryRecord?>(null) }

    // 根据筛选条件过滤记录。
    val allRecords = when (val state = loadState) {
        is HistoryLoadState.Success -> state.records
        is HistoryLoadState.Partial -> state.records
        else -> emptyList()
    }
    val filteredRecords = when (selectedFilter) {
        HistoryFilter.ALL -> allRecords
        HistoryFilter.DONE -> allRecords.filter { it.phase == dev.aiauto.android.automation.session.SessionPhase.Completed }
        HistoryFilter.ATTENTION -> allRecords.filter { it.needsAttention }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 页面头部：轻量标题 + 副标题，对齐原型。
        item {
            Column(
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.history_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 筛选标签行：全部 / 已完成 / 需处理，轻量小字号。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(stringResource(filter.labelRes), fontSize = 13.sp) },
                    )
                }
            }
        }

        // 加载/错误状态。
        when (val state = loadState) {
            is HistoryLoadState.Loading -> item {
                Row(
                    modifier = Modifier.padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = "加载记录中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is HistoryLoadState.Error -> item {
                Text(
                    text = "记录加载失败：${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            is HistoryLoadState.Partial -> item {
                Text(
                    text = "有 ${state.corruptedCount} 条记录损坏已跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            else -> Unit
        }

        // 空态：紧凑文字，不用大 EmptyState 组件。
        if (filteredRecords.isEmpty() && loadState is HistoryLoadState.Success) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.history_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.history_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 记录列表。
        items(
            items = filteredRecords,
            key = SessionHistoryRecord::id,
        ) { record ->
            HistoryRecordCard(
                record = record,
                onClick = { selectedRecord = record },
            )
        }

        // 底部隐私说明。
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.history_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // 历史详情对话框。
    selectedRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = { Text(record.task, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagChip(
                            text = record.statusLabel,
                            tone = if (record.needsAttention) TagTone.WARNING else TagTone.SUCCESS,
                        )
                        Text(
                            text = "${record.stepCount} 步",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "目标应用：${record.targetPackage}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "开始：${formatTime(record.startTimeMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "结束：${formatTime(record.endTimeMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "记录仅保存任务描述与结构字段，不保存模型输出或页面内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedRecord = null }) {
                    Text("关闭")
                }
            },
        )
    }
}

/** 单条历史记录卡片。 */
@Composable
private fun HistoryRecordCard(
    record: SessionHistoryRecord,
    onClick: () -> Unit,
) {
    val meta = buildString {
        append(formatTime(record.endTimeMs))
        append(" · ")
        append(record.stepCount).append(" 步")
    }
    val tone = when {
        record.needsAttention -> TagTone.WARNING
        else -> TagTone.SUCCESS
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TagChip(text = record.statusLabel, tone = tone)
        }
        SkillCard(
            title = record.task,
            description = "目标：${record.targetPackage}",
            meta = meta,
            onClick = onClick,
        )
    }
}

/** 将 epoch millis 格式化为本地日期时间。 */
private fun formatTime(epochMs: Long): String = runCatching {
    val instant = Instant.ofEpochMilli(epochMs)
    val localZone = instant.atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(localZone)
}.getOrDefault(epochMs.toString())
