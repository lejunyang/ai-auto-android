package dev.aiauto.android.automation.recording.replay.visual

/**
 * 功能用途：在 debug instrumentation 内构造短生命周期 N45 observation，并通过
 * production executor 与真实类型化 Accessibility router 执行本地无敏感 fixture。
 */

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager

import dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService
import dev.aiauto.android.accessibility.ScreenshotTestActivity
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
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
import dev.aiauto.testcontrol.core.N31EmulatorGate
import dev.aiauto.testcontrol.core.TestIdentity
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class N45VisualTestAction {
    TAP,
    LONG_CLICK,
    SWIPE,
}

enum class N45VisualEvidenceMutation(
    val expectedCode: ExplicitVisualReplayErrorCode,
) {
    LOW_CONFIDENCE(ExplicitVisualReplayErrorCode.CANDIDATE_LOW_CONFIDENCE),
    MULTI_CANDIDATE(ExplicitVisualReplayErrorCode.CANDIDATE_AMBIGUOUS),
    EXPIRED(ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED),
    PACKAGE_DRIFT(ExplicitVisualReplayErrorCode.FOREGROUND_PACKAGE_CHANGED),
    SCREEN_DRIFT(ExplicitVisualReplayErrorCode.SCREEN_METADATA_MISMATCH),
    SECURE(ExplicitVisualReplayErrorCode.SECURE_WINDOW),
    POST_FAIL(ExplicitVisualReplayErrorCode.POST_ACTION_VERIFICATION_FAILED),
}

data class N45VisualHarnessResult(
    val succeeded: Boolean,
    val errorCode: ExplicitVisualReplayErrorCode?,
    val actionCommitCount: Int,
    val actionAttempts: Int,
)

