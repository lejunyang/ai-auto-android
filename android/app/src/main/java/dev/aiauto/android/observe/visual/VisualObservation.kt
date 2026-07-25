package dev.aiauto.android.observe.visual

/**
 * 功能用途：绑定短生命周期视觉 observation、可信图片租约和只读候选映射，
 * 所有失败路径保持动作提交数为零并撤销图片。
 */

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.hypot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 视觉服务稳定失败码，不携带 provider 或图片敏感细节。 */
enum class VisualErrorCode {
    OBSERVATION_NOT_FOUND,
    OBSERVATION_ID_DRIFT,
    OBSERVATION_INVALID,
    OBSERVATION_EXPIRED,
    FOREGROUND_PACKAGE_CHANGED,
    IMAGE_UNVERIFIED,
    IMAGE_EMPTY,
    IMAGE_INVALID,
    IMAGE_BUDGET_EXCEEDED,
    SECURE_WINDOW,
    SCREEN_INVALID,
    CROP_INVALID,
    CANDIDATE_NOT_FOUND,
    CANDIDATE_INVALID,
    CANDIDATE_LOW_CONFIDENCE,
    CANDIDATE_AMBIGUOUS,
    PROVIDER_FAILED,
}

/** 视觉调用结果显式携带零动作提交语义。 */
sealed interface VisualResult<out T> {
    data class Success<T>(val value: T) : VisualResult<T>

    data class Failure(
        val code: VisualErrorCode,
        val candidates: List<VisualCandidate> = emptyList(),
        val actionCommitCount: Int = 0,
    ) : VisualResult<Nothing>
}

@Serializable
data class VisualScreen(
    val width: Int,
    val height: Int,
    val rotation: Int,
)

@Serializable
data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class NormalizedPoint(
    val x: Double,
    val y: Double,
)

@Serializable
data class NormalizedBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

@Serializable
data class VisualHierarchyNode(
    val role: String,
    val label: String? = null,
    val bounds: NormalizedBounds,
    val sensitive: Boolean = false,
    val children: List<VisualHierarchyNode> = emptyList(),
)

@Serializable
data class VisualPngMetadata(
    val format: String = "png",
    val sizeBytes: Int,
    val sha256: String,
    val verification: String = "trusted-context",
)

@Serializable
data class VisualObservation(
    val schemaVersion: String = "visual-observation/1.0",
    val id: String,
    val foregroundPackage: String,
    val screen: VisualScreen,
    val crop: PixelBounds,
    val capturedAt: String,
    val expiresAt: String,
    val png: VisualPngMetadata,
    val hierarchy: List<VisualHierarchyNode>,
)

data class VisualObservationCapture(
    val id: String,
    val foregroundPackage: String,
    val screen: VisualScreen,
    val crop: PixelBounds,
    val capturedAt: String,
    val expiresAt: String,
    val pngBytes: ByteArray,
    val hierarchy: List<VisualHierarchyNode>,
    val secureWindow: Boolean = false,
)

data class VisualVerificationContext(
    val observationId: String,
    val pngSizeBytes: Int,
    val pngSha256: String,
)

/** 可信校验器必须来自调用进程的独立上下文，self-hash 不能替代该判断。 */
fun interface TrustedVisualImageVerifier {
    fun verify(image: ByteArray, context: VisualVerificationContext): Boolean
}

private data class VisualObservationEntry(
    val observation: VisualObservation,
    val image: ByteArray,
)

