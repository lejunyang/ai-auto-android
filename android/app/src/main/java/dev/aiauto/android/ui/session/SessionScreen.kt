package dev.aiauto.android.ui.session

/**
 * 功能用途：实现 AI 自动化会话的状态展示、确认、暂停与停止交互。
 * 视觉按原型 session() 七状态对齐：plan/running/paused/confirm/done/failed/stopped。
 * 用自绘 ProtoCard/ProtoPrimaryButton/protoClickable，不用 Material 水波纹。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import dev.aiauto.android.R
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.ui.components.ProtoCard
import dev.aiauto.android.ui.components.ProtoPrimaryButton
import dev.aiauto.android.ui.components.ProtoSecondaryButton
import dev.aiauto.android.ui.components.ProtoTextButton
import dev.aiauto.android.ui.components.ScreenScaffold
import dev.aiauto.android.ui.components.protoClickable
import dev.aiauto.android.ui.theme.AppColors

@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
    initialTask: String = "",
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val session = uiState.session

    LaunchedEffect(initialTask) {
        if (initialTask.isNotBlank() && !session.isRunning) {
            viewModel.updateTask(initialTask)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.session_title),
        onBack = onBack,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SessionHeaderCard(uiState) }

            if (!session.isRunning) {
                item { SessionFormCard(uiState, viewModel) }
            }

            session.pendingConfirmation?.let { confirmation ->
                item {
                    ConfirmationCard(
                        actionType = confirmation.actionType,
                        reason = confirmation.reason,
                        onConfirm = { viewModel.confirm(confirmation.id, true) },
                        onReject = { viewModel.confirm(confirmation.id, false) },
                    )
                }
            }

            if (session.isRunning) {
                item { SessionControlRow(uiState, viewModel) }
            }

            if (session.audit.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.session_audit_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight(650),
                        color = AppColors.Ink,
                    )
                }
                items(session.audit.asReversed(), key = { it.sequence }) { entry ->
                    ProtoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        Text(text = phaseLabel(entry.phase), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Primary)
                        Spacer(Modifier.height(4.dp))
                        Text(text = entry.message, fontSize = 13.sp, color = AppColors.Muted, lineHeight = 20.sp)
                    }
                }
            }

            item {
                ProtoTextButton(
                    text = stringResource(R.string.session_back_home),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    center = true,
                )
            }
        }
    }
}

/** 会话头部：状态气泡 + 任务标题 + 进度条。 */
@Composable
private fun SessionHeaderCard(uiState: SessionUiState) {
    val session = uiState.session
    val phase = mapPhase(session.phase)
    val phaseColor = when (phase) {
        "done" -> AppColors.Success
        "failed" -> AppColors.Danger
        "paused" -> AppColors.Warning
        "running" -> AppColors.Primary
        else -> AppColors.Muted
    }
    val progress = when (phase) {
        "plan" -> 0f
        "running" -> (session.step.coerceAtMost(4) / 4f)
        "paused" -> (session.step.coerceAtMost(4) / 4f)
        "confirm" -> 0.75f
        "done" -> 1f
        "failed" -> 0.5f
        "stopped" -> (session.step.coerceAtMost(4) / 4f)
        else -> 0f
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(phaseColor),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = phaseLabel(phase),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = phaseColor,
            )
        }
        Text(
            text = uiState.taskInput.ifBlank { stringResource(R.string.session_title) },
            fontSize = 24.sp,
            fontWeight = FontWeight(650),
            lineHeight = 34.sp,
            color = AppColors.Ink,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.Line),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.Primary),
            )
        }
    }
}

/** 配置表单：任务输入 + 目标应用 + 截图开关 + 开始按钮。 */
@Composable
private fun SessionFormCard(uiState: SessionUiState, viewModel: SessionViewModel) {
    ProtoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.session_task_label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Ink)
            ProtoTextField(
                value = uiState.taskInput,
                onValueChange = viewModel::updateTask,
                placeholder = stringResource(R.string.session_task_supporting),
                minLines = 2,
            )
            Text(stringResource(R.string.session_target_label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Ink)
            ProtoTextField(
                value = uiState.targetPackageInput,
                onValueChange = viewModel::updateTargetPackage,
                placeholder = stringResource(R.string.session_target_label),
                singleLine = true,
            )
            if (uiState.availableTargetPackages.isNotEmpty()) {
                Text(stringResource(R.string.session_authorized_targets), fontSize = 12.sp, color = AppColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.availableTargetPackages.take(4).forEach { pkg ->
                        val selected = pkg == uiState.targetPackageInput
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) AppColors.Primary else AppColors.Line)
                                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { viewModel.updateTargetPackage(pkg) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = pkg.substringAfterLast('.'),
                                fontSize = 11.sp,
                                color = if (selected) AppColors.Surface else AppColors.Muted,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.session_screenshot_label), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.Ink)
                    Text(stringResource(R.string.session_screenshot_supporting), fontSize = 11.sp, color = AppColors.Muted, lineHeight = 16.sp)
                }
                ProtoSwitch(checked = uiState.screenshotsAllowed, onCheckedChange = viewModel::updateScreenshotsAllowed)
            }
            ProtoPrimaryButton(
                text = stringResource(R.string.session_start),
                onClick = viewModel::start,
                enabled = uiState.taskInput.isNotBlank() && uiState.targetPackageInput.isNotBlank(),
            )
        }
    }
}

