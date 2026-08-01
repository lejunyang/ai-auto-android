package dev.aiauto.android.automation.recording.replay.visual

/**
 * 功能用途：管理 production recording replay 的当前运行视觉授权与一次性 step rebind。
 */

import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.VisualTarget
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class VisualReplayAuthorizationRequest(
    val runId: String,
    val scriptId: String,
    val scriptRevision: Long,
    val requestedAtMs: Long,
    val visualStepIds: Set<String>,
)

data class VisualReplayRunBinding(
    val runId: String,
    val scriptId: String,
    val scriptRevision: Long,
    val deviceSerial: String,
    val authorizedAtMs: Long,
    val expiresAtMs: Long,
    val visualStepIds: Set<String>,
)

data class VisualReplayRunRequest(
    val runId: String,
    val scriptId: String,
    val scriptRevision: Long,
    val startedAtMs: Long,
    val visualStepIds: Set<String>,
)

data class VisualReplayRebindRequest(
    val run: VisualReplayRunRequest,
    val stepId: String,
    val historicalTarget: VisualTarget,
    val environment: ScriptEnvironment,
    val targetPackages: Set<String>,
)

/**
 * provider 必须从当前授权运行生成 fresh observation；默认调用方没有该能力。
 */
fun interface VisualReplayAuthorizationProvider {
    fun authorize(request: VisualReplayAuthorizationRequest): VisualReplayRunAuthorization?
}

/**
 * 运行授权持有设备代次和 fresh observation provider，关闭时必须撤销所有未消费证据。
 */
interface VisualReplayRunAuthorization : AutoCloseable {
    val binding: VisualReplayRunBinding

    fun currentDeviceSerial(): String?

    fun acquire(request: VisualReplayRebindRequest): AuthorizedVisualReplayStepLease?

    fun verifyAfter(
        lease: AuthorizedVisualReplayStepLease,
        request: VisualReplayVerificationRequest,
    ): VisualReplayVerification?
}

/**
 * step lease 仅包含当前运行的脱敏元数据和候选，不包含图片字节或持久化入口。
 */
class AuthorizedVisualReplayStepLease(
    val runId: String,
    val scriptId: String,
    val scriptRevision: Long,
    val stepId: String,
    val deviceSerial: String,
    val targetPackage: String,
    val authorizedAtMs: Long,
    val expiresAtMs: Long,
    val visual: VisualReplayLease,
    val reboundTarget: VisualTarget,
    val screenBefore: VisualReplayScreen,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val consumedState = AtomicBoolean(false)
    private val closedState = AtomicBoolean(false)

    val consumed: Boolean
        get() = consumedState.get()

    val closed: Boolean
        get() = closedState.get()

    internal fun consume(): Boolean =
        !closedState.get() && consumedState.compareAndSet(false, true)

    override fun close() {
        if (closedState.compareAndSet(false, true)) {
            try {
                onClose()
            } catch (_: Exception) {
                // 撤销回调失败不能覆盖动作已提交或未知提交的保守结果。
            }
        }
    }
}

internal sealed interface VisualReplayRebindResult {
    data class Ready(
        val authorization: VisualReplayRunAuthorization,
        val lease: AuthorizedVisualReplayStepLease,
    ) : VisualReplayRebindResult

    data class Rejected(val code: ExplicitVisualReplayErrorCode) : VisualReplayRebindResult
}

/**
 * 注册表只允许单个 pending/active run，并在获取 step 前永久占用该 step 的一次机会。
 */
