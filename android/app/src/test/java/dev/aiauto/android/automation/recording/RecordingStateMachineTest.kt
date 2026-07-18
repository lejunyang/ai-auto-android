package dev.aiauto.android.automation.recording

import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {
    private var nowMs = 1_000L
    private var nextId = 0
    private val machine = RecordingStateMachine(
        clockMs = { nowMs },
        idFactory = { "id-${nextId++}" },
    )

    @Test
    fun `duplicate accessibility events inside the window are recorded once`() {
        machine.start("Checkout", setOf("com.example"))
        val event = click(eventTimeMs = 1_100)

        machine.accept(event)
        machine.accept(event.copy(eventTimeMs = 1_300))

        assertEquals(1, machine.current().steps.size)
    }

    @Test
    fun `duplicate global actions inside the window are recorded once BitsUT`() {
        machine.start("Navigation", setOf("com.example"))
        nowMs = 1_100

        machine.recordGlobalAction(GlobalAction.BACK)
        nowMs = 1_300
        machine.recordGlobalAction(GlobalAction.BACK)

        assertEquals(1, machine.current().steps.size)
        assertEquals("ui.back", machine.current().steps.single().action.type)
    }

    @Test
    fun `all explicit global actions map to recorded steps BitsUT`() {
        machine.start("Navigation", setOf("com.example"))

        listOf(
            GlobalAction.BACK,
            GlobalAction.HOME,
            GlobalAction.RECENTS,
        ).forEachIndexed { index, action ->
            nowMs = 1_100L + index * 400L
            machine.recordGlobalAction(action)
        }

        assertEquals(
            listOf("ui.back", "ui.home", "ui.recents"),
            machine.current().steps.map { it.action.type },
        )
    }

    @Test
    fun `incremental text changes are coalesced to the latest value`() {
        machine.start("Search", setOf("com.example"))

        machine.accept(textChanged("q", eventTimeMs = 1_100))
        machine.accept(textChanged("query", eventTimeMs = 1_600))

        val steps = machine.current().steps
        assertEquals(1, steps.size)
        assertEquals("\"query\"", steps.single().action.params["text"].toString())
    }

    @Test
    fun `paused and unrelated package events are ignored`() {
        machine.start("Flow", setOf("com.example"))
        machine.pause()
        machine.accept(click(eventTimeMs = 1_100))
        machine.resume()
        machine.accept(click(eventTimeMs = 1_200).copy(packageName = "com.other"))

        assertTrue(machine.current().steps.isEmpty())
    }

    @Test
    fun `sensitive text creates one reusable secret variable`() {
        machine.start("Login", setOf("com.example"))
        val source = node(
            state = UiNodeState(
                enabled = true,
                editable = true,
                password = true,
                sensitive = true,
                visibleToUser = true,
            ),
        )

        machine.accept(
            RecordingEvent(
                type = RecordingEventType.TEXT_CHANGED,
                eventTimeMs = 1_100,
                packageName = "com.example",
                source = source,
                sensitive = true,
            ),
        )
        machine.accept(
            RecordingEvent(
                type = RecordingEventType.TEXT_CHANGED,
                eventTimeMs = 1_700,
                packageName = "com.example",
                source = source,
                sensitive = true,
            ),
        )

        assertEquals(1, machine.current().variables.size)
        assertTrue("secretRef" in machine.current().steps.single().action.params)
    }

    @Test
    fun `finish emits a versioned script and resets the machine`() {
        machine.start(
            name = "Original",
            targetPackages = setOf("com.example"),
            environment = ScriptEnvironment(apiLevel = 36),
        )
        machine.accept(click(eventTimeMs = 1_100))

        val script = machine.finish("Saved flow")

        assertEquals(RECORDING_SCHEMA_VERSION, script.schemaVersion)
        assertEquals("Saved flow", script.name)
        assertEquals(listOf("com.example"), script.targetPackages)
        assertEquals(36, script.environment?.apiLevel)
        assertEquals(RecordingStatus.IDLE, machine.current().status)
    }

    @Test
    fun `empty recordings cannot be persisted`() {
        machine.start("Empty", setOf("com.example"))

        assertThrows(IllegalArgumentException::class.java) {
            machine.finish()
        }
    }

    private fun click(eventTimeMs: Long) = RecordingEvent(
        type = RecordingEventType.CLICK,
        eventTimeMs = eventTimeMs,
        packageName = "com.example",
        source = node(),
    )

    private fun textChanged(
        text: String,
        eventTimeMs: Long,
    ) = RecordingEvent(
        type = RecordingEventType.TEXT_CHANGED,
        eventTimeMs = eventTimeMs,
        packageName = "com.example",
        source = node(
            resourceId = "com.example:id/search",
            state = UiNodeState(
                enabled = true,
                editable = true,
                visibleToUser = true,
            ),
        ),
        text = text,
    )

    private fun node(
        resourceId: String = "com.example:id/action",
        state: UiNodeState = UiNodeState(
            enabled = true,
            clickable = true,
            visibleToUser = true,
        ),
    ) = UiNodeSnapshot(
        packageName = "com.example",
        className = "android.widget.Button",
        resourceId = resourceId,
        text = null,
        contentDescription = null,
        bounds = UiBounds(0, 0, 100, 50),
        actions = emptySet(),
        state = state,
        children = emptyList(),
    )
}
