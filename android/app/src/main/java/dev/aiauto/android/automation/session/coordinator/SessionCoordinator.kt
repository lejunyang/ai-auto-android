package dev.aiauto.android.automation.session.coordinator

/**
 * 功能用途：会话运行时的唯一 owner，负责创建/启动/停止 AutomationSessionEngine。
 *
 * 架构边界：
 * - SessionCoordinator 是唯一 owner：创建并启动 engine，暴露 StateFlow 供订阅。
 * - 所有操作（pause/resume/stop）走 command 端口，UI/通知/overlay 不直接碰 engine。
 * - 终态时写入 SessionHistoryStore（Activity 销毁不影响历史落盘）。
 * - Activity/ViewModel 只取消自己的 collection Job，不停止会话（显式用户 stop 才停）。
 * - 进程恢复时：仅当存在活跃 lease marker 才判断陈旧会话并标记 RecoveredStopped；否则保持 Idle。
 *
 * 本类为纯 Kotlin，不依赖 Android Context，便于单测。
 * Android 特定的前台服务/通知/悬浮窗由 AutomationSessionService 作为薄壳适配。
 */

import dev.aiauto.android.automation.session.AutomationSessionEngine
import dev.aiauto.android.automation.session.AutomationSessionState
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.automation.session.SessionRequest
import dev.aiauto.android.automation.session.history.SessionHistoryRecord
import dev.aiauto.android.automation.session.history.SessionHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 会话协调器命令端口。
 * 所有 UI/通知/悬浮窗操作通过此端口，不直接持有 engine 引用。
 */
interface SessionCommandPort {
    /** 启动新会话；非阻塞，立即返回是否被接受（单会话约束时返回 false）。 */
    fun start(request: SessionRequest): Boolean

    /** 暂停当前运行中的会话。 */
    fun pause()

    /** 继续已暂停的会话。 */
    fun resume()

    /** 停止当前会话（用户显式操作）。 */
    fun stop()

    /**
     * 强制停止当前会话（Service 意外销毁/系统杀服务时调用）。
     * 立即设置 Stopped 终态并写历史，不等待 engine 回调。
     * 与正常 stop() 的区别：forceStop 是同步强制终止，用于平台约束下的安全停机。
     */
    fun forceStop(reason: String)

    /** 确认待处理动作（如无障碍权限确认、危险操作确认）。 */
    fun confirm(confirmationId: Int, approved: Boolean)

    /** 当前是否有活跃会话。 */
    val isActive: Boolean
}

/**
 * 会话协调器状态。
 * Active 不暴露 engine 引用，外部只能通过 command 端口操作。
 */
sealed interface CoordinatorState {
    data object Idle : CoordinatorState
    data object Active : CoordinatorState
    data object RecoveredStopped : CoordinatorState
}

/** 历史写入诊断：成功或失败（含安全错误码，不泄露敏感内容）。 */
sealed interface HistoryWriteDiagnostic {
    data object Success : HistoryWriteDiagnostic
    data class Failed(val errorCode: String) : HistoryWriteDiagnostic
}

/**
 * 会话运行时唯一 owner。
 *
 * @param engineFactory 创建 engine 的工厂；由 Android 层注入。
 * @param historyStore 会话历史持久化；终态时写入，为 null 时跳过。
 * @param scope 协程作用域；应为应用级或 Service 级，不与 Activity 生命周期绑定。
 * @param clock 时间源，注入便于测试。
 * @param idProvider ID 生成器，注入便于测试。
 * @param historyWriteScope 历史写入的协程作用域，应使用 IO dispatcher。
 */
