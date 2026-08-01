package dev.aiauto.android.automation.recording.replay.visual

/**
 * 测试用途：验证历史视觉步骤只有在当前运行显式 fresh rebind 后才能提交一次类型化动作。
 */

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
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
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualReplayRebindTest {
    @Test
    fun `persisted observation cannot authorize a later run without provider BitsUT`() {
        val harness = Harness()

        harness.executor.beginRun(runRequest())
        val result = harness.executor.replay(replayRequest())
        harness.executor.endRun()

        assertFailure(result, ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED, 0)
        assertTrue(harness.actions.isEmpty())
        assertEquals(0, harness.authorization.acquireCalls)
    }

    @Test
    fun `fresh authorized step rebind replaces historical metadata in memory BitsUT`() {
        val harness = Harness()
        harness.offerAuthorization()

        harness.executor.beginRun(runRequest())
        val result = harness.executor.replay(replayRequest())
        harness.executor.endRun()

        assertTrue(result is ExplicitVisualReplayResult.Success)
        assertEquals(1, harness.actions.size)
        assertEquals(1, harness.authorization.acquireCalls)
        assertEquals(1, harness.authorization.verifyCalls)
        assertTrue(harness.authorization.closed)
        assertTrue(harness.stepLease.closed)
        assertEquals(
            HISTORICAL_OBSERVATION_ID,
            harness.authorization.lastRequest?.historicalTarget?.observationId,
        )
        assertNotEquals(HISTORICAL_OBSERVATION_ID, harness.stepLease.reboundTarget.observationId)
        assertEquals(FRESH_OBSERVATION_ID, harness.stepLease.reboundTarget.observationId)
        assertEquals(FRESH_IMAGE_SHA256, harness.stepLease.reboundTarget.imageSha256)
        assertEquals(RecordedPoint(0.6, 0.4), harness.stepLease.reboundTarget.normalizedPoint)
    }

    @Test
    fun `serial package screen hash candidate and expiry drift fail before action BitsUT`() {
        val cases = listOf<Pair<ExplicitVisualReplayErrorCode, Harness.() -> Unit>>(
            ExplicitVisualReplayErrorCode.DEVICE_SERIAL_CHANGED to {
                currentSerial = "emulator-5584"
            },
            ExplicitVisualReplayErrorCode.FOREGROUND_PACKAGE_CHANGED to {
                stepLease = stepLease(targetPackage = "com.example.changed")
            },
            ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED to {
                stepLease = stepLease(runId = "123e4567-e89b-42d3-a456-426614174099")
            },
            ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED to {
                stepLease = stepLease(scriptRevision = 8)
            },
            ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED to {
                stepLease = stepLease(stepId = "different-step")
            },
            ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED to {
                stepLease = stepLease(authorizedAtMs = RUN_AUTHORIZED_AT_MS - 1)
            },
            ExplicitVisualReplayErrorCode.SCREEN_METADATA_MISMATCH to {
                stepLease = stepLease(
                    screenBefore = screen(naturalWidth = 720),
                )
            },
            ExplicitVisualReplayErrorCode.TARGET_MISMATCH to {
                stepLease = stepLease(
                    reboundTarget = reboundTarget(imageSha256 = "c".repeat(64)),
                )
            },
            ExplicitVisualReplayErrorCode.TARGET_MISMATCH to {
                stepLease = stepLease(
                    reboundTarget = reboundTarget(
                        observationId = HISTORICAL_OBSERVATION_ID,
                    ),
                )
            },
            ExplicitVisualReplayErrorCode.TARGET_MISMATCH to {
                stepLease = stepLease(
                    reboundTarget = reboundTarget(
                        normalizedPoint = RecordedPoint(0.2, 0.2),
                    ),
                )
            },
            ExplicitVisualReplayErrorCode.TARGET_MISMATCH to {
                stepLease = stepLease(
                    reboundTarget = reboundTarget(confidence = 0.95),
                )
            },
            ExplicitVisualReplayErrorCode.TARGET_MISMATCH to {
                stepLease = stepLease(
                    visual = visualLease(
                        candidates = listOf(
                            candidate(point = NormalizedPoint(0.8, 0.8)),
                        ),
                    ),
                )
            },
            ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED to {
                stepLease = stepLease(
                    visual = visualLease(
                        observation = observation(capturedAtMs = NOW_MS + 1),
                    ),
                )
            },
            ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED to {
                stepLease = stepLease(expiresAtMs = NOW_MS)
            },
        )

        cases.forEach { (code, configure) ->
            val harness = Harness().apply(configure)
            harness.authorization.stepLease = harness.stepLease
            harness.offerAuthorization()

            harness.executor.beginRun(runRequest())
            val result = harness.executor.replay(replayRequest())
            harness.executor.endRun()

            assertFailure(result, code, 0)
            assertTrue("drift=$code", harness.actions.isEmpty())
            assertTrue(harness.stepLease.closed)
        }
    }

    @Test
    fun `same run step lease is consumed once and never replayed BitsUT`() {
        val harness = Harness()
        harness.offerAuthorization()
        harness.executor.beginRun(runRequest())

        val first = harness.executor.replay(replayRequest())
        val second = harness.executor.replay(replayRequest())
        harness.executor.endRun()

        assertTrue(first is ExplicitVisualReplayResult.Success)
        assertFailure(second, ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED, 0)
        assertEquals(1, harness.actions.size)
        assertEquals(1, harness.authorization.acquireCalls)
    }

    @Test
    fun `run authorization expiry after begin fails before step acquisition BitsUT`() {
        val harness = Harness()
        harness.offerAuthorization()
        harness.executor.beginRun(runRequest())
        harness.nowMs = NOW_MS + 30_001

        val result = harness.executor.replay(replayRequest())
        harness.executor.endRun()

        assertFailure(result, ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED, 0)
        assertEquals(0, harness.authorization.acquireCalls)
        assertTrue(harness.actions.isEmpty())
        assertTrue(harness.authorization.closed)
    }

    @Test
    fun `action exception reports unknown commit and cannot be replayed BitsUT`() {
        val harness = Harness().apply { actionThrows = true }
        harness.offerAuthorization()
        harness.executor.beginRun(runRequest())

        val first = harness.executor.replay(replayRequest())
        val second = harness.executor.replay(replayRequest())
        harness.executor.endRun()

        val unknown = first as ExplicitVisualReplayResult.Failure
        assertEquals(ExplicitVisualReplayErrorCode.ACTION_COMMIT_UNKNOWN, unknown.code)
        assertEquals(1, unknown.actionCommitCount)
        assertFailure(second, ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED, 0)
        assertEquals(1, harness.actions.size)
        assertEquals(0, harness.authorization.verifyCalls)
    }

    @Test
    fun `lease cleanup exception cannot replace a successful replay result BitsUT`() {
        val harness = Harness().apply {
            stepLease = stepLease(onClose = { error("cleanup failed") })
        }
        harness.offerAuthorization()
        harness.executor.beginRun(runRequest())

        val result = harness.executor.replay(replayRequest())
        harness.executor.endRun()

        assertTrue(result is ExplicitVisualReplayResult.Success)
        assertEquals(1, harness.actions.size)
        assertTrue(harness.stepLease.closed)
    }

    @Test
    fun `post action observation must be newer stable and bound to the same serial BitsUT`() {
        val cases = listOf<VisualReplayVerification?>(
            verification(observedAtMs = SCREEN_OBSERVED_AT_MS),
            verification(observedAtMs = NOW_MS + 1),
            verification(stateChanged = false),
            verification(deviceSerial = "emulator-5584"),
            verification(windowBounds = PixelBounds(0, 0, 720, 1_600)),
            null,
        )

        cases.forEach { post ->
            val harness = Harness().apply {
                authorization.verification = post
            }
            harness.offerAuthorization()
            harness.executor.beginRun(runRequest())

            val result = harness.executor.replay(replayRequest())
            harness.executor.endRun()

            assertFailure(
                result,
                ExplicitVisualReplayErrorCode.POST_ACTION_VERIFICATION_FAILED,
                1,
            )
            assertEquals(1, harness.actions.size)
            assertEquals(1, harness.authorization.verifyCalls)
        }
    }

    private class Harness {
        var nowMs = NOW_MS
        val registry = VisualReplayAuthorizationRegistry(nowMs = { nowMs })
        var stepLease = stepLease()
        var currentSerial = DEVICE_SERIAL
        val authorization = FakeRunAuthorization(
            stepLease = stepLease,
            currentSerial = { currentSerial },
        )
        val actions = mutableListOf<ExplicitVisualAction>()
        var actionThrows = false

        val executor = ExplicitVisualReplayExecutor(
            authorizations = registry,
            actions = BoundVisualReplayActionExecutor { action, packageName ->
                actions += action
                assertEquals(TARGET_PACKAGE, packageName)
                if (actionThrows) error("dispatch status lost")
                AccessibilityResult.Success(
                    ActionExecution(route = ActionRoute.COORDINATE_GESTURE),
                )
            },
            nowMs = { nowMs },
        )

        fun offerAuthorization() {
            authorization.stepLease = stepLease
            assertTrue(registry.replace(authorizationRequest(), authorization))
        }
    }

    private class FakeRunAuthorization(
        override val binding: VisualReplayRunBinding = runBinding(),
        var stepLease: AuthorizedVisualReplayStepLease,
        private val currentSerial: () -> String,
    ) : VisualReplayRunAuthorization {
        var acquireCalls = 0
        var verifyCalls = 0
        var closed = false
        var lastRequest: VisualReplayRebindRequest? = null
        var verification: VisualReplayVerification? = verification()

        override fun currentDeviceSerial(): String? = currentSerial()

        override fun acquire(request: VisualReplayRebindRequest): AuthorizedVisualReplayStepLease? {
            acquireCalls += 1
            lastRequest = request
            return stepLease
        }

        override fun verifyAfter(
            lease: AuthorizedVisualReplayStepLease,
            request: VisualReplayVerificationRequest,
        ): VisualReplayVerification? {
            verifyCalls += 1
            return verification
        }

        override fun close() {
            closed = true
            stepLease.close()
        }
    }

    private companion object {
        const val RUN_ID = "123e4567-e89b-42d3-a456-426614174040"
        const val HISTORICAL_OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174041"
        const val FRESH_OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174042"
        const val CANDIDATE_ID = "123e4567-e89b-42d3-a456-426614174043"
        const val SCRIPT_ID = "visual-script"
        const val STEP_ID = "visual-step"
        const val TARGET_PACKAGE = "com.example.fixture"
        const val DEVICE_SERIAL = "emulator-5554"
        val FRESH_IMAGE_SHA256 = "b".repeat(64)
        val NOW_MS = Instant.parse("2026-08-01T12:00:05Z").toEpochMilli()
        val RUN_REQUESTED_AT_MS = NOW_MS - 3_000
        val RUN_AUTHORIZED_AT_MS = NOW_MS - 1_200
        val STEP_AUTHORIZED_AT_MS = NOW_MS - 1_000
        val SCREEN_OBSERVED_AT_MS = NOW_MS - 500

        fun authorizationRequest() = VisualReplayAuthorizationRequest(
            runId = RUN_ID,
            scriptId = SCRIPT_ID,
            scriptRevision = 7,
            requestedAtMs = RUN_REQUESTED_AT_MS,
            visualStepIds = setOf(STEP_ID),
        )

        fun runBinding() = VisualReplayRunBinding(
            runId = RUN_ID,
            scriptId = SCRIPT_ID,
            scriptRevision = 7,
            deviceSerial = DEVICE_SERIAL,
            authorizedAtMs = RUN_AUTHORIZED_AT_MS,
            expiresAtMs = NOW_MS + 30_000,
            visualStepIds = setOf(STEP_ID),
        )

        fun runRequest() = VisualReplayRunRequest(
            runId = RUN_ID,
            scriptId = SCRIPT_ID,
            scriptRevision = 7,
            startedAtMs = NOW_MS - 1_500,
            visualStepIds = setOf(STEP_ID),
        )

        fun replayRequest() = VisualReplayRequest(
            step = visualStep(),
            environment = environment(),
            targetPackages = setOf(TARGET_PACKAGE),
            run = runRequest(),
        )

        fun visualStep() = RecordedStep(
            id = STEP_ID,
            provenance = RecordingProvenance.VISUAL,
            action = RecordedAction(
                type = "ui.tap",
                params = kotlinx.serialization.json.buildJsonObject {
                    put("x", kotlinx.serialization.json.JsonPrimitive(540))
                    put("y", kotlinx.serialization.json.JsonPrimitive(1_200))
                },
            ),
            visualTarget = historicalTarget(),
        )

        fun historicalTarget() = VisualTarget(
            normalizedPoint = RecordedPoint(0.5, 0.5),
            normalizedBounds = RecordedBounds(0.4, 0.4, 0.6, 0.6),
            confidence = 0.95,
            source = RecordingProvenance.VISUAL,
            observationId = HISTORICAL_OBSERVATION_ID,
            imageSha256 = "a".repeat(64),
        )

        fun reboundTarget(
            normalizedPoint: RecordedPoint = RecordedPoint(0.6, 0.4),
            imageSha256: String = FRESH_IMAGE_SHA256,
            observationId: String = FRESH_OBSERVATION_ID,
            confidence: Double = 0.94,
        ) = VisualTarget(
            normalizedPoint = normalizedPoint,
            normalizedBounds = RecordedBounds(0.5, 0.3, 0.7, 0.5),
            confidence = confidence,
            source = RecordingProvenance.VISUAL,
            observationId = observationId,
            imageSha256 = imageSha256,
        )

        fun observation(
            capturedAtMs: Long = STEP_AUTHORIZED_AT_MS,
        ) = VisualObservation(
            id = FRESH_OBSERVATION_ID,
            foregroundPackage = TARGET_PACKAGE,
            screen = VisualScreen(1_080, 2_400, 0),
            crop = PixelBounds(0, 0, 1_080, 2_400),
            capturedAt = Instant.ofEpochMilli(capturedAtMs).toString(),
            expiresAt = Instant.ofEpochMilli(NOW_MS + 10_000).toString(),
            png = VisualPngMetadata(
                sizeBytes = 128,
                sha256 = FRESH_IMAGE_SHA256,
            ),
            hierarchy = emptyList(),
        )

        fun candidate(
            point: NormalizedPoint = NormalizedPoint(0.6, 0.4),
        ) = VisualCandidate(
            id = CANDIDATE_ID,
            observationId = FRESH_OBSERVATION_ID,
            source = VisualCandidateSource.MANUAL,
            point = point,
            bounds = NormalizedBounds(0.5, 0.3, 0.7, 0.5),
            confidence = 0.94,
        )

        fun visualLease(
            observation: VisualObservation = observation(),
            candidates: List<VisualCandidate> = listOf(candidate()),
        ) = VisualReplayLease(
            observation = observation,
            candidates = candidates,
            recordedDensityDpi = 420,
            secureWindow = false,
        )

        fun stepLease(
            runId: String = RUN_ID,
            scriptRevision: Long = 7,
            stepId: String = STEP_ID,
            targetPackage: String = TARGET_PACKAGE,
            reboundTarget: VisualTarget = reboundTarget(),
            screenBefore: VisualReplayScreen = screen(),
            expiresAtMs: Long = NOW_MS + 5_000,
            visual: VisualReplayLease = visualLease(),
            onClose: () -> Unit = {},
            authorizedAtMs: Long = STEP_AUTHORIZED_AT_MS,
        ) = AuthorizedVisualReplayStepLease(
            runId = runId,
            scriptId = SCRIPT_ID,
            scriptRevision = scriptRevision,
            stepId = stepId,
            deviceSerial = DEVICE_SERIAL,
            targetPackage = targetPackage,
            authorizedAtMs = authorizedAtMs,
            expiresAtMs = expiresAtMs,
            visual = visual,
            reboundTarget = reboundTarget,
            screenBefore = screenBefore,
            onClose = onClose,
        )

        fun environment() = ScriptEnvironment(
            logicalWidth = 1_080,
            logicalHeight = 2_400,
            densityDpi = 420,
            rotation = 0,
        )

        fun screen(naturalWidth: Int = 1_080) = VisualReplayScreen(
            naturalWidth = naturalWidth,
            naturalHeight = 2_400,
            densityDpi = 420,
            rotation = 0,
            windowBounds = PixelBounds(0, 0, naturalWidth, 2_400),
            systemBars = ReplayInsets(),
            foregroundPackage = TARGET_PACKAGE,
            secureWindow = false,
            observedAtMs = SCREEN_OBSERVED_AT_MS,
        )

        fun verification(
            observedAtMs: Long = NOW_MS,
            stateChanged: Boolean = true,
            deviceSerial: String = DEVICE_SERIAL,
            windowBounds: PixelBounds = screen().windowBounds,
        ) = VisualReplayVerification(
            sourceObservationId = FRESH_OBSERVATION_ID,
            deviceSerial = deviceSerial,
            foregroundPackage = TARGET_PACKAGE,
            naturalWidth = 1_080,
            naturalHeight = 2_400,
            densityDpi = 420,
            rotation = 0,
            secureWindow = false,
            observedAtMs = observedAtMs,
            stateChanged = stateChanged,
            windowBounds = windowBounds,
            systemBars = screen().systemBars,
        )

        fun assertFailure(
            result: ExplicitVisualReplayResult,
            code: ExplicitVisualReplayErrorCode,
            commits: Int?,
        ) {
            assertFalse(result is ExplicitVisualReplayResult.Success)
            val failure = result as ExplicitVisualReplayResult.Failure
            assertEquals(code, failure.code)
            assertEquals(commits, failure.actionCommitCount)
        }
    }
}
