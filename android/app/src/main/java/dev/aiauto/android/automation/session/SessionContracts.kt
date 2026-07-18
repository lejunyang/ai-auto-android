package dev.aiauto.android.automation.session

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