/** 内存 observation store 只借出副本，并在 revoke/close 时清零持有图片。 */
class VisualObservationStore(
    private val trustedVerifier: TrustedVisualImageVerifier?,
    private val maxPngBytes: Int = DEFAULT_MAX_PNG_BYTES,
) : AutoCloseable {
    private val lock = Any()
    private val entries = mutableMapOf<String, VisualObservationEntry>()
    private var closed = false

    fun register(capture: VisualObservationCapture): VisualResult<VisualObservation> {
        try {
            validateCapture(capture)?.let { return failure(it) }
            val hash = sha256(capture.pngBytes)
            val verifierBytes = capture.pngBytes.copyOf()
            val verified = try {
                trustedVerifier?.verify(
                    verifierBytes,
                    VisualVerificationContext(capture.id, capture.pngBytes.size, hash),
                ) == true
            } catch (_: Throwable) {
                false
            } finally {
                verifierBytes.fill(0)
            }
            if (!verified) return failure(VisualErrorCode.IMAGE_UNVERIFIED)

            val observation = VisualObservation(
                id = capture.id,
                foregroundPackage = capture.foregroundPackage,
                screen = capture.screen,
                crop = capture.crop,
                capturedAt = Instant.parse(capture.capturedAt).toString(),
                expiresAt = Instant.parse(capture.expiresAt).toString(),
                png = VisualPngMetadata(
                    sizeBytes = capture.pngBytes.size,
                    sha256 = hash,
                ),
                hierarchy = redactHierarchy(capture.hierarchy),
            )
            val ownedImage = capture.pngBytes.copyOf()
            synchronized(lock) {
                if (closed) {
                    ownedImage.fill(0)
                    return failure(VisualErrorCode.OBSERVATION_NOT_FOUND)
                }
                val existing = entries[capture.id]
                if (existing != null) {
                    ownedImage.fill(0)
                    return if (existing.observation == observation) {
                        VisualResult.Success(existing.observation.copyDeep())
                    } else {
                        failure(VisualErrorCode.OBSERVATION_ID_DRIFT)
                    }
                }
                entries[capture.id] = VisualObservationEntry(observation, ownedImage)
            }
            return VisualResult.Success(observation.copyDeep())
        } finally {
            capture.pngBytes.fill(0)
        }
    }

    fun lookup(id: String): VisualObservation? = synchronized(lock) {
        if (closed) null else entries[id]?.observation?.copyDeep()
    }

    fun <T> withImage(id: String, use: (ByteArray) -> T): VisualResult<T> {
        val borrowed = synchronized(lock) {
            if (closed) null else entries[id]?.image?.copyOf()
        } ?: return failure(VisualErrorCode.OBSERVATION_NOT_FOUND)
        return try {
            VisualResult.Success(use(borrowed))
        } catch (_: Throwable) {
            failure(VisualErrorCode.PROVIDER_FAILED)
        } finally {
            borrowed.fill(0)
        }
    }

    fun revoke(id: String): Boolean {
        val removed = synchronized(lock) { entries.remove(id) } ?: return false
        removed.image.fill(0)
        return true
    }

    override fun close() {
        val removed = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.toList().also { entries.clear() }
        }
        removed.forEach { it.image.fill(0) }
    }

    private fun validateCapture(capture: VisualObservationCapture): VisualErrorCode? {
        if (capture.secureWindow) return VisualErrorCode.SECURE_WINDOW
        if (capture.pngBytes.isEmpty()) return VisualErrorCode.IMAGE_EMPTY
        if (capture.pngBytes.size > maxPngBytes) return VisualErrorCode.IMAGE_BUDGET_EXCEEDED
        if (!capture.pngBytes.startsWith(PNG_SIGNATURE)) return VisualErrorCode.IMAGE_INVALID
        if (!isUuid(capture.id) || !PACKAGE_PATTERN.matches(capture.foregroundPackage)) {
            return VisualErrorCode.OBSERVATION_INVALID
        }
        val captured = runCatching { Instant.parse(capture.capturedAt) }.getOrNull()
        val expires = runCatching { Instant.parse(capture.expiresAt) }.getOrNull()
        if (captured == null || expires == null || !expires.isAfter(captured)) {
            return VisualErrorCode.OBSERVATION_INVALID
        }
        if (
            capture.screen.width !in 1..MAX_SCREEN_SIZE ||
            capture.screen.height !in 1..MAX_SCREEN_SIZE ||
            capture.screen.rotation !in ROTATIONS
        ) {
            return VisualErrorCode.SCREEN_INVALID
        }
        val (planeWidth, planeHeight) = capturePlane(capture.screen)
        if (
            capture.crop.left < 0 ||
            capture.crop.top < 0 ||
            capture.crop.right <= capture.crop.left ||
            capture.crop.bottom <= capture.crop.top ||
            capture.crop.right > planeWidth ||
            capture.crop.bottom > planeHeight
        ) {
            return VisualErrorCode.CROP_INVALID
        }
        if (capture.hierarchy.size > 256) {
            return VisualErrorCode.OBSERVATION_INVALID
        }
        if (capture.hierarchy.any { !it.isValid(0) }) {
            return VisualErrorCode.OBSERVATION_INVALID
        }
        return null
    }

    private companion object {
        const val DEFAULT_MAX_PNG_BYTES = 900 * 1024
        const val MAX_SCREEN_SIZE = 32_768
        val ROTATIONS = setOf(0, 90, 180, 270)
        val PACKAGE_PATTERN =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
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
    }
}

