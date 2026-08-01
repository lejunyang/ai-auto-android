package dev.aiauto.android.bridge

/**
 * 功能用途：严格解析桌面短期视觉 evidence，并原子调用 N45 planner、类型化执行和后置观察。
 */

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import dev.aiauto.android.BuildConfig
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.NormalizedBounds as RecordedBounds
import dev.aiauto.android.automation.recording.NormalizedPoint as RecordedPoint
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.VisualTarget
import dev.aiauto.android.automation.recording.replay.visual.AndroidVisualReplayActionExecutor
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualAction
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualReplayPlanner
import dev.aiauto.android.automation.recording.replay.visual.ReplayInsets
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayLease
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayPreparationResult
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayRequest
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayScreen
import dev.aiauto.android.automation.session.toAccessibilityCommand
import dev.aiauto.android.observe.visual.NormalizedBounds
import dev.aiauto.android.observe.visual.NormalizedPoint
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.VisualCandidate
import dev.aiauto.android.observe.visual.VisualCandidateSource
import dev.aiauto.android.observe.visual.VisualObservation
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** production screen reader 只返回 N45 preflight 所需的窗口几何，不提供独立坐标接口。 */
fun interface AttestedVisualScreenReader {
    fun current(expectedPackage: String, observedAtMs: Long): VisualReplayScreen?
}

/** 图片 decoder 允许测试确认 Bridge 持有副本在所有返回路径清零。 */
fun interface AttestedVisualImageDecoder {
    fun decode(encoded: String): ByteArray
}

/** 类型化动作端口默认委托 N45 package-bound production executor。 */
fun interface AttestedVisualActionExecutor {
    fun execute(
        action: ExplicitVisualAction,
        expectedPackage: String,
    ): AccessibilityResult<ActionExecution>
}

/** Bridge 方法使用的原子视觉动作能力，默认 release、真机和未授权路径不可用。 */
interface AttestedVisualActionBridgeGateway {
    fun isAvailable(): Boolean

    fun execute(params: JsonObject): JsonObject
}

/** 默认构造保持视觉动作关闭，只有 production factory 或测试显式注入才可执行。 */
object UnavailableAttestedVisualActionBridgeGateway : AttestedVisualActionBridgeGateway {
    override fun isAvailable(): Boolean = false

    override fun execute(params: JsonObject): JsonObject = rejected("CAPABILITY_UNAVAILABLE")
}

