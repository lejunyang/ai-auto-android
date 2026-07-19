package dev.aiauto.android.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import dev.aiauto.android.automation.session.AutomationSessionEngine
import dev.aiauto.android.automation.session.AutomationSessionFactory
import dev.aiauto.android.automation.session.AutomationSessionState
import dev.aiauto.android.automation.session.SessionRequest
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
    private val sessionFactory: AutomationSessionFactory,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SessionUiState(
            availableTargetPackages = sessionFactory.configuredTargetPackages().sorted(),
            targetPackageInput = sessionFactory.configuredTargetPackages().sorted().firstOrNull()
                .orEmpty(),
        ),
    )
    private var engine: AutomationSessionEngine? = null
    private var sessionJob: Job? = null
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

        val created = sessionFactory.create()
        if (created.isFailure) {
            mutableUiState.value = mutableUiState.value.copy(
                setupError = created.exceptionOrNull()?.message
                    ?: "Automation session dependencies are unavailable.",
            )
            return
        }
        val nextEngine = created.getOrThrow()
        engine = nextEngine
        stateCollectionJob?.cancel()
        stateCollectionJob = viewModelScope.launch {
            nextEngine.state.collect { sessionState ->
                mutableUiState.value = mutableUiState.value.copy(
                    session = sessionState,
                    setupError = null,
                )
            }
        }
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            nextEngine.start(
                SessionRequest(
                    task = task,
                    targetPackage = targetPackage,
                    screenshotsAllowed = mutableUiState.value.screenshotsAllowed,
                ),
            )
        }
    }

    fun pause() {
        engine?.pause()
    }

    fun resume() {
        engine?.resume()
    }

    fun confirm(confirmationId: Int, approved: Boolean) {
        engine?.confirmPendingAction(confirmationId, approved)
    }

    fun stop() {
        engine?.stop()
        mutableUiState.value = mutableUiState.value.copy(
            session = engine?.state?.value ?: mutableUiState.value.session,
        )
        sessionJob?.cancel()
    }

    override fun onCleared() {
        engine?.stop()
        super.onCleared()
    }

    companion object {
        fun factory(sessionFactory: AutomationSessionFactory): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SessionViewModel::class.java))
                    return SessionViewModel(sessionFactory) as T
                }
            }

        private const val MAX_TASK_LENGTH = 4_000
        private const val MAX_PACKAGE_LENGTH = 255
    }
}