@Serializable
enum class VisualCandidateSource {
    @SerialName("ocr")
    OCR,

    @SerialName("template")
    TEMPLATE,

    @SerialName("model")
    MODEL,

    @SerialName("manual")
    MANUAL,
}

data class RawVisualCandidate(
    val id: String,
    val source: VisualCandidateSource,
    val point: NormalizedPoint,
    val bounds: NormalizedBounds,
    val confidence: Double,
)

@Serializable
data class VisualCandidate(
    val id: String,
    val observationId: String,
    val source: VisualCandidateSource,
    val point: NormalizedPoint,
    val bounds: NormalizedBounds,
    val confidence: Double,
)

data class VisualProposalRequest(
    val observationId: String,
    val expectedPackage: String,
    val now: String,
)

data class VisualProposalResponse(
    val candidates: List<VisualCandidate>,
    val actionCommitCount: Int = 0,
)

/** 候选 provider 只能读取 observation 与当前调用期图片副本。 */
fun interface VisualCandidateProvider {
    fun propose(
        observation: VisualObservation,
        image: ByteArray,
    ): List<RawVisualCandidate>
}

/** 视觉候选服务只有 propose API，不持有任何动作路由。 */
class VisualProposalService(
    private val observations: VisualObservationStore,
    private val provider: VisualCandidateProvider,
    private val minimumConfidence: Double = 0.70,
    private val ambiguityDelta: Double = 0.02,
    private val nearbyDistance: Double = 0.05,
) {
    fun propose(request: VisualProposalRequest): VisualResult<VisualProposalResponse> {
        fun fail(code: VisualErrorCode): VisualResult.Failure {
            observations.revoke(request.observationId)
            return failure(code)
        }

        if (!isUuid(request.observationId)) return fail(VisualErrorCode.OBSERVATION_INVALID)
        val observation = observations.lookup(request.observationId)
            ?: return fail(VisualErrorCode.OBSERVATION_NOT_FOUND)
        val now = runCatching { Instant.parse(request.now) }.getOrNull()
            ?: return fail(VisualErrorCode.OBSERVATION_INVALID)
        if (!now.isBefore(Instant.parse(observation.expiresAt))) {
            return fail(VisualErrorCode.OBSERVATION_EXPIRED)
        }
        if (observation.foregroundPackage != request.expectedPackage) {
            return fail(VisualErrorCode.FOREGROUND_PACKAGE_CHANGED)
        }

        val provided = when (
            val result = observations.withImage(request.observationId) { image ->
                provider.propose(observation, image)
            }
        ) {
            is VisualResult.Success -> result.value
            is VisualResult.Failure -> return fail(result.code)
        }
        if (provided.isEmpty()) return fail(VisualErrorCode.CANDIDATE_NOT_FOUND)
        if (provided.size > 64 || provided.any { !it.isValid() }) {
            return fail(VisualErrorCode.CANDIDATE_INVALID)
        }
        val ranked = provided.sortedByDescending { it.confidence }
        if (ranked.first().confidence < minimumConfidence) {
            return fail(VisualErrorCode.CANDIDATE_LOW_CONFIDENCE)
        }
        if (
            ranked.size > 1 &&
            ranked[0].source != VisualCandidateSource.MANUAL &&
            ranked[1].source != VisualCandidateSource.MANUAL &&
            ranked[0].confidence - ranked[1].confidence <= ambiguityDelta &&
            hypot(
                ranked[0].point.x - ranked[1].point.x,
                ranked[0].point.y - ranked[1].point.y,
            ) <= nearbyDistance
        ) {
            return fail(VisualErrorCode.CANDIDATE_AMBIGUOUS)
        }

        return VisualResult.Success(
            VisualProposalResponse(
                candidates = provided.map { candidate ->
                    val mapped = mapCandidate(observation, candidate)
                    VisualCandidate(
                        id = candidate.id,
                        observationId = observation.id,
                        source = candidate.source,
                        point = mapped.first,
                        bounds = mapped.second,
                        confidence = candidate.confidence,
                    )
                },
            ),
        )
    }
}

