package dev.aiauto.android.automation.recording.replay.visual

/**
 * 功能用途：实现显式视觉步骤的安全坐标映射、类型化动作提交和只读后置验证。
 */

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.automation.recording.ExplicitVisualReplayPort
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.VisualReplayRunPort
import dev.aiauto.android.observe.visual.NormalizedPoint
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.VisualCandidate
import dev.aiauto.android.observe.visual.VisualObservation
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

data class ReplayInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class VisualReplayScreen(
    val naturalWidth: Int,
    val naturalHeight: Int,
    val densityDpi: Int,
    val rotation: Int,
    val windowBounds: PixelBounds,
    val systemBars: ReplayInsets,
    val foregroundPackage: String,
    val secureWindow: Boolean,
    val observedAtMs: Long,
)

data class VisualReplayLease(
    val observation: VisualObservation,
    val candidates: List<VisualCandidate>,
    val recordedDensityDpi: Int,
    val secureWindow: Boolean,
)

fun interface VisualReplayScreenReader {
    fun current(): VisualReplayScreen?
}

interface VisualReplayObservationPort {
    fun acquire(observationId: String): VisualReplayLease?

    fun revoke(observationId: String)
}

sealed interface ExplicitVisualAction {
    data class Tap(val point: ScreenPoint) : ExplicitVisualAction

    data class LongClick(
        val point: ScreenPoint,
        val durationMs: Long,
    ) : ExplicitVisualAction

    data class Swipe(
        val start: ScreenPoint,
        val end: ScreenPoint,
        val durationMs: Long,
    ) : ExplicitVisualAction
}

fun interface VisualReplayActionExecutor {
    fun execute(action: ExplicitVisualAction): AccessibilityResult<ActionExecution>
}

fun interface BoundVisualReplayActionExecutor {
    fun execute(
        action: ExplicitVisualAction,
        expectedPackage: String,
    ): AccessibilityResult<ActionExecution>
}

/** production 动作端口只把已授权 N45 动作收窄为绑定目标包的 Accessibility 命令。 */
object AndroidVisualReplayActionExecutor : BoundVisualReplayActionExecutor {
    override fun execute(
        action: ExplicitVisualAction,
        expectedPackage: String,
    ): AccessibilityResult<ActionExecution> = AccessibilityRuntime.execute(
        command = when (action) {
            is ExplicitVisualAction.Tap -> AccessibilityCommand.Tap(
                point = action.point,
                expectedPackage = expectedPackage,
            )

            is ExplicitVisualAction.LongClick -> AccessibilityCommand.Tap(
                point = action.point,
                durationMs = action.durationMs,
                expectedPackage = expectedPackage,
            )

            is ExplicitVisualAction.Swipe -> AccessibilityCommand.Swipe(
                start = action.start,
                end = action.end,
                durationMs = action.durationMs,
                expectedPackage = expectedPackage,
            )
        },
        expectedPackage = expectedPackage,
    )
}

data class VisualReplayVerificationRequest(
    val sourceObservationId: String,
    val action: ExplicitVisualAction,
    val screenBefore: VisualReplayScreen,
)

data class VisualReplayVerification(
    val sourceObservationId: String,
    val deviceSerial: String? = null,
    val foregroundPackage: String,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val densityDpi: Int,
    val rotation: Int,
    val secureWindow: Boolean,
    val observedAtMs: Long,
    val stateChanged: Boolean,
    val windowBounds: PixelBounds? = null,
    val systemBars: ReplayInsets? = null,
)

fun interface VisualReplayVerifier {
    fun verify(request: VisualReplayVerificationRequest): VisualReplayVerification?
}

data class VisualReplayRequest(
    val step: RecordedStep,
    val environment: ScriptEnvironment,
    val targetPackages: Set<String>,
    val run: VisualReplayRunRequest? = null,
)

data class PreparedVisualReplay(
    val observationId: String,
    val action: ExplicitVisualAction,
    val screenBefore: VisualReplayScreen,
)

sealed interface VisualReplayPreparationResult {
    data class Ready(val prepared: PreparedVisualReplay) : VisualReplayPreparationResult