class AndroidAttestedVisualActionGateway(
    private val accessibility: AccessibilityBridgeGateway,
    private val executionAllowed: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val screens: AttestedVisualScreenReader,
    actions: AttestedVisualActionExecutor? = null,
    private val imageDecoder: AttestedVisualImageDecoder =
        AttestedVisualImageDecoder(Base64.getDecoder()::decode),
    private val planner: ExplicitVisualReplayPlanner = ExplicitVisualReplayPlanner(nowMs),
) : AttestedVisualActionBridgeGateway {
    private val actions = actions ?: AttestedVisualActionExecutor { action, expectedPackage ->
        accessibility.execute(action.toAccessibilityCommand(expectedPackage))
    }

    override fun isAvailable(): Boolean =
        executionAllowed() && accessibility.isAvailable()

    override fun execute(params: JsonObject): JsonObject {
        if (!executionAllowed() || !accessibility.isAvailable()) {
            return rejected("CAPABILITY_UNAVAILABLE")
        }
        val request = try {
            parseRequest(params)
        } catch (_: Exception) {
            return rejected("INVALID_ARGUMENT")
        }
        val image = try {
            imageDecoder.decode(request.pngBase64)
        } catch (_: Exception) {
            return rejected("IMAGE_INVALID")
        }
        try {
            if (
                image.size != request.observation.png.sizeBytes ||
                !MessageDigest.isEqual(
                    sha256(image),
                    request.observation.png.sha256.hexBytes(),
                )
            ) {
                return rejected("IMAGE_UNVERIFIED")
            }
            val now = nowMs()
            if (
                now >= request.expiresAt.toEpochMilli() ||
                request.observation.expiresAt != request.expiresAt.toString() ||
                request.observation.capturedAt != request.capturedAt.toString()
            ) {
                return rejected("OBSERVATION_EXPIRED")
            }
            val beforeSnapshot = accessibility.snapshot(request.targetPackage).successOrNull()
                ?: return rejected("OBSERVATION_INVALID")
            if (beforeSnapshot.packageName != request.targetPackage) {
                return rejected("FOREGROUND_PACKAGE_CHANGED")
            }
            val beforeScreen = screens.current(
                request.targetPackage,
                request.capturedAt.toEpochMilli(),
            ) ?: return rejected("SCREEN_METADATA_CHANGED")
            val prepared = prepare(request, beforeScreen)
                ?: return rejected("VISUAL_PREFLIGHT_REJECTED")

            val execution = try {
                actions.execute(prepared.action, request.targetPackage)
            } catch (_: Throwable) {
                return unknown("ACTION_COMMIT_UNKNOWN")
            }
            val committed = when (execution) {
                is AccessibilityResult.Failure -> return rejected(
                    execution.error.code.name,
                )

                is AccessibilityResult.Success -> execution.value
            }
            val committedAt = nowMs()
            val afterSnapshot = accessibility.snapshot(request.targetPackage).successOrNull()
                ?: return unknown("POST_ACTION_VERIFICATION_FAILED")
            val afterScreen = screens.current(request.targetPackage, nowMs())
                ?: return unknown("POST_ACTION_VERIFICATION_FAILED")
            if (
                afterSnapshot.packageName != request.targetPackage ||
                afterSnapshot == beforeSnapshot ||
                !afterScreen.matches(beforeScreen, committedAt, nowMs())
            ) {
                return unknown("POST_ACTION_VERIFICATION_FAILED")
            }
            return buildJsonObject {
                put("commitStatus", "committed")
                put("actionCommits", 1)
                put("verified", true)
                put("route", committed.route.protocolName())
                put("screen", afterScreen.toEvidenceJson())
            }
        } finally {
            image.fill(0)
        }
    }

    private fun prepare(
        request: AttestedRequest,
        screen: VisualReplayScreen,
    ): dev.aiauto.android.automation.recording.replay.visual.PreparedVisualReplay? {
        if (
            screen.secureWindow ||
            screen.foregroundPackage != request.targetPackage ||
            screen.naturalWidth != request.observation.screen.width ||
            screen.naturalHeight != request.observation.screen.height ||
            screen.rotation != request.observation.screen.rotation ||
            screen.densityDpi <= 0
        ) {
            return null
        }
        val replayRequest = VisualReplayRequest(
            step = RecordedStep(
                id = request.candidate.id,
                recordedAtMs = request.capturedAt.toEpochMilli(),
                provenance = RecordingProvenance.VISUAL,
                action = request.action.recordedAction(
                    request.candidate.bounds,
                    request.observation.screen.width,
                    request.observation.screen.height,
                ),
                visualTarget = VisualTarget(
                    normalizedPoint = RecordedPoint(
                        request.candidate.point.x,
                        request.candidate.point.y,
                    ),
                    normalizedBounds = RecordedBounds(
                        request.candidate.bounds.left,
                        request.candidate.bounds.top,
                        request.candidate.bounds.right,
                        request.candidate.bounds.bottom,
                    ),
                    confidence = request.candidate.confidence,
                    source = RecordingProvenance.VISUAL,
                    observationId = request.observation.id,
                    imageSha256 = request.observation.png.sha256,
                ),
            ),
            environment = ScriptEnvironment(
                logicalWidth = request.observation.screen.width,
                logicalHeight = request.observation.screen.height,
                densityDpi = screen.densityDpi,
                rotation = request.observation.screen.rotation,
            ),
            targetPackages = setOf(request.targetPackage),
        )
        val lease = VisualReplayLease(
            observation = request.observation,
            candidates = listOf(request.candidate),
            recordedDensityDpi = screen.densityDpi,
            secureWindow = screen.secureWindow,
        )
        return when (val result = planner.prepare(replayRequest, lease, screen)) {
            is VisualReplayPreparationResult.Ready -> result.prepared
            is VisualReplayPreparationResult.Rejected -> null
        }
    }

    private fun parseRequest(params: JsonObject): AttestedRequest {
        params.requireExactKeys(
            setOf(
                "deviceSerial",
                "deviceFingerprint",
                "targetPackage",
                "hierarchySha256",
                "observation",
                "candidate",
                "action",
                "capturedAt",
                "expiresAt",
                "pngBase64",
            ),
        )
        val deviceSerial = params.string("deviceSerial")
        val deviceFingerprint = params.string("deviceFingerprint")
        val targetPackage = params.string("targetPackage")
        val hierarchySha256 = params.string("hierarchySha256")
        val capturedAt = Instant.parse(params.string("capturedAt"))
        val expiresAt = Instant.parse(params.string("expiresAt"))
        val pngBase64 = params.string("pngBase64")
        require(DEVICE_SERIAL.matches(deviceSerial))
        require(SHA256.matches(deviceFingerprint))
        require(PACKAGE_NAME.matches(targetPackage))
        require(SHA256.matches(hierarchySha256))
        require(expiresAt.isAfter(capturedAt))
        require(pngBase64.length in 12..MAX_IMAGE_BASE64_CHARS)

        val observationObject = params.getValue("observation").jsonObject
        val candidateObject = params.getValue("candidate").jsonObject
        val actionObject = params.getValue("action").jsonObject
        val observation = STRICT_JSON.decodeFromString<VisualObservation>(
            observationObject.toString(),
        )
        val candidate = STRICT_JSON.decodeFromString<VisualCandidate>(
            candidateObject.toString(),
        )
        val action = parseAction(actionObject)
        require(observation.schemaVersion == "visual-observation/1.0")
        require(UUID.matches(observation.id))
        require(observation.foregroundPackage == targetPackage)
        require(UUID.matches(candidate.id))
        require(candidate.observationId == observation.id)
        require(candidate.source == VisualCandidateSource.TEMPLATE)
        require(candidate.confidence >= MIN_CONFIDENCE)
        require(candidate.point.valid())
        require(candidate.bounds.validAndContains(candidate.point))
        require(observation.png.format == "png")
        require(observation.png.verification == "trusted-context")
        require(SHA256.matches(observation.png.sha256))
        require(observation.png.sizeBytes in 8..MAX_IMAGE_BYTES)
        return AttestedRequest(
            deviceSerial = deviceSerial,
            deviceFingerprint = deviceFingerprint,
            targetPackage = targetPackage,
            hierarchySha256 = hierarchySha256,
            observation = observation,
            candidate = candidate,
            action = action,
            capturedAt = capturedAt,
            expiresAt = expiresAt,
            pngBase64 = pngBase64,
        )
    }

    private fun parseAction(value: JsonObject): AttestedAction {
        val type = value.string("type")
        return when (type) {
            "tap" -> {
                value.requireExactKeys(setOf("type"))
                AttestedAction(type = type)
            }

            "long-click" -> {
                value.requireAllowedKeys(setOf("type"), setOf("durationMs"))
                AttestedAction(
                    type = type,
                    durationMs = value["durationMs"]?.jsonPrimitive?.intOrNull ?: 600,
                ).also { require(it.durationMs in 1..10_000) }
            }

            "swipe" -> {
                value.requireAllowedKeys(
                    setOf("type", "direction"),
                    setOf("durationMs"),
                )
                val direction = value.string("direction")
                require(direction in SWIPE_DIRECTIONS)
                AttestedAction(
                    type = type,
                    direction = direction,
                    durationMs = value["durationMs"]?.jsonPrimitive?.intOrNull ?: 400,
                ).also { require(it.durationMs in 1..10_000) }
            }

            else -> error("unsupported action")
        }
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.70
        private const val MAX_IMAGE_BYTES = 900 * 1024
        private const val MAX_IMAGE_BASE64_CHARS = 1_228_800
        private val DEVICE_SERIAL = Regex("^[A-Za-z0-9][A-Za-z0-9._:%+\\[\\]-]{0,254}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-" +
                "[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        private val PACKAGE_NAME =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val SWIPE_DIRECTIONS = setOf("up", "down", "left", "right")
        private val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            isLenient = false
        }

        fun production(context: Context): AndroidAttestedVisualActionGateway {
            val applicationContext = context.applicationContext
            return AndroidAttestedVisualActionGateway(
                accessibility = RuntimeAccessibilityBridgeGateway,
                executionAllowed = {
                    BuildConfig.DEBUG && isDisposableEmulator()
                },
                screens = AndroidAttestedVisualScreenReader(applicationContext),
                actions = AttestedVisualActionExecutor(
                    AndroidVisualReplayActionExecutor::execute,
                ),
            )
        }

        private fun isDisposableEmulator(): Boolean {
            val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
            val model = Build.MODEL.orEmpty().lowercase()
            val product = Build.PRODUCT.orEmpty().lowercase()
            return fingerprint.startsWith("generic") ||
                fingerprint.contains("emulator") ||
                model.contains("sdk_gphone") ||
                model.contains("emulator") ||
                product.contains("sdk_gphone") ||
                product.contains("emulator")
        }
    }
}

