package dev.aiauto.android.automation.session

/**
 * 功能用途：实现授权截图、严格视觉候选、N45 安全校验和一次性类型化动作执行接线。
 */

import android.content.Context
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.NormalizedBounds as RecordedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint as RecordedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.VisualTarget
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualAction
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualReplayPlanner
import dev.aiauto.android.automation.recording.replay.visual.PreparedVisualReplay
import dev.aiauto.android.automation.recording.replay.visual.ReplayInsets
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayLease
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayPreparationResult
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayRequest
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayScreen
import dev.aiauto.android.observe.visual.NormalizedBounds
import dev.aiauto.android.observe.visual.NormalizedPoint
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.VisualCandidate
import dev.aiauto.android.observe.visual.VisualCandidateSource
import dev.aiauto.android.observe.visual.VisualObservation
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun interface AuthorizedVisualActionContextFactory {
    fun create(
        action: dev.aiauto.android.provider.ProviderAction,
        target: AuthorizedVisualTarget,
        hierarchy: UiNodeSnapshot,
        lease: VisualSessionObservationLease,
    ): SessionActionContext
}

internal class AndroidAuthorizedVisualActionContextFactory(
    context: Context,
    private val currentSnapshot: (String) -> AccessibilityResult<UiNodeSnapshot> =
        AccessibilityRuntime::snapshot,
    private val acquireAuthorization: (String) -> ScreenshotAuthorization? =
        AutomationSessionRuntime::acquireScreenshotAuthorization,
    private val isAuthorizationActive: (ScreenshotAuthorization) -> Boolean =
        AutomationSessionRuntime::isScreenshotAuthorizationActive,
    private val screenshotCapture:
        suspend (String) -> AccessibilityResult<
            dev.aiauto.android.accessibility.model.AccessibilityScreenshot
            > = AccessibilityRuntime::captureScreenshot,
) : AuthorizedVisualActionContextFactory {
    private val applicationContext = context.applicationContext
    private val geometryProvider = AndroidVisualCaptureGeometryProvider(applicationContext)
    private val screenProvider = AndroidVisualReplayScreenProvider(applicationContext)

    override fun create(
        action: dev.aiauto.android.provider.ProviderAction,
        target: AuthorizedVisualTarget,
        hierarchy: UiNodeSnapshot,
        lease: VisualSessionObservationLease,
    ): SessionActionContext {
        val observationFactory:
            suspend (UiNodeSnapshot) -> VisualSessionObservationLease = { refreshed ->
            VisualSessionObservationFactory(
                screenshotCapture = screenshotCapture,
                currentSnapshot = currentSnapshot,
                acquireAuthorization = acquireAuthorization,
                isAuthorizationActive = isAuthorizationActive,
                captureGeometry = geometryProvider::capture,
            ).create(target.packageName, refreshed)
        }
        return AuthorizedVisualSessionActionContext(
            providerAction = action,
            target = target,
            sourceHierarchy = hierarchy,
            sourceLease = lease,
            sourceScreen = screenProvider.current(lease.observation),
            refreshObservation = observationFactory,
            screenProvider = screenProvider::current,
            actionExecutor = ::executeVisualAction,
        )
    }

    private fun executeVisualAction(
        action: ExplicitVisualAction,
        targetPackage: String,
    ): AccessibilityResult<dev.aiauto.android.accessibility.model.ActionExecution> {
        val command = when (action) {
            is ExplicitVisualAction.Tap -> AccessibilityCommand.Tap(
                action.point,
                expectedPackage = targetPackage,
            )
            is ExplicitVisualAction.LongClick ->
                AccessibilityCommand.Tap(
                    action.point,
                    action.durationMs,
                    expectedPackage = targetPackage,
                )
            is ExplicitVisualAction.Swipe -> AccessibilityCommand.Swipe(
                start = action.start,
                end = action.end,
                durationMs = action.durationMs,
                expectedPackage = targetPackage,
            )
        }
        return AccessibilityRuntime.execute(command, targetPackage)
    }
}

