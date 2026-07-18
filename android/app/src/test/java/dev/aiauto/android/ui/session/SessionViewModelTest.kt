package dev.aiauto.android.ui.session

import dev.aiauto.android.automation.session.AutomationRiskPolicy
import dev.aiauto.android.automation.session.AutomationSessionEngine
import dev.aiauto.android.automation.session.AutomationSessionFactory
import dev.aiauto.android.automation.session.SessionExecutionResult
import dev.aiauto.android.automation.session.SessionExecutor
import dev.aiauto.android.automation.session.SessionLimits
import dev.aiauto.android.automation.session.SessionObservation
import dev.aiauto.android.automation.session.SessionObserver
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.automation.session.SessionPlanner
import dev.aiauto.android.provider.ProviderAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule: TestWatcher = MainDispatcherRule(dispatcher)

    @Test
    fun `start and stop expose stopped state without another action BitsUT`() =
        runTest(dispatcher) {
            val engine = blockingEngine()
            val viewModel = SessionViewModel(FakeFactory(Result.success(engine)))
            viewModel.updateTask("Open inbox")
            viewModel.updateTargetPackage(TARGET_PACKAGE)

            viewModel.start()
            runCurrent()
            assertEquals(SessionPhase.Planning, viewModel.uiState.value.session.phase)

            viewModel.stop()

            assertEquals(SessionPhase.Stopped, viewModel.uiState.value.session.phase)
            assertTrue(!viewModel.uiState.value.session.isRunning)
        }

    @Test
    fun `start reports dependency creation failure BitsUT`() = runTest(dispatcher) {
        val viewModel = SessionViewModel(
            FakeFactory(Result.failure(IllegalStateException("Provider unavailable"))),
        )
        viewModel.updateTask("Open inbox")
        viewModel.updateTargetPackage(TARGET_PACKAGE)

        viewModel.start()

        assertTrue(viewModel.uiState.value.setupError!!.contains("Provider unavailable"))
        assertEquals(SessionPhase.Idle, viewModel.uiState.value.session.phase)
    }

    private fun blockingEngine() = AutomationSessionEngine(
        observer = SessionObserver {
            SessionObservation(TARGET_PACKAGE, "package=$TARGET_PACKAGE")
        },
        planner = SessionPlanner { awaitCancellation() },
        executor = SessionExecutor { action: ProviderAction, _: String ->
            SessionExecutionResult("unexpected ${action.type}")
        },
        riskPolicy = AutomationRiskPolicy(setOf(TARGET_PACKAGE)),
        limits = SessionLimits(
            maxSteps = 5,
            stepTimeoutMs = 10_000,
            totalTimeoutMs = 20_000,
            repeatedActionLimit = 3,
        ),
    )

    private class FakeFactory(
        private val result: Result<AutomationSessionEngine>,
    ) : AutomationSessionFactory {
        override fun create(): Result<AutomationSessionEngine> = result

        override fun configuredTargetPackages(): Set<String> = setOf(TARGET_PACKAGE)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class MainDispatcherRule(
    private val dispatcher: TestDispatcher,
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
