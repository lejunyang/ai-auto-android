package dev.aiauto.android.automation.recording

import java.io.Closeable

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordingControllerState(
    val draft: RecordingDraft = RecordingDraft(),
    val scripts: List<AutomationScriptSummary> = emptyList(),
    val selectedScript: AutomationScript? = null,
    val replayReport: ReplayReport? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
)

interface RecordingCoordinator : Closeable {
    val state: StateFlow<RecordingControllerState>

    fun start(
        name: String,
        targetPackages: Set<String>,
        environment: ScriptEnvironment? = null,
    )

    fun pause()

    fun resume()

    fun cancelRecording()

    fun finish(name: String)

    fun refresh()

    fun select(id: String)

    fun delete(id: String)

    fun replay(
        script: AutomationScript = requireNotNull(state.value.selectedScript),
        secrets: Map<String, String> = emptyMap(),
    )
}

class RecordingController(
    private val stateMachine: RecordingStateMachine,
    private val store: RecordingScriptStore,
    private val replayEngine: ReplayEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : RecordingCoordinator, RecordingEventSink {
    private val mutableState = MutableStateFlow(RecordingControllerState())
    override val state: StateFlow<RecordingControllerState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    override fun start(
        name: String,
        targetPackages: Set<String>,
        environment: ScriptEnvironment?,
    ) {
        runCatching {
            val draft = stateMachine.start(name, targetPackages, environment)
            RecordingRuntime.attach(this)
            mutableState.value = mutableState.value.copy(
                draft = draft,
                replayReport = null,
                errorMessage = null,
            )
        }.onFailure(::showError)
    }

    override fun pause() = updateDraft(stateMachine::pause)

    override fun resume() = updateDraft(stateMachine::resume)

    override fun cancelRecording() {
        RecordingRuntime.detach(this)
        updateDraft(stateMachine::cancel)
    }

    override fun finish(name: String) {
        runCatching {
            val script = stateMachine.finish(name)
            RecordingRuntime.detach(this)
            script
        }.onSuccess { script ->
            scope.launch {
                setBusy(true)
                runCatching {
                    withContext(dispatcher) { store.save(script) }
                }.onSuccess {
                    mutableState.value = mutableState.value.copy(
                        draft = stateMachine.current(),
                        selectedScript = script,
                        scripts = withContext(dispatcher) { store.list() },
                        busy = false,
                        errorMessage = null,
                    )
                }.onFailure {
                    setBusy(false)
                    showError(it)
                }
            }
        }.onFailure(::showError)
    }

    override fun refresh() {
        scope.launch {
            setBusy(true)
            runCatching { withContext(dispatcher) { store.list() } }
                .onSuccess { scripts ->
                    mutableState.value = mutableState.value.copy(
                        scripts = scripts,
                        busy = false,
                        errorMessage = null,
                    )
                }
                .onFailure {
                    setBusy(false)
                    showError(it)
                }
        }
    }

    override fun select(id: String) {
        scope.launch {
            setBusy(true)
            runCatching { withContext(dispatcher) { store.get(id) } }
                .onSuccess { script ->
                    mutableState.value = mutableState.value.copy(
                        selectedScript = script,
                        replayReport = null,
                        busy = false,
                        errorMessage = null,
                    )
                }
                .onFailure {
                    setBusy(false)
                    showError(it)
                }
        }
    }

    override fun delete(id: String) {
        scope.launch {
            setBusy(true)
            runCatching {
                withContext(dispatcher) {
                    store.delete(id)
                    store.list()
                }
            }.onSuccess { scripts ->
                mutableState.value = mutableState.value.copy(
                    scripts = scripts,
                    selectedScript = mutableState.value.selectedScript
                        ?.takeUnless { it.id == id },
                    replayReport = null,
                    busy = false,
                    errorMessage = null,
                )
            }.onFailure {
                setBusy(false)
                showError(it)
            }
        }
    }

    override fun replay(
        script: AutomationScript,
        secrets: Map<String, String>,
    ) {
        scope.launch {
            setBusy(true)
            runCatching {
                withContext(dispatcher) {
                    replayEngine.replay(script, secrets)
                }
            }
                .onSuccess { report ->
                    mutableState.value = mutableState.value.copy(
                        replayReport = report,
                        busy = false,
                        errorMessage = report.steps.lastOrNull()?.message
                            ?.takeUnless { report.succeeded },
                    )
                }
                .onFailure {
                    setBusy(false)
                    showError(it)
                }
        }
    }

    override fun accept(event: RecordingEvent) {
        val draft = stateMachine.accept(event)
        mutableState.value = mutableState.value.copy(draft = draft)
    }

    override fun close() {
        RecordingRuntime.detach(this)
        scope.cancel()
    }

    private fun updateDraft(operation: () -> RecordingDraft) {
        runCatching(operation)
            .onSuccess { draft ->
                mutableState.value = mutableState.value.copy(
                    draft = draft,
                    errorMessage = null,
                )
            }
            .onFailure(::showError)
    }

    private fun setBusy(busy: Boolean) {
        mutableState.value = mutableState.value.copy(busy = busy)
    }

    private fun showError(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            errorMessage = error.message ?: "Recording operation failed",
        )
    }
}
