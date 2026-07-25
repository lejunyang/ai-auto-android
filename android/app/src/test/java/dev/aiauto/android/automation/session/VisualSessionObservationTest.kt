package dev.aiauto.android.automation.session

/**
 * 测试用途：验证授权截图、同轮 hierarchy 与 N44 observation 的绑定和全图片清零。
 */

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.VisualScreen
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSessionObservationTest {
    @Test
    fun `factory binds stable hierarchy and active session then clears every image lease BitsUT`() =
        runTest {
        val screenshotBytes = pngBytes()
        val hierarchy = node(
            children = listOf(
                node(
                    text = "Save",
                    bounds = UiBounds(20, 40, 180, 120),
                ),
            ),
        )
        val authorization = ScreenshotAuthorization(7, TARGET_PACKAGE)
        var active = true
        val factory = VisualSessionObservationFactory(
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 200, 400, 1L),
                )
            },
            currentSnapshot = { AccessibilityResult.Success(hierarchy) },
            acquireAuthorization = { authorization },
            isAuthorizationActive = { active && it == authorization },
            now = { Instant.parse("2026-07-26T01:00:00Z") },
            observationId = { OBSERVATION_ID },
        )

        val lease = factory.create(
            targetPackage = TARGET_PACKAGE,
            hierarchy = hierarchy,
        )

        assertEquals(OBSERVATION_ID, lease.observation.id)
        assertEquals(TARGET_PACKAGE, lease.observation.foregroundPackage)
        assertEquals(200, lease.observation.screen.width)
        assertEquals(400, lease.observation.screen.height)
        assertEquals("Save", lease.observation.hierarchy.single().children.single().label)
        assertTrue(screenshotBytes.all { it == 0.toByte() })
        assertTrue(lease.hasObservation())

        var providerBytes: ByteArray? = null
        val size = lease.withProviderImage { bytes ->
            providerBytes = bytes
            assertTrue(bytes.any { it != 0.toByte() })
            bytes.size
        }
        assertEquals(PNG_SIGNATURE.size, size)
        assertTrue(requireNotNull(providerBytes).all { it == 0.toByte() })

        active = false
        lease.close()
        assertFalse(lease.hasObservation())
        lease.close()
        }

    @Test
    fun `factory preserves natural screen rotation and nonzero crop geometry BitsUT`() = runTest {
        val hierarchy = node(bounds = UiBounds(100, 200, 900, 800))
        val authorization = ScreenshotAuthorization(19, TARGET_PACKAGE)
        val screenshotBytes = pngBytes()
        val factory = VisualSessionObservationFactory(
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 400, 300, 1L),
                )
            },
            currentSnapshot = { AccessibilityResult.Success(hierarchy) },
            acquireAuthorization = { authorization },
            isAuthorizationActive = { true },
            captureGeometry = { _, _ ->
                VisualCaptureGeometry(
                    screen = VisualScreen(1_000, 2_000, 90),
                    crop = PixelBounds(100, 200, 900, 800),
                )
            },
            observationId = { OBSERVATION_ID },
        )

        factory.create(TARGET_PACKAGE, hierarchy).use { lease ->
            assertEquals(VisualScreen(1_000, 2_000, 90), lease.observation.screen)
            assertEquals(
                PixelBounds(100, 200, 900, 800),
                lease.observation.crop,
            )
        }
        assertTrue(screenshotBytes.all { it == 0.toByte() })
    }

    @Test
    fun `factory rejects screenshot crop aspect drift and clears bytes BitsUT`() = runTest {
        val hierarchy = node(bounds = UiBounds(0, 0, 800, 600))
        val authorization = ScreenshotAuthorization(20, TARGET_PACKAGE)
        val screenshotBytes = pngBytes()
        val factory = VisualSessionObservationFactory(
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 400, 400, 1L),
                )
            },
            currentSnapshot = { AccessibilityResult.Success(hierarchy) },
            acquireAuthorization = { authorization },
            isAuthorizationActive = { true },
            captureGeometry = { _, _ ->
                VisualCaptureGeometry(
                    screen = VisualScreen(1_000, 2_000, 0),
                    crop = PixelBounds(0, 0, 800, 600),
                )
            },
        )

        assertFailsVisualSession("aspect ratios") {
            factory.create(TARGET_PACKAGE, hierarchy)
        }
        assertTrue(screenshotBytes.all { it == 0.toByte() })
    }

    @Test
    fun `factory rejects missing authorization and hierarchy drift while clearing screenshot BitsUT`() =
        runTest {
        val hierarchy = node()
        var captureCalls = 0
        val missingAuthorization = VisualSessionObservationFactory(
            screenshotCapture = {
                captureCalls += 1
                error("capture must not run")
            },
            currentSnapshot = { AccessibilityResult.Success(hierarchy) },
            acquireAuthorization = { null },
            isAuthorizationActive = { false },
        )

        assertFailsVisualSession("not authorized") {
            missingAuthorization.create(TARGET_PACKAGE, hierarchy)
        }
        assertEquals(0, captureCalls)

        val screenshotBytes = pngBytes()
        val drifted = hierarchy.copy(children = listOf(node(text = "Changed")))
        val authorization = ScreenshotAuthorization(8, TARGET_PACKAGE)
        val driftFactory = VisualSessionObservationFactory(
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 200, 400, 1L),
                )
            },
            currentSnapshot = { AccessibilityResult.Success(drifted) },
            acquireAuthorization = { authorization },
            isAuthorizationActive = { true },
        )

        assertFailsVisualSession("changed") {
            driftFactory.create(TARGET_PACKAGE, hierarchy)
        }
        assertTrue(screenshotBytes.all { it == 0.toByte() })
        }

    @Test
    fun `factory rejects authorization revoked after capture and clears screenshot BitsUT`() =
        runTest {
            val hierarchy = node()
            val authorization = ScreenshotAuthorization(18, TARGET_PACKAGE)
            val screenshotBytes = pngBytes()
            var authorizationChecks = 0
            val factory = VisualSessionObservationFactory(
                screenshotCapture = {
                    AccessibilityResult.Success(
                        AccessibilityScreenshot(screenshotBytes, 200, 400, 1L),
                    )
                },
                currentSnapshot = { AccessibilityResult.Success(hierarchy) },
                acquireAuthorization = { authorization },
                isAuthorizationActive = {
                    authorizationChecks += 1
                    authorizationChecks == 1
                },
            )

            assertFailsVisualSession("expired") {
                factory.create(TARGET_PACKAGE, hierarchy)
            }

            assertTrue(screenshotBytes.all { it == 0.toByte() })
        }

    @Test
    fun `factory redacts sensitive descendants and rejects package mismatch BitsUT`() = runTest {
        val sensitiveHierarchy = node(
            children = listOf(
                node(
                    text = "secret-value",
                    state = UiNodeState(password = true, sensitive = true),
                    children = listOf(node(text = "nested-secret")),
                ),
            ),
        )
        val authorization = ScreenshotAuthorization(9, TARGET_PACKAGE)
        val screenshotBytes = pngBytes()
        val factory = VisualSessionObservationFactory(
            screenshotCapture = {
                AccessibilityResult.Success(
                    AccessibilityScreenshot(screenshotBytes, 200, 400, 1L),
                )
            },
            currentSnapshot = { AccessibilityResult.Success(sensitiveHierarchy) },
            acquireAuthorization = { authorization },
            isAuthorizationActive = { true },
            observationId = { OBSERVATION_ID },
        )

        factory.create(TARGET_PACKAGE, sensitiveHierarchy).use { lease ->
            val sensitive = lease.observation.hierarchy.single().children.single()
            assertTrue(sensitive.sensitive)
            assertEquals(null, sensitive.label)
            assertTrue(sensitive.children.single().sensitive)
            assertEquals(null, sensitive.children.single().label)
        }

        val wrongPackage = node(packageName = "com.example.other")
        val mismatchedBytes = pngBytes()
        var mismatchCaptureCalls = 0
        val mismatchFactory = VisualSessionObservationFactory(
            screenshotCapture = {
                mismatchCaptureCalls += 1
                AccessibilityResult.Success(
                    AccessibilityScreenshot(mismatchedBytes, 200, 400, 1L),
                )
            },
            currentSnapshot = { AccessibilityResult.Success(wrongPackage) },
            acquireAuthorization = { authorization },
            isAuthorizationActive = { true },
        )
        assertFailsVisualSession("package") {
            mismatchFactory.create(TARGET_PACKAGE, wrongPackage)
        }
        assertEquals(0, mismatchCaptureCalls)
        assertTrue(mismatchedBytes.contentEquals(PNG_SIGNATURE))
    }

    private suspend fun assertFailsVisualSession(
        expectedMessage: String,
        block: suspend () -> Unit,
    ) {
        val error = try {
            block()
            null
        } catch (caught: Throwable) {
            caught
        }
        assertNotNull(error)
        assertTrue(error is SessionFailureException)
        assertTrue(error?.message.orEmpty().contains(expectedMessage, ignoreCase = true))
    }

    private fun node(
        packageName: String = TARGET_PACKAGE,
        text: String? = null,
        bounds: UiBounds = UiBounds(0, 0, 200, 400),
        state: UiNodeState = UiNodeState(enabled = true, visibleToUser = true),
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = packageName,
        className = "android.widget.Button",
        resourceId = null,
        text = text,
        contentDescription = null,
        bounds = bounds,
        actions = emptySet(),
        state = state,
        children = children,
    )

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
        const val OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174044"
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )

        fun pngBytes(): ByteArray = PNG_SIGNATURE.copyOf()
    }
}