    data class Rejected(
        val code: ExplicitVisualReplayErrorCode,
        val message: String,
    ) : VisualReplayPreparationResult
}

enum class ExplicitVisualReplayErrorCode {
    VISUAL_REPLAY_UNAVAILABLE,
    VISUAL_REBIND_REQUIRED,
    DEVICE_SERIAL_CHANGED,
    OBSERVATION_NOT_FOUND,
    OBSERVATION_ID_MISMATCH,
    OBSERVATION_EXPIRED,
    FOREGROUND_PACKAGE_CHANGED,
    SCREEN_METADATA_MISMATCH,
    TARGET_MISMATCH,
    CANDIDATE_LOW_CONFIDENCE,
    CANDIDATE_AMBIGUOUS,
    SECURE_WINDOW,
    INVALID_COORDINATE,
    ACTION_FAILED,
    ACTION_COMMIT_UNKNOWN,
    POST_ACTION_VERIFICATION_FAILED,
    UNSUPPORTED_ACTION,
}

sealed interface ExplicitVisualReplayResult {
    data class Success(
        val action: ExplicitVisualAction,
        val execution: ActionExecution,
        val actionCommitCount: Int = 1,
    ) : ExplicitVisualReplayResult

    data class Failure(
        val code: ExplicitVisualReplayErrorCode,
        val message: String,
        val actionCommitCount: Int,
    ) : ExplicitVisualReplayResult
}

