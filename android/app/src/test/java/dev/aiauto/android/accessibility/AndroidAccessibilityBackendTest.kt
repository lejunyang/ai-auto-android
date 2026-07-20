package dev.aiauto.android.accessibility

/**
 * 测试用途：验证 AndroidAccessibilityBackend 的功能契约、失败语义及自动化安全边界。
 */

import android.accessibilityservice.AccessibilityService

import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.automation.recording.RecordingController
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.RecordingStateMachine
import dev.aiauto.android.automation.recording.ReplayEngine
import dev.aiauto.android.automation.recording.ScriptEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilityBackendTest {
    @Test
    fun `successful service global action is published to recording runtime BitsUT`() {
        val service = mockk<AccessibilityService>()
        every {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } returns true
        var nowMs = 1_000L
        val stateMachine = RecordingStateMachine(
            clockMs = { nowMs },
            idFactory = { "step" },
        )
        val store = mockk<RecordingScriptStore>()
        every { store.list() } returns emptyList()
        val controller = RecordingController(
            stateMachine = stateMachine,
            store = store,
            replayEngine = mockk<ReplayEngine>(),
            dispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        controller.start(
            name = "Navigation",
            targetPackages = setOf("com.example"),
            environment = ScriptEnvironment(),
        )

        try {
            val backend = backend(service)
            nowMs = 1_100
            val first = backend.performGlobal(GlobalAction.BACK)
            nowMs = 1_300
            val duplicate = backend.performGlobal(GlobalAction.BACK)

            assertTrue(first)
            assertTrue(duplicate)
            assertEquals(1, controller.state.value.draft.steps.size)
            assertEquals("ui.back", controller.state.value.draft.steps.single().action.type)
        } finally {
            controller.close()
        }
    }

    private fun backend(service: AccessibilityService) = AndroidAccessibilityBackend(
        service = service,
        settingsRepository = mockk<AccessibilitySettingsRepository>(),
        snapshotter = mockk<AccessibilitySnapshotter>(),
    )
}
