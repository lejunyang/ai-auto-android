package dev.aiauto.android.provider

// 功能用途：实现 ProviderConfig 对应的 AI Provider 配置、调用、动作解析或敏感日志保护。

data class ProviderConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val timeoutSeconds: Int = 30,
)

enum class ProviderConfigField {
    BASE_URL,
    MODEL,
    TIMEOUT,
    API_KEY,
}

data class ProviderValidationResult(
    val errors: Map<ProviderConfigField, String>,
) {
    val isValid: Boolean = errors.isEmpty()
}

object ProviderConfigValidator {
    private const val MIN_TIMEOUT_SECONDS = 1
    private const val MAX_TIMEOUT_SECONDS = 300

    fun validate(config: ProviderConfig, apiKey: String): ProviderValidationResult {
        val errors = buildMap {
            val uri = runCatching { java.net.URI(config.baseUrl.trim()) }.getOrNull()
            if (
                uri == null ||
                uri.scheme != "https" ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null
            ) {
                put(ProviderConfigField.BASE_URL, "Base URL must be an HTTPS origin or path")
            }
            if (config.model.isBlank()) {
                put(ProviderConfigField.MODEL, "Model is required")
            }
            if (config.timeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
                put(ProviderConfigField.TIMEOUT, "Timeout must be between 1 and 300 seconds")
            }
            if (apiKey.isBlank()) {
                put(ProviderConfigField.API_KEY, "API key is required")
            }
        }
        return ProviderValidationResult(errors)
    }

    fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')
}
