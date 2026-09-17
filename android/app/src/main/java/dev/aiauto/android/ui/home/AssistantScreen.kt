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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.outlined.RadioButtonChecked
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.ui.components.AppIcons
import dev.aiauto.android.ui.components.EmptyState
import dev.aiauto.android.ui.components.IconTile
import dev.aiauto.android.ui.components.ScriptLoadState
import dev.aiauto.android.ui.components.dashedBorder
import dev.aiauto.android.ui.components.produceScriptSummaries
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppShapes
import dev.aiauto.android.ui.theme.AppSpacing
import dev.aiauto.android.ui.theme.AppTypographyTokens
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            },
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

        // 4. 快捷 chips：灰底小胶囊，背景 #E8EDF4、文字 #5D6C82、圆角 10dp、min-height 44dp、字号 11sp、自动换行。
        item {
            val quick1 = stringResource(R.string.assistant_quick_1)
            val quick2 = stringResource(R.string.assistant_quick_2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(quick1, quick2).forEach { label ->
                    Surface(
                        onClick = { taskInput = label },
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.QuickChipBackground,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, fontSize = 11.sp, color = AppColors.QuickChipText)
                        }
                    }
                }
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
                // 原型 .skill-grid 是非滚动的两列等宽网格，高度由 tile 内容（固定 112.5dp）撑开。
                // 这里用普通 Column+Row 静态布局，避免在 LazyColumn 内嵌同方向 LazyVerticalGrid
                // （嵌套同方向滚动需固定高度，此前写死 100dp 把 tile 从 112.5 压到 100dp）。
                val tiles = scripts.take(4)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.chunked(2).forEach { rowTiles ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowTiles.forEach { script ->
                                Box(modifier = Modifier.weight(1f)) {
                                    SkillTile(script = script, onClick = { onOpenSkill(script.id) })
                                }
                            }
                            // 奇数个时补一个等宽空位，保持最后一行左对齐、宽度一致。
                            if (rowTiles.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
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

/** 问候区：你好小字 + 大标题 + 右侧 tiny-orbit 品牌符号（auto）。 */
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
                fontSize = 27.sp,
                fontWeight = FontWeight(620),
                letterSpacing = (-1).sp,
                lineHeight = 37.8.sp,
            )
        }
        // 原型 .tiny-orbit：42dp、1px #C9D8F6 描边、圆角14、pale 底，整体旋转 -7°；
        // 内部 auto 品牌符号 27dp 反向旋转 7°保持正立。
        Surface(
            shape = RoundedCornerShape(AppShapes.TinyOrbit),
            color = AppColors.PrimaryContainer,
            border = BorderStroke(1.dp, AppColors.TinyOrbitBorder),
            modifier = Modifier
                .size(AppSpacing.TinyOrbitSize)
                .graphicsLayer { rotationZ = -7f },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Auto,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier
                        .size(27.dp)
                        .graphicsLayer { rotationZ = 7f },
                )
            }
        }
    }
}

