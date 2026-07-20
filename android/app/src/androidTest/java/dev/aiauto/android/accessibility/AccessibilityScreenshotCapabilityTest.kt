package dev.aiauto.android.accessibility

// 设备测试用途：在真实 Android 运行时验证 AccessibilityScreenshotCapability 的设备能力、权限前提与生命周期边界。

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory

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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityScreenshotCapabilityTest {
    @Test
    fun realScreenshotFlowsThroughProductionPlannerToProvider() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Manual accessibility tests require manualAccessibility=true",
            InstrumentationRegistry.getArguments()
                .getString(MANUAL_ACCESSIBILITY_ARGUMENT) == "true",
        )
        val targetContext = instrumentation.targetContext
        var activity: Activity? = null
        var provider: CapturingProvider? = null

        try {
            val service = ScreenshotTestAccessibilityService.awaitConnected(TIMEOUT_SECONDS)
            activity = instrumentation.startActivitySync(
                Intent(targetContext, ScreenshotTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            instrumentation.waitForIdleSync()

            val controller = AccessibilityScreenshotController(
                platform = AndroidScreenshotPlatform(
                    service = service,
                    callbackExecutor = service.mainExecutor,
                ),
                snapshot = service::snapshot,
                isAuthorized = { it == targetContext.packageName },
            )
            val capturingProvider = CapturingProvider().also { provider = it }
            val planner = ProviderSessionPlanner(
                provider = capturingProvider,
                screenshotCapture = controller::capture,
            )

            planner.plan(
                SessionPlanRequest(
                    task = "Inspect the test surface",
                    targetPackage = targetContext.packageName,
                    observation = SessionObservation(
                        activePackage = targetContext.packageName,
                        uiSummary = "Non-sensitive screenshot test surface",
                    ),
                    previousActionSummary = null,
                    screenshotsAllowed = true,
                ),
            )

            val png = checkNotNull(capturingProvider.screenshotPng)
            assertTrue("Expected a non-empty PNG", png.size > PNG_SIGNATURE.size)
            assertArrayEquals(PNG_SIGNATURE, png.copyOf(PNG_SIGNATURE.size))
            assertTrue(
                "Expected screenshot to stay within the local byte budget",
                png.size <= ScreenshotImagePolicy().maxPngBytes,
            )
            val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            assertTrue(
                "Expected screenshot dimensions to be locally minimized",
                maxOf(bounds.outWidth, bounds.outHeight) <=
                    ScreenshotImagePolicy().maxLongEdgePx,
            )
        } finally {
            provider?.clearScreenshot()
            activity?.finish()
            instrumentation.waitForIdleSync()
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

        fun clearScreenshot() {
            screenshotPng?.fill(0)
            screenshotPng = null
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val MANUAL_ACCESSIBILITY_ARGUMENT = "manualAccessibility"
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
