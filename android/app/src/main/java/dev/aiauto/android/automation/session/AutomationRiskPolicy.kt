package dev.aiauto.android.automation.session

/**
 * 功能用途：实现 AutomationRiskPolicy 对应的受控 AI 自动化会话、风险判断与生命周期管理。
 */

import dev.aiauto.android.provider.ProviderAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AutomationRiskPolicy(
    private val allowedPackages: Set<String>,
) {
    fun evaluateTask(task: String, targetPackage: String): RiskDecision {
        if (targetPackage !in allowedPackages) {
            return RiskDecision.Block("The target package is not in the configured allowlist.")
        }
        if (targetPackage.matchesBlockedPackage()) {
            return RiskDecision.Block(
                "Payment, package installer, and system settings targets are not allowed.",
            )
        }
        if (task.normalized().containsAny(BLOCKED_TASK_TERMS)) {
            return RiskDecision.Block(
                "Payment, installation, permission, or system-setting tasks are not allowed.",
            )
        }
        return RiskDecision.Allow
    }

    fun evaluateAction(action: ProviderAction, targetPackage: String): RiskDecision {
        if (action.type !in ALLOWED_ACTION_TYPES) {
            return RiskDecision.Block("The provider returned an unsupported action.")
        }
        if (action.type in TARGETED_ACTION_TYPES && action.targetPackage() != targetPackage) {
            return RiskDecision.Block(
                "The action must explicitly target the authorized package.",
            )
        }
        val actionPackages = action.packageNames()
        if (actionPackages.any { it != targetPackage || it !in allowedPackages }) {
            return RiskDecision.Block("The action attempts to leave the authorized target package.")
        }
        if (
            action.type in BLOCKED_ACTION_TYPES ||
            action.type in TARGET_ESCAPE_ACTION_TYPES ||
            action.type.startsWithAny(BLOCKED_PREFIXES)
        ) {
            return RiskDecision.Block("The requested action is blocked by the local safety policy.")
        }

        val semanticTarget = action.semanticTarget().normalized()
        if (semanticTarget.containsAny(BLOCKED_ACTION_TERMS)) {
            return RiskDecision.Block(
                "The action appears to involve payment, permission, or installation.",
            )
        }
        if (
            action.type == "ui.tap" ||
            action.hasVisualTarget() ||
            action.type in CONFIRMATION_ACTION_TYPES &&
            semanticTarget.containsAny(CONFIRMATION_TERMS)
        ) {
            return RiskDecision.RequireConfirmation(
                "This action can send, submit, delete, or otherwise change external data.",
            )
        }
        return RiskDecision.Allow
    }

    private fun ProviderAction.packageNames(): Set<String> = buildSet {
        params["packageName"]?.jsonPrimitive?.contentOrNull?.let(::add)
        targetPackage()?.let(::add)
    }

    private fun ProviderAction.targetPackage(): String? =
        sequenceOf("target", "visualTarget")
            .mapNotNull { key ->
                params[key]
                    ?.let { it as? JsonObject }
                    ?.get("packageName")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            .firstOrNull()

    private fun ProviderAction.hasVisualTarget(): Boolean =
        params["visualTarget"] is JsonObject

    private fun ProviderAction.semanticTarget(): String = when (type) {
        "ui.click", "ui.longClick" -> params["target"]?.flattenStrings().orEmpty()
        "app.launch", "app.stop" -> params.flattenStrings()
        else -> ""
    }

    private fun JsonElement.flattenStrings(): String = when (this) {
        is JsonObject -> entries.joinToString(" ") { (key, value) ->
            "$key ${value.flattenStrings()}"
        }

        is JsonArray -> joinToString(" ") { it.flattenStrings() }
        is JsonPrimitive -> contentOrNull.orEmpty()
    }

    private fun String.matchesBlockedPackage(): Boolean {
        val normalized = lowercase()
        return BLOCKED_PACKAGE_PARTS.any(normalized::contains)
    }

    private fun String.normalized(): String = lowercase().replace(NON_WORD, " ")

    private fun String.containsAny(values: Set<String>): Boolean = values.any(::contains)

    private fun String.startsWithAny(values: Set<String>): Boolean = values.any(::startsWith)

    private companion object {
        val NON_WORD = Regex("[^\\p{L}\\p{N}._-]+")

        val ALLOWED_ACTION_TYPES = setOf(
            "app.launch",
            "app.stop",
            "ui.click",
            "ui.longClick",
            "ui.tap",
            "ui.swipe",
            "ui.setText",
            "ui.scroll",
            "ui.back",
            "ui.home",
            "ui.recents",
            "ui.wait",
            "ui.assert",
            "task.finish",
        )
        val BLOCKED_ACTION_TYPES = setOf(
            "app.stop",
            "app.install",
            "app.uninstall",
            "permission.grant",
            "payment.confirm",
            "system.settings",
        )
        val BLOCKED_PREFIXES = setOf("payment.", "permission.", "system.", "package.")
        val CONFIRMATION_ACTION_TYPES = setOf("ui.click", "ui.longClick")
        val TARGETED_ACTION_TYPES = setOf(
            "ui.click",
            "ui.longClick",
            "ui.tap",
            "ui.swipe",
            "ui.setText",
        )
        val TARGET_ESCAPE_ACTION_TYPES = setOf("ui.home", "ui.recents")
        val BLOCKED_PACKAGE_PARTS = setOf(
            "packageinstaller",
            "permissioncontroller",
            "settings",
            "wallet",
            "payment",
            "bank",
        )
        val BLOCKED_TASK_TERMS = setOf(
            "pay",
            "payment",
            "purchase",
            "checkout",
            "transfer money",
            "install",
            "uninstall",
            "grant permission",
            "system setting",
            "\u652f\u4ed8",
            "\u8f6c\u8d26",
            "\u8d2d\u4e70",
            "\u5b89\u88c5",
            "\u5378\u8f7d",
            "\u6743\u9650",
        )
        val BLOCKED_ACTION_TERMS = setOf(
            "pay",
            "payment",
            "purchase",
            "checkout",
            "transfer",
            "install",
            "permission",
            "authorize",
            "\u652f\u4ed8",
            "\u8f6c\u8d26",
            "\u8d2d\u4e70",
            "\u5b89\u88c5",
            "\u6388\u6743",
        )
        val CONFIRMATION_TERMS = setOf(
            "send",
            "submit",
            "post",
            "publish",
            "delete",
            "remove",
            "confirm",
            "\u53d1\u9001",
            "\u63d0\u4ea4",
            "\u53d1\u5e03",
            "\u5220\u9664",
            "\u79fb\u9664",
            "\u786e\u8ba4",
        )
    }
}