private class AndroidAttestedVisualScreenReader(context: Context) :
    AttestedVisualScreenReader {
    private val applicationContext = context.applicationContext

    override fun current(
        expectedPackage: String,
        observedAtMs: Long,
    ): VisualReplayScreen? {
        val windowManager =
            applicationContext.getSystemService(WindowManager::class.java) ?: return null
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
        val naturalWidth = if (rotation == 90 || rotation == 270) {
            bounds.height()
        } else {
            bounds.width()
        }
        val naturalHeight = if (rotation == 90 || rotation == 270) {
            bounds.width()
        } else {
            bounds.height()
        }
        return VisualReplayScreen(
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
            densityDpi = applicationContext.resources.configuration.densityDpi,
            rotation = rotation,
            windowBounds = PixelBounds(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
            ),
            systemBars = ReplayInsets(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
            ),
            foregroundPackage = expectedPackage,
            secureWindow = false,
            observedAtMs = observedAtMs,
        )
    }
}

private data class AttestedRequest(
    val deviceSerial: String,
    val deviceFingerprint: String,
    val targetPackage: String,
    val hierarchySha256: String,
    val observation: VisualObservation,
    val candidate: VisualCandidate,
    val action: AttestedAction,
    val capturedAt: Instant,
    val expiresAt: Instant,
    val pngBase64: String,
)

