package dev.aiauto.android.automation.recording.replay.visual

/**
 * 测试用途：验证 N45 自然坐标映射、视觉证据前置校验、类型化动作提交和只读后置验证。
 */

import java.time.Instant
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.automation.recording.NormalizedBounds as RecordedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint as RecordedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.VisualTarget
import dev.aiauto.android.observe.visual.NormalizedBounds
import dev.aiauto.android.observe.visual.NormalizedPoint
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.VisualCandidate
import dev.aiauto.android.observe.visual.VisualCandidateSource
import dev.aiauto.android.observe.visual.VisualObservation
import dev.aiauto.android.observe.visual.VisualPngMetadata
import dev.aiauto.android.observe.visual.VisualScreen
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitVisualReplayTest {
    @Test
    fun `maps natural coordinates across three resolutions and every rotation BitsUT`() {
        val resolutions = listOf(
            Triple(720, 1_600, 280),
            Triple(1_080, 2_400, 420),
            Triple(1_440, 3_200, 560),
        )
        val point = NormalizedPoint(x = 0.25, y = 0.75)

        resolutions.forEach { (width, height, density) ->
            listOf(0, 90, 180, 270).forEach { rotation ->
                val current = screen(
                    naturalWidth = width,
                    naturalHeight = height,
                    densityDpi = density,
                    rotation = rotation,
                )

                val mapped = VisualCoordinateTransformer.mapPoint(
                    observation = observation(),
                    current = current,
                    point = point,
                ).successValue()

                assertEquals(
                    "resolution=$width x $height rotation=$rotation",
                    expectedRotatedPoint(width, height, rotation, point),
                    mapped,
                )
            }
        }
    }

    @Test
    fun `inverts rotated crop then applies window offset and system bars BitsUT`() {
        val rotatedObservation = observation(
            screen = VisualScreen(width = 1_000, height = 2_000, rotation = 90),
            crop = PixelBounds(left = 200, top = 100, right = 1_200, bottom = 900),
        )
        // 该自然点对应旧裁剪平面中的 (450, 700)；crop 只校验来源，当前窗口按自然点映射。
        val point = NormalizedPoint(x = 0.70, y = 0.775)
        val current = screen(
            naturalWidth = 1_000,
            naturalHeight = 2_000,
            densityDpi = 400,
            rotation = 270,
            windowBounds = PixelBounds(100, 50, 1_900, 950),
            systemBars = ReplayInsets(left = 40, top = 20, right = 60, bottom = 30),
        )

        val mapped = VisualCoordinateTransformer.mapPoint(
            observation = rotatedObservation,
            current = current,
            point = point,
        ).successValue()

        assertEquals(ScreenPoint(x = 1_457, y = 325), mapped)
    }

    @Test
    fun `plans tap long click and relative swipe without semantic selectors BitsUT`() {
        val cases = listOf(
            step(action = tapAction()) to ExplicitVisualAction.Tap(ScreenPoint(540, 1_800)),
            step(action = longClickAction()) to ExplicitVisualAction.LongClick(
                point = ScreenPoint(540, 1_800),
                durationMs = 750,
            ),
            step(action = swipeAction()) to ExplicitVisualAction.Swipe(
                start = ScreenPoint(432, 1_680),
                end = ScreenPoint(648, 1_920),
                durationMs = 450,
            ),
        )

        cases.forEach { (step, expected) ->
            val harness = Harness()

            val result = harness.executor.replay(request(step))

            assertTrue(result is ExplicitVisualReplayResult.Success)
            assertEquals(expected, harness.actions.single())
            assertEquals(1, harness.verifyRequests.size)
            assertEquals(listOf(OBSERVATION_ID), harness.revoked)
        }
    }

    @Test
    fun `expired package drift low confidence ambiguity and secure window fail before commit BitsUT`() {
        val cases = listOf<Pair<ExplicitVisualReplayErrorCode, Harness.() -> Unit>>(
            ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED to {
                lease = lease(
                    observation = observation(expiresAt = "2026-07-26T00:00:00Z"),
                )
            },
            ExplicitVisualReplayErrorCode.FOREGROUND_PACKAGE_CHANGED to {
                currentScreen = screen(foregroundPackage = "com.example.changed")
            },
            ExplicitVisualReplayErrorCode.CANDIDATE_LOW_CONFIDENCE to {
                lease = lease(candidates = listOf(candidate(confidence = 0.69)))
            },
            ExplicitVisualReplayErrorCode.CANDIDATE_AMBIGUOUS to {
                lease = lease(
                    candidates = listOf(candidate(), candidate(id = CANDIDATE_ID_2)),
                )
            },
            ExplicitVisualReplayErrorCode.SECURE_WINDOW to {
                currentScreen = screen(secureWindow = true)
            },
        )

        cases.forEach { (expected, configure) ->
            val harness = Harness().apply(configure)

            val result = harness.executor.replay(request(step(action = tapAction())))

            assertFailure(result, expected, expectedCommitCount = 0)
            assertTrue(harness.actions.isEmpty())
            assertTrue(harness.verifyRequests.isEmpty())
            assertEquals(listOf(OBSERVATION_ID), harness.revoked)
        }
    }

    @Test
    fun `observation target and density drift fail before commit BitsUT`() {
        val cases = listOf<Pair<ExplicitVisualReplayErrorCode, VisualReplayRequest>>(
            ExplicitVisualReplayErrorCode.OBSERVATION_ID_MISMATCH to request(
                step(action = tapAction()).copy(
                    visualTarget = visualTarget(observationId = "different-observation"),
                ),
            ),
            ExplicitVisualReplayErrorCode.SCREEN_METADATA_MISMATCH to request(
                step(action = tapAction()),
                environment = environment(densityDpi = 320),
            ),
            ExplicitVisualReplayErrorCode.TARGET_MISMATCH to request(
                step(action = tapAction()).copy(
                    visualTarget = visualTarget(point = RecordedPoint(0.4, 0.5)),
                ),
            ),
        )

        cases.forEach { (expected, request) ->
            val harness = Harness()

            val result = harness.executor.replay(request)

            assertFailure(result, expected, expectedCommitCount = 0)
            assertTrue(harness.actions.isEmpty())
        }

        val currentDensityDrift = Harness().apply {
            currentScreen = screen(densityDpi = 320)
        }
        val result = currentDensityDrift.executor.replay(
            request(step(action = tapAction())),
        )
        assertFailure(
            result,
            ExplicitVisualReplayErrorCode.SCREEN_METADATA_MISMATCH,
            expectedCommitCount = 0,
        )
        assertTrue(currentDensityDrift.actions.isEmpty())
    }

    @Test
    fun `typed action rejection keeps commit count zero and revokes observation BitsUT`() {
        val harness = Harness().apply {
            actionResult = AccessibilityResult.Failure(
                code = AccessibilityErrorCode.GESTURE_FAILED,
                message = "rejected",
            )
        }

        val result = harness.executor.replay(request(step(action = tapAction())))

        assertFailure(result, ExplicitVisualReplayErrorCode.ACTION_FAILED, 0)
        assertEquals(1, harness.actions.size)
        assertTrue(harness.verifyRequests.isEmpty())
        assertEquals(listOf(OBSERVATION_ID), harness.revoked)
    }

    @Test
    fun `missing stale or rejected post verification fails after one commit without retry BitsUT`() {
        val cases = listOf<Harness.() -> Unit>(
            { verification = null },
            {
                verification = verification().copy(
                    sourceObservationId = "different-observation",
                )
            },
            { verification = verification().copy(stateChanged = false) },
            { verifierThrows = true },
        )

        cases.forEach { configure ->
            val harness = Harness().apply(configure)

            val result = harness.executor.replay(request(step(action = tapAction())))

            assertFailure(
                result,
                ExplicitVisualReplayErrorCode.POST_ACTION_VERIFICATION_FAILED,
                expectedCommitCount = 1,
            )
            assertEquals(1, harness.actions.size)
            assertEquals(1, harness.verifyRequests.size)
            assertEquals(listOf(OBSERVATION_ID), harness.revoked)
        }
    }

    private class Harness {
        var lease: VisualReplayLease? = lease()
        var currentScreen: VisualReplayScreen? = screen()
        var actionResult: AccessibilityResult<ActionExecution> = AccessibilityResult.Success(
            ActionExecution(route = ActionRoute.COORDINATE_GESTURE),
        )
        var verification: VisualReplayVerification? = verification()
        var verifierThrows = false
        val actions = mutableListOf<ExplicitVisualAction>()
        val verifyRequests = mutableListOf<VisualReplayVerificationRequest>()
        val revoked = mutableListOf<String>()

        val executor = ExplicitVisualReplayExecutor(
            observations = object : VisualReplayObservationPort {
                override fun acquire(observationId: String): VisualReplayLease? = lease

                override fun revoke(observationId: String) {
                    revoked += observationId
                }
            },
            screens = VisualReplayScreenReader { currentScreen },
            actions = VisualReplayActionExecutor { action ->
                actions += action
                actionResult
            },
            verifier = VisualReplayVerifier { request ->
                verifyRequests += request
                if (verifierThrows) error("verifier failed")
                verification
            },
            nowMs = { NOW_MS },
        )
    }

    private companion object {
        const val OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174045"
        const val CANDIDATE_ID = "123e4567-e89b-42d3-a456-426614174046"
        const val CANDIDATE_ID_2 = "123e4567-e89b-42d3-a456-426614174047"
        const val TARGET_PACKAGE = "com.example.fixture"
        val NOW_MS: Long = Instant.parse("2026-07-26T00:00:05Z").toEpochMilli()

        fun observation(
            screen: VisualScreen = VisualScreen(width = 1_080, height = 2_400, rotation = 0),
            crop: PixelBounds = PixelBounds(0, 0, 1_080, 2_400),
            expiresAt: String = "2026-07-26T00:00:10Z",
        ) = VisualObservation(
            id = OBSERVATION_ID,
            foregroundPackage = TARGET_PACKAGE,
            screen = screen,
            crop = crop,
            capturedAt = "2026-07-26T00:00:00Z",
            expiresAt = expiresAt,
            png = VisualPngMetadata(
                sizeBytes = 128,
                sha256 = "a".repeat(64),
            ),
            hierarchy = emptyList(),
        )

        fun candidate(
            id: String = CANDIDATE_ID,
            confidence: Double = 0.93,
        ) = VisualCandidate(
            id = id,
            observationId = OBSERVATION_ID,
            source = VisualCandidateSource.MANUAL,
            point = NormalizedPoint(0.5, 0.75),
            bounds = NormalizedBounds(0.4, 0.7, 0.6, 0.8),
            confidence = confidence,
        )

        fun lease(
            observation: VisualObservation = observation(),
            candidates: List<VisualCandidate> = listOf(candidate()),
        ) = VisualReplayLease(
            observation = observation,
            candidates = candidates,
            recordedDensityDpi = 420,
            secureWindow = false,
        )

        fun screen(
            naturalWidth: Int = 1_080,
            naturalHeight: Int = 2_400,
            densityDpi: Int = 420,
            rotation: Int = 0,
            windowBounds: PixelBounds = fullPlane(naturalWidth, naturalHeight, rotation),
            systemBars: ReplayInsets = ReplayInsets(),
            foregroundPackage: String = TARGET_PACKAGE,
            secureWindow: Boolean = false,
        ) = VisualReplayScreen(
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
            densityDpi = densityDpi,
            rotation = rotation,
            windowBounds = windowBounds,
            systemBars = systemBars,
            foregroundPackage = foregroundPackage,
            secureWindow = secureWindow,
            observedAtMs = NOW_MS - 1,
        )

        fun fullPlane(width: Int, height: Int, rotation: Int): PixelBounds =
            if (rotation == 90 || rotation == 270) {
                PixelBounds(0, 0, height, width)
            } else {
                PixelBounds(0, 0, width, height)
            }

        fun environment(densityDpi: Int = 420) = ScriptEnvironment(
            logicalWidth = 1_080,
            logicalHeight = 2_400,
            densityDpi = densityDpi,
            rotation = 0,
        )

        fun visualTarget(
            observationId: String = OBSERVATION_ID,
            point: RecordedPoint = RecordedPoint(0.5, 0.75),
        ) = VisualTarget(
            normalizedPoint = point,
            normalizedBounds = RecordedBounds(0.4, 0.7, 0.6, 0.8),
            confidence = 0.93,
            source = RecordingProvenance.MANUAL,
            observationId = observationId,
            imageSha256 = "a".repeat(64),
        )

        fun step(action: RecordedAction) = RecordedStep(
            id = "visual-step",
            provenance = RecordingProvenance.VISUAL,
            action = action,
            visualTarget = visualTarget(),
        )

        fun request(
            step: RecordedStep,
            environment: ScriptEnvironment = environment(),
        ) = VisualReplayRequest(
            step = step,
            environment = environment,
            targetPackages = setOf(TARGET_PACKAGE),
        )

        fun tapAction() = RecordedAction(
            type = "ui.tap",
            params = buildJsonObject {
                put("x", JsonPrimitive(540))
                put("y", JsonPrimitive(1_800))
            },
        )

        fun longClickAction() = RecordedAction(
            type = "ui.longClick",
            params = buildJsonObject {
                put("target", JsonObject(emptyMap()))
                put("durationMs", JsonPrimitive(750))
            },
        )

        fun swipeAction() = RecordedAction(
            type = "ui.swipe",
            params = buildJsonObject {
                put("start", pointJson(432, 1_680))
                put("end", pointJson(648, 1_920))
                put("durationMs", JsonPrimitive(450))
            },
        )

        fun pointJson(x: Int, y: Int) = buildJsonObject {
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
        }

        fun verification() = VisualReplayVerification(
            sourceObservationId = OBSERVATION_ID,
            foregroundPackage = TARGET_PACKAGE,
            naturalWidth = 1_080,
            naturalHeight = 2_400,
            densityDpi = 420,
            rotation = 0,
            secureWindow = false,
            observedAtMs = NOW_MS + 1,
            stateChanged = true,
        )

        fun expectedRotatedPoint(
            width: Int,
            height: Int,
            rotation: Int,
            point: NormalizedPoint,
        ): ScreenPoint = when (rotation) {
            90 -> ScreenPoint(
                x = ((1.0 - point.y) * height).toInt(),
                y = (point.x * width).toInt(),
            )

            180 -> ScreenPoint(
                x = ((1.0 - point.x) * width).toInt(),
                y = ((1.0 - point.y) * height).toInt(),
            )

            270 -> ScreenPoint(
                x = (point.y * height).toInt(),
                y = ((1.0 - point.x) * width).toInt(),
            )

            else -> ScreenPoint(
                x = (point.x * width).toInt(),
                y = (point.y * height).toInt(),
            )
        }

        fun <T> AccessibilityResult<T>.successValue(): T {
            assertTrue(this is AccessibilityResult.Success)
            return (this as AccessibilityResult.Success).value
        }

        fun assertFailure(
            result: ExplicitVisualReplayResult,
            expectedCode: ExplicitVisualReplayErrorCode,
            expectedCommitCount: Int,
        ) {
            assertFalse(result is ExplicitVisualReplayResult.Success)
            result as ExplicitVisualReplayResult.Failure
            assertEquals(expectedCode, result.code)
            assertEquals(expectedCommitCount, result.actionCommitCount)
        }
    }
}
