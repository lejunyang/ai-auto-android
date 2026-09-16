package dev.aiauto.android.accessibility

/**
 * 功能用途：捕获 Toast 与通知文本，供自动化判断「刚才那个一闪而过的提示说了什么」。
 *
 * 为什么需要专门的捕获器：Toast 通常只显示 2 到 3.5 秒，且不进入节点树的常规
 * 位置。等调用方发现需要它时再去查快照已经晚了——提示早已消失。因此必须在
 * 事件到达的那一刻就记录下来，供之后查询。
 *
 * 事件来源是 TYPE_NOTIFICATION_STATE_CHANGED：平台把 Toast 与通知都归入这一类，
 * 二者用 className 区分（Toast 的 className 为 android.widget.Toast）。
 * 不用 TYPE_WINDOWS_CHANGED：那个事件只说明窗口集合变了，不带文本。
 *
 * 缓冲有界且带时间戳：无界缓冲在长时间会话里会持续增长，而没有时间戳的记录
 * 无法回答「这是刚才那次操作触发的，还是十分钟前遗留的」。
 */

import android.view.accessibility.AccessibilityEvent

import java.util.ArrayDeque

/** 一条被捕获的瞬时提示。 */
data class TransientMessage(
    val text: String,
    val packageName: String?,
    /** 是否为 Toast。false 表示这是一条通知。 */
    val toast: Boolean,
    val eventTimeMs: Long,
)

class TransientMessageCapture(
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
) {
    // ArrayDeque 而非 List：需要在超出上限时从头部丢弃最旧的一条，
    // 用 List 做这件事每次都是 O(n) 的元素搬移。
    private val messages = ArrayDeque<TransientMessage>()

    /**
     * 处理一个无障碍事件，若它携带提示文本则记录下来。
     *
     * 返回是否记录成功，供调用方在测试里断言捕获确实发生。
     */
    @Synchronized
    fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return false
        }
        // 事件文本是 CharSequence 列表：Toast 只有一项，通知可能有标题与正文。
        // 用换行拼接而不是只取首项——只取首项会丢掉通知正文，
        // 而正文往往才是自动化要判断的内容。
        val text = event.text
            .mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
        if (text.isBlank()) {
            return false
        }
        val className = event.className?.toString()
        record(
            TransientMessage(
                text = text,
                packageName = event.packageName?.toString(),
                toast = className == TOAST_CLASS_NAME,
                eventTimeMs = event.eventTime,
            ),
        )
        return true
    }

    @Synchronized
    private fun record(message: TransientMessage) {
        if (messages.size >= maxMessages) {
            messages.removeFirst()
        }
        messages.addLast(message)
    }

    /**
     * 返回已捕获的提示，按时间从旧到新。
     *
     * 返回快照副本而不是内部集合：调用方遍历期间可能有新事件到达，
     * 直接暴露内部集合会抛 ConcurrentModificationException。
     */
    @Synchronized
    fun messages(): List<TransientMessage> = messages.toList()

    /**
     * 只返回 Toast。
     *
     * 单独提供是因为二者的用途不同：Toast 几乎总是当前操作的直接反馈，
     * 而通知可能来自任何后台应用，混在一起会让判据不可靠。
     */
    @Synchronized
    fun toasts(): List<TransientMessage> = messages.filter(TransientMessage::toast)

    /** 清空缓冲。在一次操作前调用，可让之后的查询只包含该操作触发的提示。 */
    @Synchronized
    fun clear() {
        messages.clear()
    }

    private companion object {
        /**
         * 缓冲上限。
         *
         * 取 64：单次自动化步骤通常产生 0 到 2 条提示，64 足以覆盖一段较长的
         * 操作序列。上限存在的意义是防止长时间会话里通知持续累积占用内存。
         */
        const val DEFAULT_MAX_MESSAGES = 64

        /** Toast 事件的 className，平台以此区分 Toast 与通知。 */
        const val TOAST_CLASS_NAME = "android.widget.Toast"
    }
}
