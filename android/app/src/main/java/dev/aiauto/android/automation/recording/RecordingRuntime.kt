package dev.aiauto.android.automation.recording

// 功能用途：实现 RecordingRuntime 对应的语义录制、脚本持久化或确定性回放能力。

import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.UiNodeSnapshot

fun interface RecordingEventSink {
    fun accept(event: RecordingEvent)

    fun acceptGlobalAction(action: GlobalAction) = Unit
}

object RecordingRuntime {
    // 进程内只允许一个活动录制接收器，避免多个页面同时消费同一批无障碍事件。
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
        // 敏感输入只保留事件类型和引用所需元数据，原始文本绝不进入录制草稿。
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