private data class AttestedAction(
    val type: String,
    val direction: String? = null,
    val durationMs: Int = 0,
) {
    fun recordedAction(
        bounds: NormalizedBounds,
        width: Int,
        height: Int,
    ): RecordedAction = when (type) {
        "tap" -> RecordedAction(type = "ui.tap", params = buildJsonObject {})
        "long-click" -> RecordedAction(
            type = "ui.longClick",
            params = buildJsonObject { put("durationMs", durationMs) },
        )

        "swipe" -> {
            val horizontalCenter = (bounds.left + bounds.right) / 2.0
            val verticalCenter = (bounds.top + bounds.bottom) / 2.0
            val start = when (direction) {
                "up" -> NormalizedPoint(horizontalCenter, bounds.top + bounds.height() * 0.8)
                "down" -> NormalizedPoint(horizontalCenter, bounds.top + bounds.height() * 0.2)
                "left" -> NormalizedPoint(bounds.left + bounds.width() * 0.8, verticalCenter)
                else -> NormalizedPoint(bounds.left + bounds.width() * 0.2, verticalCenter)
            }
            val end = when (direction) {
                "up" -> NormalizedPoint(horizontalCenter, bounds.top + bounds.height() * 0.2)
                "down" -> NormalizedPoint(horizontalCenter, bounds.top + bounds.height() * 0.8)
                "left" -> NormalizedPoint(bounds.left + bounds.width() * 0.2, verticalCenter)
                else -> NormalizedPoint(bounds.left + bounds.width() * 0.8, verticalCenter)
            }
            RecordedAction(
                type = "ui.swipe",
                params = buildJsonObject {
                    put("start", start.toPixelJson(width, height))
                    put("end", end.toPixelJson(width, height))
                    put("durationMs", durationMs)
                },
            )
        }

        else -> error("unsupported action")
    }
}