@Serializable
data class VisualProposalBundle(
    val observation: VisualObservation,
    val candidates: List<VisualCandidate>,
    val actionCommitCount: Int,
)

/** 严格 JSON 契约拒绝未知字段，且结构中不存在原图、路径或 Base64 字段。 */
object VisualJson {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    fun encodeBundle(bundle: VisualProposalBundle): String {
        require(bundle.actionCommitCount == 0)
        return json.encodeToString(bundle)
    }

    fun decodeBundle(value: String): VisualProposalBundle =
        json.decodeFromString<VisualProposalBundle>(value).also {
            require(it.actionCommitCount == 0)
        }
}

private fun mapCandidate(
    observation: VisualObservation,
    candidate: RawVisualCandidate,
): Pair<NormalizedPoint, NormalizedBounds> {
    fun map(point: NormalizedPoint): NormalizedPoint {
        val captureX =
            observation.crop.left + point.x * (observation.crop.right - observation.crop.left)
        val captureY =
            observation.crop.top + point.y * (observation.crop.bottom - observation.crop.top)
        return when (observation.screen.rotation) {
            90 -> NormalizedPoint(
                x = captureY / observation.screen.width,
                y = 1.0 - captureX / observation.screen.height,
            )

            180 -> NormalizedPoint(
                x = 1.0 - captureX / observation.screen.width,
                y = 1.0 - captureY / observation.screen.height,
            )

            270 -> NormalizedPoint(
                x = 1.0 - captureY / observation.screen.width,
                y = captureX / observation.screen.height,
            )

            else -> NormalizedPoint(
                x = captureX / observation.screen.width,
                y = captureY / observation.screen.height,
            )
        }
    }

    val point = map(candidate.point)
    val corners = listOf(
        map(NormalizedPoint(candidate.bounds.left, candidate.bounds.top)),
        map(NormalizedPoint(candidate.bounds.right, candidate.bounds.top)),
        map(NormalizedPoint(candidate.bounds.left, candidate.bounds.bottom)),
        map(NormalizedPoint(candidate.bounds.right, candidate.bounds.bottom)),
    )
    return point to NormalizedBounds(
        left = corners.minOf { it.x },
        top = corners.minOf { it.y },
        right = corners.maxOf { it.x },
        bottom = corners.maxOf { it.y },
    )
}

private fun RawVisualCandidate.isValid(): Boolean =
    isUuid(id) &&
        point.isValid() &&
        bounds.isValid() &&
        confidence.isFinite() &&
        confidence in 0.0..1.0

private fun VisualHierarchyNode.isValid(depth: Int): Boolean =
    depth <= 64 &&
        role.isNotEmpty() &&
        role.length <= 128 &&
        (label?.length ?: 0) <= 256 &&
        bounds.isValid() &&
        children.size <= 256 &&
        children.all { it.isValid(depth + 1) }

private fun NormalizedPoint.isValid(): Boolean =
    x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0

private fun NormalizedBounds.isValid(): Boolean =
    left.isFinite() &&
        top.isFinite() &&
        right.isFinite() &&
        bottom.isFinite() &&
        left in 0.0..1.0 &&
        top in 0.0..1.0 &&
        right > left &&
        right <= 1.0 &&
        bottom > top &&
        bottom <= 1.0

private fun redactHierarchy(
    nodes: List<VisualHierarchyNode>,
    inheritedSensitive: Boolean = false,
): List<VisualHierarchyNode> = nodes.map { node ->
    val sensitive = inheritedSensitive || node.sensitive
    node.copy(
        label = if (sensitive) null else node.label,
        sensitive = sensitive,
        children = redactHierarchy(node.children, sensitive),
    )
}

private fun VisualObservation.copyDeep(): VisualObservation =
    copy(hierarchy = hierarchy.map { it.copyDeep() })

private fun VisualHierarchyNode.copyDeep(): VisualHierarchyNode =
    copy(children = children.map { it.copyDeep() })

private fun capturePlane(screen: VisualScreen): Pair<Int, Int> =
    if (screen.rotation == 90 || screen.rotation == 270) {
        screen.height to screen.width
    } else {
        screen.width to screen.height
    }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

private val UUID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

private fun failure(code: VisualErrorCode): VisualResult.Failure =
    VisualResult.Failure(code = code)
