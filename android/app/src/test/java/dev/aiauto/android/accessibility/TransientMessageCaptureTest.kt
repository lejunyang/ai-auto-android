package dev.aiauto.android.accessibility

/**
 * 测试用途：验证 Toast 与通知捕获的事件筛选、Toast 与通知的区分、
 * 有界缓冲的淘汰顺序，以及并发遍历安全。
 */

import android.view.accessibility.AccessibilityEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientMessageCaptureTest {
    @Test
    fun `captures toast text and marks it as a toast BitsUT`() {
        val capture = TransientMessageCapture()

        val accepted = capture.onAccessibilityEvent(
            event(
                eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                className = "android.widget.Toast",
                packageName = "com.example",
                text = listOf("Saved"),
                eventTimeMs = 1_000,
            ),
        )

        assertTrue(accepted)
        val messages = capture.messages()
        assertEquals(1, messages.size)
        assertEquals("Saved", messages.single().text)
        assertTrue(messages.single().toast)
        assertEquals("com.example", messages.single().packageName)
        assertEquals(1_000L, messages.single().eventTimeMs)
    }

    @Test
    fun `separates notifications from toasts BitsUT`() {
        // 二者用途不同：Toast 几乎总是当前操作的直接反馈，通知可能来自任何
        // 后台应用。混在一起会让判据不可靠，因此必须能分开取。
        val capture = TransientMessageCapture()
        capture.onAccessibilityEvent(
            event(
                eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                className = "android.widget.Toast",
                packageName = "com.example",
                text = listOf("Toast body"),
                eventTimeMs = 1,
            ),
        )
        capture.onAccessibilityEvent(
            event(
                eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                className = "android.app.Notification",
                packageName = "com.other",
                text = listOf("Notification title", "Notification body"),
                eventTimeMs = 2,
            ),
        )

        assertEquals(2, capture.messages().size)
        val toasts = capture.toasts()
        assertEquals(1, toasts.size)
        assertEquals("Toast body", toasts.single().text)
        // 通知的多段文本必须全部保留：只取首项会丢掉正文，
        // 而正文往往才是自动化要判断的内容。
        val notification = capture.messages().first { !it.toast }
        assertEquals("Notification title\nNotification body", notification.text)
    }

    @Test
    fun `ignores unrelated event types and blank text BitsUT`() {
        val capture = TransientMessageCapture()

        assertFalse(
            capture.onAccessibilityEvent(
                event(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    className = "android.widget.Button",
                    packageName = "com.example",
                    text = listOf("Clicked"),
                    eventTimeMs = 1,
                ),
            ),
        )
        assertFalse(
            capture.onAccessibilityEvent(
                event(
                    eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                    className = "android.widget.Toast",
                    packageName = "com.example",
                    text = listOf("", "   "),
                    eventTimeMs = 2,
                ),
            ),
        )
        assertTrue(capture.messages().isEmpty())
    }

    @Test
    fun `drops the oldest message when the buffer is full BitsUT`() {
        // 有界缓冲的意义是防止长会话里通知持续累积占内存；
        // 淘汰必须从最旧的一端开始，否则会丢掉刚发生的提示。
        val capture = TransientMessageCapture(maxMessages = 2)
        repeat(3) { index ->
            capture.onAccessibilityEvent(
                event(
                    eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                    className = "android.widget.Toast",
                    packageName = "com.example",
                    text = listOf("message $index"),
                    eventTimeMs = index.toLong(),
                ),
            )
        }

        val messages = capture.messages()
        assertEquals(2, messages.size)
        assertEquals(listOf("message 1", "message 2"), messages.map(TransientMessage::text))
    }

    @Test
    fun `clear isolates messages captured before an action BitsUT`() {
        val capture = TransientMessageCapture()
        capture.onAccessibilityEvent(
            event(
                eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                className = "android.widget.Toast",
                packageName = "com.example",
                text = listOf("stale"),
                eventTimeMs = 1,
            ),
        )

        capture.clear()

        assertTrue(capture.messages().isEmpty())
    }

    @Test
    fun `messages returns a snapshot that survives concurrent capture BitsUT`() {
        // 返回内部集合会让调用方遍历期间抛 ConcurrentModificationException，
        // 因此必须回副本。
        val capture = TransientMessageCapture()
        capture.onAccessibilityEvent(
            event(
                eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                className = "android.widget.Toast",
                packageName = "com.example",
                text = listOf("first"),
                eventTimeMs = 1,
            ),
        )

        val snapshot = capture.messages()
        capture.onAccessibilityEvent(
            event(
                eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                className = "android.widget.Toast",
                packageName = "com.example",
                text = listOf("second"),
                eventTimeMs = 2,
            ),
        )

        assertEquals(1, snapshot.size)
        assertEquals(2, capture.messages().size)
    }

    private fun event(
        eventType: Int,
        className: String,
        packageName: String,
        text: List<String>,
        eventTimeMs: Long,
    ): AccessibilityEvent {
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns eventType
        every { event.className } returns className
        every { event.packageName } returns packageName
        every { event.text } returns text.toMutableList<CharSequence>()
        every { event.eventTime } returns eventTimeMs
        return event
    }
}
