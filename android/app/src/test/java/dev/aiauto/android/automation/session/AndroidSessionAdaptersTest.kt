package dev.aiauto.android.automation.session

// 测试用途：验证 AndroidSessionAdapters 的功能契约、失败语义及自动化安全边界。

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `planner captures one authorized screenshot and clears it after provider returns BitsUT`() =
        runTest {
            val screenshotBytes = byteArrayOf(1, 2, 3, 4)
            val provider = FakeProvider(finishAction())
            var captures = 0
            val planner = ProviderSessionPlanner(provider) {
                captures += 1
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 1, 1, 1L),
                )
            }

            planner.plan(request(screenshotsAllowed = true))

            assertEquals(1, captures)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), provider.screenshotAtCall)
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), screenshotBytes)
        }

    @Test
    fun `planner does not capture when screenshot authorization is disabled BitsUT`() = runTest {
        val provider = FakeProvider(finishAction())
        var captures = 0
        val planner = ProviderSessionPlanner(provider) {
            captures += 1
            error("capture must not run")
        }

        planner.plan(request(screenshotsAllowed = false))

        assertEquals(0, captures)
        assertEquals(null, provider.lastPrompt?.screenshotPng)
    }

    @Test
    fun `planner clears screenshot when provider fails BitsUT`() = runTest {
        val screenshotBytes = byteArrayOf(9, 8, 7)
        val planner = ProviderSessionPlanner(
            provider = object : AutomationProvider {
                override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
                    throw IllegalStateException("provider failed")
                }

                override suspend fun testConnection(): ProviderConnectionResult =
                    ProviderConnectionResult.Success
            },
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 1, 1, 1L),
                )
            },
        )

        try {
            planner.plan(request(screenshotsAllowed = true))
            fail("Expected provider failure")
        } catch (error: IllegalStateException) {
            assertEquals("provider failed", error.message)
        }
        assertTrue(screenshotBytes.all { it == 0.toByte() })
    }

    private fun request(screenshotsAllowed: Boolean) = SessionPlanRequest(
        task = "Continue",
        targetPackage = TARGET_PACKAGE,
        observation = SessionObservation(TARGET_PACKAGE, "package=$TARGET_PACKAGE"),
        previousActionSummary = null,
        screenshotsAllowed = screenshotsAllowed,
    )

    private fun finishAction() = ProviderAction(
        type = "task.finish",
        params = buildJsonObject { put("summary", "done") },
    )

    private class FakeProvider(
        private val action: ProviderAction,
    ) : AutomationProvider {
        var lastPrompt: AutomationPrompt? = null
        var screenshotAtCall: ByteArray? = null

        override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
            lastPrompt = prompt
            screenshotAtCall = prompt.screenshotPng?.copyOf()
            return ProviderResult(action = action, rawContent = action.toString())
        }

        override suspend fun testConnection(): ProviderConnectionResult =
            ProviderConnectionResult.Success
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
