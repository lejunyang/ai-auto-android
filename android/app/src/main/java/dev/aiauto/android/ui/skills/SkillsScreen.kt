package dev.aiauto.android.ui.skills

/**
 * 功能用途：展示 C 端"技能"主页面。按原型 skills-all 单列宽卡列表布局：
 * 页头(info按钮) + 区块标题行(教会它的事 + 加号) + filter-tabs(全部/我教的/灵感) +
 * 技能单列宽卡(图标块+来源tag+标题+描述+步数+chevron) + 灵感未来概念卡 + teachCard。
 * 数据全部读取自 RecordingScriptStore，不内置示例技能。
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import dev.aiauto.android.ui.components.ProtoIconButton
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
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.ui.components.AppIconBlock
import dev.aiauto.android.ui.components.AppIcons
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.theme.AppColors

/** 技能筛选标签枚举：全部 / 我教的 / 灵感（未来概念）。 */
private enum class SkillFilter {
    ALL,
    MINE,
    IDEAS,
}

/**
 * 技能主页面：从 scriptStore 读取真实脚本并渲染单列宽卡列表。
 *
 * @param scriptStore 脚本存储，list() 已按 createdAt 降序返回。
 * @param onOpenSkill 点击某个技能卡片，回调脚本 id 进入编辑器。
 * @param onStartRecording 点击区块标题 + 按钮或底部录制引导卡片，进入录制流程。
 */
