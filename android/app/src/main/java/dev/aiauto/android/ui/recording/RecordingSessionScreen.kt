package dev.aiauto.android.ui.recording

/**
 * 功能用途：录制新流程页（原型 #record 视图）。
 * 展示大标题、蓝色安全 NoticeBar、脚本名输入、目标应用选择、开始/暂停/结束录制按钮。
 * 表现层按原型重做，保留全部录制控制回调（开始/暂停/恢复/结束/取消）与步骤预览。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingDraft
import dev.aiauto.android.automation.recording.RecordingStatus
import dev.aiauto.android.ui.theme.AppColors

@Composable
fun RecordingSessionScreen(
    draft: RecordingDraft,
    name: String,
    targetPackages: String,
    errorMessage: String?,
    onNameChanged: (String) -> Unit,
    onTargetPackagesChanged: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // 页头：返回箭头 + 标题区。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = AppColors.Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 大标题（原型 #record）。
        Text(
            text = stringResource(R.string.record_hero_title),
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.Ink,
            lineHeight = 37.8.sp,
        )
        Text(
            text = stringResource(R.string.record_hero_subtitle),
            fontSize = 12.sp,
            color = AppColors.Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )

        // 蓝色安全 NoticeBar（替换原紫色状态卡）。
        RecordNoticeBar(status = draft.status)

        // 脚本名称：label 外置 + 自绘输入框（不用 Material 聚焦蓝框）。
        Text(
            text = stringResource(R.string.record_name_label),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        ProtoField(
            value = name,
            onValueChange = onNameChanged,
            placeholder = stringResource(R.string.record_name_placeholder),
            enabled = draft.status == RecordingStatus.IDLE,
        )

        // 目标应用：只记录这些应用 + 右侧更改。
        Text(
            text = stringResource(R.string.record_target_label),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        // 目标应用卡片：展示已选包名，点"更改"跳转编辑。
        TargetAppCard(
            packages = targetPackages,
            onChange = onTargetPackagesChanged,
        )

        // 底部安全说明（中性文字）。
        Text(
            text = stringResource(R.string.record_privacy_note),
            fontSize = 11.sp,
            color = AppColors.Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 16.dp),
        )

        // 录制控制按钮区。
        when (draft.status) {
            RecordingStatus.IDLE -> PrimaryButton(
                text = stringResource(R.string.recording_start),
                onClick = onStart,
                enabled = name.isNotBlank() && targetPackages.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .testTag(RecordingTestTags.START),
            )

            RecordingStatus.RECORDING -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.recording_pause),
                    onClick = onPause,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RecordingTestTags.PAUSE),
                )
                PrimaryButton(
                    text = stringResource(R.string.recording_finish),
                    onClick = onFinish,
                    enabled = draft.steps.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RecordingTestTags.FINISH),
                )
            }

            RecordingStatus.PAUSED -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.recording_resume),
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RecordingTestTags.RESUME),
                )
                PrimaryButton(
                    text = stringResource(R.string.recording_finish),
                    onClick = onFinish,
                    enabled = draft.steps.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RecordingTestTags.FINISH),
                )
            }
        }

        if (draft.status != RecordingStatus.IDLE) {
            SecondaryButton(
                text = stringResource(R.string.recording_cancel),
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }

        // 步骤预览。
        Text(
            text = stringResource(R.string.recording_step_preview),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (draft.steps.isEmpty()) {
            Text(
                text = stringResource(R.string.recording_no_steps),
                fontSize = 12.sp,
                color = AppColors.Muted,
            )
        } else {
            draft.steps.takeLast(MAX_PREVIEW_STEPS).forEachIndexed { index, step ->
                RecordingStepCard(
                    number = draft.steps.size - draft.steps
                        .takeLast(MAX_PREVIEW_STEPS).size + index + 1,
                    step = step,
                )
            }
        }
        (draft.errorMessage ?: errorMessage)?.let { message ->
            Text(
                text = message,
                color = androidx.compose.ui.graphics.Color(0xFFB63E49),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        // 底部留白。
        Box(modifier = Modifier.height(32.dp))
    }
}

/** 蓝色安全 NoticeBar（原型 record 视图顶部提示条）。 */
@Composable
private fun RecordNoticeBar(status: RecordingStatus) {
    val (bg, fg, text) = when (status) {
        RecordingStatus.IDLE -> Triple(
            0xFFEEF4FF, 0xFF2563EB,
            stringResource(R.string.record_notice_idle),
        )
        RecordingStatus.RECORDING -> Triple(
            0xFFFDE8E8, 0xFFB63E49,
            stringResource(R.string.record_notice_recording),
        )
        RecordingStatus.PAUSED -> Triple(
            0xFFFFF4E0, 0xFF9B6419,
            stringResource(R.string.record_notice_paused),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(androidx.compose.ui.graphics.Color(bg))
            .padding(14.dp),
    ) {
        Text(text = text, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(fg), lineHeight = 18.sp)
    }
}

/** 自绘输入框：白底 + 1dp Line 描边 + 13dp 圆角 + 50dp 高（与 Provider 页同款）。 */
@Composable
private fun ProtoField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(AppColors.Surface)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.Ink),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 16.sp,
                color = AppColors.Muted,
            )
        }
    }
}

/** 目标应用卡片：展示已选包名，右侧"更改"。 */
@Composable
private fun TargetAppCard(
    packages: String,
    onChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(AppColors.Surface)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (packages.isBlank()) {
                        stringResource(R.string.record_target_empty)
                    } else {
                        packages
                    },
                    fontSize = 14.sp,
                    color = if (packages.isBlank()) AppColors.Muted else AppColors.Ink,
                )
            }
            Row(
                modifier = Modifier.clickable { onChange(packages) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.record_target_change),
                    fontSize = 14.sp,
                    color = AppColors.Primary,
                )
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 蓝色主按钮（原型 ProtoPrimaryButton）。 */
@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bg = if (enabled) AppColors.Primary else AppColors.Line
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) AppColors.Surface else AppColors.Muted,
        )
    }
}

/** 次要按钮（白底描边）。 */
@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .clickable(onClick = onClick)
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Primary,
        )
    }
}

@Composable
internal fun RecordingStepCard(
    number: Int,
    step: RecordedStep,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(AppColors.Surface)
            .padding(14.dp)
            .testTag(RecordingTestTags.step(number)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$number. ${step.action.type}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Ink,
            )
            Text(
                text = stringResource(R.string.recording_step_time, step.recordedAtMs),
                fontSize = 11.sp,
                color = AppColors.Muted,
            )
            if ("secretRef" in step.action.params) {
                Text(
                    text = stringResource(R.string.recording_secret_step),
                    fontSize = 11.sp,
                    color = androidx.compose.ui.graphics.Color(0xFF9B6419),
                )
            }
        }
    }
}

private const val MAX_PREVIEW_STEPS = 20
