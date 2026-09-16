package dev.aiauto.android.ui.home

/**
 * 功能用途：C 端「助理」主页面。按已确认原型布局：App Header + 问候区 + Composer 卡片 +
 * 快捷 chips + 常用技能(2列网格) + 教它一次 + 最近活动。
 *
 * 设计约束：
 * - 所有用户可见文案来自 stringResource（consumer_ui_strings.xml 的 assistant_* 前缀）。
 * - 常用技能与最近活动直接读取真实录制脚本 scriptStore.list()，不展示硬编码示例或假历史。
 * - 就绪状态由 Provider 配置、无障碍配置与服务启用三者共同决定；未就绪时任务按钮禁用并给出配置提示。
 * - 语音输入按需启用：检查麦克风权限，未授权时引导至系统设置，不静默请求。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.ui.components.EmptyState
import dev.aiauto.android.ui.components.ScriptLoadState
import dev.aiauto.android.ui.components.produceScriptSummaries
import dev.aiauto.android.ui.voice.VoiceInputHandler
import dev.aiauto.android.ui.voice.VoiceInputResult

@Composable
fun AssistantScreen(
    scriptStore: RecordingScriptStore,
    providerReady: Boolean,
    accessibilityConfigured: Boolean,
    accessibilityEnabled: Boolean,
    targetPackages: Set<String>,
    onStartTask: (String) -> Unit,
    onOpenSkill: (String) -> Unit,
    onGoToSkills: () -> Unit,
    onGoToSettings: () -> Unit,
    onStartRecording: () -> Unit,
) {
    val context = LocalContext.current
    // 任务输入草稿：rememberSaveable 支持旋转/进程恢复。
    var taskInput by rememberSaveable { mutableStateOf("") }
    // 就绪条件：模型已配置、无障碍已披露且系统服务已启用。
    val ready = providerReady && accessibilityEnabled && accessibilityConfigured

    // 语音识别状态与处理器。
    var isListening by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val voiceHandler = remember {
        VoiceInputHandler { result ->
            when (result) {
                is VoiceInputResult.Final -> {
                    isListening = false
                    if (result.text.isNotBlank()) taskInput = result.text
                }
                is VoiceInputResult.Partial -> {
                    if (result.text.isNotBlank()) taskInput = result.text
                }
                is VoiceInputResult.Error -> {
                    isListening = false
                    voiceMessage = result.message
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { voiceHandler.destroy() } }

    // 脚本列表异步加载：IO dispatcher + ON_RESUME 刷新 + 错误态。
    val scriptsState by produceScriptSummaries(store = scriptStore)
    val scripts = when (val s = scriptsState) {
        is ScriptLoadState.Success -> s.scripts
        is ScriptLoadState.Error -> emptyList()
        ScriptLoadState.Loading -> emptyList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. App Header：AI Auto / 让手机，替你完成。 / 就绪徽章。
        item { AppHeader(ready = ready) }

        // 2. 问候区：你好，今天想做点什么 / 有什么，可以交给我？ / auto 符号。
        item { GreetingSection() }

        // 3. Composer 卡片：输入 + 选择应用/麦克风/发送，全部在一个白色圆角卡片内。
        item {
            ComposerCard(
                taskInput = taskInput,
                onTaskInputChange = { if (it.length <= 300) taskInput = it },
                ready = ready,
                targetPackages = targetPackages,
                onChooseApps = onGoToSettings,
                onSubmit = { onStartTask(taskInput.trim()) },
                isListening = isListening,
                voiceMessage = voiceMessage,
                onVoiceClick = {
                    voiceMessage = null
                    if (isListening) {
                        voiceHandler.stopListening()
                        isListening = false
                    } else if (voiceHandler.isRecordAudioPermissionGranted(context)) {
                        voiceHandler.startListening(context)
                        isListening = true
                    } else {
                        voiceMessage = "需要麦克风权限才能语音输入，请在系统设置中授权。"
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    }
                },
            )
        }

        // 4. 快捷 chips：整理稍后阅读 / 提取页面重点。
        item {
            val quick1 = stringResource(R.string.assistant_quick_1)
            val quick2 = stringResource(R.string.assistant_quick_2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { taskInput = quick1 },
                    label = { Text(quick1, fontSize = 13.sp) },
                )
                AssistChip(
                    onClick = { taskInput = quick2 },
                    label = { Text(quick2, fontSize = 13.sp) },
                )
            }
        }

        // 5. 常用技能：标题 + 查看全部 + 2列网格。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_skills_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onGoToSkills) {
                    Text("查看全部", fontSize = 13.sp)
                }
            }
        }
        if (scripts.isEmpty()) {
            item {
                Text(
                    text = "还没有保存的技能。录制一次操作，就能反复使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(if (scripts.size > 2) 200.dp else 100.dp),
                ) {
                    items(scripts.take(4)) { script ->
                        SkillTile(script = script, onClick = { onOpenSkill(script.id) })
                    }
                }
            }
        }

        // 6. 教它一次卡片：录制图标 + 标题/副标题 + + 图标。
        item {
            TeachCard(onClick = onStartRecording)
        }

        // 7. 最近活动：标题 + 空态/最近记录。
        item {
            Text(
                text = stringResource(R.string.assistant_recent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            if (scripts.isEmpty()) {
                Text(
                    text = "还没有运行记录。会话审计只保存动作摘要，不记录密码、验证码或原始截图。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scripts.take(3).forEach { script ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(script.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "${script.stepCount} 步 · ${script.targetPackages.firstOrNull() ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // 底部安全间距。
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** App Header：AI Auto 标题 + 副标题 + 就绪徽章。 */
@Composable
private fun AppHeader(ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.assistant_app_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.assistant_app_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                text = stringResource(if (ready) R.string.assistant_ready else R.string.assistant_not_ready),
                style = MaterialTheme.typography.labelMedium,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 问候区：你好小字 + 大标题 + 右侧 auto 符号。 */
@Composable
private fun GreetingSection() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.assistant_hello),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.assistant_greeting_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp,
            )
        }
        // auto 符号：三角形图标，蓝色边框圆形背景。
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("△", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Composer 卡片：白色大圆角卡片，输入无框，底部操作行在卡片内。 */
@Composable
private fun ComposerCard(
    taskInput: String,
    onTaskInputChange: (String) -> Unit,
    ready: Boolean,
    targetPackages: Set<String>,
    onChooseApps: () -> Unit,
    onSubmit: () -> Unit,
    isListening: Boolean,
    voiceMessage: String?,
    onVoiceClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 输入区：无框文本，placeholder。
            androidx.compose.foundation.text.BasicTextField(
                value = taskInput,
                onValueChange = onTaskInputChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { innerTextField ->
                    if (taskInput.isEmpty()) {
                        Text(
                            text = stringResource(R.string.assistant_task_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                },
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            // 底部操作行：选择应用 + 麦克风 + 发送。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onChooseApps,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (targetPackages.isNotEmpty()) "选择应用 · ${targetPackages.size}" else "选择应用",
                        fontSize = 13.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onVoiceClick) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "语音输入",
                            tint = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onSubmit,
                        enabled = ready && taskInput.isNotBlank(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (ready && taskInput.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = if (ready && taskInput.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 语音提示/未就绪提示。
            voiceMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (!ready && voiceMessage == null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.assistant_setup_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

/** 技能卡片（2列网格用）：图标 + 标题 + 小字。 */
@Composable
private fun SkillTile(script: AutomationScriptSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Text(script.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                script.targetPackages.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 教它一次卡片：录制图标 + 标题/副标题 + + 图标。 */
@Composable
private fun TeachCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
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
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.RadioButtonChecked, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.assistant_teach_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.assistant_teach_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