/** 纯 planner 复用 N45 全部 evidence 与几何前置门，不持有设备动作或 observation 生命周期。 */
class ExplicitVisualReplayPlanner(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun prepare(
        request: VisualReplayRequest,
        lease: VisualReplayLease,
        screen: VisualReplayScreen,
    ): VisualReplayPreparationResult {
        preflight(request, lease)?.let { return it }
        preflightScreen(request, lease, screen)?.let { return it }
        val action = planAction(request, lease.observation, screen, lease.candidates.single())
            ?: return rejected(
                ExplicitVisualReplayErrorCode.UNSUPPORTED_ACTION,
                "Unsupported explicit visual action",
            )
        return VisualReplayPreparationResult.Ready(
            PreparedVisualReplay(
                observationId = lease.observation.id,
                action = action,
                screenBefore = screen,
            ),
        )
    }

    private fun preflight(
        request: VisualReplayRequest,
        lease: VisualReplayLease,
    ): VisualReplayPreparationResult.Rejected? {
        val target = request.step.visualTarget
            ?: return rejected(ExplicitVisualReplayErrorCode.TARGET_MISMATCH, "Visual target missing")
        if (request.step.provenance !in VISUAL_PROVENANCE) {
            return rejected(
                ExplicitVisualReplayErrorCode.VISUAL_REPLAY_UNAVAILABLE,
                "Step provenance is not explicit visual",
            )
        }
        if (lease.observation.id != target.observationId) {
            return rejected(
                ExplicitVisualReplayErrorCode.OBSERVATION_ID_MISMATCH,
                "Observation id drifted",
            )
        }
        if (nowMs() >= Instant.parse(lease.observation.expiresAt).toEpochMilli()) {
            return rejected(
                ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED,
                "Observation expired",
            )
        }
        if (lease.secureWindow) {
            return rejected(ExplicitVisualReplayErrorCode.SECURE_WINDOW, "Secure window")
        }
        if (lease.candidates.isEmpty()) {
            return rejected(
                ExplicitVisualReplayErrorCode.CANDIDATE_LOW_CONFIDENCE,
                "No candidate",
            )
        }
        if (lease.candidates.size != 1) {
            return rejected(
                ExplicitVisualReplayErrorCode.CANDIDATE_AMBIGUOUS,
                "Multiple candidates",
            )
        }
        val candidate = lease.candidates.single()
        if (
            !candidate.point.valid() ||
            !candidate.bounds.valid() ||
            !candidate.bounds.contains(candidate.point)
        ) {
            return rejected(
                ExplicitVisualReplayErrorCode.TARGET_MISMATCH,
                "Candidate geometry is invalid",
            )
        }
        if (candidate.confidence < MIN_CONFIDENCE || target.confidence < MIN_CONFIDENCE) {
            return rejected(
                ExplicitVisualReplayErrorCode.CANDIDATE_LOW_CONFIDENCE,
                "Candidate confidence is too low",
            )
        }
        if (
            candidate.observationId != lease.observation.id ||
            target.imageSha256 != lease.observation.png.sha256 ||
            abs(target.confidence - candidate.confidence) > EPSILON ||
            target.normalizedPoint?.let {
                abs(it.x - candidate.point.x) > EPSILON ||
                    abs(it.y - candidate.point.y) > EPSILON
            } == true ||
            target.normalizedBounds?.let {
                abs(it.left - candidate.bounds.left) > EPSILON ||
                    abs(it.top - candidate.bounds.top) > EPSILON ||
                    abs(it.right - candidate.bounds.right) > EPSILON ||
                    abs(it.bottom - candidate.bounds.bottom) > EPSILON
            } == true
        ) {
            return rejected(
                ExplicitVisualReplayErrorCode.TARGET_MISMATCH,
                "Visual target drifted",
            )
        }
        return null
    }

    private fun preflightScreen(
        request: VisualReplayRequest,
        lease: VisualReplayLease,
        screen: VisualReplayScreen,
    ): VisualReplayPreparationResult.Rejected? {
        if (screen.secureWindow) {
            return rejected(ExplicitVisualReplayErrorCode.SECURE_WINDOW, "Secure window")
        }
        if (
            screen.foregroundPackage != lease.observation.foregroundPackage ||
            screen.foregroundPackage !in request.targetPackages
        ) {
            return rejected(
                ExplicitVisualReplayErrorCode.FOREGROUND_PACKAGE_CHANGED,
                "Foreground package changed",
            )
        }
        val environment = request.environment
        if (
            environment.logicalWidth != lease.observation.screen.width ||
            environment.logicalHeight != lease.observation.screen.height ||
            environment.rotation != lease.observation.screen.rotation ||
            environment.densityDpi != lease.recordedDensityDpi ||
            screen.naturalWidth != lease.observation.screen.width ||
            screen.naturalHeight != lease.observation.screen.height ||
            screen.rotation != lease.observation.screen.rotation ||
            screen.densityDpi != lease.recordedDensityDpi ||
            screen.densityDpi <= 0
        ) {
            return rejected(
                ExplicitVisualReplayErrorCode.SCREEN_METADATA_MISMATCH,
                "Recorded screen metadata drifted",
            )
        }
        return null
    }

    private fun planAction(
        request: VisualReplayRequest,
        observation: VisualObservation,
        screen: VisualReplayScreen,
        candidate: VisualCandidate,
    ): ExplicitVisualAction? {
        val point = VisualCoordinateTransformer.mapPoint(
            observation,
            screen,
            candidate.point,
        ).successOrNull() ?: return null
        return when (request.step.action.type) {
            "ui.tap" -> ExplicitVisualAction.Tap(point)
            "ui.longClick" -> ExplicitVisualAction.LongClick(
                point,
                request.step.action.params["durationMs"]?.toString()?.toLongOrNull() ?: 600,
            )

            "ui.swipe" -> {
                val start = request.step.action.params["start"] as? kotlinx.serialization.json.JsonObject
                val end = request.step.action.params["end"] as? kotlinx.serialization.json.JsonObject
                val environment = request.environment
                val width = environment.logicalWidth ?: return null
                val height = environment.logicalHeight ?: return null
                val bounds = candidate.bounds
                val startRelative = start?.toRelative(bounds, width, height) ?: return null
                val endRelative = end?.toRelative(bounds, width, height) ?: return null
                val startNatural = NormalizedPoint(
                    bounds.left + startRelative.x * (bounds.right - bounds.left),
                    bounds.top + startRelative.y * (bounds.bottom - bounds.top),
                )
                val endNatural = NormalizedPoint(
                    bounds.left + endRelative.x * (bounds.right - bounds.left),
                    bounds.top + endRelative.y * (bounds.bottom - bounds.top),
                )
                val mappedStart = VisualCoordinateTransformer.mapPoint(
                    observation,
                    screen,
                    startNatural,
                ).successOrNull() ?: return null
                val mappedEnd = VisualCoordinateTransformer.mapPoint(
                    observation,
                    screen,
                    endNatural,
                ).successOrNull() ?: return null
                ExplicitVisualAction.Swipe(
                    mappedStart,
                    mappedEnd,
                    request.step.action.params["durationMs"]?.toString()?.toLongOrNull() ?: 400,
                )
            }

            else -> null
        }
    }

    private fun rejected(
        code: ExplicitVisualReplayErrorCode,
        message: String,
    ) = VisualReplayPreparationResult.Rejected(code, message)

    private companion object {
        const val MIN_CONFIDENCE = 0.70
        const val EPSILON = 0.000_000_001
        val VISUAL_PROVENANCE = setOf(
            RecordingProvenance.VISUAL,
            RecordingProvenance.COORDINATE,
            RecordingProvenance.MANUAL,
        )
    }
}

