package dev.aiauto.android.ui.history

/**
 * 功能用途：实现 C 端"记录"主页面。当前没有独立的会话运行历史持久化，
 * 因此以已保存的录制脚本作为可复用运行记录展示；三个筛选标签仅维护视觉选中态，
 * 不伪造运行状态字段，空列表时展示空态并说明隐私边界。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.ui.components.EmptyState
import dev.aiauto.android.ui.components.GreetingHeader
import dev.aiauto.android.ui.components.SkillCard
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记录页筛选选项。当前数据模型没有运行状态字段（已完成/需处理），
 * 三个选项均展示全部脚本，仅维护选中视觉态，不伪造状态。
 */
private enum class HistoryFilter(val labelRes: Int) {
    ALL(R.string.history_filter_all),
    DONE(R.string.history_filter_done),
    ATTENTION(R.string.history_filter_attention),
}

@Composable
fun HistoryScreen(
    scriptStore: RecordingScriptStore,
    onOpenScript: (String) -> Unit,
) {
    // 从真实存储读取脚本列表；list() 已按创建时间倒序返回，不在此硬编码任何示例记录。
    val scripts = remember(scriptStore) { scriptStore.list() }
    // 当前选中的筛选标签；当前模型无运行状态，仅作视觉态管理。
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 页面头部：标题"运行记录"与副标题。
        item {
            GreetingHeader(
                title = stringResource(R.string.history_title),
                subtitle = stringResource(R.string.history_subtitle),
            )
        }

        // 筛选标签行：全部 / 已完成 / 需处理。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(stringResource(filter.labelRes)) },
                    )
                }
            }
        }

        // 空态：无脚本时展示说明文案，引导用户先运行任务或回放技能。
        if (scripts.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_empty_desc),
                )
            }
        } else {
            // 记录列表：每个脚本一张记录卡片，点击回调 onOpenScript(script.id)。
            items(
                items = scripts,
                key = AutomationScriptSummary::id,
            ) { script ->
                RecordCard(
                    script = script,
                    onClick = { onOpenScript(script.id) },
                )
            }
        }

        // 底部隐私说明：只保留脚本元数据与动作摘要，不保存敏感内容。
        item {
            Text(
                text = stringResource(R.string.history_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

/**
 * 单条记录卡片：复用 SkillCard 展示脚本名称、步骤数与目标应用、创建时间，
 * 并在卡片上方右侧用 TagChip 标记"已保存"状态。
 */
@Composable
private fun RecordCard(
    script: AutomationScriptSummary,
    onClick: () -> Unit,
) {
    // 描述由"X 步"与"目标：…"两段拼接，中间使用中性分隔符，中文文案均走 stringResource。
    val stepsText = stringResource(R.string.history_steps, script.stepCount)
    val targetText = stringResource(
        R.string.skills_target_apps,
        script.targetPackages.joinToString("、"),
    )
    // 元数据：创建于 + 格式化后的本地日期时间。
    val createdText = stringResource(
        R.string.history_created_at,
        formatCreatedAt(script.createdAt),
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 状态标签右对齐置于卡片上方。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TagChip(
                text = stringResource(R.string.history_saved_tag),
                tone = TagTone.SUCCESS,
            )
        }
        SkillCard(
            title = script.name,
            description = "$stepsText · $targetText",
            meta = createdText,
            onClick = onClick,
        )
    }
}

/**
 * 将 ISO instant 字符串解析并格式化为本地日期时间（yyyy-MM-dd HH:mm）。
 * 解析失败时回退原始字符串，避免时间格式异常导致页面崩溃。
 */
private fun formatCreatedAt(raw: String): String = runCatching {
    val instant = Instant.parse(raw)
    val localZone = instant.atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(localZone)
}.getOrDefault(raw)