class VisualReplayAuthorizationRegistry(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    private var pending: PendingAuthorization? = null
    private var active: ActiveAuthorization? = null

    fun replace(
        request: VisualReplayAuthorizationRequest,
        authorization: VisualReplayRunAuthorization,
    ): Boolean {
        if (!request.valid() || !authorization.binding.validFor(request, nowMs())) {
            closeSafely(authorization)
            return false
        }
        val old = synchronized(lock) {
            val previous = pending
            pending = PendingAuthorization(request, authorization)
            previous
        }
        closeSafely(old?.authorization)
        return true
    }

    fun beginRun(request: VisualReplayRunRequest): Boolean {
        val now = nowMs()
        val selected = synchronized(lock) {
            val candidate = pending
            pending = null
            val previous = active
            active = null
            val accepted = candidate?.authorization?.binding?.validFor(request, now) == true
            if (accepted) {
                active = ActiveAuthorization(
                    run = request,
                    authorization = candidate.authorization,
                )
            }
            BeginRunSelection(previous, candidate, accepted)
        }
        closeSafely(selected.previous?.authorization)
        if (!selected.accepted) {
            closeSafely(selected.candidate?.authorization)
        }
        return selected.accepted
    }

    internal fun acquire(request: VisualReplayRebindRequest): VisualReplayRebindResult {
        val current = synchronized(lock) {
            val running = active
            if (
                running == null ||
                running.run != request.run ||
                request.stepId !in running.run.visualStepIds ||
                !running.consumedStepIds.add(request.stepId)
            ) {
                null
            } else {
                running
            }
        } ?: return rejected(ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED)

        val authorization = current.authorization
        val now = nowMs()
        if (!authorization.binding.validFor(request.run, now)) {
            return rejected(ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED)
        }
        if (!authorization.serialMatchesBinding()) {
            return rejected(ExplicitVisualReplayErrorCode.DEVICE_SERIAL_CHANGED)
        }
        val lease = try {
            authorization.acquire(request)
        } catch (_: Throwable) {
            null
        } ?: return rejected(ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED)

        val rejection = lease.validateFor(
            request = request,
            binding = authorization.binding,
            now = now,
        )
        if (rejection != null || !lease.consume()) {
            closeSafely(lease)
            return rejected(rejection ?: ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED)
        }
        return VisualReplayRebindResult.Ready(authorization, lease)
    }

    internal fun validateBeforeCommit(
        authorization: VisualReplayRunAuthorization,
        lease: AuthorizedVisualReplayStepLease,
        request: VisualReplayRebindRequest,
    ): ExplicitVisualReplayErrorCode? {
        val now = nowMs()
        if (!authorization.binding.validFor(request.run, now)) {
            return ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED
        }
        if (!authorization.serialMatchesBinding()) {
            return ExplicitVisualReplayErrorCode.DEVICE_SERIAL_CHANGED
        }
        return lease.validateFor(request, authorization.binding, now)
    }

    fun endRun() {
        val removed = synchronized(lock) {
            val running = active
            active = null
            running
        }
        closeSafely(removed?.authorization)
    }

    override fun close() {
        val removed = synchronized(lock) {
            val values = listOfNotNull(pending?.authorization, active?.authorization)
            pending = null
            active = null
            values
        }
        removed.forEach(::closeSafely)
    }

    private data class PendingAuthorization(
        val request: VisualReplayAuthorizationRequest,
        val authorization: VisualReplayRunAuthorization,
    )

    private data class BeginRunSelection(
        val previous: ActiveAuthorization?,
        val candidate: PendingAuthorization?,
        val accepted: Boolean,
    )

    private data class ActiveAuthorization(
        val run: VisualReplayRunRequest,
        val authorization: VisualReplayRunAuthorization,
        val consumedStepIds: MutableSet<String> = mutableSetOf(),
    )
}

private fun VisualReplayAuthorizationRequest.valid(): Boolean =
    RUN_ID_PATTERN.matches(runId) &&
        SCRIPT_ID_PATTERN.matches(scriptId) &&
        scriptRevision > 0 &&
        requestedAtMs > 0 &&
        visualStepIds.isNotEmpty() &&
        visualStepIds.size <= MAX_VISUAL_STEPS &&
        visualStepIds.all(SCRIPT_ID_PATTERN::matches)

