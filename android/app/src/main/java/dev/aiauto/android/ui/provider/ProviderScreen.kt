package dev.aiauto.android.ui.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

import dev.aiauto.android.provider.OpenAICompatibleProvider
import dev.aiauto.android.provider.ProviderActionParser
import dev.aiauto.android.provider.ProviderConfig
import dev.aiauto.android.provider.ProviderConfigField
import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.provider.ProviderConfigValidator
import dev.aiauto.android.provider.ProviderConnectionResult
import dev.aiauto.android.ui.components.ScreenScaffold

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
        title = "AI Provider",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "OpenAI 兼容接口",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "请求只会发送当前任务所需的最小界面摘要。API Key 使用 Android Keystore 加密，不写入日志或备份。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    clearFeedback()
                },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                supportingText = {
                    Text(errors[ProviderConfigField.BASE_URL] ?: "仅允许 HTTPS，不接受查询参数或内嵌账号")
                },
                isError = errors.containsKey(ProviderConfigField.BASE_URL),
                enabled = !isTesting,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    clearFeedback()
                },
                label = { Text("模型") },
                placeholder = { Text("gpt-4.1-mini") },
                supportingText = {
                    Text(errors[ProviderConfigField.MODEL] ?: "填写 Provider 支持的模型标识")
                },
                isError = errors.containsKey(ProviderConfigField.MODEL),
                enabled = !isTesting,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = timeout,
                onValueChange = {
                    timeout = it.filter(Char::isDigit).take(3)
                    clearFeedback()
                },
                label = { Text("超时（秒）") },
                supportingText = {
                    Text(errors[ProviderConfigField.TIMEOUT] ?: "允许 1 到 300 秒")
                },
                isError = errors.containsKey(ProviderConfigField.TIMEOUT),
                enabled = !isTesting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    clearFeedback()
                },
                label = { Text("API Key") },
                placeholder = {
                    Text(if (hasStoredApiKey) "已加密保存，留空则不更新" else "输入 API Key")
                },
                supportingText = {
                    Text(errors[ProviderConfigField.API_KEY] ?: "保存后输入框会立即清空")
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
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (!validate()) return@Button
                    val keyChars = apiKey.takeIf { it.isNotBlank() }?.toCharArray()
                    try {
                        repository.save(currentConfig(), keyChars)
                    } catch (_: Exception) {
                        saveFeedback = SaveFeedback.Error
                        return@Button
                    }
                    if (keyChars != null) {
                        hasStoredApiKey = true
                    }
                    apiKey = ""
                    saveFeedback = SaveFeedback.Success
                    onProviderStateChanged(true)
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存配置")
            }
            OutlinedButton(
                onClick = {
                    if (!validate()) return@OutlinedButton
                    val keyChars = apiKey.takeIf { it.isNotBlank() }?.toCharArray()
                        ?: repository.readApiKey()
                    if (keyChars == null) {
                        hasStoredApiKey = false
                        errors = errors + (
                            ProviderConfigField.API_KEY to
                                "已保存的 API Key 无法读取，请重新输入"
                            )
                        onProviderStateChanged(false)
                        return@OutlinedButton
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
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (connectionFeedback is ConnectionFeedback.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text("正在测试")
                } else {
                    Text("测试连接")
                }
            }
            when (saveFeedback) {
                SaveFeedback.Idle -> Unit
                SaveFeedback.Success -> Text(
                    text = "配置已安全保存",
                    color = MaterialTheme.colorScheme.primary,
                )

                SaveFeedback.Error -> Text(
                    text = "配置保存失败，请确认设备安全状态后重试。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (val feedback = connectionFeedback) {
                ConnectionFeedback.Idle, ConnectionFeedback.Testing -> Unit
                ConnectionFeedback.Success -> Text(
                    text = "连接成功，Provider 返回了有效响应。",
                    color = MaterialTheme.colorScheme.primary,
                )

                is ConnectionFeedback.Error -> Text(
                    text = "连接失败：${feedback.message}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
