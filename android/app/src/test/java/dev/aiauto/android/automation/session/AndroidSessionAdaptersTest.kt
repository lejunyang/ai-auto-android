package dev.aiauto.android.automation.session

import dev.aiauto.android.provider.AutomationPrompt
import dev.aiauto.android.provider.AutomationProvider
import dev.aiauto.android.provider.ProviderAction
import dev.aiauto.android.provider.ProviderConnectionResult
import dev.aiauto.android.provider.ProviderResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidSessionAdaptersTest {
    @Test
    fun `planner binds semantic target to authorized package BitsUT`() = runTest {
        val provider = FakeProvider(
            ProviderAction(
                type = "ui.click",
                params = buildJsonObject {
                    put(
                        "target",
                        buildJsonObject {
                            put(
                                "fingerprint",
                                buildJsonObject { put("text", "Continue") },
                            )
                        },
                    )
                },
            ),
        )

        val action = ProviderSessionPlanner(provider).plan(
            SessionPlanRequest(
                task = "Continue",
                targetPackage = TARGET_PACKAGE,
                observation = SessionObservation(
                    activePackage = TARGET_PACKAGE,
                    uiSummary = "package=$TARGET_PACKAGE",
                ),
                previousActionSummary = null,
            ),
        )

        assertEquals(
            TARGET_PACKAGE,
            action.params.getValue("target")
                .jsonObject
                .getValue("packageName")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            TARGET_PACKAGE,
            provider.lastPrompt!!.uiSummary
                .lineSequence()
                .first()
                .substringAfter(": "),
        )
    }

    private class FakeProvider(
        private val action: ProviderAction,
    ) : AutomationProvider {
        var lastPrompt: AutomationPrompt? = null

        override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
            lastPrompt = prompt
            return ProviderResult(action = action, rawContent = action.toString())
        }

        override suspend fun testConnection(): ProviderConnectionResult =
            ProviderConnectionResult.Success
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
