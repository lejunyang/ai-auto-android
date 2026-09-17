package dev.aiauto.android.ui.provider

/**
 * 功能用途：实现 ProviderScreen 对应的 AI Provider 配置、调用、动作解析或敏感日志保护。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

import dev.aiauto.android.R
import dev.aiauto.android.provider.OpenAICompatibleProvider
import dev.aiauto.android.provider.ProviderActionParser
import dev.aiauto.android.provider.ProviderConfig
import dev.aiauto.android.provider.ProviderConfigField
import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.provider.ProviderConfigValidator
import dev.aiauto.android.provider.ProviderConnectionResult
import dev.aiauto.android.ui.components.FieldLabel
import dev.aiauto.android.ui.components.NoteText
import dev.aiauto.android.ui.components.NoticeBar
import dev.aiauto.android.ui.components.PageDesc
import dev.aiauto.android.ui.components.PageHeading
import dev.aiauto.android.ui.components.ProtoPrimaryButton
import dev.aiauto.android.ui.components.ProtoSecondaryButton
import dev.aiauto.android.ui.components.ProtoTextButton
import dev.aiauto.android.ui.components.ScreenScaffold
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing

private sealed interface ConnectionFeedback {
    data object Idle : ConnectionFeedback
    data object Testing : ConnectionFeedback
    data object Success : ConnectionFeedback
    data class Error(val message: String) : ConnectionFeedback
}

private sealed interface SaveFeedback {
    data object Idle : SaveFeedback
    data object Success : SaveFeedback
    data object Error : SaveFeedback
}

@Composable
fun ProviderScreen(
    repository: ProviderConfigRepository,
    onBack: () -> Unit,
    onProviderStateChanged: (Boolean) -> Unit,
) {
    val stored = remember(repository) { repository.load() }
    var baseUrl by remember { mutableStateOf(stored.config.baseUrl) }
    var model by remember { mutableStateOf(stored.config.model) }
    var timeout by remember { mutableStateOf(stored.config.timeoutSeconds.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var hasStoredApiKey by remember { mutableStateOf(stored.hasApiKey) }
    var errors by remember { mutableStateOf(emptyMap<ProviderConfigField, String>()) }
    var saveFeedback by remember { mutableStateOf<SaveFeedback>(SaveFeedback.Idle) }
    var connectionFeedback by remember { mutableStateOf<ConnectionFeedback>(ConnectionFeedback.Idle) }
    // 高级设置折叠态（原型 <details>）：超时等参数默认收起，不挡住第一次使用。
    var advancedExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isTesting = connectionFeedback is ConnectionFeedback.Testing

    fun clearFeedback() {
        saveFeedback = SaveFeedback.Idle
        connectionFeedback = ConnectionFeedback.Idle
    }

    fun currentConfig() = ProviderConfig(
        baseUrl = baseUrl,
        model = model,
        timeoutSeconds = timeout.toIntOrNull() ?: 0,
    )

    fun validate(): Boolean {
        val validationKey = when {
            apiKey.isNotBlank() -> apiKey
            hasStoredApiKey -> "stored-key"
            else -> ""
        }
        val result = ProviderConfigValidator.validate(currentConfig(), validationKey)
        errors = result.errors
        return result.isValid
    }

    ScreenScaffold(
        title = stringResource(R.string.provider_page_title),
        onBack = onBack,
        subtitle = stringResource(R.string.provider_page_subtitle),
        infoText = stringResource(R.string.provider_page_info),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.ScreenHorizontal)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 页面大标题与描述（原型 page-heading + page-desc）。
            PageHeading(text = stringResource(R.string.provider_heading))
            PageDesc(text = stringResource(R.string.provider_desc))

            // 2. 安全披露提示条：真实的密钥加密与最小数据发送边界。
            //    原型此处是"不要输入真实 API Key"的演示免责，真实功能替换为实际安全约束。
            NoticeBar(text = stringResource(R.string.provider_security_notice))

            Spacer(Modifier.height(4.dp))

            // 3. 表单字段：服务地址 / 模型名称 / API Key（原型 label + input 结构）。
            FieldLabel(text = stringResource(R.string.provider_field_base_url))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    clearFeedback()
                },
                placeholder = { Text(stringResource(R.string.provider_base_url_placeholder)) },
                supportingText = {
                    Text(
                        errors[ProviderConfigField.BASE_URL]
                            ?: stringResource(R.string.provider_base_url_hint),
                    )
                },
                isError = errors.containsKey(ProviderConfigField.BASE_URL),
                enabled = !isTesting,
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            FieldLabel(text = stringResource(R.string.provider_field_model))
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    clearFeedback()
                },
                placeholder = { Text(stringResource(R.string.provider_model_placeholder)) },
                supportingText = {
                    Text(
                        errors[ProviderConfigField.MODEL]
                            ?: stringResource(R.string.provider_model_hint),
                    )
                },
                isError = errors.containsKey(ProviderConfigField.MODEL),
                enabled = !isTesting,
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            FieldLabel(text = stringResource(R.string.provider_field_api_key))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    clearFeedback()
                },
                placeholder = {
                    Text(
                        if (hasStoredApiKey) {
                            stringResource(R.string.provider_api_key_stored)
                        } else {
                            stringResource(R.string.provider_api_key_placeholder)
                        },
                    )
                },
                supportingText = {
                    Text(
                        errors[ProviderConfigField.API_KEY]
                            ?: stringResource(R.string.provider_api_key_hint),
                    )
                },
                isError = errors.containsKey(ProviderConfigField.API_KEY),
                enabled = !isTesting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // 4. 高级设置（原型 <details> 折叠）：超时不挡住第一次使用。
            ProtoTextButton(
                text = if (advancedExpanded) {
                    stringResource(R.string.provider_advanced_collapse)
                } else {
                    stringResource(R.string.provider_advanced_expand)
                },
                onClick = { advancedExpanded = !advancedExpanded },
            )
            if (advancedExpanded) {
                FieldLabel(text = stringResource(R.string.provider_field_timeout))
                OutlinedTextField(
                    value = timeout,
                    onValueChange = {
                        timeout = it.filter(Char::isDigit).take(3)
                        clearFeedback()
                    },
                    supportingText = {
                        Text(
                            errors[ProviderConfigField.TIMEOUT]
                                ?: stringResource(R.string.provider_timeout_hint),
                        )
                    },
                    isError = errors.containsKey(ProviderConfigField.TIMEOUT),
                    enabled = !isTesting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))

            // 5. 操作按钮（原型 .stack：主按钮 + 次按钮）。
            ProtoPrimaryButton(
                text = stringResource(R.string.provider_save),
                enabled = !isTesting,
                onClick = {
                    if (!validate()) return@ProtoPrimaryButton
                    val keyChars = apiKey.takeIf { it.isNotBlank() }?.toCharArray()
                    try {
                        repository.save(currentConfig(), keyChars)
                    } catch (_: Exception) {
                        saveFeedback = SaveFeedback.Error
                        return@ProtoPrimaryButton
                    }
                    if (keyChars != null) {
                        hasStoredApiKey = true
                    }
                    apiKey = ""
                    saveFeedback = SaveFeedback.Success
                    onProviderStateChanged(true)
                },
            )
            ProtoSecondaryButton(
                text = if (isTesting) {
                    stringResource(R.string.provider_testing)
                } else {
                    stringResource(R.string.provider_test)
                },
                enabled = !isTesting,
                onClick = {
                    if (!validate()) return@ProtoSecondaryButton
                    val keyChars = apiKey.takeIf { it.isNotBlank() }?.toCharArray()
                        ?: repository.readApiKey()
                    if (keyChars == null) {
                        hasStoredApiKey = false
                        errors = errors + (
                            ProviderConfigField.API_KEY to
                                "已保存的 API Key 无法读取，请重新输入"
                            )
                        onProviderStateChanged(false)
                        return@ProtoSecondaryButton
                    }
                    connectionFeedback = ConnectionFeedback.Testing
                    scope.launch {
                        try {
                            val result = OpenAICompatibleProvider(
                                config = currentConfig(),
                                apiKey = keyChars.concatToString(),
                                actionParser = ProviderActionParser(),
                            ).testConnection()
                            connectionFeedback = when (result) {
                                ProviderConnectionResult.Success -> ConnectionFeedback.Success
                                is ProviderConnectionResult.Failure ->
                                    ConnectionFeedback.Error(result.message)
                            }
                        } finally {
                            keyChars.fill('\u0000')
                        }
                    }
                },
            )

            // 6. 真实反馈：保存与连接测试结果，不硬编码成功。
            when (saveFeedback) {
                SaveFeedback.Idle -> Unit
                SaveFeedback.Success -> Text(
                    text = stringResource(R.string.provider_save_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Success,
                )

                SaveFeedback.Error -> Text(
                    text = stringResource(R.string.provider_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Danger,
                )
            }
            when (val feedback = connectionFeedback) {
                ConnectionFeedback.Idle -> Unit
                ConnectionFeedback.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.provider_testing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Muted,
                    )
                }

                ConnectionFeedback.Success -> Text(
                    text = stringResource(R.string.provider_test_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Success,
                )

                is ConnectionFeedback.Error -> Text(
                    text = stringResource(R.string.provider_test_failed, feedback.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Danger,
                )
            }

            // 7. 底部说明：密钥加密与连接测试的真实职责边界。
            NoteText(text = stringResource(R.string.provider_bottom_note))
        }
    }
}
