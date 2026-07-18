package dev.aiauto.android.ui.recording

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import dev.aiauto.android.automation.recording.AndroidReplayGateway
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordingController
import dev.aiauto.android.automation.recording.RecordingControllerState
import dev.aiauto.android.automation.recording.RecordingCoordinator
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.RecordingStateMachine
import dev.aiauto.android.automation.recording.RecordingStatus
import dev.aiauto.android.automation.recording.ReplayEngine
import dev.aiauto.android.automation.recording.SecretResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class RecordingDestination {
    LIST,
    SESSION,
    DETAIL,
}

data class RecordingUiState(
    val destination: RecordingDestination = RecordingDestination.LIST,
    val recording: RecordingControllerState = RecordingControllerState(),
    val nameInput: String = "",
    val targetPackagesInput: String = "",
    val selectedScriptId: String? = null,
) {
    val selectedScript: AutomationScript?
        get() = recording.selectedScript?.takeIf { it.id == selectedScriptId }

    val requiredSecretRefs: List<String>
        get() = selectedScript?.let(::requiredSecretRefs).orEmpty()
}

class RecordingViewModel(
    private val coordinator: RecordingCoordinator,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        RecordingUiState(recording = coordinator.state.value),
    )
    private var awaitingFinishedScript = false

    val uiState: StateFlow<RecordingUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.state.collect { recordingState ->
                mutableUiState.update { current ->
                    val finishedScript = recordingState.selectedScript
                        ?.takeIf {
                            awaitingFinishedScript &&
                                recordingState.draft.status == RecordingStatus.IDLE &&
                                !recordingState.busy
                        }
                    if (finishedScript != null) {
                        awaitingFinishedScript = false
                    }
                    current.copy(
                        recording = recordingState,
                        destination = if (finishedScript == null) {
                            current.destination
                        } else {
                            RecordingDestination.DETAIL
                        },
                        selectedScriptId = finishedScript?.id ?: current.selectedScriptId,
                    )
                }
            }
        }
    }

    fun openNewRecording() {
        awaitingFinishedScript = false
        mutableUiState.update {
            it.copy(
                destination = RecordingDestination.SESSION,
                nameInput = "",
                targetPackagesInput = "",
                selectedScriptId = null,
            )
        }
    }

    fun updateName(value: String) {
        if (mutableUiState.value.recording.draft.status == RecordingStatus.IDLE) {
            mutableUiState.update { it.copy(nameInput = value.take(MAX_NAME_LENGTH)) }
        }
    }

    fun updateTargetPackages(value: String) {
        if (mutableUiState.value.recording.draft.status == RecordingStatus.IDLE) {
            mutableUiState.update {
                it.copy(targetPackagesInput = value.take(MAX_TARGET_PACKAGES_INPUT_LENGTH))
            }
        }
    }

    fun start() {
        val state = mutableUiState.value
        val name = state.nameInput.trim()
        val targetPackages = parseTargetPackages(state.targetPackagesInput)
        if (name.isEmpty() || targetPackages.isEmpty()) {
            return
        }
        coordinator.start(name, targetPackages)
    }

    fun pause() {
        coordinator.pause()
    }

    fun resume() {
        coordinator.resume()
    }

    fun finish() {
        awaitingFinishedScript = true
        coordinator.finish(mutableUiState.value.nameInput)
    }

    fun cancel() {
        awaitingFinishedScript = false
        coordinator.cancelRecording()
        mutableUiState.update {
            it.copy(
                destination = RecordingDestination.LIST,
                selectedScriptId = null,
            )
        }
    }

    fun openScript(id: String) {
        awaitingFinishedScript = false
        mutableUiState.update {
            it.copy(
                destination = RecordingDestination.DETAIL,
                selectedScriptId = id,
            )
        }
        coordinator.select(id)
    }

    fun replay(secretValues: Map<String, String>) {
        val script = mutableUiState.value.selectedScript ?: return
        val requiredRefs = requiredSecretRefs(script)
        if (!hasRequiredSecrets(requiredRefs, secretValues)) {
            return
        }
        val replaySecrets = requiredRefs.associateWith { alias ->
            requireNotNull(secretValues[alias])
        }
        coordinator.replay(script, replaySecrets)
    }

    fun deleteSelected() {
        val id = mutableUiState.value.selectedScriptId ?: return
        coordinator.delete(id)
        mutableUiState.update {
            it.copy(
                destination = RecordingDestination.LIST,
                selectedScriptId = null,
            )
        }
    }

    fun navigateBack() {
        when (mutableUiState.value.destination) {
            RecordingDestination.LIST -> Unit
            RecordingDestination.SESSION -> cancel()
            RecordingDestination.DETAIL -> mutableUiState.update {
                it.copy(
                    destination = RecordingDestination.LIST,
                    selectedScriptId = null,
                )
            }
        }
    }

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(RecordingViewModel::class.java))
                    val coordinator = RecordingController(
                        stateMachine = RecordingStateMachine(),
                        store = RecordingScriptStore.from(applicationContext),
                        replayEngine = ReplayEngine(
                            gateway = AndroidReplayGateway(),
                            secretResolver = object : SecretResolver {
                                override fun resolve(alias: String): String? = null
                            },
                        ),
                    )
                    return RecordingViewModel(coordinator) as T
                }
            }
        }

        private const val MAX_NAME_LENGTH = 128
        private const val MAX_TARGET_PACKAGES_INPUT_LENGTH = 4_000
    }
}

internal fun requiredSecretRefs(script: AutomationScript): List<String> =
    script.steps.mapNotNull { step ->
        step.action.params["secretRef"]?.jsonPrimitive?.contentOrNull
    }.distinct()

internal fun hasRequiredSecrets(
    requiredRefs: List<String>,
    secretValues: Map<String, String>,
): Boolean = requiredRefs.all { alias ->
    secretValues[alias]?.isNotBlank() == true
}

private fun parseTargetPackages(value: String): Set<String> =
    value.split(TARGET_PACKAGE_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

private val TARGET_PACKAGE_SEPARATOR = Regex("[\\s,]+")