internal data class AuthorizedVisualTarget(
    val packageName: String,
    val observationId: String,
    val imageSha256: String,
    val candidateId: String,
    val confidence: Double,
    val point: NormalizedPoint,
    val bounds: NormalizedBounds,
)

internal fun providerVisualTarget(action: dev.aiauto.android.provider.ProviderAction):
    AuthorizedVisualTarget? {
    val value = action.params["visualTarget"] as? JsonObject ?: return null
    return runCatching {
        val point = value.getValue("point").jsonObject
        val bounds = value.getValue("bounds").jsonObject
        AuthorizedVisualTarget(
            packageName = value.getValue("packageName").jsonPrimitive.content,
            observationId = value.getValue("observationId").jsonPrimitive.content,
            imageSha256 = value.getValue("imageSha256").jsonPrimitive.content,
            candidateId = value.getValue("candidateId").jsonPrimitive.content,
            confidence = value.getValue("confidence").jsonPrimitive.double,
            point = NormalizedPoint(
                point.getValue("x").jsonPrimitive.double,
                point.getValue("y").jsonPrimitive.double,
            ),
            bounds = NormalizedBounds(
                bounds.getValue("left").jsonPrimitive.double,
                bounds.getValue("top").jsonPrimitive.double,
                bounds.getValue("right").jsonPrimitive.double,
                bounds.getValue("bottom").jsonPrimitive.double,
            ),
        )
    }.getOrNull()
}

internal class AndroidVisualReplayScreenProvider(context: Context) {
    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)

    fun current(observation: VisualObservation): VisualReplayScreen {
        val metrics = windowManager.maximumWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        @Suppress("DEPRECATION")
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val currentWidth = bounds.width()
        val currentHeight = bounds.height()
        val naturalWidth = if (rotation == 90 || rotation == 270) {
            currentHeight
        } else {
            currentWidth
        }
        val naturalHeight = if (rotation == 90 || rotation == 270) {
            currentWidth
        } else {
            currentHeight
        }
        return VisualReplayScreen(
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
            densityDpi = applicationContext.resources.configuration.densityDpi,
            rotation = rotation,
            windowBounds = PixelBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            systemBars = ReplayInsets(insets.left, insets.top, insets.right, insets.bottom),
            foregroundPackage = observation.foregroundPackage,
            secureWindow = false,
            observedAtMs = Instant.parse(observation.capturedAt).toEpochMilli(),
        )
    }
}