/** 运行时控制按钮行：暂停/继续/停止。 */
@Composable
private fun SessionControlRow(uiState: SessionUiState, viewModel: SessionViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (uiState.session.canPause) {
            ProtoSecondaryButton(
                text = stringResource(R.string.session_pause),
                onClick = viewModel::pause,
                modifier = Modifier.weight(1f),
            )
        }
        if (uiState.session.canResume) {
            ProtoPrimaryButton(
                text = stringResource(R.string.session_resume),
                onClick = viewModel::resume,
                modifier = Modifier.weight(1f),
            )
        }
        ProtoSecondaryButton(
            text = stringResource(R.string.session_stop),
            onClick = viewModel::stop,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 待确认卡片。 */
@Composable
private fun ConfirmationCard(
    actionType: String,
    reason: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    ProtoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.session_confirmation_title), fontSize = 16.sp, fontWeight = FontWeight(650), color = AppColors.Ink)
            Text(actionType, fontSize = 13.sp, color = AppColors.Primary, fontWeight = FontWeight.SemiBold)
            Text(reason, fontSize = 13.sp, color = AppColors.Muted, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtoSecondaryButton(text = stringResource(R.string.session_reject), onClick = onReject, modifier = Modifier.weight(1f))
                ProtoPrimaryButton(text = stringResource(R.string.session_confirm), onClick = onConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 自绘输入框：白底+1dp描边+13dp圆角。 */
@Composable
private fun ProtoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Line, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 15.sp,
            color = AppColors.Ink,
            lineHeight = 22.sp,
        ),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = AppColors.Muted)
                }
                innerTextField()
            }
        },
    )
}

/** 自绘 Switch：44x26dp 胶囊+圆形滑块。 */
@Composable
private fun ProtoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val trackColor = if (checked) AppColors.Primary else AppColors.Line
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AppColors.Surface),
        )
    }
}

/** SessionPhase 映射到原型七状态。 */
private fun mapPhase(phase: SessionPhase): String = when (phase) {
    SessionPhase.Idle, SessionPhase.Observing, SessionPhase.Planning -> "plan"
    SessionPhase.Executing, SessionPhase.Verifying -> "running"
    SessionPhase.Paused -> "paused"
    SessionPhase.AwaitingConfirmation -> "confirm"
    SessionPhase.Completed -> "done"
    SessionPhase.Failed -> "failed"
    SessionPhase.Stopped -> "stopped"
}

/** 原型七状态标签。 */
@Composable
private fun phaseLabel(phase: String): String = when (phase) {
    "plan" -> stringResource(R.string.session_phase_plan)
    "running" -> stringResource(R.string.session_phase_running)
    "paused" -> stringResource(R.string.session_phase_paused)
    "confirm" -> stringResource(R.string.session_phase_confirm)
    "done" -> stringResource(R.string.session_phase_done)
    "failed" -> stringResource(R.string.session_phase_failed)
    "stopped" -> stringResource(R.string.session_phase_stopped)
    else -> ""
}

/** SessionPhase 原始标签（审计日志用）。 */
@Composable
private fun phaseLabel(phase: SessionPhase): String = stringResource(
    when (phase) {
        SessionPhase.Idle -> R.string.session_phase_idle
        SessionPhase.Observing -> R.string.session_phase_observing
        SessionPhase.Planning -> R.string.session_phase_planning
        SessionPhase.AwaitingConfirmation -> R.string.session_phase_confirmation
        SessionPhase.Executing -> R.string.session_phase_executing
        SessionPhase.Verifying -> R.string.session_phase_verifying
        SessionPhase.Paused -> R.string.session_phase_paused
        SessionPhase.Completed -> R.string.session_phase_completed
        SessionPhase.Failed -> R.string.session_phase_failed
        SessionPhase.Stopped -> R.string.session_phase_stopped
    },
)