/** Composer 卡片：白色24dp圆角卡片，1px #D9E3F3边框+阴影，输入无框，底部操作行在卡片内。 */
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
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 7.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = AppColors.ComposerBorder,
                shape = RoundedCornerShape(24.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 输入区：无框文本，minHeight 50dp，字号 15sp，placeholder #909CAB。
            androidx.compose.foundation.text.BasicTextField(
                value = taskInput,
                onValueChange = onTaskInputChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp,
                ),
                decorationBox = { innerTextField ->
                    if (taskInput.isEmpty()) {
                        Text(
                            text = stringResource(R.string.assistant_task_hint),
                            fontSize = 15.sp,
                            color = AppColors.ComposerPlaceholder,
                        )
                    }
                    innerTextField()
                },
            )

            // 底部操作行：margin-top 5dp，两端对齐。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 选择应用：灰底 pill，背景 #F5F7FA、圆角 9dp、字号 11sp、文字 #6D7889、min-height 44dp、无边框。
                Surface(
                    onClick = onChooseApps,
                    shape = RoundedCornerShape(9.dp),
                    color = AppColors.ComposerPillBackground,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Row(
                        // 原型 .composer-foot .pill：padding 6px 8px、gap 5px；min-height 44dp 由手机模式覆盖。
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            AppIcons.Phone,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = AppColors.ComposerPillText,
                        )
                        Text(
                            text = if (targetPackages.isNotEmpty()) "选择应用 · ${targetPackages.size}" else "选择应用",
                            fontSize = 11.sp,
                            color = AppColors.ComposerPillText,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onVoiceClick, modifier = Modifier.size(44.dp)) {
                        Icon(
                            AppIcons.Mic,
                            contentDescription = "语音输入",
                            tint = if (isListening) MaterialTheme.colorScheme.primary else AppColors.ComposerPillText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 发送按钮：蓝色圆角 14dp，向上箭头，点击区 44dp。
                    Surface(
                        onClick = onSubmit,
                        enabled = ready && taskInput.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (ready && taskInput.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                AppIcons.Up,
                                contentDescription = null,
                                tint = if (ready && taskInput.isNotBlank()) MaterialTheme.colorScheme.onPrimary else AppColors.ComposerPillText,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // 语音提示/未就绪提示：在卡片内，无额外边框。
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
    // 原型 .skill-tile 实测：180.5x112.5dp、18dp 圆角、1dp #E5EAF1 描边、白底、12dp 内边距。
    val shape = RoundedCornerShape(AppShapes.SkillTile)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSpacing.SkillTileHeight)
            .background(AppColors.Surface, shape)
            .border(BorderStroke(1.dp, AppColors.SkillTileBorder), shape)
            .clickable(onClick = onClick)
            .padding(AppSpacing.SkillTilePadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 原型 .tile-icon 实测：35dp 方块、11dp 圆角、#EEF4FF 底；图标用与原型一致的描边书本。
        IconTile(icon = AppIcons.Book)
        Text(
            text = script.name,
            fontSize = AppTypographyTokens.TileTitleFontSize,
            lineHeight = AppTypographyTokens.TileTitleLineHeight,
            fontWeight = FontWeight.Bold,
            color = AppColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = script.targetPackages.firstOrNull() ?: "",
            fontSize = AppTypographyTokens.TileMetaFontSize,
            lineHeight = AppTypographyTokens.TileMetaLineHeight,
            color = AppColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 教它一次卡片：录制图标 + 标题/副标题 + + 图标。 */
@Composable
private fun TeachCard(onClick: () -> Unit) {
    // 原型 .teach-card 实测：371x73.5dp、18dp 圆角、1dp #CCD9EC 虚线描边、#F3F6FC 底、16dp 内边距。
    val shape = RoundedCornerShape(AppShapes.TeachCard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSpacing.TeachCardHeight)
            .background(AppColors.TeachBackground, shape)
            .dashedBorder(color = AppColors.TeachBorder, cornerRadius = AppShapes.TeachCard)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.TeachCardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 录制图标：原型用 record 图标（同心圆），不是实心圆点。
        Icon(
            imageVector = Icons.Outlined.RadioButtonChecked,
            contentDescription = null,
            tint = AppColors.Primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.assistant_teach_title),
                fontSize = AppTypographyTokens.TileTitleFontSize,
                lineHeight = AppTypographyTokens.TileTitleLineHeight,
                fontWeight = FontWeight.Bold,
                color = AppColors.Ink,
            )
            Text(
                text = stringResource(R.string.assistant_teach_subtitle),
                fontSize = AppTypographyTokens.TileMetaFontSize,
                lineHeight = AppTypographyTokens.TileMetaLineHeight,
                color = AppColors.Muted,
            )
        }
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = AppColors.Primary,
            modifier = Modifier.size(20.dp),
        )
    }
}
