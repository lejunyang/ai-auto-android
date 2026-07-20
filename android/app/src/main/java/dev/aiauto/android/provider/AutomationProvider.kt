package dev.aiauto.android.provider

// 功能用途：实现 AutomationProvider 对应的 AI Provider 配置、调用、动作解析或敏感日志保护。

data class AutomationPrompt(
    val task: String,
    val uiSummary: String,
    val previousActionSummary: String? = null,
    val screenshotPng: ByteArray? = null,
)

data class ProviderResult(
    val action: ProviderAction,
    val rawContent: String,
)

sealed interface ProviderConnectionResult {
    data object Success : ProviderConnectionResult

    data class Failure(val message: String) : ProviderConnectionResult
}

interface AutomationProvider {
    suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult

    suspend fun testConnection(): ProviderConnectionResult
}
