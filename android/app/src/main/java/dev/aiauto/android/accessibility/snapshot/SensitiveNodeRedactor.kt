package dev.aiauto.android.accessibility.snapshot

// 功能用途：实现 SensitiveNodeRedactor 对应的无障碍节点快照与敏感信息最小化处理。

import java.util.Locale

import dev.aiauto.android.accessibility.model.UiNodeSnapshot

class SensitiveNodeRedactor {
    fun redact(root: UiNodeSnapshot): UiNodeSnapshot =
        redactNode(node = root, inheritedSensitive = false)

    private fun redactNode(
        node: UiNodeSnapshot,
        inheritedSensitive: Boolean,
    ): UiNodeSnapshot {
        val sensitive = inheritedSensitive || node.state.password || hasSensitiveMetadata(node)
        return node.copy(
            text = node.text.takeUnless { sensitive },
            contentDescription = node.contentDescription.takeUnless { sensitive },
            state = node.state.copy(sensitive = sensitive),
            children = node.children.map { child ->
                redactNode(node = child, inheritedSensitive = sensitive)
            },
        )
    }

    private fun hasSensitiveMetadata(node: UiNodeSnapshot): Boolean {
        val metadata = listOfNotNull(
            node.resourceId,
            node.text,
            node.contentDescription,
        ).joinToString(separator = " ").lowercase(Locale.ROOT)

        return SENSITIVE_PATTERN.containsMatchIn(metadata) ||
            SENSITIVE_TERMS.any(metadata::contains)
    }

    private companion object {
        val SENSITIVE_PATTERN = Regex(
            pattern = """
                password|passwd|passcode|verification[\s_-]*code|one[\s_-]*time|
                otp|(?:^|[\W_])pin(?:$|[\W_])|payment|credit[\s_-]*card|
                card[\s_-]*number|security[\s_-]*code|cvv|cvc|
                bank[\s_-]*account|routing[\s_-]*number
            """.trimIndent().replace(Regex("[\\n ]+"), ""),
            option = RegexOption.IGNORE_CASE,
        )

        val SENSITIVE_TERMS = listOf(
            "密码",
            "验证码",
            "支付",
            "银行卡",
            "信用卡",
        )
    }
}