object VisualCoordinateTransformer {
    fun mapPoint(
        observation: VisualObservation,
        current: VisualReplayScreen,
        point: NormalizedPoint,
    ): AccessibilityResult<ScreenPoint> {
        if (!point.valid() || !observation.geometryValid() || !current.valid()) {
            return invalid("Visual coordinate metadata is invalid")
        }
        val currentPlane = current.currentPlane()
        val interactive = PixelBounds(
            left = current.windowBounds.left + current.systemBars.left,
            top = current.windowBounds.top + current.systemBars.top,
            right = current.windowBounds.right - current.systemBars.right,
            bottom = current.windowBounds.bottom - current.systemBars.bottom,
        )
        if (interactive.right <= interactive.left || interactive.bottom <= interactive.top) {
            return invalid("The current interactive window is empty")
        }

        val observationRotated = naturalToObservationPlane(point, observation)
        if (
            observationRotated.x !in observation.crop.left.toDouble()..
            observation.crop.right.toDouble() ||
            observationRotated.y !in observation.crop.top.toDouble()..
            observation.crop.bottom.toDouble()
        ) {
            return invalid("The target is outside the source observation crop")
        }
        // N44 candidate 已是自然全屏坐标；crop 只用于来源完整性校验，不能再次拉伸到全屏。
        val currentRotated = naturalToRotated(point, current)
        val mapped = ScreenPoint(
            mapAxis(currentRotated.x, interactive.left, interactive.right),
            mapAxis(currentRotated.y, interactive.top, interactive.bottom),
        )
        if (
            mapped.x !in currentPlane.left until currentPlane.right ||
            mapped.y !in currentPlane.top until currentPlane.bottom
        ) {
            return invalid("The mapped point is outside the current screen")
        }
        return AccessibilityResult.Success(mapped)
    }

    private fun naturalToRotated(
        point: NormalizedPoint,
        screen: VisualReplayScreen,
    ): NormalizedPoint = when (screen.rotation) {
        90 -> NormalizedPoint(1.0 - point.y, point.x)
        180 -> NormalizedPoint(1.0 - point.x, 1.0 - point.y)
        270 -> NormalizedPoint(point.y, 1.0 - point.x)
        else -> point
    }

    private fun naturalToObservationPlane(
        point: NormalizedPoint,
        observation: VisualObservation,
    ): NormalizedPoint {
        val plane = observation.screenPlane()
        return when (observation.screen.rotation) {
            90 -> NormalizedPoint(
                x = (1.0 - point.y) * plane.right,
                y = point.x * plane.bottom,
            )

            180 -> NormalizedPoint(
                x = (1.0 - point.x) * plane.right,
                y = (1.0 - point.y) * plane.bottom,
            )

            270 -> NormalizedPoint(
                x = point.y * plane.right,
                y = (1.0 - point.x) * plane.bottom,
            )

            else -> NormalizedPoint(
                x = point.x * plane.right,
                y = point.y * plane.bottom,
            )
        }
    }

    private fun mapAxis(value: Double, start: Int, end: Int): Int =
        (start + floor(value * (end - start)).toInt()).coerceIn(start, end - 1)

    private fun invalid(message: String): AccessibilityResult.Failure =
        AccessibilityResult.Failure(
            code = dev.aiauto.android.accessibility.model.AccessibilityErrorCode.INVALID_COORDINATE,
            message = message,
        )
}

