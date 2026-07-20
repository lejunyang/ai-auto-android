package dev.aiauto.android.bridge

/**
 * 功能用途：实现 AccessibilityCommandJsonParser 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。
 */

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.NormalizedPoint
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.provider.ProviderAction
import dev.aiauto.android.provider.ProviderActionParseException
import dev.aiauto.android.provider.ProviderActionParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AccessibilityCommandJsonParser(
    private val actionParser: ProviderActionParser = ProviderActionParser(),
) {
    @Throws(BridgeException::class)
    fun parse(rawAction: JsonObject): AccessibilityCommand {
        val action = try {
            actionParser.parse(rawAction.toString())
        } catch (error: ProviderActionParseException) {
            throw BridgeException(
                code = BridgeErrorCode.INVALID_ARGUMENT,
                message = error.message ?: "The action does not match protocol v1.",
            )
        }
        return action.toAccessibilityCommand()
    }

    private fun ProviderAction.toAccessibilityCommand(): AccessibilityCommand =
        when (type) {
            "ui.click" -> AccessibilityCommand.Click(params.target())
            "ui.longClick" -> AccessibilityCommand.LongClick(
                target = params.target(),
                durationMs = params["durationMs"]?.jsonPrimitive?.int?.toLong() ?: 600L,
            )

            "ui.setText" -> {
                val text = params["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw BridgeException(
                        code = BridgeErrorCode.ACTION_NOT_ALLOWED,
                        message = "Bridge text input does not resolve secretRef values.",
                    )
                AccessibilityCommand.SetText(target = params.target(), text = text)
            }

            "ui.scroll" -> AccessibilityCommand.Scroll(
                direction = ScrollDirection.valueOf(
                    params["direction"]!!.jsonPrimitive.content.uppercase(),
                ),
                target = params["target"]?.jsonObject?.toNodeTarget(),
                amount = params["amount"]?.jsonPrimitive?.double ?: 0.8,
            )

            "ui.tap" -> AccessibilityCommand.Tap(params.toScreenPoint())
            "ui.swipe" -> AccessibilityCommand.Swipe(
                start = params["start"]!!.jsonObject.toScreenPoint(),
                end = params["end"]!!.jsonObject.toScreenPoint(),
                durationMs = params["durationMs"]!!.jsonPrimitive.int.toLong(),
            )

            "ui.back" -> AccessibilityCommand.Navigate(GlobalAction.BACK)
            "ui.home" -> AccessibilityCommand.Navigate(GlobalAction.HOME)
            "ui.recents" -> AccessibilityCommand.Navigate(GlobalAction.RECENTS)
            else -> throw BridgeException(
                code = BridgeErrorCode.CAPABILITY_UNAVAILABLE,
                message = "The action is valid but is not implemented by the accessibility bridge.",
            )
        }

    private fun JsonObject.target(): NodeTarget =
        getValue("target").jsonObject.toNodeTarget()

    private fun JsonObject.toNodeTarget(): NodeTarget = NodeTarget(
        packageName = this["packageName"]?.jsonPrimitive?.contentOrNull,
        selectorCandidates = (this["selectorCandidates"] as? JsonArray)
            ?.map { it.jsonObject.toSelectorCandidate() }
            .orEmpty(),
        fingerprint = this["fingerprint"]
            ?.jsonObject
            ?.mapValues { (_, value) -> value.scalarContentOrNull() }
            .orEmpty(),
        recordedBounds = this["recordedBounds"]?.jsonObject?.let { bounds ->
            UiBounds(
                left = bounds.getValue("left").jsonPrimitive.int,
                top = bounds.getValue("top").jsonPrimitive.int,
                right = bounds.getValue("right").jsonPrimitive.int,
                bottom = bounds.getValue("bottom").jsonPrimitive.int,
            )
        },
        relativePoint = this["relativePoint"]?.jsonObject?.toNormalizedPoint(),
        normalizedScreenPoint = this["normalizedScreenPoint"]
            ?.jsonObject
            ?.toNormalizedPoint(),
    )

    private fun JsonObject.toSelectorCandidate(): SelectorCandidate = SelectorCandidate(
        strategy = when (getValue("strategy").jsonPrimitive.content) {
            "resourceId" -> SelectorStrategy.RESOURCE_ID
            "contentDescription" -> SelectorStrategy.CONTENT_DESCRIPTION
            "text" -> SelectorStrategy.TEXT
            "role" -> SelectorStrategy.ROLE
            "ancestor" -> SelectorStrategy.ANCESTOR
            "fingerprint" -> SelectorStrategy.FINGERPRINT
            else -> throw BridgeException(
                code = BridgeErrorCode.INVALID_ARGUMENT,
                message = "The selector strategy is not supported.",
            )
        },
        value = getValue("value").jsonPrimitive.content,
        weight = getValue("weight").jsonPrimitive.double,
        required = this["required"]?.jsonPrimitive?.boolean ?: false,
    )

    private fun JsonObject.toScreenPoint(): ScreenPoint = ScreenPoint(
        x = getValue("x").jsonPrimitive.int,
        y = getValue("y").jsonPrimitive.int,
    )

    private fun JsonObject.toNormalizedPoint(): NormalizedPoint = NormalizedPoint(
        x = getValue("x").jsonPrimitive.double,
        y = getValue("y").jsonPrimitive.double,
    )

    private fun JsonElement.scalarContentOrNull(): String? = when (this) {
        JsonNull -> null
        is JsonPrimitive -> content
        else -> null
    }
}