internal class AuthorizedVisualSessionActionContext(
    providerAction: dev.aiauto.android.provider.ProviderAction,
    target: AuthorizedVisualTarget,
    sourceHierarchy: UiNodeSnapshot,
    sourceLease: VisualSessionObservationLease,
    sourceScreen: VisualReplayScreen,
    private val refreshObservation:
        suspend (UiNodeSnapshot) -> VisualSessionObservationLease,
    private val screenProvider: (VisualObservation) -> VisualReplayScreen,
    private val actionExecutor:
        (ExplicitVisualAction, String) -> AccessibilityResult<
            dev.aiauto.android.accessibility.model.ActionExecution
            >,
    private val planner: ExplicitVisualReplayPlanner = ExplicitVisualReplayPlanner(),
) : SessionActionContext {
    private val closed = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private var providerAction: dev.aiauto.android.provider.ProviderAction? = providerAction
    private var target: AuthorizedVisualTarget? = target
    private var sourceHierarchy: UiNodeSnapshot? = sourceHierarchy
    private var sourceLease: VisualSessionObservationLease? = sourceLease
    private var sourceScreen: VisualReplayScreen? = sourceScreen
    private var preExecutionLease: VisualSessionObservationLease? = null
    private var postExecutionLease: VisualSessionObservationLease? = null
    private var prepared: PreparedVisualReplay? = null
    private var executionSucceeded = false

    override suspend fun validateBefore(
        observation: SessionObservation,
        targetPackage: String,
    ) {
        checkOpen()
        val currentTarget = requireNotNull(target)
        val currentSourceHierarchy = requireNotNull(sourceHierarchy)
        val currentSourceLease = requireNotNull(sourceLease)
        if (
            targetPackage != currentTarget.packageName ||
            observation.activePackage != targetPackage ||
            observation.hierarchy != currentSourceHierarchy ||
            !currentSourceLease.hasObservation() ||
            currentSourceLease.observation.id != currentTarget.observationId ||
            currentSourceLease.observation.png.sha256 != currentTarget.imageSha256
        ) {
            throw SessionFailureException("The authorized visual evidence changed before execution.")
        }
        val hierarchy = observation.hierarchy
            ?: throw SessionFailureException("The visual pre-execution hierarchy is unavailable.")
        val refreshed = refreshObservation(hierarchy)
        preExecutionLease = refreshed
        if (
            refreshed.observation.foregroundPackage != targetPackage ||
            refreshed.observation.png.sha256 != currentSourceLease.observation.png.sha256
        ) {
            throw SessionFailureException("The visual screenshot changed before execution.")
        }
        val screen = screenProvider(refreshed.observation)
        val lease = VisualReplayLease(
            observation = currentSourceLease.observation,
            candidates = listOf(currentTarget.toCandidate()),
            recordedDensityDpi = requireNotNull(sourceScreen).densityDpi,
            secureWindow = false,
        )
        val request = visualRequest()
        prepared = when (val result = planner.prepare(request, lease, screen)) {
            is VisualReplayPreparationResult.Ready -> result.prepared
            is VisualReplayPreparationResult.Rejected -> throw SessionFailureException(
                "Visual action rejected before commit: ${result.code.name}",
            )
        }
    }

    override suspend fun execute(targetPackage: String): SessionExecutionResult {
        checkOpen()
        if (!consumed.compareAndSet(false, true)) {
            throw SessionFailureException("The authorized visual action was already consumed.")
        }
        val visual = prepared
            ?: throw SessionFailureException("The authorized visual action was not prepared.")
        val result = actionExecutor(visual.action, targetPackage)
        return when (result) {
            is AccessibilityResult.Failure -> throw SessionFailureException(
                "Visual action failed before commit: ${result.error.code.name}",
            )

            is AccessibilityResult.Success -> {
                executionSucceeded = true
                SessionExecutionResult(
                    "Executed authorized visual action through ${result.value.route.name.lowercase()}.",
                )
            }
        }
    }

    override suspend fun verifyAfter(
        observation: SessionObservation,
        targetPackage: String,
    ) {
        checkOpen()
        if (!executionSucceeded) {
            throw SessionFailureException("The visual action was not committed.")
        }
        try {
            val hierarchy = observation.hierarchy
                ?: throw SessionFailureException("The post-action hierarchy is unavailable.")
            val post = refreshObservation(hierarchy)
            postExecutionLease = post
            val before = prepared?.screenBefore
                ?: throw SessionFailureException("The visual verification state is unavailable.")
            val after = screenProvider(post.observation)
            if (
                observation.activePackage != targetPackage ||
                post.observation.foregroundPackage != targetPackage ||
                hierarchy == sourceHierarchy &&
                post.observation.png.sha256 == sourceLease?.observation?.png?.sha256 ||
                after.naturalWidth != before.naturalWidth ||
                after.naturalHeight != before.naturalHeight ||
                after.densityDpi != before.densityDpi ||
                after.rotation != before.rotation ||
                after.secureWindow ||
                !Instant.parse(post.observation.capturedAt).isAfter(
                    Instant.parse(preExecutionLease!!.observation.capturedAt),
                )
            ) {
                throw SessionFailureException("The post-action evidence did not change safely.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SessionFailureException(
                "Visual action verification failed after one commit.",
                error,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeSafely(postExecutionLease)
        closeSafely(preExecutionLease)
        closeSafely(sourceLease)
        postExecutionLease = null
        preExecutionLease = null
        sourceLease = null
        sourceHierarchy = null
        sourceScreen = null
        target = null
        providerAction = null
        prepared = null
    }

    private fun visualRequest(): VisualReplayRequest {
        val observation = requireNotNull(sourceLease).observation
        val currentTarget = requireNotNull(target)
        return VisualReplayRequest(
            step = RecordedStep(
                id = "session-visual-action",
                provenance = RecordingProvenance.VISUAL,
                action = requireNotNull(providerAction).toRecordedAction(
                    currentTarget,
                    observation,
                ),
                visualTarget = VisualTarget(
                    normalizedPoint = RecordedPoint(currentTarget.point.x, currentTarget.point.y),
                    normalizedBounds = RecordedBounds(
                        currentTarget.bounds.left,
                        currentTarget.bounds.top,
                        currentTarget.bounds.right,
                        currentTarget.bounds.bottom,
                    ),
                    confidence = currentTarget.confidence,
                    source = RecordingProvenance.VISUAL,
                    observationId = currentTarget.observationId,
                    imageSha256 = currentTarget.imageSha256,
                ),
            ),
            environment = ScriptEnvironment(
                logicalWidth = observation.screen.width,
                logicalHeight = observation.screen.height,
                densityDpi = requireNotNull(sourceScreen).densityDpi,
                rotation = observation.screen.rotation,
            ),
            targetPackages = setOf(currentTarget.packageName),
        )
    }

    private fun checkOpen() {
        if (closed.get()) {
            throw SessionFailureException("The authorized visual action context is closed.")
        }
    }

    private fun AuthorizedVisualTarget.toCandidate() = VisualCandidate(
        id = candidateId,
        observationId = observationId,
        source = VisualCandidateSource.MODEL,
        point = point,
        bounds = bounds,
        confidence = confidence,
    )

    private fun closeSafely(value: AutoCloseable?) {
        try {
            value?.close()
        } catch (_: Exception) {
            // 继续撤销其余图片租约，避免单个清理错误留下敏感引用。
        }
    }
}

private fun dev.aiauto.android.provider.ProviderAction.toRecordedAction(
    target: AuthorizedVisualTarget,
    observation: VisualObservation,
): RecordedAction = when (type) {
    "ui.tap" -> RecordedAction(
        type,
        kotlinx.serialization.json.buildJsonObject {
            put("x", (target.point.x * observation.screen.width).toInt())
            put("y", (target.point.y * observation.screen.height).toInt())
        },
    )

    "ui.longClick" -> RecordedAction(
        type,
        kotlinx.serialization.json.buildJsonObject {
            put("target", kotlinx.serialization.json.buildJsonObject {})
            put("durationMs", params["durationMs"]?.jsonPrimitive?.int ?: 600)
        },
    )

    "ui.swipe" -> {
        val start = params.getValue("start").jsonObject
        val end = params.getValue("end").jsonObject
        RecordedAction(
            type,
            kotlinx.serialization.json.buildJsonObject {
                put(
                    "start",
                    target.relativeEndpointToRecorded(start, observation),
                )
                put(
                    "end",
                    target.relativeEndpointToRecorded(end, observation),
                )
                put("durationMs", params.getValue("durationMs").jsonPrimitive.int)
            },
        )
    }

    else -> throw SessionFailureException("The visual action type is unsupported.")
}

private fun AuthorizedVisualTarget.relativeEndpointToRecorded(
    endpoint: JsonObject,
    observation: VisualObservation,
): JsonObject {
    val relativeX = endpoint.getValue("x").jsonPrimitive.double
    val relativeY = endpoint.getValue("y").jsonPrimitive.double
    val naturalX = bounds.left + relativeX * (bounds.right - bounds.left)
    val naturalY = bounds.top + relativeY * (bounds.bottom - bounds.top)
    return kotlinx.serialization.json.buildJsonObject {
        put("x", (naturalX * observation.screen.width).toInt())
        put("y", (naturalY * observation.screen.height).toInt())
    }
}
