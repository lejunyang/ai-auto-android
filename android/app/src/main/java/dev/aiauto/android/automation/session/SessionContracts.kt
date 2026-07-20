package dev.aiauto.android.automation.session

/**
 * 功能用途：实现 SessionContracts 对应的受控 AI 自动化会话、风险判断与生命周期管理。
 */

import dev.aiauto.android.provider.ProviderAction

fun interface SessionObserver {
    suspend fun observe(targetPackage: String): SessionObservation
}

fun interface SessionPlanner {
    suspend fun plan(request: SessionPlanRequest): ProviderAction
}

fun interface SessionExecutor {
    suspend fun execute(
        action: ProviderAction,
        targetPackage: String,
    ): SessionExecutionResult
}

interface AutomationSessionFactory {
    fun create(): Result<AutomationSessionEngine>

    fun configuredTargetPackages(): Set<String>
}
