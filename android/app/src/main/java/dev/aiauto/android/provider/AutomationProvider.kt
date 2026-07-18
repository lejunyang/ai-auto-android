package dev.aiauto.android.provider

data class AutomationPrompt(
    val task: String,
    val uiSummary: String,
    val previousActionSummary: String? = null,
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
