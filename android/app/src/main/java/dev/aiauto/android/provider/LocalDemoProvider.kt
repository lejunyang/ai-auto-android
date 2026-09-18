package dev.aiauto.android.provider

/**
 * 功能用途：本地演示 Provider，在没有真实 AI 服务时跑通完整会话 UI/流程。
 *
 * 安全边界：
 * - 仅用于 debug/test 变体和 UI 流程验证，不发起任何网络请求，不接触真实 API key。
 * - 返回的动作严格通过 ProviderActionParser 校验，不构造畸形参数。
 * - 目标包名从 prompt.uiSummary 中提取，与会话授权范围一致，不越权操作其他应用。
 */

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LocalDemoProvider : AutomationProvider {

    // 演示动作步骤计数器：每次 planNextAction 调用推进一步。
    private var step = 0

    override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
        val targetPackage = extractTargetPackage(prompt.uiSummary) ?: DEFAULT_TARGET_PACKAGE
        val action = when (step) {
            0 -> ProviderAction(
                type = "app.launch",
                params = JsonObject(
                    mapOf("packageName" to JsonPrimitive(targetPackage)),
                ),
            )
            else -> ProviderAction(
                type = "task.finish",
                params = JsonObject(emptyMap()),
            )
        }
        step++
        return ProviderResult(
            action = action,
            rawContent = "local-demo step=${step - 1} type=${action.type}",
        )
    }

    override suspend fun testConnection(): ProviderConnectionResult =
        ProviderConnectionResult.Success

    // 从 uiSummary 中提取 "Authorized target package: <pkg>"。
    private fun extractTargetPackage(uiSummary: String): String? {
        val match = TARGET_PACKAGE_REGEX.find(uiSummary)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    companion object {
        // 默认目标包：系统设置，模拟器中必然存在且无敏感数据。
        private const val DEFAULT_TARGET_PACKAGE = "com.android.settings"
        private val TARGET_PACKAGE_REGEX = Regex("Authorized target package:\\s*(\\S+)")
    }
}