@Composable
fun SkillsScreen(
    scriptStore: RecordingScriptStore,
    onOpenSkill: (String) -> Unit,
    onStartRecording: () -> Unit,
) {
    // 读取真实脚本列表；store 内部已按创建时间降序排序。
    val scripts = remember { scriptStore.list() }
    // 当前选中的筛选标签，默认"全部"。
    var selectedFilter by remember { mutableStateOf(SkillFilter.ALL) }
    // info 对话框显示状态。
    var showInfoDialog by remember { mutableStateOf(false) }
    // 灵感卡"规划中"对话框。
    var showIdeaDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // 1. 页面头部：左侧"我的技能"标题与副标题，右侧 info 按钮（不是 +）。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 原型 .app-title：18sp/650/letterSpacing -0.7（顶部导航条小标题）。
                    Text(
                        text = stringResource(R.string.skills_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight(650),
                        lineHeight = 24.sp,
                        letterSpacing = (-0.7).sp,
                    )
                    // 原型 .sub-title（手机模式 12px、muted）。
                    Text(
                        text = stringResource(R.string.skills_subtitle),
                        fontSize = 12.sp,
                        color = AppColors.Muted,
                        lineHeight = 16.sp,
                    )
                }
                ProtoIconButton(onClick = { showInfoDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.skills_info_dialog_title),
                        tint = AppColors.Muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // 2. 区块标题行：h2.page-heading「教会它的事」+ 右侧 + 按钮（进入录制）。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.skills_page_heading),
                    fontSize = 24.sp,
                    fontWeight = FontWeight(700),
                    lineHeight = 36.sp,
                    letterSpacing = (-0.8).sp,
                )
                ProtoIconButton(onClick = onStartRecording) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.skills_new),
                        tint = AppColors.Primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // 3. filter-tabs：全部 / 我教的 / 灵感。容器 flex gap8px、margin17px 0；
        // 按钮 font-size12px、圆角10px、padding 8px 12px、min-height44px；
        // 未选中 bg#eaf0f7 文字#6c7b90；选中 bg=var(--ink) 白字。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkillFilter.entries.forEach { filter ->
                    val labelRes = when (filter) {
                        SkillFilter.ALL -> R.string.skills_filter_all
                        SkillFilter.MINE -> R.string.skills_filter_mine
                        SkillFilter.IDEAS -> R.string.skills_filter_ideas
                    }
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
                                text = stringResource(labelRes),
                                fontSize = 12.sp,
                                color = if (selected) AppColors.Surface else AppColors.FilterTabText,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // 4. 内容区：根据筛选标签显示技能列表或灵感卡。
        if (selectedFilter == SkillFilter.IDEAS) {
            // 灵感 tab：两张"未来概念"构想卡，不可执行，点击弹"规划中"。
            item { Spacer(Modifier.height(2.dp)) }
            item {
                IdeaCard(
                    title = stringResource(R.string.skills_idea_1_title),
                    desc = stringResource(R.string.skills_idea_1_desc),
                    onClick = { showIdeaDialog = true },
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                IdeaCard(
                    title = stringResource(R.string.skills_idea_2_title),
                    desc = stringResource(R.string.skills_idea_2_desc),
                    onClick = { showIdeaDialog = true },
                )
            }
        } else {
            // 全部 / 我教的：真实技能列表（MVP 阶段两者内容一致）。
            if (scripts.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.skills_empty_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.skills_empty_desc),
                            fontSize = 12.sp,
                            color = AppColors.Muted,
                        )
                    }
                }
            } else {
                items(scripts, key = { it.id }) { script ->
                    SkillListCard(
                        script = script,
                        onClick = { onOpenSkill(script.id) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        // 5. 底部录制引导卡片：虚线轻卡。
        item { Spacer(Modifier.height(4.dp)) }
        item { TeachLightCard(onClick = onStartRecording) }

        // 6. 底部安全间距。
        item { Spacer(Modifier.height(8.dp)) }
    }

    // info 对话框：技能页说明。
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.skills_info_dialog_title)) },
            text = { Text(stringResource(R.string.skills_info_dialog_content)) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    // 灵感卡"规划中"对话框。
    if (showIdeaDialog) {
        AlertDialog(
            onDismissRequest = { showIdeaDialog = false },
            title = { Text(stringResource(R.string.skills_idea_dialog_title)) },
            text = { Text(stringResource(R.string.skills_idea_dialog_content)) },
            confirmButton = {
                TextButton(onClick = { showIdeaDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/**
 * 技能单列宽卡：按原型 .card.skill-list-card，width100%、单列、margin-bottom10px、
 * bg surface、1px line 描边、radius 圆角、padding17px。
 * 卡内：顶部图标块+来源tag → 标题 → 描述 → 底部步数+chevron。
 */
@Composable
private fun SkillListCard(
    script: AutomationScriptSummary,
    onClick: () -> Unit,
) {
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
            // 顶部 row between：左侧应用/类型图标块 + 右侧来源 tag。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧应用图标块：真实目标应用 launcher 图标（最多两个），未安装回退 Book/Note。
                AppIconBlock(targetPackages = script.targetPackages.toList())
                // 来源 tag：复用通用 TagChip（.tag.green，行高与 padding 与原型一致），文案"已保存"。
                TagChip(
                    text = stringResource(R.string.history_saved_tag),
                    tone = TagTone.SUCCESS,
                )
            }

            // 标题：h3 技能名 14px（margin 12px 0 7px）。
            Text(
                text = script.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 7.dp),
                maxLines = 1,
            )

            // 描述：p 11px muted、line-height1.8；优先 description（summary 无此字段），
            // 没有就显示目标包名，严禁写死假描述。
            val descText = if (script.targetPackages.isNotEmpty()) {
                stringResource(R.string.skills_target_apps, script.targetPackages.joinToString("、"))
            } else {
                stringResource(R.string.skills_empty_desc)
            }
            Text(
                text = descText,
                fontSize = 11.sp,
                color = AppColors.Muted,
                lineHeight = 19.8.sp,
                maxLines = 2,
            )

            // 底部 row between（margin-top14）：左 small muted「N 步 · 手动触发」+ 右 chevron。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.history_steps,
                        script.stepCount,
                    ) + " · " + stringResource(R.string.skills_manual_trigger),
                    fontSize = 11.sp,
                    color = AppColors.Muted,
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.skills_view_steps),
                    tint = AppColors.Muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 灵感未来概念卡：明确标注"未来概念"蓝色 tag，不可执行，点击弹"规划中"。
 * 与技能卡同宽同圆角，但视觉上区分（更浅的描边）。
 */
@Composable
private fun IdeaCard(
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
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
            // 顶部：未来概念 tag（蓝色）。
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = AppColors.PrimaryContainer,
            ) {
                Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(
                        text = stringResource(R.string.skills_future_concept),
                        fontSize = 11.sp,
                        color = AppColors.Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // 标题。
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 7.dp),
            )
            // 描述。
            Text(
                text = desc,
                fontSize = 11.sp,
                color = AppColors.Muted,
                lineHeight = 19.8.sp,
            )
        }
    }
}

/** 虚线轻卡录制引导：按原型视觉，虚线边框 + 录制图标 + 标题/副标题。 */
@Composable
private fun TeachLightCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = AppColors.Line,
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
                color = AppColors.PrimaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Spark,
                        contentDescription = null,
                        tint = AppColors.Primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.skills_teach_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.skills_teach_subtitle),
                    fontSize = 12.sp,
                    color = AppColors.Muted,
                )
            }
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = AppColors.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
