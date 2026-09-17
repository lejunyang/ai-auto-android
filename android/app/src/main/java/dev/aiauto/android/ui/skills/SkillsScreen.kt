package dev.aiauto.android.ui.skills

/**
 * 功能用途：C 端"技能"主页面。
 * 展示用户已录制的自动化脚本列表（头部标题、筛选标签、技能卡片、空态与录制引导），
 * 数据全部来自 RecordingScriptStore，不内置任何示例技能。
 * 按原型视觉：紧凑空态、虚线轻卡录制引导、"灵感"分组明确标注未实现。
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 技能筛选标签枚举；MVP 阶段无系统预置技能，"全部"与"我教的"实际均展示全部脚本。 */
private enum class SkillFilter {
    ALL,
    MINE,
}

/**
 * 技能主页面：从 scriptStore 读取真实脚本并渲染列表。
 *
 * @param scriptStore 脚本存储，list() 已按 createdAt 降序返回。
 * @param onOpenSkill 点击某个技能卡片，回调脚本 id 进入详情/回放。
 * @param onStartRecording 点击头部 + 按钮或底部录制引导卡片，进入录制流程。
 */
@Composable
fun SkillsScreen(
    scriptStore: RecordingScriptStore,
    onOpenSkill: (String) -> Unit,
    onStartRecording: () -> Unit,
) {
    // 读取真实脚本列表；store 内部已按创建时间降序排序，这里不再二次排序。
    val scripts = remember { scriptStore.list() }
    // 当前选中的筛选标签，默认"全部"。
    var selectedFilter by remember { mutableStateOf(SkillFilter.ALL) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 页面头部：左侧"我的技能"标题与副标题，右侧 + 图标按钮触发录制。
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.skills_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.skills_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onStartRecording) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.skills_new),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 筛选标签行：全部 / 我教的。MVP 阶段两者内容一致，仅维护选中态。
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkillFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            val labelRes = when (filter) {
                                SkillFilter.ALL -> R.string.skills_filter_all
                                SkillFilter.MINE -> R.string.skills_filter_mine
                            }
                            Text(stringResource(labelRes), fontSize = 13.sp)
                        },
                    )
                }
            }
        }

        // 技能列表：无脚本时展示紧凑空态，否则逐张渲染真实技能卡片。
        if (scripts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.skills_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.skills_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(scripts, key = { it.id }) { script ->
                SkillGridCard(
                    title = script.name,
                    subtitle = stringResource(
                        R.string.skills_target_apps,
                        script.targetPackages.joinToString("、"),
                    ),
                    meta = buildScriptMeta(script),
                    onClick = { onOpenSkill(script.id) },
                )
            }
        }

        // 底部录制引导卡片：虚线轻卡，引导用户录制新技能。
        item(span = { GridItemSpan(maxLineSpan) }) {
            TeachLightCard(onClick = onStartRecording)
        }

        // 底部安全间距。
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

/** 虚线轻卡录制引导：按原型视觉，虚线边框 + 录制图标 + 标题/副标题。 */
@Composable
private fun TeachLightCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.RadioButtonChecked, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.skills_teach_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.skills_teach_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 拼接技能卡片元信息：步骤数与创建时间。
 * 复用通用格式串 history_steps（"N 步"）与 history_created_at（"创建于 %1$s"）。
 */
@Composable
private fun buildScriptMeta(script: AutomationScriptSummary): String {
    val stepsText = stringResource(R.string.history_steps, script.stepCount)
    val createdAtText = stringResource(R.string.history_created_at, formatCreatedAt(script.createdAt))
    return "$stepsText · $createdAtText"
}

/**
 * 将 ISO instant 字符串格式化为本地日期时间（yyyy-MM-dd HH:mm）。
 * 解析失败时回退为原始字符串，避免崩溃或展示空时间。
 */
private fun formatCreatedAt(iso: String): String {
    return try {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(formatter)
    } catch (e: Exception) {
        iso
    }
}

/**
 * 技能网格卡片：按原型 2 列网格视觉，紧凑卡片含彩色图标块 + 标题 + 副标题 + 元信息。
 * 卡片有边框 + 冷白底 + 无阴影，与原型卡片语言一致。
 */
@Composable
private fun SkillGridCard(
    title: String,
    subtitle: String,
    meta: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 彩色图标块：35x35，圆角 11，背景 primaryContainer。
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(35.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // 标题：tileTitle 13sp/700。
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            // 副标题：tileMeta 12sp/400。
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // 元信息：步骤数 + 创建时间。
            Text(
                text = meta,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
