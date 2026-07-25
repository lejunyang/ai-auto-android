package dev.aiauto.android.observe.visual

/**
 * 测试用途：验证视觉候选规范化映射、只提议不执行以及全部风险条件失败关闭。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualProposalServiceTest {
    @Test
    fun `crop coordinates map through every screen rotation BitsUT`() {
        data class RotationCase(
            val rotation: Int,
            val crop: PixelBounds,
            val expectedPoint: NormalizedPoint,
            val expectedBounds: NormalizedBounds,
        )

        val cases = listOf(
            RotationCase(
                rotation = 0,
                crop = PixelBounds(100, 400, 900, 1200),
                expectedPoint = NormalizedPoint(0.5, 0.4),
                expectedBounds = NormalizedBounds(0.26, 0.3, 0.58, 0.5),
            ),
            RotationCase(
                rotation = 90,
                crop = PixelBounds(200, 100, 1200, 900),
                expectedPoint = NormalizedPoint(0.5, 0.65),
                expectedBounds = NormalizedBounds(0.3, 0.6, 0.7, 0.8),
            ),
            RotationCase(
                rotation = 180,
                crop = PixelBounds(100, 400, 900, 1200),
                expectedPoint = NormalizedPoint(0.5, 0.6),
                expectedBounds = NormalizedBounds(0.42, 0.5, 0.74, 0.7),
            ),
            RotationCase(
                rotation = 270,
                crop = PixelBounds(200, 100, 1200, 900),
                expectedPoint = NormalizedPoint(0.5, 0.35),
                expectedBounds = NormalizedBounds(0.3, 0.2, 0.7, 0.4),
            ),
        )

        cases.forEach { case ->
            val store = registeredStore(rotation = case.rotation, crop = case.crop)
            val service = VisualProposalService(
                observations = store,
                provider = VisualCandidateProvider { _, _ ->
                    listOf(raw(confidence = 0.99))
                },
            )

            val candidate = success(service.propose(validRequest())).candidates.single()

            assertPoint(case.expectedPoint, candidate.point)
            assertBounds(case.expectedBounds, candidate.bounds)
            store.close()
        }
    }

    @Test
    fun `all candidate sources map crop coordinates through rotation BitsUT`() {
        val store = registeredStore()
        val service = VisualProposalService(
            observations = store,
            provider = VisualCandidateProvider { _, _ ->
                VisualCandidateSource.entries.mapIndexed { index, source ->
                    RawVisualCandidate(
                        id = "123e4567-e89b-42d3-a456-42661417410${index + 1}",
                        source = source,
                        point = NormalizedPoint(0.5, 0.5),
                        bounds = NormalizedBounds(0.2, 0.25, 0.6, 0.75),
                        confidence = if (source == VisualCandidateSource.MANUAL) {
                            1.0
                        } else {
                            0.99 - index * 0.05
                        },
                    )
                }
            },
        )

        val result = service.propose(
            VisualProposalRequest(
                observationId = OBSERVATION_ID,
                expectedPackage = TARGET_PACKAGE,
                now = "2026-07-26T01:00:05Z",
            ),
        )

        val response = success(result)
        assertEquals(4, response.candidates.size)
        assertEquals(VisualCandidateSource.entries, response.candidates.map { it.source })
        response.candidates.forEach { candidate ->
            assertEquals(NormalizedPoint(0.5, 0.65), candidate.point)
            assertEquals(NormalizedBounds(0.3, 0.6, 0.7, 0.8), candidate.bounds)
            assertEquals(OBSERVATION_ID, candidate.observationId)
        }
        assertEquals(0, response.actionCommitCount)
        assertFalse(
            VisualProposalService::class.java.methods.any {
                it.name.contains("execute", ignoreCase = true)
            },
        )
        store.close()
    }

    @Test
    fun `expired or changed foreground package never calls provider BitsUT`() {
        listOf(
            VisualProposalRequest(
                OBSERVATION_ID,
                TARGET_PACKAGE,
                "2026-07-26T01:00:11Z",
            ) to VisualErrorCode.OBSERVATION_EXPIRED,
            VisualProposalRequest(
                OBSERVATION_ID,
                "com.example.changed",
                "2026-07-26T01:00:05Z",
            ) to VisualErrorCode.FOREGROUND_PACKAGE_CHANGED,
        ).forEach { (request, expectedCode) ->
            val store = registeredStore()
            var providerCalls = 0
            val service = VisualProposalService(
                observations = store,
                provider = VisualCandidateProvider { _, _ ->
                    providerCalls += 1
                    emptyList()
                },
            )

            assertFailure(service.propose(request), expectedCode)
            assertEquals(0, providerCalls)
            assertEquals(null, store.lookup(OBSERVATION_ID))
            store.close()
        }
    }

    @Test
    fun `low confidence and nearby top candidates fail closed and revoke bytes BitsUT`() {
        val cases = listOf(
            listOf(raw(id = CANDIDATE_ONE, confidence = 0.69)) to
                VisualErrorCode.CANDIDATE_LOW_CONFIDENCE,
            listOf(
                raw(id = CANDIDATE_ONE, confidence = 0.91),
                raw(
                    id = CANDIDATE_TWO,
                    point = NormalizedPoint(0.51, 0.51),
                    confidence = 0.9,
                ),
            ) to VisualErrorCode.CANDIDATE_AMBIGUOUS,
        )

        cases.forEach { (provided, expectedCode) ->
            val store = registeredStore()
            val service = VisualProposalService(
                observations = store,
                provider = VisualCandidateProvider { _, _ -> provided },
                minimumConfidence = 0.7,
                ambiguityDelta = 0.02,
                nearbyDistance = 0.05,
            )

            assertFailure(service.propose(validRequest()), expectedCode)
            assertEquals(null, store.lookup(OBSERVATION_ID))
            store.close()
        }
    }

    @Test
    fun `empty provider invalid coordinates and provider errors fail closed BitsUT`() {
        val providers = listOf(
            VisualCandidateProvider { _, _ -> emptyList() } to VisualErrorCode.CANDIDATE_NOT_FOUND,
            VisualCandidateProvider { _, _ ->
                listOf(raw(point = NormalizedPoint(1.1, 0.5)))
            } to VisualErrorCode.CANDIDATE_INVALID,
            VisualCandidateProvider { _, _ ->
                throw IllegalStateException("provider secret must not escape")
            } to VisualErrorCode.PROVIDER_FAILED,
        )

        providers.forEach { (provider, expectedCode) ->
            val store = registeredStore()
            val service = VisualProposalService(store, provider)

            val result = service.propose(validRequest())

            assertFailure(result, expectedCode)
            assertEquals(null, store.lookup(OBSERVATION_ID))
            store.close()
        }
    }

    private fun registeredStore(
        rotation: Int = 90,
        crop: PixelBounds = PixelBounds(200, 100, 1200, 900),
    ): VisualObservationStore {
        val store = VisualObservationStore(
            TrustedVisualImageVerifier { bytes, context ->
                bytes.isNotEmpty() && context.pngSha256.length == 64
            },
        )
        success(
            store.register(
                VisualObservationCapture(
                    id = OBSERVATION_ID,
                    foregroundPackage = TARGET_PACKAGE,
                    screen = VisualScreen(1000, 2000, rotation),
                    crop = crop,
                    capturedAt = "2026-07-26T01:00:00Z",
                    expiresAt = "2026-07-26T01:00:10Z",
                    pngBytes = byteArrayOf(
                        0x89.toByte(),
                        0x50,
                        0x4e,
                        0x47,
                        0x0d,
                        0x0a,
                        0x1a,
                        0x0a,
                    ),
                    hierarchy = emptyList(),
                ),
            ),
        )
        return store
    }

    private fun validRequest() = VisualProposalRequest(
        OBSERVATION_ID,
        TARGET_PACKAGE,
        "2026-07-26T01:00:05Z",
    )

    private fun raw(
        id: String = CANDIDATE_ONE,
        source: VisualCandidateSource = VisualCandidateSource.MODEL,
        point: NormalizedPoint = NormalizedPoint(0.5, 0.5),
        confidence: Double = 0.99,
    ) = RawVisualCandidate(
        id = id,
        source = source,
        point = point,
        bounds = NormalizedBounds(0.2, 0.25, 0.6, 0.75),
        confidence = confidence,
    )

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

    private fun assertPoint(expected: NormalizedPoint, actual: NormalizedPoint) {
        assertEquals(expected.x, actual.x, 0.000_000_001)
        assertEquals(expected.y, actual.y, 0.000_000_001)
    }

    private fun assertBounds(expected: NormalizedBounds, actual: NormalizedBounds) {
        assertEquals(expected.left, actual.left, 0.000_000_001)
        assertEquals(expected.top, actual.top, 0.000_000_001)
        assertEquals(expected.right, actual.right, 0.000_000_001)
        assertEquals(expected.bottom, actual.bottom, 0.000_000_001)
    }

    private companion object {
        const val OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174044"
        const val TARGET_PACKAGE = "com.example.notes"
        const val CANDIDATE_ONE = "123e4567-e89b-42d3-a456-426614174101"
        const val CANDIDATE_TWO = "123e4567-e89b-42d3-a456-426614174102"
    }
}