private fun rejected(code: String): JsonObject = buildJsonObject {
    put("commitStatus", "not_committed")
    put("actionCommits", 0)
    put("verified", false)
    put("errorCode", code)
}

private fun unknown(code: String): JsonObject = buildJsonObject {
    put("commitStatus", "unknown")
    put("actionCommits", JsonNull)
    put("verified", false)
    put("errorCode", code)
}

private fun JsonObject.requireExactKeys(required: Set<String>) {
    require(keys == required)
}

private fun JsonObject.requireAllowedKeys(
    required: Set<String>,
    optional: Set<String>,
) {
    require((required - keys).isEmpty())
    require((keys - required - optional).isEmpty())
}

private fun JsonObject.string(name: String): String =
    (getValue(name) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?: error("$name is not a string")

private fun NormalizedPoint.valid(): Boolean =
    x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0

private fun NormalizedBounds.validAndContains(point: NormalizedPoint): Boolean =
    left.isFinite() &&
        top.isFinite() &&
        right.isFinite() &&
        bottom.isFinite() &&
        left in 0.0..1.0 &&
        top in 0.0..1.0 &&
        right in 0.0..1.0 &&
        bottom in 0.0..1.0 &&
        right > left &&
        bottom > top &&
        point.x in left..right &&
        point.y in top..bottom

private fun NormalizedBounds.width(): Double = right - left

private fun NormalizedBounds.height(): Double = bottom - top

private fun NormalizedPoint.toPixelJson(width: Int, height: Int): JsonObject =
    buildJsonObject {
        put("x", (x * width).toInt().coerceIn(0, width - 1))
        put("y", (y * height).toInt().coerceIn(0, height - 1))
    }

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun sha256(value: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(value)

private fun <T> AccessibilityResult<T>.successOrNull(): T? =
    (this as? AccessibilityResult.Success)?.value

private fun VisualReplayScreen.matches(
    before: VisualReplayScreen,
    committedAtMs: Long,
    verifiedAtMs: Long,
): Boolean =
    foregroundPackage == before.foregroundPackage &&
        naturalWidth == before.naturalWidth &&
        naturalHeight == before.naturalHeight &&
        densityDpi == before.densityDpi &&
        rotation == before.rotation &&
        windowBounds == before.windowBounds &&
        systemBars == before.systemBars &&
        !secureWindow &&
        observedAtMs in committedAtMs..verifiedAtMs

private fun VisualReplayScreen.toEvidenceJson(): JsonObject = buildJsonObject {
    put("naturalWidth", naturalWidth)
    put("naturalHeight", naturalHeight)
    put("densityDpi", densityDpi)
    put("rotation", rotation)
    put(
        "window",
        buildJsonObject {
            put("left", windowBounds.left)
            put("top", windowBounds.top)
            put("right", windowBounds.right)
            put("bottom", windowBounds.bottom)
        },
    )
    put(
        "insets",
        buildJsonObject {
            put("left", systemBars.left)
            put("top", systemBars.top)
            put("right", systemBars.right)
            put("bottom", systemBars.bottom)
        },
    )
}

private fun dev.aiauto.android.accessibility.model.ActionRoute.protocolName(): String =
    when (this) {
        dev.aiauto.android.accessibility.model.ActionRoute.NODE_ACTION -> "nodeAction"
        dev.aiauto.android.accessibility.model.ActionRoute.NODE_GESTURE -> "nodeGesture"
        dev.aiauto.android.accessibility.model.ActionRoute.COORDINATE_GESTURE ->
            "coordinateGesture"
        dev.aiauto.android.accessibility.model.ActionRoute.SCREEN_GESTURE -> "screenGesture"
        dev.aiauto.android.accessibility.model.ActionRoute.GLOBAL_ACTION -> "globalAction"
    }
