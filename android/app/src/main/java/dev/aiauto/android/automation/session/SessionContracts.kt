package dev.aiauto.android.automation.session

/**
 * 功能用途：实现 SessionContracts 对应的受控 AI 自动化会话、风险判断与生命周期管理。
 */

import dev.aiauto.android.provider.ProviderAction

fun interface SessionObserver {
    suspend fun observe(targetPackage: String): SessionObservation
}

fun interface SessionPlanner {
    suspend fun plan(request: SessionPlanRequest): SessionPlannedAction
}

fun interface SessionExecutor {
    suspend fun execute(
        action: ProviderAction,
        targetPackage: String,
    ): SessionExecutionResult
}

/** 视觉等一次性上下文在风险确认后复核、执行、后置验证，并由 Engine 统一关闭。 */
interface SessionActionContext : AutoCloseable {
    suspend fun validateBefore(
        observation: SessionObservation,
        targetPackage: String,
    )

    suspend fun execute(targetPackage: String): SessionExecutionResult

    suspend fun verifyAfter(
        observation: SessionObservation,
        targetPackage: String,
    )
}

interface AutomationSessionFactory {
    fun create(): Result<AutomationSessionEngine>

    fun configuredTargetPackages(): Set<String>
}
