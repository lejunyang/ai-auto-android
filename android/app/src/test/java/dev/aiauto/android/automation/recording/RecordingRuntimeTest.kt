package dev.aiauto.android.automation.recording

/**
 * 测试用途：验证 RecordingRuntime 的功能契约、失败语义及自动化安全边界。
 */

import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRuntimeTest {
    @Test
    fun `accessibility event uses runtime wall clock and reaches state machine BitsUT`() {
        val stateMachine = RecordingStateMachine(
            clockMs = { 1_000L },
            idFactory = { "step" },
        )
        stateMachine.start("Click", setOf("com.example"))
        val sink = RecordingEventSink { event ->
            stateMachine.accept(event)
        }
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns "com.example"
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_CLICKED
        RecordingRuntime.attach(sink)

        try {
            RecordingRuntime.publish(
                event = event,
                source = node(),
                receivedAtMs = 1_100L,
            )

            val step = stateMachine.current().steps.single()
            assertEquals(100L, step.recordedAtMs)
            assertEquals("ui.click", step.action.type)
        } finally {
            RecordingRuntime.detach(sink)
        }
    }

    private fun node() = UiNodeSnapshot(
        packageName = "com.example",
        className = "android.widget.Button",
        resourceId = "com.example:id/action",
        text = "Continue",
        contentDescription = null,
        bounds = UiBounds(0, 0, 100, 50),
        actions = emptySet(),
        state = UiNodeState(
            enabled = true,
            clickable = true,
            visibleToUser = true,
        ),
        children = emptyList(),
    )
}
