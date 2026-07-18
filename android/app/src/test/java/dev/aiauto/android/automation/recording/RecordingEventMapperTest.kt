package dev.aiauto.android.automation.recording

import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEventMapperTest {
    private val mapper = RecordingEventMapper()

    @Test
    fun `click creates semantic selectors and coordinate fallback`() {
        val mapped = mapper.map(
            event(
                type = RecordingEventType.CLICK,
                source = node(
                    resourceId = "com.example:id/continue_button",
                    text = "Continue",
                    className = "android.widget.Button",
                ),
            ),
        )

        assertNotNull(mapped)
        val target = mapped!!.action.params.getValue("target").toString()
        assertEquals("ui.click", mapped.action.type)
        assertTrue(target.contains("resourceId"))
        assertTrue(target.contains("contentDescription").not())
        assertTrue(target.contains("recordedBounds"))
        assertTrue(target.contains("relativePoint"))
    }

    @Test
    fun `password input stores only a secret reference`() {
        val mapped = mapper.map(
            event(
                type = RecordingEventType.TEXT_CHANGED,
                source = node(
                    resourceId = "com.example:id/password",
                    text = null,
                    state = UiNodeState(
                        enabled = true,
                        editable = true,
                        password = true,
                        sensitive = true,
                        visibleToUser = true,
                    ),
                ),
                text = "must-not-be-stored",
                sensitive = true,
            ),
        )

        assertNotNull(mapped)
        assertEquals("ui.setText", mapped!!.action.type)
        assertTrue("secretRef" in mapped.action.params)
        assertFalse("text" in mapped.action.params)
        assertFalse(mapped.action.toString().contains("must-not-be-stored"))
        assertEquals("secret", mapped.secretVariable?.type)
        assertTrue(mapped.secretVariable?.sensitive == true)
    }

    @Test
    fun `regular text input remains a semantic text action`() {
        val mapped = mapper.map(
            event(
                type = RecordingEventType.TEXT_CHANGED,
                source = node(
                    resourceId = "com.example:id/search",
                    state = UiNodeState(
                        enabled = true,
                        editable = true,
                        visibleToUser = true,
                    ),
                ),
                text = "query",
            ),
        )

        assertEquals("query", mapped?.action?.params?.get("text")?.toString()?.trim('"'))
        assertNull(mapped?.secretVariable)
    }

    @Test
    fun `scroll direction follows the dominant reported delta`() {
        val mapped = mapper.map(
            event(
                type = RecordingEventType.SCROLLED,
                source = node(),
                scrollDeltaY = 180,
            ),
        )

        assertEquals("ui.scroll", mapped?.action?.type)
        assertEquals("down", mapped?.action?.params?.get("direction")?.toString()?.trim('"'))
    }

    @Test
    fun `click without a source node is ignored`() {
        assertNull(mapper.map(event(type = RecordingEventType.CLICK, source = null)))
    }

    private fun event(
        type: RecordingEventType,
        source: UiNodeSnapshot?,
        text: String? = null,
        sensitive: Boolean = false,
        scrollDeltaY: Int = 0,
    ) = RecordingEvent(
        type = type,
        eventTimeMs = 1_000,
        packageName = "com.example",
        source = source,
        text = text,
        sensitive = sensitive,
        scrollDeltaY = scrollDeltaY,
    )

    private fun node(
        resourceId: String? = null,
        text: String? = null,
        className: String = "android.widget.TextView",
        state: UiNodeState = UiNodeState(
            enabled = true,
            visibleToUser = true,
        ),
    ) = UiNodeSnapshot(
        packageName = "com.example",
        className = className,
        resourceId = resourceId,
        text = text,
        contentDescription = null,
        bounds = UiBounds(left = 10, top = 20, right = 110, bottom = 70),
        actions = setOf(NodeAction.CLICK),
        state = state,
        children = emptyList(),
    )
}
