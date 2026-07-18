package dev.aiauto.android.ui.recording

import androidx.lifecycle.ViewModelStore

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingControllerState
import dev.aiauto.android.automation.recording.RecordingCoordinator
import dev.aiauto.android.automation.recording.ScriptEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule: TestWatcher = RecordingMainDispatcherRule(dispatcher)

    @Test
    fun `recording controls delegate parsed input and return to list BitsUT`() =
        runTest(dispatcher) {
            val coordinator = FakeRecordingCoordinator()
            val viewModel = RecordingViewModel(coordinator)
            runCurrent()

            viewModel.openNewRecording()
            viewModel.updateName("Checkout")
            viewModel.updateTargetPackages("com.example.shop, com.example.auth")
            viewModel.start()
            viewModel.pause()
            viewModel.resume()

            assertEquals(RecordingDestination.SESSION, viewModel.uiState.value.destination)
            assertEquals("Checkout", coordinator.startedName)
            assertEquals(
                setOf("com.example.shop", "com.example.auth"),
                coordinator.startedPackages,
            )
            assertEquals(1, coordinator.pauseCalls)
            assertEquals(1, coordinator.resumeCalls)

            viewModel.navigateBack()

            assertEquals(1, coordinator.cancelCalls)
            assertEquals(RecordingDestination.LIST, viewModel.uiState.value.destination)
        }

    @Test
    fun `detail replay passes only required in memory secrets BitsUT`() =
        runTest(dispatcher) {
            val script = secretScript()
            val coordinator = FakeRecordingCoordinator(mapOf(script.id to script))
            val viewModel = RecordingViewModel(coordinator)
            runCurrent()

            viewModel.openScript(script.id)
            runCurrent()

            assertEquals(RecordingDestination.DETAIL, viewModel.uiState.value.destination)
            assertEquals(listOf("account.password"), viewModel.uiState.value.requiredSecretRefs)

            viewModel.replay(
                mapOf(
                    "account.password" to "run-only-value",
                    "unused" to "must-not-pass",
                ),
            )

            assertEquals(script, coordinator.replayedScript)
            assertEquals(
                mapOf("account.password" to "run-only-value"),
                coordinator.replayedSecrets,
            )
            assertTrue("run-only-value" !in viewModel.uiState.value.toString())
        }

    @Test
    fun `finish saves current name and opens saved script detail BitsUT`() =
        runTest(dispatcher) {
            val script = secretScript()
            val coordinator = FakeRecordingCoordinator(finishedScript = script)
            val viewModel = RecordingViewModel(coordinator)
            runCurrent()
            viewModel.openNewRecording()
            viewModel.updateName("Named recording")

            viewModel.finish()
            runCurrent()

            assertEquals("Named recording", coordinator.finishedName)
            assertEquals(RecordingDestination.DETAIL, viewModel.uiState.value.destination)
            assertEquals(script, viewModel.uiState.value.selectedScript)
        }

    @Test
    fun `detail replay rejects missing secret and delete returns to list BitsUT`() =
        runTest(dispatcher) {
            val script = secretScript()
            val coordinator = FakeRecordingCoordinator(mapOf(script.id to script))
            val viewModel = RecordingViewModel(coordinator)
            runCurrent()
            viewModel.openScript(script.id)
            runCurrent()

            viewModel.replay(emptyMap())

            assertNull(coordinator.replayedScript)

            viewModel.deleteSelected()

            assertEquals(script.id, coordinator.deletedId)
            assertEquals(RecordingDestination.LIST, viewModel.uiState.value.destination)
        }

    @Test
    fun `clearing ViewModel closes recording coordinator BitsUT`() {
        val coordinator = FakeRecordingCoordinator()
        val store = ViewModelStore()
        store.put("recording", RecordingViewModel(coordinator))

        store.clear()

        assertTrue(coordinator.closed)
    }

    private fun secretScript() = AutomationScript(
        id = "secret-script",
        name = "Secret form",
        targetPackages = listOf("com.example.shop"),
        createdAt = "2026-07-18T00:00:00Z",
        steps = listOf(
            RecordedStep(
                id = "step-1",
                recordedAtMs = 0,
                action = RecordedAction(
                    type = "ui.setText",
                    params = buildJsonObject {
                        put("secretRef", JsonPrimitive("account.password"))
                    },
                ),
            ),
        ),
    )

    private class FakeRecordingCoordinator(
        private val availableScripts: Map<String, AutomationScript> = emptyMap(),
        private val finishedScript: AutomationScript? = null,
    ) : RecordingCoordinator {
        private val mutableState = MutableStateFlow(RecordingControllerState())
        override val state: StateFlow<RecordingControllerState> = mutableState

        var startedName: String? = null
        var startedPackages: Set<String>? = null
        var pauseCalls = 0
        var resumeCalls = 0
        var cancelCalls = 0
        var finishedName: String? = null
        var deletedId: String? = null
        var replayedScript: AutomationScript? = null
        var replayedSecrets: Map<String, String>? = null
        var closed = false

        override fun start(
            name: String,
            targetPackages: Set<String>,
            environment: ScriptEnvironment?,
        ) {
            startedName = name
            startedPackages = targetPackages
        }

        override fun pause() {
            pauseCalls += 1
        }

        override fun resume() {
            resumeCalls += 1
        }

        override fun cancelRecording() {
            cancelCalls += 1
        }

        override fun finish(name: String) {
            finishedName = name
            mutableState.value = mutableState.value.copy(selectedScript = finishedScript)
        }

        override fun refresh() = Unit

        override fun select(id: String) {
            mutableState.value = mutableState.value.copy(
                selectedScript = availableScripts[id],
            )
        }

        override fun delete(id: String) {
            deletedId = id
            mutableState.value = mutableState.value.copy(selectedScript = null)
        }

        override fun replay(
            script: AutomationScript,
            secrets: Map<String, String>,
        ) {
            replayedScript = script
            replayedSecrets = secrets
        }

        override fun close() {
            closed = true
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class RecordingMainDispatcherRule(
    private val dispatcher: TestDispatcher,
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