class SessionCoordinator(
    private val engineFactory: () -> AutomationSessionEngine,
    private val historyStore: SessionHistoryStore?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
    private val historyWriteScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionCommandPort {

    private val _coordinatorState = MutableStateFlow<CoordinatorState>(CoordinatorState.Idle)
    val coordinatorState: StateFlow<CoordinatorState> = _coordinatorState.asStateFlow()

    private val _sessionState = MutableStateFlow(AutomationSessionState())
    val sessionState: StateFlow<AutomationSessionState> = _sessionState.asStateFlow()

    private val _historyWriteDiagnostic = MutableStateFlow<HistoryWriteDiagnostic?>(null)
    val historyWriteDiagnostic: StateFlow<HistoryWriteDiagnostic?> = _historyWriteDiagnostic.asStateFlow()

    // engine 引用私有，外部不可直接访问。
    private var activeEngine: AutomationSessionEngine? = null
    private var stateCollectionJob: Job? = null
    private var sessionJob: Job? = null
    private var stopTimeoutJob: Job? = null
    private var currentRequest: SessionRequest? = null
    private var startTimeMs: Long = 0L
    private var historyWritten = false

    // 单会话互斥锁：保证 start 的检查-设置是原子的，防止并发请求同时通过检查。
    private val startLock = Any()

    // stop 后等待终态的超时时间：engine 不发 Stopped 时强制清理。
    private val stopTimeoutMs: Long = 5_000L

    override val isActive: Boolean
        get() = _coordinatorState.value is CoordinatorState.Active

    /**
     * 启动新会话。非阻塞：立即返回是否被接受。
     * engine.start() 是挂起的长期 loop，由 scope.launch 异步执行。
     * 单会话约束：已有会话运行时返回 false。
     * 使用 Mutex 保证检查-设置原子性，防止并发请求同时通过。
     */
    override fun start(request: SessionRequest): Boolean {
        // close() 后 scope 已取消，拒绝启动新会话。
        if (!scope.isActive) return false
        // 使用 synchronized 保证检查-设置原子性，避免 runBlocking 导致的测试死锁。
        val engineToStart = synchronized(startLock) {
            if (isActive) return@synchronized null
            historyWritten = false
            _historyWriteDiagnostic.value = null
            val engine = try {
                engineFactory()
            } catch (_: Exception) {
                return@synchronized null
            }
            activeEngine = engine
            currentRequest = request
            startTimeMs = clock()
            _coordinatorState.value = CoordinatorState.Active
            engine
        } ?: return false

        // 订阅 engine 状态，同步到外部 StateFlow，并在终态时写历史+清理。
        stateCollectionJob?.cancel()
        stateCollectionJob = scope.launch {
            engineToStart.state.collectLatest { state ->
                _sessionState.value = state
                if (state.phase in TERMINAL_PHASES) {
                    writeHistoryIfNeeded(state)
                    cleanupAfterTerminal()
                }
            }
        }

        // 异步启动 engine。sessionJob 完成后检查：
        // 如果 engine.start 正常返回但没有发终态，视为异常结束，强制清理。
        sessionJob = scope.launch {
            try {
                engineToStart.start(request)
            } catch (_: Exception) {
                // 启动失败：清理状态。
                cleanupAfterTerminal()
                return@launch
            }
            // engine.start 正常返回但可能没有发终态（不合约完成）。
            // 如果此时仍 Active，视为异常结束，强制清理。
            if (isActive) {
                _sessionState.value = AutomationSessionState(phase = SessionPhase.Stopped)
                writeHistoryIfNeeded(_sessionState.value)
                cleanupAfterTerminal()
            }
        }

        return true
    }

    override fun pause() {
        activeEngine?.pause()
    }

    override fun resume() {
        activeEngine?.resume()
    }

    override fun stop() {
        val engine = activeEngine ?: return
        engine.stop()
        // stop 是异步的，engine 可能不发 Stopped 终态。
        // 启动超时任务：如果 stopTimeoutMs 内仍未收到终态，强制清理。
        stopTimeoutJob?.cancel()
        stopTimeoutJob = scope.launch {
            withTimeoutOrNull(stopTimeoutMs) {
                // 等待终态，collectLatest 在收到终态时会触发 cleanup。
                // 这里只是等待超时，实际清理由 state collector 或超时后强制执行。
                kotlinx.coroutines.delay(stopTimeoutMs)
            }
            // 超时后如果仍 Active，强制清理。
            if (isActive) {
                _sessionState.value = AutomationSessionState(phase = SessionPhase.Stopped)
                writeHistoryIfNeeded(_sessionState.value)
                cleanupAfterTerminal()
            }
        }
    }

    override fun forceStop(reason: String) {
        if (!isActive) return
        // 强制停止：立即设置 Stopped 终态并写历史，不等待 engine 回调。
        // 用于 Service 意外销毁/系统杀服务时的安全停机。
        try {
            activeEngine?.stop()
        } catch (_: Exception) {
            // 忽略停止时的异常。
        }
        stopTimeoutJob?.cancel()
        stopTimeoutJob = null
        _sessionState.value = AutomationSessionState(phase = SessionPhase.Stopped)
        writeHistoryIfNeeded(_sessionState.value)
        cleanupAfterTerminal()
    }

    override fun confirm(confirmationId: Int, approved: Boolean) {
        activeEngine?.confirmPendingAction(confirmationId, approved)
    }

    /**
     * 进程恢复时调用。
     * 仅当当前为 Active（说明有陈旧会话残留）才 stop engine 并标记 RecoveredStopped；
     * 否则保持 Idle，不产生虚假的恢复状态。
     * 生产环境应结合持久化的 active lease marker 判断；本类默认以当前内存状态为准。
     */
    fun handleProcessRecovery() {
        if (!isActive) return
        try {
            activeEngine?.stop()
        } catch (_: Exception) {
            // 清理失败不影响状态重置。
        }
        cleanupAfterTerminal()
        _coordinatorState.value = CoordinatorState.RecoveredStopped
    }

    /**
     * 关闭协调器，取消所有协程作用域，防止泄漏。
     * 由 Service onDestroy 或应用清理时调用。
     * 关闭后 coordinator 不可再用，需重新创建。
     */
    fun close() {
        try {
            activeEngine?.stop()
        } catch (_: Exception) {
            // 忽略关闭时的异常。
        }
        cleanupAfterTerminal()
        stopTimeoutJob?.cancel()
        stopTimeoutJob = null
        scope.cancel()
        historyWriteScope.cancel()
    }

    /** 终态后清理：取消状态收集和会话 job，重置 coordinator state。 */
    private fun cleanupAfterTerminal() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        sessionJob?.cancel()
        sessionJob = null
        currentRequest = null
        activeEngine = null
        _coordinatorState.value = CoordinatorState.Idle
    }

    /** 终态时写入历史。只写一次，由 historyWritten 标志保护。在 IO dispatcher 执行。 */
    private fun writeHistoryIfNeeded(state: AutomationSessionState) {
        if (historyWritten) return
        val request = currentRequest ?: return
        if (state.phase !in TERMINAL_PHASES) return

        historyWritten = true
        val endTimeMs = clock()

        historyWriteScope.launch {
            val result = runCatching {
                // 安全策略：不保存用户输入的原始 task 描述。
                // 自由文本可能包含密码/验证码/API key，无法可靠识别任意自由文本中的秘密。
                // 使用固定摘要「自动化任务」，记录页显示摘要 + 目标 App + 时间 + 状态。
                val record = SessionHistoryRecord(
                    id = idProvider(),
                    task = SessionHistoryRecord.TASK_SUMMARY,
                    targetPackage = request.targetPackage,
                    phase = state.phase,
                    // completionSummary/failureMessage 默认为 null：模型输出可能包含页面文本/敏感内容，
                    // 保守默认不保存，未来产品策略明确后可按需填充。
                    completionSummary = null,
                    failureMessage = null,
                    stepCount = state.step,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                )
                historyStore?.save(record)
            }
            _historyWriteDiagnostic.value = if (result.isSuccess) {
                HistoryWriteDiagnostic.Success
            } else {
                // 暴露安全错误码，不泄露敏感内容或异常详情。
                HistoryWriteDiagnostic.Failed("HISTORY_WRITE_FAILED")
            }
        }
    }

    private companion object {
        val TERMINAL_PHASES = setOf(
            SessionPhase.Completed,
            SessionPhase.Failed,
            SessionPhase.Stopped,
        )
    }
}