private fun VisualReplayRunBinding.validFor(
    request: VisualReplayAuthorizationRequest,
    now: Long,
): Boolean =
    runId == request.runId &&
        scriptId == request.scriptId &&
        scriptRevision == request.scriptRevision &&
        visualStepIds == request.visualStepIds &&
        DEVICE_SERIAL_PATTERN.matches(deviceSerial) &&
        authorizedAtMs in request.requestedAtMs..now &&
        expiresAtMs > now &&
        expiresAtMs - authorizedAtMs <= MAX_RUN_AUTHORIZATION_TTL_MS

private fun VisualReplayRunBinding.validFor(
    request: VisualReplayRunRequest,
    now: Long,
): Boolean =
    runId == request.runId &&
        scriptId == request.scriptId &&
        scriptRevision == request.scriptRevision &&
        visualStepIds == request.visualStepIds &&
        authorizedAtMs >= request.startedAtMs &&
        authorizedAtMs <= now &&
        request.startedAtMs <= now &&
        expiresAtMs > now

private fun AuthorizedVisualReplayStepLease.validateFor(
    request: VisualReplayRebindRequest,
    binding: VisualReplayRunBinding,
    now: Long,
): ExplicitVisualReplayErrorCode? {
    if (
        runId != binding.runId ||
        scriptId != request.run.scriptId ||
        scriptRevision != request.run.scriptRevision ||
        stepId != request.stepId
    ) {
        return ExplicitVisualReplayErrorCode.VISUAL_REBIND_REQUIRED
    }
    if (deviceSerial != binding.deviceSerial) {
        return ExplicitVisualReplayErrorCode.DEVICE_SERIAL_CHANGED
    }
    if (
        targetPackage !in request.targetPackages ||
        visual.observation.foregroundPackage != targetPackage ||
        screenBefore.foregroundPackage != targetPackage
    ) {
        return ExplicitVisualReplayErrorCode.FOREGROUND_PACKAGE_CHANGED
    }
    if (
        authorizedAtMs < binding.authorizedAtMs ||
        authorizedAtMs > now ||
        expiresAtMs <= now ||
        expiresAtMs - authorizedAtMs > MAX_STEP_AUTHORIZATION_TTL_MS
    ) {
        return ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED
    }
    val capturedAtMs = runCatching {
        Instant.parse(visual.observation.capturedAt).toEpochMilli()
    }.getOrNull() ?: return ExplicitVisualReplayErrorCode.TARGET_MISMATCH
    val observationExpiresAtMs = runCatching {
        Instant.parse(visual.observation.expiresAt).toEpochMilli()
    }.getOrNull() ?: return ExplicitVisualReplayErrorCode.TARGET_MISMATCH
    if (
        capturedAtMs !in authorizedAtMs..now ||
        observationExpiresAtMs <= now ||
        screenBefore.observedAtMs !in capturedAtMs..now
    ) {
        return ExplicitVisualReplayErrorCode.OBSERVATION_EXPIRED
    }
    if (
        reboundTarget.observationId == request.historicalTarget.observationId ||
        reboundTarget.observationId != visual.observation.id ||
        reboundTarget.imageSha256 != visual.observation.png.sha256
    ) {
        return ExplicitVisualReplayErrorCode.TARGET_MISMATCH
    }
    return null
}

private fun rejected(code: ExplicitVisualReplayErrorCode) =
    VisualReplayRebindResult.Rejected(code)

private fun VisualReplayRunAuthorization.serialMatchesBinding(): Boolean =
    try {
        currentDeviceSerial() == binding.deviceSerial
    } catch (_: Throwable) {
        false
    }

private fun closeSafely(value: AutoCloseable?) {
    try {
        value?.close()
    } catch (_: Exception) {
        // 清理失败不能让同一授权重新进入后续动作路径。
    }
}

private const val MAX_VISUAL_STEPS = 10_000
private const val MAX_RUN_AUTHORIZATION_TTL_MS = 60_000L
private const val MAX_STEP_AUTHORIZATION_TTL_MS = 15_000L
private val RUN_ID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val SCRIPT_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val DEVICE_SERIAL_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
