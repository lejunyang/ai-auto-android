package dev.aiauto.android.automation.session

/**
 * 测试用途：验证 AndroidSessionAdapters 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.provider.AutomationPrompt
import dev.aiauto.android.provider.AutomationProvider
import dev.aiauto.android.provider.ProviderAction
import dev.aiauto.android.provider.ProviderConnectionResult
import dev.aiauto.android.provider.ProviderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
                                "selectorCandidates",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("strategy", "text")
                                            put("value", "Continue")
                                            put("weight", 1.0)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )

        val planned = ProviderSessionPlanner(provider).plan(
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
            planned.action.params.getValue("target")
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
            val capturedPng = pngBytes(screenshotBytes)
            val provider = FakeProvider(finishAction())
            var captures = 0
            val planner = visualPlanner(provider) {
                captures += 1
                AccessibilityResult.Success(
                    AccessibilityScreenshot(capturedPng, 100, 200, 1L),
                )
            }

            planner.plan(request(screenshotsAllowed = true))

            assertEquals(1, captures)
            assertArrayEquals(pngBytes(screenshotBytes), provider.screenshotAtCall)
            assertEquals(TARGET_PACKAGE, provider.visualAtCall?.foregroundPackage)
            assertEquals(100, provider.visualAtCall?.width)
            assertEquals(200, provider.visualAtCall?.height)
            assertEquals(provider.screenshotAtCall?.size, provider.visualAtCall?.pngSizeBytes)
            assertTrue(provider.visualAtCall?.pngSha256?.length == 64)
            assertTrue(requireNotNull(provider.lastPrompt?.screenshotPng).all { it == 0.toByte() })
            assertTrue(capturedPng.all { it == 0.toByte() })
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
        val capturedPng = pngBytes(screenshotBytes)
        var failedPrompt: AutomationPrompt? = null
        val planner = ProviderSessionPlanner(
            provider = object : AutomationProvider {
                override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
                    failedPrompt = prompt
                    throw IllegalStateException("provider failed")
                }

                override suspend fun testConnection(): ProviderConnectionResult =
                    ProviderConnectionResult.Success
            },
            currentSnapshot = { AccessibilityResult.Success(hierarchy()) },
            acquireAuthorization = { AUTHORIZATION },
            isAuthorizationActive = { it == AUTHORIZATION },
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(capturedPng, 100, 200, 1L),
                )
            },
        )

        try {
            planner.plan(request(screenshotsAllowed = true))
            fail("Expected provider failure")
        } catch (error: IllegalStateException) {
            assertEquals("provider failed", error.message)
        }
        assertTrue(requireNotNull(failedPrompt?.screenshotPng).all { it == 0.toByte() })
        assertTrue(capturedPng.all { it == 0.toByte() })
    }

    @Test
    fun `planner clears visual image and propagates provider cancellation BitsUT`() = runTest {
        val capturedPng = pngBytes(byteArrayOf(6, 5, 4))
        var cancelledPrompt: AutomationPrompt? = null
        val planner = visualPlanner(
            provider = object : AutomationProvider {
                override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
                    cancelledPrompt = prompt
                    throw CancellationException("cancelled")
                }

                override suspend fun testConnection(): ProviderConnectionResult =
                    ProviderConnectionResult.Success
            },
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(capturedPng, 100, 200, 1L),
                )
            },
        )

        try {
            planner.plan(request(screenshotsAllowed = true))
            fail("Expected provider cancellation")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }

        assertTrue(requireNotNull(cancelledPrompt?.screenshotPng).all { it == 0.toByte() })
        assertTrue(capturedPng.all { it == 0.toByte() })
    }

    @Test
    fun `planner transfers visual lease to context and closes it with planned action BitsUT`() =
        runTest {
            val capturedPng = pngBytes(byteArrayOf(7, 7, 7))
            var leaseVisibleAtFactory = false
            var contextClosed = false
            val planner = ProviderSessionPlanner(
                provider = FakeProvider(visualTap(capturedPng)),
                currentSnapshot = { AccessibilityResult.Success(hierarchy()) },
                acquireAuthorization = { AUTHORIZATION },
                isAuthorizationActive = { it == AUTHORIZATION },
                visualContextFactory = AuthorizedVisualActionContextFactory {
                        _, _, _, lease,
                    ->
                    leaseVisibleAtFactory = lease.hasObservation()
                    object : SessionActionContext {
                        override suspend fun validateBefore(
                            observation: SessionObservation,
                            targetPackage: String,
                        ) = Unit

                        override suspend fun execute(targetPackage: String) =
                            SessionExecutionResult("visual")

                        override suspend fun verifyAfter(
                            observation: SessionObservation,
                            targetPackage: String,
                        ) = Unit

                        override fun close() {
                            contextClosed = true
                            lease.close()
                        }
                    }
                },
                screenshotCapture = {
                    AccessibilityResult.Success(
                        AccessibilityScreenshot(capturedPng, 100, 200, 1L),
                    )
                },
            )

            val planned = planner.plan(request(screenshotsAllowed = true))

            assertTrue(leaseVisibleAtFactory)
            assertTrue(planned.context != null)
            assertTrue(capturedPng.all { it == 0.toByte() })
            assertTrue(!contextClosed)
            planned.close()
            assertTrue(contextClosed)
            assertEquals(null, planned.context)
        }

    @Test
    fun `planner rejects bare visual swipe and missing factory while closing screenshot lease BitsUT`() =
        runTest {
            for (action in listOf(
                ProviderAction(
                    type = "ui.tap",
                    params = buildJsonObject {},
                ),
                visualTap(pngBytes()).copy(type = "ui.swipe"),
                visualTap(pngBytes()),
            )) {
                val capturedPng = pngBytes(byteArrayOf(4, 4, 4))
                val planner = ProviderSessionPlanner(
                    provider = FakeProvider(action),
                    currentSnapshot = { AccessibilityResult.Success(hierarchy()) },
                    acquireAuthorization = { AUTHORIZATION },
                    isAuthorizationActive = { it == AUTHORIZATION },
                    screenshotCapture = {
                        AccessibilityResult.Success(
                            AccessibilityScreenshot(capturedPng, 100, 200, 1L),
                        )
                    },
                )

                val failure = try {
                    planner.plan(request(screenshotsAllowed = true))
                    null
                } catch (error: SessionFailureException) {
                    error
                }

                assertTrue(failure != null)
                assertTrue(capturedPng.all { it == 0.toByte() })
            }
        }

    @Test
    fun `planner rejects forged visual evidence when screenshots are not authorized BitsUT`() =
        runTest {
            var contextCalls = 0
            val planner = ProviderSessionPlanner(
                provider = FakeProvider(visualTap(pngBytes())),
                visualContextFactory = AuthorizedVisualActionContextFactory { _, _, _, _ ->
                    contextCalls += 1
                    error("context factory must not run without an authorized observation")
                },
                screenshotCapture = {
                    error("screenshot capture must not run without authorization")
                },
            )

            val failure = try {
                planner.plan(request(screenshotsAllowed = false))
                null
            } catch (error: SessionFailureException) {
                error
            }

            assertTrue(failure?.message.orEmpty().contains("active screenshot observation"))
            assertEquals(0, contextCalls)
        }

    private fun visualPlanner(
        provider: AutomationProvider,
        screenshotCapture: suspend (String) -> AccessibilityResult<AccessibilityScreenshot>,
    ) = ProviderSessionPlanner(
        provider = provider,
        currentSnapshot = { AccessibilityResult.Success(hierarchy()) },
        acquireAuthorization = { AUTHORIZATION },
        isAuthorizationActive = { it == AUTHORIZATION },
        screenshotCapture = screenshotCapture,
    )

    private fun request(screenshotsAllowed: Boolean) = SessionPlanRequest(
        task = "Continue",
        targetPackage = TARGET_PACKAGE,
        observation = SessionObservation(
            TARGET_PACKAGE,
            "package=$TARGET_PACKAGE",
            hierarchy(),
        ),
        previousActionSummary = null,
        screenshotsAllowed = screenshotsAllowed,
    )

    private fun finishAction() = ProviderAction(
        type = "task.finish",
        params = buildJsonObject { put("summary", "done") },
    )

    private fun visualTap(png: ByteArray) = ProviderAction(
        type = "ui.tap",
        params = buildJsonObject {
            put(
                "visualTarget",
                buildJsonObject {
                    put("packageName", TARGET_PACKAGE)
                    put("observationId", "123e4567-e89b-42d3-a456-426614174044")
                    put("imageSha256", sha256(png))
                    put("candidateId", "123e4567-e89b-42d3-a456-426614174045")
                    put("source", "model")
                    put("confidence", 0.93)
                    put("point", buildJsonObject {
                        put("x", 0.5)
                        put("y", 0.75)
                    })
                    put("bounds", buildJsonObject {
                        put("left", 0.4)
                        put("top", 0.7)
                        put("right", 0.6)
                        put("bottom", 0.8)
                    })
                },
            )
        },
    )

    private class FakeProvider(
        private val action: ProviderAction,
    ) : AutomationProvider {
        var lastPrompt: AutomationPrompt? = null
        var screenshotAtCall: ByteArray? = null
        var visualAtCall: dev.aiauto.android.provider.VisualObservationContext? = null

        override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
            lastPrompt = prompt
            screenshotAtCall = prompt.screenshotPng?.copyOf()
            visualAtCall = prompt.visualObservation
            return ProviderResult(action = action, rawContent = action.toString())
        }

        override suspend fun testConnection(): ProviderConnectionResult =
            ProviderConnectionResult.Success
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
        val AUTHORIZATION = ScreenshotAuthorization(17, TARGET_PACKAGE)

        fun hierarchy() = UiNodeSnapshot(
            packageName = TARGET_PACKAGE,
            className = "android.view.View",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = UiBounds(0, 0, 100, 200),
            actions = emptySet(),
            state = UiNodeState(enabled = true, visibleToUser = true),
            children = emptyList(),
        )

        fun pngBytes(tail: ByteArray = byteArrayOf()): ByteArray =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            ) + tail

        fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
