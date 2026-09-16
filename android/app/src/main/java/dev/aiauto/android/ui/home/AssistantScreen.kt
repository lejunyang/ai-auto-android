package dev.aiauto.android.ui.home

/**
 * 功能用途：C 端「助理」主页面。提供自然语言任务输入、常用技能直达、录制引导与最近活动概览。
 *
 * 设计约束：
 * - 所有用户可见文案均来自 stringResource（consumer_ui_strings.xml 的 assistant_* 前缀）。
 * - 常用技能与最近活动直接读取真实录制脚本 scriptStore.list()，不展示任何硬编码示例或假历史。
 * - 就绪状态由 Provider 配置、无障碍配置与服务启用三者共同决定；未就绪时任务按钮禁用并给出配置提示。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.ui.components.GreetingHeader
import dev.aiauto.android.ui.components.ReadyBadge
import dev.aiauto.android.ui.components.SectionHeader
import dev.aiauto.android.ui.components.SkillCard

/** 任务描述输入框的最大字符长度，与原型一致。 */
private const val MAX_TASK_LENGTH = 300

/** 常用技能区最多展示的技能数量。 */
private const val MAX_QUICK_SKILLS = 2

/** 最近活动区最多展示的脚本数量。 */
private const val MAX_RECENT_SCRIPTS = 3

@Composable
fun AssistantScreen(
    providerReady: Boolean,
    accessibilityConfigured: Boolean,
    accessibilityEnabled: Boolean,
    targetPackages: Set<String>,
    scriptStore: RecordingScriptStore,
    onStartTask: (String) -> Unit,
    onOpenSkill: (String) -> Unit,
    onStartRecording: () -> Unit,
    onGoToSkills: () -> Unit,
    onGoToSettings: () -> Unit,
) {
    // 综合就绪条件：模型已配置、无障碍已配置且服务已在系统中启用。
    val ready = providerReady && accessibilityEnabled && accessibilityConfigured

    // 任务输入框的本地编辑态，提交时才回传给导航层。
    var taskInput by remember { mutableStateOf("") }

    // 一次性读取真实脚本列表（文件读取，脚本数量少，可接受；导航层切换 tab 会重建本页重新读取）。
    val scripts = remember(scriptStore) { scriptStore.list() }
    val quickSkills = scripts.take(MAX_QUICK_SKILLS)
    val recentScripts = scripts.take(MAX_RECENT_SCRIPTS)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. 问候头部 + 右侧就绪状态徽章。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GreetingHeader(
                    title = stringResource(R.string.assistant_greeting_title),
                    subtitle = stringResource(R.string.assistant_greeting_subtitle),
                    modifier = Modifier.weight(1f),
                )
                ReadyBadge(ready = ready)
            }
        }

        // 2. 任务输入区：多行输入 + 选择应用 / 预览计划按钮。
        item {
            TaskInputSection(
                taskInput = taskInput,
                onTaskInputChange = { input ->
                    if (input.length <= MAX_TASK_LENGTH) taskInput = input
                },
                ready = ready,
                targetPackages = targetPackages,
                onChooseApps = onGoToSettings,
                onSubmit = { onStartTask(taskInput.trim()) },
            )
        }

        // 3. 常用技能：真实脚本前 N 个，无脚本时显示简短空态文案。
        item {
            SectionHeader(
                title = stringResource(R.string.assistant_quick_skills),
                actionLabel = stringResource(R.string.assistant_view_all),
                onActionClick = onGoToSkills,
            )
        }
        if (quickSkills.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.assistant_no_skills),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(quickSkills.size) { index ->
                val script = quickSkills[index]
                SkillCard(
                    title = script.name,
                    description = script.targetPackages.joinToString(" → "),
                    meta = stringResource(R.string.history_steps, script.stepCount),
                    onClick = { onOpenSkill(script.id) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Schema,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }

        // 4. 「教它一次」入口卡片，点击进入录制流程。
        item {
            TeachOnceCard(onStartRecording = onStartRecording)
        }

        // 5. 最近活动：展示真实脚本的最近创建记录，无脚本时显示审计说明空态。
        item {
            SectionHeader(title = stringResource(R.string.assistant_recent))
        }
        if (recentScripts.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.assistant_no_recent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(recentScripts.size) { index ->
                val script = recentScripts[index]
                RecentScriptRow(
                    script = script,
                    onClick = { onOpenSkill(script.id) },
                )
            }
        }
    }
}

/**
 * 任务输入区：多行输入框 + 底部操作行（选择应用、预览计划）。
 * 未就绪时预览按钮禁用，并在下方给出前往配置的提示文案。
 */
@Composable
private fun TaskInputSection(
    taskInput: String,
    onTaskInputChange: (String) -> Unit,
    ready: Boolean,
    targetPackages: Set<String>,
    onChooseApps: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = taskInput,
            onValueChange = onTaskInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(stringResource(R.string.assistant_task_hint))
            },
            minLines = 2,
            maxLines = 3,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选择应用按钮：文案附带已配置的目标应用数量，点击跳转设置页选择应用。
            OutlinedButton(
                onClick = onChooseApps,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = chooseAppsLabel(targetPackages.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // 预览计划按钮：任务为空或未就绪时禁用，避免空跑或在未配置环境下启动任务。
            Button(
                onClick = onSubmit,
                enabled = ready && taskInput.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.assistant_start))
            }
        }

        // 未就绪时给出明确的配置引导提示，而不是让用户面对一个无反馈的禁用按钮。
        if (!ready) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.assistant_setup_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** 组装「选择应用」按钮文案：已配置目标应用时附带数量。 */
@Composable
private fun chooseAppsLabel(count: Int): String {
    val base = stringResource(R.string.assistant_choose_apps)
    return if (count <= 0) {
        base
    } else {
        "$base · " + stringResource(R.string.settings_target_count, count)
    }
}

/** 「教它一次」引导卡片：点击进入录制，把人工操作沉淀为可复用技能。 */
@Composable
private fun TeachOnceCard(onStartRecording: () -> Unit) {
    Card(
        onClick = onStartRecording,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.assistant_teach_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.assistant_teach_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 最近活动中的单条脚本记录：紧凑展示名称与步数，点击进入脚本详情。 */
@Composable
private fun RecentScriptRow(
    script: AutomationScriptSummary,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Schema,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.history_steps, script.stepCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
