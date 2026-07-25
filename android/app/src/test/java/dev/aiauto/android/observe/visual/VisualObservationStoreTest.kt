package dev.aiauto.android.observe.visual

/**
 * 测试用途：验证视觉 observation 的绑定、防漂移、可信校验、脱敏和图片清零边界。
 */

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VisualObservationStoreTest {
    @Test
    fun `registration binds metadata and clears caller plus verifier bytes BitsUT`() {
        val callerBytes = pngBytes()
        var verifierBytes: ByteArray? = null
        val store = VisualObservationStore(
            trustedVerifier = TrustedVisualImageVerifier { bytes, context ->
                verifierBytes = bytes
                context.observationId == OBSERVATION_ID &&
                    context.pngSha256 == sha256(bytes)
            },
        )

        val result = store.register(capture(pngBytes = callerBytes))

        val observation = success(result)
        assertEquals(OBSERVATION_ID, observation.id)
        assertEquals(TARGET_PACKAGE, observation.foregroundPackage)
        assertEquals(90, observation.screen.rotation)
        assertEquals(PixelBounds(200, 100, 1200, 900), observation.crop)
        assertEquals(sha256(pngBytes()), observation.png.sha256)
        assertEquals("Save", observation.hierarchy.single().label)
        assertTrue(callerBytes.all { it == 0.toByte() })
        assertTrue(requireNotNull(verifierBytes).all { it == 0.toByte() })
        store.close()
    }

    @Test
    fun `same observation id cannot drift package screen crop time hash or hierarchy BitsUT`() {
        val store = trustedStore()
        val first = success(store.register(capture()))
        val driftedCaptures = listOf(
            capture(foregroundPackage = "com.example.changed"),
            capture(screen = VisualScreen(1001, 2000, 90)),
            capture(crop = PixelBounds(201, 100, 1200, 900)),
            capture(expiresAt = "2026-07-26T01:00:11Z"),
            capture(pngBytes = pngBytes(extra = 1)),
            capture(
                hierarchy = listOf(
                    VisualHierarchyNode(
                        role = "button",
                        label = "Changed",
                        bounds = bounds(),
                    ),
                ),
            ),
        )

        driftedCaptures.forEach { drifted ->
            val result = store.register(drifted)
            assertFailure(result, VisualErrorCode.OBSERVATION_ID_DRIFT)
            assertTrue(drifted.pngBytes.all { it == 0.toByte() })
        }
        assertEquals(first, requireNotNull(store.lookup(OBSERVATION_ID)))
        store.close()
    }

    @Test
    fun `self hash cannot replace trusted verifier and secure empty or oversized images fail closed BitsUT`() {
        val cases = listOf(
            VisualObservationStore(trustedVerifier = null) to capture(),
            trustedStore() to capture(secureWindow = true),
            trustedStore() to capture(pngBytes = ByteArray(0)),
            VisualObservationStore(
                trustedVerifier = allowVerifier(),
                maxPngBytes = 7,
            ) to capture(),
        )
        val expectedCodes = listOf(
            VisualErrorCode.IMAGE_UNVERIFIED,
            VisualErrorCode.SECURE_WINDOW,
            VisualErrorCode.IMAGE_EMPTY,
            VisualErrorCode.IMAGE_BUDGET_EXCEEDED,
        )

        cases.zip(expectedCodes).forEach { (case, expected) ->
            val (store, input) = case
            val result = store.register(input)
            assertFailure(result, expected)
            assertTrue(input.pngBytes.all { it == 0.toByte() })
            assertEquals(null, store.lookup(OBSERVATION_ID))
            store.close()
        }
    }

    @Test
    fun `uuid version seven observation identity is accepted BitsUT`() {
        val store = trustedStore()
        val uuidV7 = "019f9a38-21b8-73b2-b5a9-81dff4845375"

        val observation = success(
            store.register(capture().copy(id = uuidV7)),
        )

        assertEquals(uuidV7, observation.id)
        store.close()
    }

    @Test
    fun `crop must be positive and inside the rotated capture plane BitsUT`() {
        val invalidCrops = listOf(
            PixelBounds(200, 100, 200, 900),
            PixelBounds(200, 100, 1200, 100),
            PixelBounds(1200, 100, 200, 900),
            PixelBounds(200, 900, 1200, 100),
            PixelBounds(-1, 100, 1200, 900),
            PixelBounds(200, -1, 1200, 900),
            PixelBounds(200, 100, 2001, 900),
            PixelBounds(200, 100, 1200, 1001),
        )

        invalidCrops.forEach { crop ->
            val store = trustedStore()
            val input = capture(crop = crop)

            assertFailure(store.register(input), VisualErrorCode.CROP_INVALID)
            assertTrue(input.pngBytes.all { it == 0.toByte() })
            assertEquals(null, store.lookup(OBSERVATION_ID))
            store.close()
        }
    }

    @Test
    fun `screen dimensions and rotation must define a valid natural coordinate space BitsUT`() {
        val invalidScreens = listOf(
            VisualScreen(0, 2000, 0),
            VisualScreen(1000, 0, 0),
            VisualScreen(1000, 2000, 45),
        )

        invalidScreens.forEach { screen ->
            val store = trustedStore()
            val input = capture(screen = screen)

            assertFailure(store.register(input), VisualErrorCode.SCREEN_INVALID)
            assertTrue(input.pngBytes.all { it == 0.toByte() })
            store.close()
        }
    }

    @Test
    fun `sensitive hierarchy labels are removed before observation leaves store BitsUT`() {
        val store = trustedStore()
        val observation = success(
            store.register(
                capture(
                    hierarchy = listOf(
                        VisualHierarchyNode(
                            role = "password",
                            label = "secret-123",
                            bounds = bounds(),
                            sensitive = true,
                            children = listOf(
                                VisualHierarchyNode(
                                    role = "text",
                                    label = "nested-secret",
                                    bounds = bounds(),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val sensitive = observation.hierarchy.single()
        assertEquals(null, sensitive.label)
        assertEquals(null, sensitive.children.single().label)
        assertTrue(sensitive.children.single().sensitive)
        store.close()
    }

    @Test
    fun `image lease clears callback copy and owned bytes on revoke BitsUT`() {
        val store = trustedStore()
        success(store.register(capture()))
        var callbackBytes: ByteArray? = null

        store.withImage(OBSERVATION_ID) { bytes ->
            callbackBytes = bytes
            assertNotEquals(0, bytes.count { it != 0.toByte() })
        }
        assertTrue(requireNotNull(callbackBytes).all { it == 0.toByte() })
        assertTrue(store.revoke(OBSERVATION_ID))
        assertFailure(
            store.withImage(OBSERVATION_ID) { error("revoked image must be unavailable") },
            VisualErrorCode.OBSERVATION_NOT_FOUND,
        )
        store.close()
    }

    private fun trustedStore() = VisualObservationStore(allowVerifier())

    private fun allowVerifier() = TrustedVisualImageVerifier { bytes, context ->
        context.pngSha256 == sha256(bytes)
    }

    private fun capture(
        foregroundPackage: String = TARGET_PACKAGE,
        screen: VisualScreen = VisualScreen(1000, 2000, 90),
        crop: PixelBounds = PixelBounds(200, 100, 1200, 900),
        expiresAt: String = "2026-07-26T01:00:10Z",
        pngBytes: ByteArray = pngBytes(),
        hierarchy: List<VisualHierarchyNode> = listOf(
            VisualHierarchyNode(
                role = "button",
                label = "Save",
                bounds = bounds(),
            ),
        ),
        secureWindow: Boolean = false,
    ) = VisualObservationCapture(
        id = OBSERVATION_ID,
        foregroundPackage = foregroundPackage,
        screen = screen,
        crop = crop,
        capturedAt = "2026-07-26T01:00:00Z",
        expiresAt = expiresAt,
        pngBytes = pngBytes,
        hierarchy = hierarchy,
        secureWindow = secureWindow,
    )

    private fun bounds() = NormalizedBounds(0.3, 0.6, 0.7, 0.8)

    private fun <T> success(result: VisualResult<T>): T {
        assertTrue(result is VisualResult.Success)
        return (result as VisualResult.Success).value
    }

    private fun assertFailure(result: VisualResult<*>, expected: VisualErrorCode) {
        assertTrue(result is VisualResult.Failure)
        val failure = result as VisualResult.Failure
        assertEquals(expected, failure.code)
        assertEquals(emptyList<VisualCandidate>(), failure.candidates)
        assertEquals(0, failure.actionCommitCount)
    }

    private companion object {
        const val OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174044"
        const val TARGET_PACKAGE = "com.example.notes"

        fun pngBytes(extra: Byte = 0): ByteArray =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
                extra,
            )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
