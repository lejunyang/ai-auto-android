package dev.aiauto.android.ui.session

/**
 * 功能用途：实现 AI 自动化会话的状态展示、确认、暂停与停止交互。
 *
 * 架构边界：
 * - ViewModel 仅为 UI 展示层：订阅 SessionCoordinator.sessionState，通过 command 端口操作。
 * - 不创建/启动/停止 engine，不写历史（由 SessionCoordinator 作为唯一 owner 负责）。
 * - onCleared 只取消自己的 collection Job，不停止会话（显式用户 stop 才停）。
 * - Activity 销毁/配置变更/返回界面后，重新订阅同一 coordinator 即可恢复状态。
 */

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import dev.aiauto.android.automation.session.AutomationSessionState
import dev.aiauto.android.automation.session.SessionRequest
import dev.aiauto.android.automation.session.coordinator.SessionRuntime
import dev.aiauto.android.automation.session.service.AutomationSessionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionUiState(
    val taskInput: String = "",
    val targetPackageInput: String = "",
    val screenshotsAllowed: Boolean = false,
    val availableTargetPackages: List<String> = emptyList(),
    val session: AutomationSessionState = AutomationSessionState(),
    val setupError: String? = null,
)

class SessionViewModel(
    private val appContext: Context,
    private val availablePackages: List<String>,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SessionUiState(
            availableTargetPackages = availablePackages,
            targetPackageInput = availablePackages.firstOrNull().orEmpty(),
        ),
    )
    private var stateCollectionJob: Job? = null

    val uiState: StateFlow<SessionUiState> = mutableUiState.asStateFlow()

    fun updateTask(value: String) {
        if (!mutableUiState.value.session.isRunning) {
            mutableUiState.value = mutableUiState.value.copy(
                taskInput = value.take(MAX_TASK_LENGTH),
                setupError = null,
            )
        }
    }

    fun updateTargetPackage(value: String) {
        if (!mutableUiState.value.session.isRunning) {
            mutableUiState.value = mutableUiState.value.copy(
                targetPackageInput = value.take(MAX_PACKAGE_LENGTH),
                setupError = null,
            )
        }
    }

    fun updateScreenshotsAllowed(value: Boolean) {
        if (!mutableUiState.value.session.isRunning) {
            mutableUiState.value = mutableUiState.value.copy(
                screenshotsAllowed = value,
                setupError = null,
            )
        }
    }

    /**
     * 启动会话：通过前台 Service 传递完整 SessionRequest。
     * Service 作为薄壳调用 SessionCoordinator.start()，coordinator 是唯一 runtime owner。
     * 启动后订阅 coordinator.sessionState 同步 UI。
     */
    fun start() {
        if (mutableUiState.value.session.isRunning) {
            return
        }
        val task = mutableUiState.value.taskInput.trim()
        val targetPackage = mutableUiState.value.targetPackageInput.trim()
        if (task.isEmpty() || targetPackage.isEmpty()) {
            mutableUiState.value = mutableUiState.value.copy(
                setupError = "Enter a task and select an authorized target package.",
            )
            return
        }

        val coordinator = SessionRuntime.getCoordinator()
        if (coordinator == null) {
            mutableUiState.value = mutableUiState.value.copy(
                setupError = "Session runtime is not initialized.",
            )
            return
        }

        // 传递完整 SessionRequest（含 screenshotsAllowed）给 Service，Service 再交给 coordinator。
        AutomationSessionService.start(
            context = appContext,
            task = task,
            targetPackage = targetPackage,
            screenshotsAllowed = mutableUiState.value.screenshotsAllowed,
        )

        // 订阅 coordinator.sessionState，同步 UI 状态。
        subscribeToCoordinator()
    }

    /** 订阅 coordinator.sessionState，Activity 恢复时也可调用重新订阅。 */
    fun subscribeToCoordinator() {
        val coordinator = SessionRuntime.getCoordinator() ?: return
        stateCollectionJob?.cancel()
        stateCollectionJob = viewModelScope.launch {
            coordinator.sessionState.collect { sessionState ->
                mutableUiState.value = mutableUiState.value.copy(
                    session = sessionState,
                    setupError = null,
                )
            }
        }
    }

    fun pause() {
        SessionRuntime.getCoordinator()?.pause()
    }

    fun resume() {
        SessionRuntime.getCoordinator()?.resume()
    }

    fun confirm(confirmationId: Int, approved: Boolean) {
        SessionRuntime.getCoordinator()?.confirm(confirmationId, approved)
    }

    fun stop() {
        // 用户显式 stop：通过 Service 发送 ACTION_STOP，Service 路由到 coordinator.stop()。
        AutomationSessionService.stop(appContext)
    }

    /**
     * ViewModel 销毁时只取消自己的订阅，不停止会话。
     * 后台会话由 Service/coordinator 持有，Activity 销毁不影响运行。
     */
    override fun onCleared() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        super.onCleared()
    }

    companion object {
        fun factory(
            appContext: Context,
            availablePackages: List<String>,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SessionViewModel::class.java))
                    return SessionViewModel(appContext, availablePackages) as T
                }
            }

        private const val MAX_TASK_LENGTH = 4_000
        private const val MAX_PACKAGE_LENGTH = 255
    }
}
