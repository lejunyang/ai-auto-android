package dev.aiauto.android.ui.skills

/**
 * 功能用途：C 端"技能"主页面。
 * 展示用户已录制的自动化脚本列表（头部标题、筛选标签、技能卡片、空态与录制引导），
 * 数据全部来自 RecordingScriptStore，不内置任何示例技能。
 * 按原型视觉：紧凑空态、虚线轻卡录制引导、"灵感"分组明确标注未实现。
 */

import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.aiauto.android.ui.components.SkillCard

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 页面头部：左侧"我的技能"标题与副标题，右侧 + 图标按钮触发录制。
        item {
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
        item {
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
            item {
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
                SkillCard(
                    title = script.name,
                    // 描述展示目标应用包名，多个以"、"连接。
                    description = stringResource(
                        R.string.skills_target_apps,
                        script.targetPackages.joinToString("、"),
                    ),
                    // 元信息："N 步 · 创建于 yyyy-MM-dd HH:mm"。
                    meta = buildScriptMeta(script),
                    onClick = { onOpenSkill(script.id) },
                )
            }
        }

        // 底部录制引导卡片：虚线轻卡，引导用户录制新技能。
        item {
            TeachLightCard(onClick = onStartRecording)
        }

        // 底部安全间距。
        item { Spacer(Modifier.height(8.dp)) }
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