class N45VisualDeviceHarness(
    context: Context,
    private val expectedIdentity: TestIdentity,
    private val currentIdentity: () -> TestIdentity,
    private val arguments: Bundle,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)
    private var closed = false

    init {
        N31EmulatorGate.validateTrustedIdentity(expectedIdentity)
        assertIdentityUnchanged(currentIdentity())
        check(
            Settings.Global.getString(
                applicationContext.contentResolver,
                SNAPSHOT_MARKER_KEY,
            ) == REQUIRED_SNAPSHOT_MARKER,
        ) {
            "N45 requires the verified N31 clean snapshot marker"
        }
    }

    fun execute(
        action: N45VisualTestAction,
        mutation: N45VisualEvidenceMutation? = null,
    ): N45VisualHarnessResult {
        check(!closed) { "The N45 visual harness is closed" }
        assertIdentityUnchanged(currentIdentity())
        val service = checkNotNull(ScreenshotTestAccessibilityService.connectedService) {
            "The N45 debug accessibility service is not connected"
        }
        val expectedStatus = "${action.name}_PASS"
        val source = source(action, mutation)
        val observation = source.observation
        var revoked = false
        var actionAttempts = 0
        val executor = ExplicitVisualReplayExecutor(
            observations = object : VisualReplayObservationPort {
                override fun acquire(observationId: String): VisualReplayLease? {
                    if (revoked || observationId != observation.id) return null
                    return VisualReplayLease(
                        observation = observation,
                        candidates = source.candidates,
                        recordedDensityDpi = source.screen.densityDpi,
                        secureWindow = mutation == N45VisualEvidenceMutation.SECURE,
                    )
                }

                override fun revoke(observationId: String) {
                    if (observationId == observation.id) revoked = true
                }
            },
            screens = VisualReplayScreenReader {
                source.screen.copy(
                    foregroundPackage = if (
                        mutation == N45VisualEvidenceMutation.PACKAGE_DRIFT
                    ) {
                        "dev.aiauto.changed"
                    } else {
                        source.screen.foregroundPackage
                    },
                    secureWindow = mutation == N45VisualEvidenceMutation.SECURE,
                )
            },
            actions = VisualReplayActionExecutor { visual ->
                assertIdentityUnchanged(currentIdentity())
                actionAttempts += 1
                service.executeN45VisualAction(
                    visual,
                    applicationContext.packageName,
                ).also { result ->
                    if (result is AccessibilityResult.Success) {
                        SystemClock.sleep(visual.settleDelayMs())
                    }
                }
            },
            verifier = VisualReplayVerifier { request ->
                assertIdentityUnchanged(currentIdentity())
                val stateChanged = if (
                    mutation == N45VisualEvidenceMutation.POST_FAIL
                ) {
                    false
                } else {
                    awaitDescription(service, expectedStatus)
                }
                VisualReplayVerification(
                    sourceObservationId = request.sourceObservationId,
                    foregroundPackage = applicationContext.packageName,
                    naturalWidth = source.screen.naturalWidth,
                    naturalHeight = source.screen.naturalHeight,
                    densityDpi = source.screen.densityDpi,
                    rotation = source.screen.rotation,
                    secureWindow = false,
                    observedAtMs = maxOf(
                        System.currentTimeMillis(),
                        request.screenBefore.observedAtMs + 1,
                    ),
                    stateChanged = stateChanged,
                )
            },
            nowMs = System::currentTimeMillis,
        )
        val result = executor.replay(source.request)
        check(revoked) { "N45 observation was not revoked" }
        val expectedAttempts = if (
            mutation == null || mutation == N45VisualEvidenceMutation.POST_FAIL
        ) {
            1
        } else {
            0
        }
        check(actionAttempts == expectedAttempts) {
            "N45 action attempts violated the single-commit contract"
        }
        return when (result) {
            is ExplicitVisualReplayResult.Success -> N45VisualHarnessResult(
                succeeded = true,
                errorCode = null,
                actionCommitCount = result.actionCommitCount,
                actionAttempts = actionAttempts,
            )
            is ExplicitVisualReplayResult.Failure -> N45VisualHarnessResult(
                succeeded = false,
                errorCode = result.code,
                actionCommitCount = result.actionCommitCount,
                actionAttempts = actionAttempts,
            )
        }
    }

    fun assertIdentityUnchanged(actual: TestIdentity) {
        N31EmulatorGate.validateUnchanged(expectedIdentity, actual)
    }

    override fun close() {
        closed = true
    }

    private fun source(
        action: N45VisualTestAction,
        mutation: N45VisualEvidenceMutation?,
    ): Source {
        val screen = currentScreen()
        val service = checkNotNull(ScreenshotTestAccessibilityService.connectedService)
        val snapshot = when (val result = service.snapshot(applicationContext.packageName)) {
            is AccessibilityResult.Failure -> error(result.error.message)
            is AccessibilityResult.Success -> result.value
        }
        val description = when (action) {
            N45VisualTestAction.TAP -> ScreenshotTestActivity.VISUAL_TAP_TARGET_DESCRIPTION
            N45VisualTestAction.LONG_CLICK ->
                ScreenshotTestActivity.VISUAL_LONG_CLICK_TARGET_DESCRIPTION
            N45VisualTestAction.SWIPE ->
                ScreenshotTestActivity.VISUAL_SWIPE_TARGET_DESCRIPTION
        }
        val target = checkNotNull(snapshot.findDescription(description)) {
            "The N45 fixture target is missing"
        }
        val plane = if (screen.rotation == 90) {
            PixelBounds(0, 0, screen.naturalHeight, screen.naturalWidth)
        } else {
            PixelBounds(0, 0, screen.naturalWidth, screen.naturalHeight)
        }
        val point = target.bounds.centerNatural(screen)
        val bounds = target.bounds.naturalBounds(screen)
        val now = Instant.now()
        val observationId = UUID.randomUUID().toString()
        val pngHash = sha256(
            "n45:${expectedIdentity.avdFingerprint()}:${screen.rotation}".toByteArray(),
        )
        val observation = VisualObservation(
            id = observationId,
            foregroundPackage = applicationContext.packageName,
            screen = VisualScreen(
                width = screen.naturalWidth,
                height = screen.naturalHeight,
                rotation = screen.rotation,
            ),
            crop = plane,
            capturedAt = now.minusMillis(1).toString(),
            expiresAt = if (mutation == N45VisualEvidenceMutation.EXPIRED) {
                now.minusMillis(1).toString()
            } else {
                now.plusSeconds(30).toString()
            },
            png = VisualPngMetadata(
                sizeBytes = 64,
                sha256 = pngHash,
            ),
            hierarchy = emptyList(),
        )
        val confidence = if (
            mutation == N45VisualEvidenceMutation.LOW_CONFIDENCE
        ) {
            0.69
        } else {
            0.99
        }
        val candidate = VisualCandidate(
            id = UUID.randomUUID().toString(),
            observationId = observationId,
            source = VisualCandidateSource.MANUAL,
            point = point,
            bounds = bounds,
            confidence = confidence,
        )
        val candidates = if (mutation == N45VisualEvidenceMutation.MULTI_CANDIDATE) {
            listOf(
                candidate,
                candidate.copy(id = UUID.randomUUID().toString()),
            )
        } else {
            listOf(candidate)
        }
        val recordedAction = when (action) {
            N45VisualTestAction.TAP -> RecordedAction(
                "ui.tap",
                buildJsonObject {
                    put("x", JsonPrimitive((point.x * screen.naturalWidth).toInt()))
                    put("y", JsonPrimitive((point.y * screen.naturalHeight).toInt()))
                },
            )
            N45VisualTestAction.LONG_CLICK -> RecordedAction(
                "ui.longClick",
                buildJsonObject {
                    put("target", JsonObject(emptyMap()))
                    put("durationMs", JsonPrimitive(LONG_CLICK_DURATION_MS))
                },
            )
            N45VisualTestAction.SWIPE -> {
                val boundsWidth = bounds.right - bounds.left
                val boundsHeight = bounds.bottom - bounds.top
                val (startPoint, endPoint) = if (screen.rotation == 90) {
                    Pair(
                        NormalizedPoint(
                            bounds.left + boundsWidth * (1.0 - SWIPE_EDGE_FRACTION),
                            point.y,
                        ),
                        NormalizedPoint(
                            bounds.left + boundsWidth * SWIPE_EDGE_FRACTION,
                            point.y,
                        ),
                    )
                } else {
                    Pair(
                        NormalizedPoint(
                            point.x,
                            bounds.top + boundsHeight * (1.0 - SWIPE_EDGE_FRACTION),
                        ),
                        NormalizedPoint(
                            point.x,
                            bounds.top + boundsHeight * SWIPE_EDGE_FRACTION,
                        ),
                    )
                }
                RecordedAction(
                    "ui.swipe",
                    buildJsonObject {
                        put(
                            "start",
                            pointJson(
                                (startPoint.x * screen.naturalWidth).toInt(),
                                (startPoint.y * screen.naturalHeight).toInt(),
                            ),
                        )
                        put(
                            "end",
                            pointJson(
                                (endPoint.x * screen.naturalWidth).toInt(),
                                (endPoint.y * screen.naturalHeight).toInt(),
                            ),
                        )
                        put("durationMs", JsonPrimitive(SWIPE_DURATION_MS))
                    },
                )
            }
        }
        val request = VisualReplayRequest(
            step = RecordedStep(
                id = UUID.randomUUID().toString(),
                provenance = RecordingProvenance.VISUAL,
                action = recordedAction,
                visualTarget = VisualTarget(
                    normalizedPoint = RecordedPoint(point.x, point.y),
                    normalizedBounds = RecordedBounds(
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                    ),
                    confidence = confidence,
                    source = RecordingProvenance.VISUAL,
                    observationId = observationId,
                    imageSha256 = pngHash,
                ),
            ),
            environment = ScriptEnvironment(
                apiLevel = android.os.Build.VERSION.SDK_INT,
                logicalWidth = if (
                    mutation == N45VisualEvidenceMutation.SCREEN_DRIFT
                ) {
                    screen.naturalWidth + 1
                } else {
                    screen.naturalWidth
                },
                logicalHeight = screen.naturalHeight,
                densityDpi = screen.densityDpi,
                rotation = screen.rotation,
            ),
            targetPackages = setOf(applicationContext.packageName),
        )
        return Source(observation, candidates, screen, request)
    }

    private fun currentScreen(): VisualReplayScreen {
        val resolution = checkNotNull(arguments.getString(ARGUMENT_RESOLUTION))
        val expectedRotation = checkNotNull(arguments.getString(ARGUMENT_ROTATION)).toInt()
        val natural = resolution.split('x').map(String::toInt)
        val metrics = windowManager.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        @Suppress("DEPRECATION")
        val actualRotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        check(actualRotation == expectedRotation) {
            "N45 fixture rotation drifted from the matrix argument"
        }
        val expectedWidth = if (actualRotation == 90) natural[1] else natural[0]
        val expectedHeight = if (actualRotation == 90) natural[0] else natural[1]
        check(
            metrics.bounds.width() == expectedWidth &&
                metrics.bounds.height() == expectedHeight,
        ) {
            "N45 fixture screen bounds drifted from the matrix argument"
        }
        return VisualReplayScreen(
            naturalWidth = natural[0],
            naturalHeight = natural[1],
            densityDpi = applicationContext.resources.configuration.densityDpi,
            rotation = actualRotation,
            windowBounds = PixelBounds(
                metrics.bounds.left,
                metrics.bounds.top,
                metrics.bounds.right,
                metrics.bounds.bottom,
            ),
            systemBars = ReplayInsets(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
            ),
            foregroundPackage = applicationContext.packageName,
            secureWindow = false,
            observedAtMs = System.currentTimeMillis() - 1,
        )
    }

    private data class Source(
        val observation: VisualObservation,
        val candidates: List<VisualCandidate>,
        val screen: VisualReplayScreen,
        val request: VisualReplayRequest,
    )

    private companion object {
        const val SNAPSHOT_MARKER_KEY = "ai_auto_snapshot_marker"
        const val REQUIRED_SNAPSHOT_MARKER = "clean"
        const val ARGUMENT_RESOLUTION = "n45MatrixResolution"
        const val ARGUMENT_ROTATION = "n45MatrixRotation"
        const val TAP_DURATION_MS = 100L
        const val LONG_CLICK_DURATION_MS = 750L
        const val SWIPE_DURATION_MS = 450L
        const val SWIPE_EDGE_FRACTION = 0.2
        const val POST_ACTION_TIMEOUT_MS = 5_000L
        const val POST_ACTION_POLL_MS = 50L
        const val POST_ACTION_SETTLE_MS = 250L

        fun pointJson(x: Int, y: Int) = buildJsonObject {
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
        }

        fun UiNodeSnapshot.findDescription(value: String): UiNodeSnapshot? =
            if (contentDescription == value) {
                this
            } else {
                children.firstNotNullOfOrNull { it.findDescription(value) }
            }

        fun AccessibilityResult<UiNodeSnapshot>.containsDescription(value: String): Boolean =
            (this as? AccessibilityResult.Success)?.value?.findDescription(value) != null

        fun awaitDescription(
            service: ScreenshotTestAccessibilityService,
            value: String,
        ): Boolean {
            val deadline = SystemClock.uptimeMillis() + POST_ACTION_TIMEOUT_MS
            do {
                if (service.snapshot(service.packageName).containsDescription(value)) {
                    return true
                }
                SystemClock.sleep(POST_ACTION_POLL_MS)
            } while (SystemClock.uptimeMillis() < deadline)
            return false
        }

        fun ExplicitVisualAction.settleDelayMs(): Long = when (this) {
            is ExplicitVisualAction.Tap -> TAP_DURATION_MS
            is ExplicitVisualAction.LongClick -> durationMs
            is ExplicitVisualAction.Swipe -> durationMs
        } + POST_ACTION_SETTLE_MS

        fun dev.aiauto.android.accessibility.model.UiBounds.centerNatural(
            screen: VisualReplayScreen,
        ): NormalizedPoint = inverseCurrentPoint(center().x, center().y, screen)

        fun dev.aiauto.android.accessibility.model.UiBounds.naturalBounds(
            screen: VisualReplayScreen,
        ): NormalizedBounds {
            val points = listOf(
                inverseCurrentPoint(left, top, screen),
                inverseCurrentPoint(right - 1, top, screen),
                inverseCurrentPoint(left, bottom - 1, screen),
                inverseCurrentPoint(right - 1, bottom - 1, screen),
            )
            return NormalizedBounds(
                left = points.minOf(NormalizedPoint::x),
                top = points.minOf(NormalizedPoint::y),
                right = points.maxOf(NormalizedPoint::x),
                bottom = points.maxOf(NormalizedPoint::y),
            )
        }

        fun inverseCurrentPoint(
            x: Int,
            y: Int,
            screen: VisualReplayScreen,
        ): NormalizedPoint {
            val interactiveLeft = screen.windowBounds.left + screen.systemBars.left
            val interactiveTop = screen.windowBounds.top + screen.systemBars.top
            val interactiveRight = screen.windowBounds.right - screen.systemBars.right
            val interactiveBottom = screen.windowBounds.bottom - screen.systemBars.bottom
            check(
                x in interactiveLeft until interactiveRight &&
                    y in interactiveTop until interactiveBottom,
            ) {
                "N45 target bounds escape the production interactive window"
            }
            val rotated = NormalizedPoint(
                x = (x - interactiveLeft).toDouble() /
                    (interactiveRight - interactiveLeft),
                y = (y - interactiveTop).toDouble() /
                    (interactiveBottom - interactiveTop),
            )
            return if (screen.rotation == 90) {
                NormalizedPoint(rotated.y, 1.0 - rotated.x)
            } else {
                rotated
            }
        }

        fun sha256(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