class ExplicitVisualReplayExecutor private constructor(
    private val authorizations: VisualReplayAuthorizationRegistry?,
    private val actions: BoundVisualReplayActionExecutor?,
    private val legacyObservations: VisualReplayObservationPort?,
    private val legacyScreens: VisualReplayScreenReader?,
    private val legacyActions: VisualReplayActionExecutor?,
    private val legacyVerifier: VisualReplayVerifier?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val planner: ExplicitVisualReplayPlanner = ExplicitVisualReplayPlanner(nowMs),
) : ExplicitVisualReplayPort, VisualReplayRunPort {
    constructor(
        authorizations: VisualReplayAuthorizationRegistry,
        actions: BoundVisualReplayActionExecutor,
        nowMs: () -> Long = System::currentTimeMillis,
        planner: ExplicitVisualReplayPlanner = ExplicitVisualReplayPlanner(nowMs),
    ) : this(
        authorizations = authorizations,
        actions = actions,
        legacyObservations = null,
        legacyScreens = null,
        legacyActions = null,
        legacyVerifier = null,
        nowMs = nowMs,
        planner = planner,
    )

    /**
     * 仅保留给既有 N45 debug/device harness；production recording factory 不使用该入口。
     */
    internal constructor(
        observations: VisualReplayObservationPort,
        screens: VisualReplayScreenReader,
        actions: VisualReplayActionExecutor,
        verifier: VisualReplayVerifier,
        nowMs: () -> Long = System::currentTimeMillis,
        planner: ExplicitVisualReplayPlanner = ExplicitVisualReplayPlanner(nowMs),
    ) : this(
        authorizations = null,
        actions = null,
        legacyObservations = observations,
        legacyScreens = screens,
        legacyActions = actions,
        legacyVerifier = verifier,
        nowMs = nowMs,
        planner = planner,
    )

    override fun beginRun(
        request: VisualReplayRunRequest,
        authorizationRequest: VisualReplayAuthorizationRequest,
        authorization: VisualReplayRunAuthorization?,
    ): Boolean {
        val registry = authorizations ?: return false
        registry.endRun()
        if (
            authorization == null ||
            !registry.replace(authorizationRequest, authorization)
        ) {
            return false
        }
        return registry.beginRun(request)
    }

    fun beginRun(request: VisualReplayRunRequest): Boolean =
        authorizations?.beginRun(request) == true

    override fun endRun() {
        authorizations?.endRun()
    }

    override fun replay(request: VisualReplayRequest): ExplicitVisualReplayResult {
        if (authorizations == null) {
            return replayLegacy(request)
        }
        val historicalTarget = request.step.visualTarget
            ?: return failure(ExplicitVisualReplayErrorCode.TARGET_MISMATCH, "Visual target missing")
        val run = request.run
            ?: return failure(
                ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED,
                "Current visual replay authorization is missing",
            )
        val rebindRequest = VisualReplayRebindRequest(
            run = run,
            stepId = request.step.id,
            historicalTarget = historicalTarget,
            environment = request.environment,
            targetPackages = request.targetPackages,
        )
        val rebound = when (
            val result = authorizations.acquire(rebindRequest)
        ) {
            is VisualReplayRebindResult.Rejected -> return failure(
                result.code,
                "Current visual replay rebind was rejected",
            )

            is VisualReplayRebindResult.Ready -> result
        }
        val lease = rebound.lease
        try {
            val reboundRequest = request.copy(
                step = request.step.copy(visualTarget = lease.reboundTarget),
            )
            val prepared = when (
                val result = planner.prepare(
                    reboundRequest,
                    lease.visual,
                    lease.screenBefore,
                )
            ) {
                is VisualReplayPreparationResult.Rejected -> return failure(
                    result.code,
                    result.message,
                )
                is VisualReplayPreparationResult.Ready -> result.prepared
            }
            val action = prepared.action
            val preCommitRejection = authorizations.validateBeforeCommit(
                authorization = rebound.authorization,
                lease = lease,
                request = rebindRequest,
            )
            if (preCommitRejection != null) {
                return failure(
                    preCommitRejection,
                    "Visual authorization changed before action commit",
                )
            }
            val execution = try {
                requireNotNull(actions).execute(action, lease.targetPackage)
            } catch (_: Exception) {
                return failure(
                    ExplicitVisualReplayErrorCode.ACTION_COMMIT_UNKNOWN,
                    "Visual action commit status is unknown",
                    commits = 1,
                )
            }
            val committed = when (execution) {
                is AccessibilityResult.Failure -> return failure(
                    ExplicitVisualReplayErrorCode.ACTION_FAILED,
                    execution.error.message,
                )

                is AccessibilityResult.Success -> execution.value
            }
            val actionCommittedAtMs = nowMs()
            val verification = try {
                rebound.authorization.verifyAfter(
                    lease,
                    VisualReplayVerificationRequest(
                        prepared.observationId,
                        action,
                        prepared.screenBefore,
                    ),
                )
            } catch (_: Throwable) {
                null
            }
            if (
                authorizations.validateBeforeCommit(
                    authorization = rebound.authorization,
                    lease = lease,
                    request = rebindRequest,
                ) != null ||
                !verification.validFor(
                    prepared.observationId,
                    lease.deviceSerial,
                    prepared.screenBefore,
                    actionCommittedAtMs,
                    nowMs(),
                )
            ) {
                return failure(
                    ExplicitVisualReplayErrorCode.POST_ACTION_VERIFICATION_FAILED,
                    "Post action visual verification failed",
                    commits = 1,
                )
            }
            return ExplicitVisualReplayResult.Success(action, committed)
        } finally {
            lease.close()
        }
    }

    private fun failure(
        code: ExplicitVisualReplayErrorCode,
        message: String,
        commits: Int = 0,
    ) = ExplicitVisualReplayResult.Failure(code, message, commits)

    private fun replayLegacy(request: VisualReplayRequest): ExplicitVisualReplayResult {
        val target = request.step.visualTarget
            ?: return failure(ExplicitVisualReplayErrorCode.TARGET_MISMATCH, "Visual target missing")
        val observationId = target.observationId
            ?: return failure(
                ExplicitVisualReplayErrorCode.OBSERVATION_ID_MISMATCH,
                "Observation id missing",
            )
        val observations = requireNotNull(legacyObservations)
        val lease = observations.acquire(observationId)
            ?: return failure(
                ExplicitVisualReplayErrorCode.OBSERVATION_NOT_FOUND,
                "Observation not found",
            )
        try {
            val screen = requireNotNull(legacyScreens).current()
                ?: return failure(
                    ExplicitVisualReplayErrorCode.SCREEN_METADATA_MISMATCH,
                    "Current screen unavailable",
                )
            val prepared = when (val result = planner.prepare(request, lease, screen)) {
                is VisualReplayPreparationResult.Rejected -> return failure(
                    result.code,
                    result.message,
                )

                is VisualReplayPreparationResult.Ready -> result.prepared
            }
            val action = prepared.action
            val execution = when (val result = requireNotNull(legacyActions).execute(action)) {
                is AccessibilityResult.Failure -> return failure(
                    ExplicitVisualReplayErrorCode.ACTION_FAILED,
                    result.error.message,
                )

                is AccessibilityResult.Success -> result.value
            }
            val verification = try {
                requireNotNull(legacyVerifier).verify(
                    VisualReplayVerificationRequest(
                        prepared.observationId,
                        action,
                        prepared.screenBefore,
                    ),
                )
            } catch (_: RuntimeException) {
                null
            }
            if (!verification.validLegacy(prepared.observationId, prepared.screenBefore)) {
                return failure(
                    ExplicitVisualReplayErrorCode.POST_ACTION_VERIFICATION_FAILED,
                    "Post action visual verification failed",
                    commits = 1,
                )
            }
            return ExplicitVisualReplayResult.Success(action, execution)
        } finally {
            observations.revoke(observationId)
        }
    }
}

