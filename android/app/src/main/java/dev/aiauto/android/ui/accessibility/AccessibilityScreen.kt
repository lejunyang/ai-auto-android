package dev.aiauto.android.ui.accessibility

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp

import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.settings.TargetPackageParseResult
import dev.aiauto.android.accessibility.settings.TargetPackageParser
import dev.aiauto.android.ui.components.ScreenScaffold

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
        title = "无障碍执行",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (serviceEnabled) "系统服务已启用" else "系统服务未启用",
                style = MaterialTheme.typography.headlineSmall,
                color = if (serviceEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "醒目披露：此服务可以读取屏幕内容并代你操作",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "启用后，服务会在你填写的目标应用中读取界面文字、控件说明、层级、位置和状态，并可执行点击、长按、输入、滚动、返回、主页和最近任务。",
                    )
                    Text(
                        text = "这些信息可能包含你正在查看或输入的内容。密码字段、验证码和支付相关节点会在快照中清除，且不会记录或上传；受保护页面可能无法观察或操作。",
                    )
                    Text(
                        text = "本应用不是面向残障辅助用途的无障碍工具。你可以随时在系统设置中关闭服务，或在此撤销同意。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            OutlinedTextField(
                value = targetPackages,
                onValueChange = {
                    targetPackages = it
                    feedback = null
                    hasError = false
                },
                label = { Text("允许操作的目标应用包名") },
                placeholder = { Text("com.example.app") },
                supportingText = {
                    Text("每行、空格或逗号分隔，最多 32 个；服务不会处理其他应用。")
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    text = "我已阅读并同意上述读取与操作范围",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(
                onClick = {
                    if (!disclosureAccepted) {
                        hasError = true
                        feedback = "请先明确勾选同意醒目披露。"
                        return@Button
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
                                return@Button
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
                                return@Button
                            }
                            onSettingsChanged(settings)
                            feedback = "配置已保存。请在系统页面选择“AI 安卓自动化执行服务”。"
                            hasError = false
                            openSystemSettings()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("同意并打开系统无障碍设置")
            }
            OutlinedButton(
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
                        return@OutlinedButton
                    }
                    disclosureAccepted = false
                    onSettingsChanged(settings)
                    hasError = false
                    feedback = "已撤销 App 内同意。若系统服务仍启用，请同时在系统设置中关闭。"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("撤销同意")
            }
            feedback?.let { message ->
                Text(
                    text = message,
                    color = if (hasError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}
