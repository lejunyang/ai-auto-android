package dev.aiauto.android.automation.session

import android.content.Context
import android.content.Intent

import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.bridge.AccessibilityCommandJsonParser
import dev.aiauto.android.bridge.BridgeException
import dev.aiauto.android.provider.AutomationPrompt
import dev.aiauto.android.provider.AutomationProvider
import dev.aiauto.android.provider.OpenAICompatibleProvider
import dev.aiauto.android.provider.ProviderAction
import dev.aiauto.android.provider.ProviderActionParser
import dev.aiauto.android.provider.ProviderConfigRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProviderSessionPlanner(
    private val provider: AutomationProvider,
) : SessionPlanner {
    override suspend fun plan(request: SessionPlanRequest): ProviderAction =
        provider.planNextAction(
            AutomationPrompt(
                task = request.task,
                uiSummary = buildString {
                    appendLine("Authorized target package: ${request.targetPackage}")
                    append(request.observation.uiSummary)
                },
                previousActionSummary = request.previousActionSummary,
            ),
        ).action.bindToTargetPackage(request.targetPackage)

    private fun ProviderAction.bindToTargetPackage(targetPackage: String): ProviderAction {
        val target = params["target"] as? JsonObject ?: return this
        if ("packageName" in target) {
            return this
        }
        val boundTarget = JsonObject(
            target + ("packageName" to JsonPrimitive(targetPackage)),
        )
        return copy(
            params = JsonObject(params + ("target" to boundTarget)),
        )
    }
}

class RuntimeSessionObserver(
    private val contextBuilder: UiContextBuilder = UiContextBuilder(),
) : SessionObserver {
    override suspend fun observe(targetPackage: String): SessionObservation =
        when (val snapshot = AccessibilityRuntime.snapshot(targetPackage)) {
            is AccessibilityResult.Failure -> throw SessionFailureException(
                snapshot.error.message,
            )

            is AccessibilityResult.Success -> {
                val activePackage = snapshot.value.packageName
                    ?: throw SessionFailureException("The active UI has no package name.")
                if (activePackage != targetPackage) {
                    throw SessionFailureException(
                        "The active package changed outside the authorized target.",
                    )
                }
                SessionObservation(
                    activePackage = activePackage,
                    uiSummary = contextBuilder.build(snapshot.value),
                )
            }
        }
}

class RuntimeSessionExecutor(
    context: Context,
    private val commandParser: AccessibilityCommandJsonParser =
        AccessibilityCommandJsonParser(),
) : SessionExecutor {
    private val applicationContext = context.applicationContext

    override suspend fun execute(
        action: ProviderAction,
        targetPackage: String,
    ): SessionExecutionResult = when (action.type) {
        "app.launch" -> launchApp(action, targetPackage)
        "app.stop" -> throw SessionFailureException(
            "Stopping another app is not supported by the non-privileged executor.",
        )

        "ui.wait" -> SessionExecutionResult("Wait condition deferred to verification.")
        "ui.assert" -> SessionExecutionResult("Assertion deferred to verification.")
        else -> executeAccessibilityAction(action)
    }

    private fun launchApp(
        action: ProviderAction,
        targetPackage: String,
    ): SessionExecutionResult {
        val requestedPackage = action.params["packageName"]
            ?.toString()
            ?.trim('"')
            ?: throw SessionFailureException("The launch action has no package name.")
        if (requestedPackage != targetPackage) {
            throw SessionFailureException("The launch action targets a different package.")
        }
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(requestedPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: throw SessionFailureException("The target app has no launchable activity.")
        applicationContext.startActivity(intent)
        return SessionExecutionResult("Launched the authorized target app.")
    }

    private fun executeAccessibilityAction(
        action: ProviderAction,
    ): SessionExecutionResult {
        val command = try {
            commandParser.parse(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive(action.type))
                    put("params", action.params)
                },
            )
        } catch (error: BridgeException) {
            throw SessionFailureException(error.message ?: "The action cannot be executed.", error)
        }
        return when (val result = AccessibilityRuntime.execute(command)) {
            is AccessibilityResult.Failure -> throw SessionFailureException(result.error.message)
            is AccessibilityResult.Success -> SessionExecutionResult(
                "Executed ${action.type} through ${result.value.route.name.lowercase()}.",
            )
        }
    }
}

class AndroidAutomationSessionFactory(
    context: Context,
    private val providerRepository: ProviderConfigRepository,
    private val accessibilityRepository: AccessibilitySettingsRepository,
    private val limits: SessionLimits = SessionLimits(),
) : AutomationSessionFactory {
    private val applicationContext = context.applicationContext

    override fun create(): Result<AutomationSessionEngine> = runCatching {
        val storedProvider = providerRepository.load()
        val apiKeyChars = providerRepository.readApiKey()
            ?: throw SessionFailureException("Configure an AI Provider API key first.")
        val apiKey = try {
            apiKeyChars.concatToString()
        } finally {
            apiKeyChars.fill('\u0000')
        }
        val settings = accessibilityRepository.load()
        if (!settings.isReady) {
            throw SessionFailureException(
                "Accept the accessibility disclosure and configure target packages first.",
            )
        }
        val provider = OpenAICompatibleProvider(
            config = storedProvider.config,
            apiKey = apiKey,
            actionParser = ProviderActionParser(),
        )
        AutomationSessionEngine(
            observer = RuntimeSessionObserver(),
            planner = ProviderSessionPlanner(provider),
            executor = RuntimeSessionExecutor(applicationContext),
            riskPolicy = AutomationRiskPolicy(settings.targetPackages),
            limits = limits,
        )
    }

    override fun configuredTargetPackages(): Set<String> =
        accessibilityRepository.load().targetPackages
}