private fun VisualReplayVerification?.validFor(
    observationId: String,
    expectedDeviceSerial: String,
    screen: VisualReplayScreen,
    actionCommittedAtMs: Long,
    verifiedAtMs: Long,
): Boolean = this != null &&
    sourceObservationId == observationId &&
    deviceSerial == expectedDeviceSerial &&
    foregroundPackage == screen.foregroundPackage &&
    naturalWidth == screen.naturalWidth &&
    naturalHeight == screen.naturalHeight &&
    densityDpi == screen.densityDpi &&
    rotation == screen.rotation &&
    windowBounds == screen.windowBounds &&
    systemBars == screen.systemBars &&
    !secureWindow &&
    observedAtMs in actionCommittedAtMs..verifiedAtMs &&
    stateChanged

private fun VisualReplayVerification?.validLegacy(
    observationId: String,
    screen: VisualReplayScreen,
): Boolean = this != null &&
    sourceObservationId == observationId &&
    foregroundPackage == screen.foregroundPackage &&
    naturalWidth == screen.naturalWidth &&
    naturalHeight == screen.naturalHeight &&
    densityDpi == screen.densityDpi &&
    rotation == screen.rotation &&
    !secureWindow &&
    observedAtMs > screen.observedAtMs &&
    stateChanged

