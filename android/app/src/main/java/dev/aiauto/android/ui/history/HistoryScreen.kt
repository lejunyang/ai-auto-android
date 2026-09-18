package dev.aiauto.android.ui.history

/**
 * 功能用途：实现 C 端"记录"主页面，展示真实会话运行历史。
 * 按原型 history-all 单列宽卡列表：页头(info) + 区块标题 + filter-tabs +
 * 记录卡(状态tag+标题固定"自动化任务"+状态说明+元信息+chevron) + 隐私note。
 *
 * 数据来源：SessionHistoryStore（append-only、有界、损坏文件明确反馈）。
 * 筛选基于真实 phase 字段：全部 / 已完成（Completed）/ 需处理（Failed）。
 * 安全策略：不保存用户输入的原始任务描述，仅保存固定摘要「自动化任务」+ 结构字段。
 */

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import dev.aiauto.android.ui.components.ProtoCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.automation.session.history.HistoryListResult
import dev.aiauto.android.automation.session.history.SessionHistoryRecord
import dev.aiauto.android.automation.session.history.SessionHistoryStore
import dev.aiauto.android.ui.components.HistoryLoadState
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.components.produceHistoryRecords
import dev.aiauto.android.ui.theme.AppColors
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
    // info 对话框显示状态。
    var showInfoDialog by remember { mutableStateOf(false) }

    // 根据筛选条件过滤记录。
    val allRecords = when (val state = loadState) {
        is HistoryLoadState.Success -> state.records
        is HistoryLoadState.Partial -> state.records
        else -> emptyList()
    }
    val filteredRecords = when (selectedFilter) {
        HistoryFilter.ALL -> allRecords
        HistoryFilter.DONE -> allRecords.filter { it.phase == SessionPhase.Completed }
        HistoryFilter.ATTENTION -> allRecords.filter { it.needsAttention }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // 1. 页面头部：左侧"运行记录"标题与副标题，右侧 info 按钮。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.history_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight(650),
                        lineHeight = 24.sp,
                        letterSpacing = (-0.7).sp,
                    )
                    Text(
                        text = stringResource(R.string.history_subtitle),
                        fontSize = 12.sp,
                        color = AppColors.Muted,
                        lineHeight = 16.sp,
                    )
                }
                IconButton(onClick = { showInfoDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.history_info_dialog_title),
                        tint = AppColors.Muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // 2. 区块标题：h2.page-heading「做过的，都在这里。」。
        item {
            Text(
                text = stringResource(R.string.history_page_heading),
                fontSize = 24.sp,
                fontWeight = FontWeight(700),
                lineHeight = 36.sp,
                letterSpacing = (-0.8).sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // 3. filter-tabs：全部 / 已完成 / 需处理。选中态 ink 底白字。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    val selected = selectedFilter == filter
                    Surface(
                        onClick = { selectedFilter = filter },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) AppColors.Ink else AppColors.FilterTabBackground,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(filter.labelRes),
                                fontSize = 12.sp,
                                color = if (selected) AppColors.Surface else AppColors.FilterTabText,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // 4. 加载/错误/部分损坏状态。
        when (val state = loadState) {
            is HistoryLoadState.Loading -> item {
                Row(
                    modifier = Modifier.padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProtoCircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = "加载记录中…",
                        fontSize = 13.sp,
                        color = AppColors.Muted,
                    )
                }
            }

            is HistoryLoadState.Error -> item {
                Text(
                    text = "记录加载失败：${state.message}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            is HistoryLoadState.Partial -> item {
                Text(
                    text = "有 ${state.corruptedCount} 条记录损坏已跳过",
                    fontSize = 11.sp,
                    color = AppColors.Muted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            else -> Unit
        }

        // 5. 空态：紧凑文字。
        if (filteredRecords.isEmpty() && loadState is HistoryLoadState.Success) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.history_empty_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.history_empty_desc),
                        fontSize = 12.sp,
                        color = AppColors.Muted,
                    )
                }
            }
        }

        // 6. 记录列表：单列宽卡。
        items(
            items = filteredRecords,
            key = SessionHistoryRecord::id,
        ) { record ->
            HistoryRecordCard(
                record = record,
                onClick = { selectedRecord = record },
            )
            Spacer(Modifier.height(10.dp))
        }

        // 7. 底部隐私说明。
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.history_privacy_note),
                fontSize = 11.sp,
                color = AppColors.Muted,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // 历史详情对话框（真实只读详情，数据来自 SessionHistoryRecord）。
    selectedRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = { Text(stringResource(R.string.history_task_summary), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagChip(
                            text = if (record.needsAttention) stringResource(R.string.history_filter_attention) else stringResource(R.string.history_filter_done),
                            tone = if (record.needsAttention) TagTone.WARNING else TagTone.SUCCESS,
                        )
                        Text(
                            text = "${record.stepCount} 步",
                            fontSize = 12.sp,
                            color = AppColors.Muted,
                        )
                    }
                    Text(
                        text = "目标应用：${record.targetPackage}",
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "开始：${formatTime(record.startTimeMs)}",
                        fontSize = 12.sp,
                        color = AppColors.Muted,
                    )
                    Text(
                        text = "结束：${formatTime(record.endTimeMs)}",
                        fontSize = 12.sp,
                        color = AppColors.Muted,
                    )
                    Text(
                        text = stringResource(R.string.history_privacy_note),
                        fontSize = 11.sp,
                        color = AppColors.Muted,
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

    // info 对话框。
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.history_info_dialog_title)) },
            text = { Text(stringResource(R.string.history_info_dialog_content)) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/**
 * 单条历史记录卡片：按原型 .card.skill-list-card 单列宽卡。
 * 顶部 row between = 左状态 tag + 右 chevron；h3 标题固定「自动化任务」；
 * p 状态说明（已完成/需处理）；底部 small muted 元信息「目标包名 · 时间 · N 步」。
 */
@Composable
private fun HistoryRecordCard(
    record: SessionHistoryRecord,
    onClick: () -> Unit,
) {
    val tone = when {
        record.needsAttention -> TagTone.WARNING
        else -> TagTone.SUCCESS
    }
    // 状态标签固定显示"已完成"/"需处理"，不使用 record.statusLabel（可能为"失败"等）。
    val statusLabel = if (record.needsAttention) {
        stringResource(R.string.history_filter_attention)
    } else {
        stringResource(R.string.history_filter_done)
    }
    val statusDesc = if (record.needsAttention) {
        stringResource(R.string.history_status_attention_desc)
    } else {
        stringResource(R.string.history_status_done_desc)
    }
    val meta = buildString {
        append(record.targetPackage)
        append(" · ")
        append(formatTime(record.endTimeMs))
        append(" · ")
        append(record.stepCount).append(" 步")
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = AppColors.Line,
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(17.dp)) {
            // 顶部 row between：左状态 tag + 右 chevron。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagChip(text = statusLabel, tone = tone)
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.skills_view_steps),
                    tint = AppColors.Muted,
                    modifier = Modifier.size(16.dp),
                )
            }

            // h3 标题：固定「自动化任务」（TASK_SUMMARY 安全脱敏）。
            Text(
                text = stringResource(R.string.history_task_summary),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 7.dp),
            )

            // p 状态说明：11px muted、line-height1.8。
            Text(
                text = statusDesc,
                fontSize = 11.sp,
                color = AppColors.Muted,
                lineHeight = 19.8.sp,
            )

            // 底部 small muted 元信息（margin-top14）。
            Text(
                text = meta,
                fontSize = 11.sp,
                color = AppColors.Muted,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** 将 epoch millis 格式化为本地日期时间。 */
private fun formatTime(epochMs: Long): String = runCatching {
    val instant = Instant.ofEpochMilli(epochMs)
    val localZone = instant.atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(localZone)
}.getOrDefault(epochMs.toString())
