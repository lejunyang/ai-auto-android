package dev.aiauto.android.automation.session

/**
 * 功能用途：实现 UiContextBuilder 对应的受控 AI 自动化会话、风险判断与生命周期管理。
 */

import dev.aiauto.android.accessibility.model.UiNodeSnapshot

class UiContextBuilder(
    private val maxNodes: Int = 80,
    private val maxDepth: Int = 8,
    private val maxTextLength: Int = 120,
    private val maxSummaryLength: Int = 12_000,
) {
    init {
        require(maxNodes in 1..500)
        require(maxDepth in 1..20)
        require(maxTextLength in 8..1_024)
        require(maxSummaryLength in 256..100_000)
    }

    fun build(root: UiNodeSnapshot): String {
        val summary = StringBuilder()
        var visited = 0

        fun appendNode(node: UiNodeSnapshot, depth: Int) {
            if (visited >= maxNodes || depth > maxDepth || summary.length >= maxSummaryLength) {
                return
            }
            visited += 1
            summary.append("  ".repeat(depth))
                .append("- role=")
                .append(node.className?.substringAfterLast('.') ?: "unknown")
            node.resourceId?.takeLast(maxTextLength)?.let {
                summary.append(" id=").append(sanitize(it))
            }
            if (!node.state.sensitive && !node.state.password) {
                node.text?.takeIf(String::isNotBlank)?.let {
                    summary.append(" text=").append(sanitize(it))
                }
                node.contentDescription?.takeIf(String::isNotBlank)?.let {
                    summary.append(" description=").append(sanitize(it))
                }
            }
            summary.append(" bounds=")
                .append(node.bounds.left)
                .append(',')
                .append(node.bounds.top)
                .append(',')
                .append(node.bounds.right)
                .append(',')
                .append(node.bounds.bottom)
                .append(" flags=")
                .append(
                    buildList {
                        if (node.state.clickable) add("clickable")
                        if (node.state.editable) add("editable")
                        if (node.state.scrollable) add("scrollable")
                        if (node.state.checked) add("checked")
                        if (node.state.selected) add("selected")
                        if (node.state.sensitive || node.state.password) add("sensitive")
                    }.joinToString(","),
                )
                .appendLine()
            node.children.forEach { appendNode(it, depth + 1) }
        }

        summary.append("package=").append(root.packageName.orEmpty()).appendLine()
        appendNode(root, depth = 0)
        if (visited >= maxNodes) {
            summary.appendLine("[node-limit-reached]")
        }
        return summary.take(maxSummaryLength).toString()
    }

    private fun sanitize(value: String): String =
        value
            .replace(WHITESPACE, " ")
            .replace(CONTROL_CHARACTERS, "")
            .trim()
            .take(maxTextLength)

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
    }
}
