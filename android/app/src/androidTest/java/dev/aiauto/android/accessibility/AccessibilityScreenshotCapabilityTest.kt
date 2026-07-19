package dev.aiauto.android.accessibility

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.automation.session.ProviderSessionPlanner
import dev.aiauto.android.automation.session.SessionObservation
import dev.aiauto.android.automation.session.SessionPlanRequest
import dev.aiauto.android.provider.AutomationPrompt
import dev.aiauto.android.provider.AutomationProvider
import dev.aiauto.android.provider.ProviderAction
import dev.aiauto.android.provider.ProviderConnectionResult
import dev.aiauto.android.provider.ProviderResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityScreenshotCapabilityTest {
    @Test
    fun realScreenshotFlowsThroughProductionPlannerToProvider() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val resolver = testContext.contentResolver
        val automation = instrumentation.uiAutomation
        val originalServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val originalEnabled = Settings.Secure.getString(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
        )
        var activity: Activity? = null

        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            ScreenshotTestAccessibilityService.reset()
            val serviceComponent = ComponentName(
                testContext,
                ScreenshotTestAccessibilityService::class.java,
            ).flattenToString()
            val enabledServices = originalServices
                .orEmpty()
                .split(':')
                .filter(String::isNotBlank)
                .plus(serviceComponent)
                .distinct()
                .joinToString(":")
            check(
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    enabledServices,
                ),
            )
            check(
                Settings.Secure.putInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1,
                ),
            )

            val service = ScreenshotTestAccessibilityService.awaitConnected(TIMEOUT_SECONDS)
            activity = instrumentation.startActivitySync(
                Intent(testContext, ScreenshotTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            instrumentation.waitForIdleSync()

            val controller = AccessibilityScreenshotController(
                platform = AndroidScreenshotPlatform(
                    service = service,
                    callbackExecutor = service.mainExecutor,
                ),
                snapshot = service::snapshot,
                isAuthorized = { it == testContext.packageName },
            )
            val provider = CapturingProvider()
            val planner = ProviderSessionPlanner(
                provider = provider,
                screenshotCapture = controller::capture,
            )

            planner.plan(
                SessionPlanRequest(
                    task = "Inspect the test surface",
                    targetPackage = testContext.packageName,
                    observation = SessionObservation(
                        activePackage = testContext.packageName,
                        uiSummary = "Non-sensitive screenshot test surface",
                    ),
                    previousActionSummary = null,
                    screenshotsAllowed = true,
                ),
            )

            val png = checkNotNull(provider.screenshotPng)
            assertTrue("Expected a non-empty PNG", png.size > PNG_SIGNATURE.size)
            assertArrayEquals(PNG_SIGNATURE, png.copyOf(PNG_SIGNATURE.size))
        } finally {
            activity?.finish()
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                originalServices,
            )
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                originalEnabled,
            )
            automation.dropShellPermissionIdentity()
            instrumentation.waitForIdleSync()
            ScreenshotTestAccessibilityService.reset()
        }
    }

    private class CapturingProvider : AutomationProvider {
        var screenshotPng: ByteArray? = null
            private set

        override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
            screenshotPng = prompt.screenshotPng?.copyOf()
            return ProviderResult(
                action = ProviderAction(
                    type = "task.finish",
                    params = buildJsonObject { put("summary", "captured") },
                ),
                rawContent = """{"type":"task.finish"}""",
            )
        }

        override suspend fun testConnection(): ProviderConnectionResult =
            ProviderConnectionResult.Success
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
