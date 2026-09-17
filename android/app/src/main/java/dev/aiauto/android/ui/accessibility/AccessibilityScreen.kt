package dev.aiauto.android.ui.accessibility

/**
 * 功能用途：实现 AccessibilityScreen 对应的无障碍观察、截图、动作或用户触摸安全控制。
 */

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.settings.TargetPackageParseResult
import dev.aiauto.android.accessibility.settings.TargetPackageParser
import dev.aiauto.android.ui.components.FieldLabel
import dev.aiauto.android.ui.components.NoteText
import dev.aiauto.android.ui.components.NoticeBar
import dev.aiauto.android.ui.components.NoticeTone
import dev.aiauto.android.ui.components.PageDesc
import dev.aiauto.android.ui.components.PageHeading
import dev.aiauto.android.ui.components.ProtoCard
import dev.aiauto.android.ui.components.ProtoPrimaryButton
import dev.aiauto.android.ui.components.ProtoSecondaryButton
import dev.aiauto.android.ui.components.ProtoSectionHead
import dev.aiauto.android.ui.components.ScreenScaffold
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing

@Composable
fun AccessibilityScreen(
    repository: AccessibilitySettingsRepository,
    serviceEnabled: Boolean,
    onBack: () -> Unit,
    onSettingsChanged: (AccessibilitySettings) -> Unit,
) {
    val context = LocalContext.current
    val stored = remember(repository) { repository.load() }
    var targetPackages by remember {
        mutableStateOf(stored.targetPackages.joinToString(separator = "\n"))
    }
    var disclosureAccepted by remember { mutableStateOf(stored.disclosureAccepted) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }

    fun openSystemSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            hasError = true
            feedback = "此设备没有可用的无障碍设置页面。"
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.accessibility_page_title),
        onBack = onBack,
        subtitle = stringResource(R.string.accessibility_page_subtitle),
        infoText = stringResource(R.string.accessibility_page_info),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.ScreenHorizontal)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 页面大标题与描述（原型 page-heading + page-desc）。
            PageHeading(text = stringResource(R.string.accessibility_heading))
            PageDesc(text = stringResource(R.string.accessibility_desc))

            // 2. 醒目披露（原型 .notice.amber）：无障碍是高影响能力，必须显著披露。
            //    此处保留全部原有披露文案，只改容器表现。
            NoticeBar(
                tone = NoticeTone.AMBER,
                title = stringResource(R.string.accessibility_disclosure_title),
                text = stringResource(R.string.accessibility_disclosure_body),
            )
            NoteText(text = stringResource(R.string.accessibility_disclosure_detail))
            NoteText(text = stringResource(R.string.accessibility_disclosure_scope))

            Spacer(Modifier.height(4.dp))

            // 3. 系统服务状态：真实读取，不硬编码。
            ProtoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.accessibility_service_state),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.Ink,
                    )
                    TagChip(
                        text = if (serviceEnabled) {
                            stringResource(R.string.accessibility_service_on)
                        } else {
                            stringResource(R.string.accessibility_service_off)
                        },
                        tone = if (serviceEnabled) TagTone.SUCCESS else TagTone.WARNING,
                    )
                }
            }

            // 4.「允许的应用」分区：包名输入（真实配置，非示例列表）。
            ProtoSectionHead(title = stringResource(R.string.accessibility_apps_section))
            FieldLabel(text = stringResource(R.string.accessibility_packages_label))
            OutlinedTextField(
                value = targetPackages,
                onValueChange = {
                    targetPackages = it
                    feedback = null
                    hasError = false
                },
                placeholder = { Text(stringResource(R.string.accessibility_packages_placeholder)) },
                supportingText = {
                    Text(stringResource(R.string.accessibility_packages_hint))
                },
                minLines = 3,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // 5. 同意勾选（原型底部 checkbox + label）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(min = AppSpacing.MinTouchTarget),
            ) {
                Checkbox(
                    checked = disclosureAccepted,
                    onCheckedChange = {
                        disclosureAccepted = it
                        feedback = null
                        hasError = false
                    },
                )
                Text(
                    text = stringResource(R.string.accessibility_consent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Ink,
                )
            }

            // 6. 主操作：同意并跳系统设置（原型 .primary.full「查看系统授权步骤」的真实对应）。
            ProtoPrimaryButton(
                text = stringResource(R.string.accessibility_grant),
                onClick = {
                    if (!disclosureAccepted) {
                        hasError = true
                        feedback = "请先明确勾选同意醒目披露。"
                        return@ProtoPrimaryButton
                    }
                    when (val parsed = TargetPackageParser.parse(targetPackages)) {
                        is TargetPackageParseResult.Invalid -> {
                            hasError = true
                            feedback = "包名无效：${parsed.invalidValues.joinToString()}"
                        }

                        is TargetPackageParseResult.Valid -> {
                            if (parsed.packages.isEmpty()) {
                                hasError = true
                                feedback = "请至少填写一个目标应用包名。"
                                return@ProtoPrimaryButton
                            }
                            val settings = AccessibilitySettings(
                                disclosureAccepted = true,
                                targetPackages = parsed.packages,
                            )
                            try {
                                repository.save(settings)
                            } catch (_: IllegalStateException) {
                                hasError = true
                                feedback = "保存无障碍配置失败，请重试。"
                                return@ProtoPrimaryButton
                            }
                            onSettingsChanged(settings)
                            feedback = "配置已保存。请在系统页面选择“AI 安卓自动化执行服务”。"
                            hasError = false
                            openSystemSettings()
                        }
                    }
                },
            )

            // 7. 撤销同意：用户随时可收回授权。
            ProtoSecondaryButton(
                text = stringResource(R.string.accessibility_revoke),
                onClick = {
                    val latestPackages = repository.load().targetPackages
                    val settings = AccessibilitySettings(
                        disclosureAccepted = false,
                        targetPackages = latestPackages,
                    )
                    try {
                        repository.save(settings)
                    } catch (_: IllegalStateException) {
                        hasError = true
                        feedback = "撤销同意失败，请重试。"
                        return@ProtoSecondaryButton
                    }
                    disclosureAccepted = false
                    onSettingsChanged(settings)
                    hasError = false
                    feedback = "已撤销 App 内同意。若系统服务仍启用，请同时在系统设置中关闭。"
                },
            )

            // 8. 真实操作反馈。
            feedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasError) AppColors.Danger else AppColors.Success,
                )
            }

            // 9. 底部边界说明。
            NoteText(text = stringResource(R.string.accessibility_bottom_note))
        }
    }
}
