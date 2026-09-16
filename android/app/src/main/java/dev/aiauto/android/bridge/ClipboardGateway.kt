package dev.aiauto.android.bridge

/**
 * 功能用途：实现桥侧剪贴板读写，供桌面端在无法使用 ADB 时读取或设置剪贴板文本。
 *
 * 为什么必须在 App 内实现：设备上不存在 `cmd clipboard` shell 命令
 * （实测返回 "No shell command implementation"），ADB 通道拿不到剪贴板。
 * 只有应用进程内的 ClipboardManager 能访问它。
 *
 * 读取受平台限制约束：自 Android 10（API 29）起，只有当应用持有输入焦点
 * 或是默认输入法时才能读到剪贴板内容，否则 primaryClip 返回 null。
 * 这不是缺陷而是隐私保护，因此读取失败要如实上报为「不可用」而不是「空」——
 * 两者对调用方的含义完全不同：前者要换通道，后者是有效结果。
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/** 剪贴板读取结果。 */
sealed interface ClipboardReadResult {
    /** 成功读到文本（可能为空串，那是有效结果）。 */
    data class Text(val value: String) : ClipboardReadResult

    /** 剪贴板为空：确实没有内容，而不是读不到。 */
    data object Empty : ClipboardReadResult

    /**
     * 无法读取。
     *
     * 与 [Empty] 分开是关键：API 29+ 下应用不在前台时读取被平台拒绝，
     * 此时把结果当成「空」会让调用方误以为剪贴板真的没内容。
     */
    data class Unavailable(val reason: String) : ClipboardReadResult
}

class ClipboardGateway(context: Context) {
    private val applicationContext = context.applicationContext

    private val manager: ClipboardManager?
        get() = runCatching {
            applicationContext.getSystemService(ClipboardManager::class.java)
        }.getOrNull()

    /**
     * 读取剪贴板首项的文本。
     *
     * 取 coerceToText 而非 text：剪贴板项可能是 URI 或 Intent，
     * coerceToText 会给出它们的可读表示，而直接取 text 在这些情况下返回 null，
     * 让调用方误判为空剪贴板。
     */
    fun read(): ClipboardReadResult {
        val clipboard = manager
            ?: return ClipboardReadResult.Unavailable("The clipboard service is not available")
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return ClipboardReadResult.Unavailable(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "Reading the clipboard requires the app to hold input focus on API 29+"
                } else {
                    "The clipboard could not be read"
                },
            )
        if (clip.itemCount == 0) {
            return ClipboardReadResult.Empty
        }
        val text = runCatching {
            clip.getItemAt(0).coerceToText(applicationContext)?.toString()
        }.getOrNull()
        return if (text == null) {
            ClipboardReadResult.Empty
        } else {
            ClipboardReadResult.Text(text)
        }
    }

    /**
     * 写入剪贴板文本。
     *
     * 写入不受读取那条前台限制约束，因此比读取可靠得多。
     * 返回 false 只在剪贴板服务本身不可用时出现。
     */
    fun write(text: String): Boolean {
        val clipboard = manager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            true
        }.getOrDefault(false)
    }

    private companion object {
        /**
         * 剪贴板项的标签。
         *
         * 用固定标签而非空串：部分输入法会显示该标签，
         * 明确标出来源比显示空白更有助于操作员识别这是自动化写入的内容。
         */
        const val CLIP_LABEL = "aactl"
    }
}
