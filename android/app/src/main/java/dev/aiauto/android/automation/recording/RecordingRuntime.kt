package dev.aiauto.android.automation.recording

import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.UiNodeSnapshot

fun interface RecordingEventSink {
    fun accept(event: RecordingEvent)

    fun acceptGlobalAction(action: GlobalAction) = Unit
}

object RecordingRuntime {
    @Volatile
    private var sink: RecordingEventSink? = null

    fun isListening(): Boolean = sink != null

    fun attach(eventSink: RecordingEventSink) {
        check(sink == null || sink === eventSink) {
            "Another recording event sink is already attached"
        }
        sink = eventSink
    }

    fun detach(eventSink: RecordingEventSink) {
        if (sink === eventSink) {
            sink = null
        }
    }

    fun publish(
        event: AccessibilityEvent,
        source: UiNodeSnapshot?,
        receivedAtMs: Long = System.currentTimeMillis(),
    ) {
        val currentSink = sink ?: return
        val mapped = AndroidRecordingEventAdapter.map(event, source, receivedAtMs) ?: return
        currentSink.accept(mapped)
    }

    fun publishGlobalAction(action: GlobalAction) {
        sink?.acceptGlobalAction(action)
    }
}

object AndroidRecordingEventAdapter {
    fun map(
        event: AccessibilityEvent,
        source: UiNodeSnapshot?,
        receivedAtMs: Long = System.currentTimeMillis(),
    ): RecordingEvent? {
        val packageName = event.packageName?.toString()?.takeIf(String::isNotBlank)
            ?: return null
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> RecordingEventType.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> RecordingEventType.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RecordingEventType.TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> RecordingEventType.SCROLLED
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> RecordingEventType.WINDOW_CHANGED

            else -> return null
        }
        val sensitive = event.isPassword ||
            source?.state?.password == true ||
            source?.state?.sensitive == true
        return RecordingEvent(
            type = type,
            eventTimeMs = receivedAtMs,
            packageName = packageName,
            source = source,
            text = if (sensitive) null else event.text.lastOrNull()?.toString(),
            sensitive = sensitive,
            scrollDeltaX = event.scrollDeltaX,
            scrollDeltaY = event.scrollDeltaY,
        )
    }
}
