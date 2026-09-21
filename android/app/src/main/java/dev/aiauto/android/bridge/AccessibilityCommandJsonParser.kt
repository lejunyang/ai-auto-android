package dev.aiauto.android.bridge

/**
 * 功能用途：实现 AccessibilityCommandJsonParser 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。
 */

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.ClipboardOperation
import dev.aiauto.android.accessibility.model.DeviceKey
import dev.aiauto.android.accessibility.model.DragAnchor
import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.NormalizedPoint
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorMatchMode
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
            "ui.notifications" -> AccessibilityCommand.Navigate(GlobalAction.NOTIFICATIONS)
            "ui.quickSettings" -> AccessibilityCommand.Navigate(GlobalAction.QUICK_SETTINGS)
            "ui.lockScreen" -> AccessibilityCommand.Navigate(GlobalAction.LOCK_SCREEN)
            "ui.powerDialog" -> AccessibilityCommand.Navigate(GlobalAction.POWER_DIALOG)
            "ui.dismissNotificationShade" ->
                AccessibilityCommand.Navigate(GlobalAction.DISMISS_NOTIFICATION_SHADE)

            "ui.drag" -> AccessibilityCommand.Drag(
                start = params.getValue("start").jsonObject.toDragAnchor(),
                end = params.getValue("end").jsonObject.toDragAnchor(),
                holdMs = params["holdMs"]?.jsonPrimitive?.int?.toLong() ?: 600L,
                moveMs = params["moveMs"]?.jsonPrimitive?.int?.toLong() ?: 500L,
            )

            "ui.multiTap" -> AccessibilityCommand.MultiTap(
                target = params["target"]?.jsonObject?.toNodeTarget(),
                point = params["point"]?.jsonObject?.toScreenPoint(),
                taps = params.getValue("taps").jsonPrimitive.int,
            )

            "ui.pinch" -> AccessibilityCommand.Pinch(
                target = params["target"]?.jsonObject?.toNodeTarget(),
                center = params["center"]?.jsonObject?.toScreenPoint(),
                scale = params.getValue("scale").jsonPrimitive.double,
                durationMs = params["durationMs"]?.jsonPrimitive?.int?.toLong() ?: 500L,
            )

            "ui.rotate" -> AccessibilityCommand.Rotate(
                target = params["target"]?.jsonObject?.toNodeTarget(),
                center = params["center"]?.jsonObject?.toScreenPoint(),
                degrees = params.getValue("degrees").jsonPrimitive.double,
                durationMs = params["durationMs"]?.jsonPrimitive?.int?.toLong() ?: 600L,
            )

            "ui.twoFingerScroll" -> AccessibilityCommand.TwoFingerScroll(
                target = params["target"]?.jsonObject?.toNodeTarget(),
                center = params["center"]?.jsonObject?.toScreenPoint(),
                deltaX = params.getValue("deltaX").jsonPrimitive.int,
                deltaY = params.getValue("deltaY").jsonPrimitive.int,
                durationMs = params["durationMs"]?.jsonPrimitive?.int?.toLong() ?: 400L,
            )

            "ui.pathGesture" -> AccessibilityCommand.PathGesture(
                points = (params.getValue("points") as JsonArray)
                    .map { it.jsonObject.toScreenPoint() },
                durationMs = params["durationMs"]?.jsonPrimitive?.int?.toLong() ?: 800L,
            )

            "ui.scrollIntoView" -> AccessibilityCommand.ScrollIntoView(
                target = params.target(),
                container = params["container"]?.jsonObject?.toNodeTarget(),
                direction = params["direction"]?.jsonPrimitive?.content
                    ?.uppercase()
                    ?.let(ScrollDirection::valueOf)
                    ?: ScrollDirection.FORWARD,
                maxScrolls = params["maxScrolls"]?.jsonPrimitive?.int ?: 12,
                amount = params["amount"]?.jsonPrimitive?.double ?: 0.6,
            )

            "ui.selectText" -> AccessibilityCommand.SelectText(
                target = params.target(),
                startIndex = params.getValue("startIndex").jsonPrimitive.int,
                endIndex = params["endIndex"]?.jsonPrimitive?.int,
            )

            "ui.copy" -> AccessibilityCommand.Clipboard(
                target = params.target(),
                operation = ClipboardOperation.COPY,
            )

            "ui.cut" -> AccessibilityCommand.Clipboard(
                target = params.target(),
                operation = ClipboardOperation.CUT,
            )

            "ui.paste" -> AccessibilityCommand.Clipboard(
                target = params.target(),
                operation = ClipboardOperation.PASTE,
            )

            "ui.nodeAction" -> AccessibilityCommand.PerformNodeAction(
                target = params.target(),
                action = params.getValue("action").jsonPrimitive.content.toNodeAction(),
            )

            "ui.setProgress" -> AccessibilityCommand.SetProgress(
                target = params.target(),
                value = params.getValue("value").jsonPrimitive.double.toFloat(),
            )

            "ui.scrollToPosition" -> AccessibilityCommand.ScrollToPosition(
                target = params.target(),
                row = params.getValue("row").jsonPrimitive.int,
                column = params["column"]?.jsonPrimitive?.int ?: 0,
            )

            "ui.pressKey" -> AccessibilityCommand.PressKey(
                key = params.getValue("key").jsonPrimitive.content.toDeviceKey(),
                repeatCount = params["repeatCount"]?.jsonPrimitive?.int ?: 1,
            )

            // ime.show 无参数：聚焦的是系统当前输入焦点，不接受选择器。
            // 语义是「聚焦输入控件」，键盘是否弹出由输入法决定，桥侧不作承诺。
            "ime.show" -> AccessibilityCommand.ImeShow

            // clipboard.set 无 target：写的是系统剪贴板而非某个节点。
            // 对应的 clipboard.get 刻意不接线，平台原因见 ClipboardGateway 注释。
            "clipboard.set" -> AccessibilityCommand.ClipboardSet(
                text = params.getValue("text").jsonPrimitive.content,
            )

            else -> throw BridgeException(
                code = BridgeErrorCode.CAPABILITY_UNAVAILABLE,
                message = "The action is valid but is not implemented by the accessibility bridge.",
            )
        }

    private fun JsonObject.toDragAnchor(): DragAnchor = DragAnchor(
        target = this["target"]?.jsonObject?.toNodeTarget(),
        point = this["point"]?.jsonObject?.toScreenPoint(),
    )

    private fun String.toNodeAction(): NodeAction = when (this) {
        "focus" -> NodeAction.FOCUS
        "clearFocus" -> NodeAction.CLEAR_FOCUS
        "select" -> NodeAction.SELECT
        "clearSelection" -> NodeAction.CLEAR_SELECTION
        "expand" -> NodeAction.EXPAND
        "collapse" -> NodeAction.COLLAPSE
        "dismiss" -> NodeAction.DISMISS
        else -> throw BridgeException(
            code = BridgeErrorCode.INVALID_ARGUMENT,
            message = "The node action is not supported.",
        )
    }

    private fun String.toDeviceKey(): DeviceKey = when (this) {
        "BACK" -> DeviceKey.BACK
        "HOME" -> DeviceKey.HOME
        "RECENTS" -> DeviceKey.RECENTS
        "ENTER" -> DeviceKey.ENTER
        "TAB" -> DeviceKey.TAB
        "ESCAPE" -> DeviceKey.ESCAPE
        "SPACE" -> DeviceKey.SPACE
        "DELETE" -> DeviceKey.DELETE
        "FORWARD_DEL" -> DeviceKey.FORWARD_DELETE
        "DPAD_UP" -> DeviceKey.DPAD_UP
        "DPAD_DOWN" -> DeviceKey.DPAD_DOWN
        "DPAD_LEFT" -> DeviceKey.DPAD_LEFT
        "DPAD_RIGHT" -> DeviceKey.DPAD_RIGHT
        "DPAD_CENTER" -> DeviceKey.DPAD_CENTER
        "MOVE_HOME" -> DeviceKey.MOVE_HOME
        "MOVE_END" -> DeviceKey.MOVE_END
        "PAGE_UP" -> DeviceKey.PAGE_UP
        "PAGE_DOWN" -> DeviceKey.PAGE_DOWN
        "VOLUME_UP" -> DeviceKey.VOLUME_UP
        "VOLUME_DOWN" -> DeviceKey.VOLUME_DOWN
        "VOLUME_MUTE" -> DeviceKey.VOLUME_MUTE
        else -> throw BridgeException(
            code = BridgeErrorCode.INVALID_ARGUMENT,
            message = "The key is not supported.",
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

    private fun JsonObject.toSelectorCandidate(): SelectorCandidate {
        val strategy = when (getValue("strategy").jsonPrimitive.content) {
            "resourceId" -> SelectorStrategy.RESOURCE_ID
            "contentDescription" -> SelectorStrategy.CONTENT_DESCRIPTION
            "text" -> SelectorStrategy.TEXT
            "role" -> SelectorStrategy.ROLE
            "ancestor" -> SelectorStrategy.ANCESTOR
            "fingerprint" -> SelectorStrategy.FINGERPRINT
            "className" -> SelectorStrategy.CLASS_NAME
            "package" -> SelectorStrategy.PACKAGE
            else -> throw BridgeException(
                code = BridgeErrorCode.INVALID_ARGUMENT,
                message = "The selector strategy is not supported.",
            )
        }
        val match = when (this["match"]?.jsonPrimitive?.content) {
            null, "exact" -> SelectorMatchMode.EXACT
            "contains" -> SelectorMatchMode.CONTAINS
            "startsWith" -> SelectorMatchMode.STARTS_WITH
            "endsWith" -> SelectorMatchMode.ENDS_WITH
            else -> throw BridgeException(
                code = BridgeErrorCode.INVALID_ARGUMENT,
                message = "The selector match mode is not supported.",
            )
        }
        // match 只对 text/contentDescription 有意义。标识符维度上用非精确匹配会
        // 命中意外元素（btn_ok 与 btn_ok_cancel），schema 无法表达这条跨字段约束，
        // 在此运行时拒绝。
        val textual = strategy == SelectorStrategy.TEXT ||
            strategy == SelectorStrategy.CONTENT_DESCRIPTION
        if (!textual && match != SelectorMatchMode.EXACT) {
            throw BridgeException(
                code = BridgeErrorCode.INVALID_ARGUMENT,
                message = "match applies only to text and contentDescription selectors.",
            )
        }
        return SelectorCandidate(
            strategy = strategy,
            value = getValue("value").jsonPrimitive.content,
            weight = getValue("weight").jsonPrimitive.double,
            required = this["required"]?.jsonPrimitive?.boolean ?: false,
            match = match,
        )
    }

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