private fun kotlinx.serialization.json.JsonObject.toRelative(
    bounds: dev.aiauto.android.observe.visual.NormalizedBounds,
    width: Int,
    height: Int,
): NormalizedPoint? {
    val x = this["x"]?.toString()?.toIntOrNull() ?: return null
    val y = this["y"]?.toString()?.toIntOrNull() ?: return null
    val normalizedX = x.toDouble() / width
    val normalizedY = y.toDouble() / height
    val boundsWidth = bounds.right - bounds.left
    val boundsHeight = bounds.bottom - bounds.top
    if (boundsWidth <= 0 || boundsHeight <= 0) return null
    val relative = NormalizedPoint(
        (normalizedX - bounds.left) / boundsWidth,
        (normalizedY - bounds.top) / boundsHeight,
    )
    return relative.takeIf(NormalizedPoint::valid)
}

private fun <T> AccessibilityResult<T>.successOrNull(): T? =
    (this as? AccessibilityResult.Success)?.value

private fun NormalizedPoint.valid(): Boolean =
    x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0

private fun dev.aiauto.android.observe.visual.NormalizedBounds.valid(): Boolean =
    left.isFinite() &&
        top.isFinite() &&
        right.isFinite() &&
        bottom.isFinite() &&
        left in 0.0..1.0 &&
        top in 0.0..1.0 &&
        right in 0.0..1.0 &&
        bottom in 0.0..1.0 &&
        right > left &&
        bottom > top

private fun dev.aiauto.android.observe.visual.NormalizedBounds.contains(
    point: NormalizedPoint,
): Boolean =
    point.x in left..right && point.y in top..bottom

private fun VisualObservation.geometryValid(): Boolean =
    screen.width > 0 &&
        screen.height > 0 &&
        screen.rotation in setOf(0, 90, 180, 270) &&
        crop.inside(screenPlane())

private fun VisualReplayScreen.valid(): Boolean =
    naturalWidth > 0 &&
        naturalHeight > 0 &&
        densityDpi > 0 &&
        rotation in setOf(0, 90, 180, 270) &&
        systemBars.left >= 0 &&
        systemBars.top >= 0 &&
        systemBars.right >= 0 &&
        systemBars.bottom >= 0 &&
        windowBounds.inside(currentPlane()) &&
        windowBounds.right - windowBounds.left > systemBars.left + systemBars.right &&
        windowBounds.bottom - windowBounds.top > systemBars.top + systemBars.bottom

private fun VisualObservation.screenPlane(): PixelBounds =
    if (screen.rotation == 90 || screen.rotation == 270) {
        PixelBounds(0, 0, screen.height, screen.width)
    } else {
        PixelBounds(0, 0, screen.width, screen.height)
    }

private fun VisualReplayScreen.currentPlane(): PixelBounds =
    if (rotation == 90 || rotation == 270) {
        PixelBounds(0, 0, naturalHeight, naturalWidth)
    } else {
        PixelBounds(0, 0, naturalWidth, naturalHeight)
    }

private fun PixelBounds.inside(parent: PixelBounds): Boolean =
    left >= parent.left &&
        top >= parent.top &&
        right <= parent.right &&
        bottom <= parent.bottom &&
        right > left &&
        bottom > top
