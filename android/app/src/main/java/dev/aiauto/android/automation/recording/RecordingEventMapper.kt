package dev.aiauto.android.automation.recording

/**
 * 功能用途：实现 RecordingEventMapper 对应的语义录制、脚本持久化或确定性回放能力。
 */

import java.util.Locale

import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.selector.NodeFingerprint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

enum class RecordingEventType {
    CLICK,
    LONG_CLICK,
    TEXT_CHANGED,
    SCROLLED,
    WINDOW_CHANGED,
}

data class RecordingEvent(
    val type: RecordingEventType,
    val eventTimeMs: Long,
    val packageName: String,
    val source: UiNodeSnapshot? = null,
    val text: String? = null,
    val sensitive: Boolean = false,
    val scrollDeltaX: Int = 0,
    val scrollDeltaY: Int = 0,
)

data class MappedRecordingAction(
    val action: RecordedAction,
    val secretVariable: ScriptVariable? = null,
)

class RecordingEventMapper {
    fun map(event: RecordingEvent): MappedRecordingAction? = when (event.type) {
        RecordingEventType.CLICK -> event.source?.let {
            MappedRecordingAction(
                RecordedAction(
                    type = "ui.click",
                    params = buildJsonObject { put("target", it.toTargetJson()) },
                ),
            )
        }

        RecordingEventType.LONG_CLICK -> event.source?.let {
            MappedRecordingAction(
                RecordedAction(
                    type = "ui.longClick",
                    params = buildJsonObject { put("target", it.toTargetJson()) },
                ),
            )
        }

        RecordingEventType.TEXT_CHANGED -> mapText(event)
        RecordingEventType.SCROLLED -> event.source?.let { source ->
            MappedRecordingAction(
                RecordedAction(
                    type = "ui.scroll",
                    params = buildJsonObject {
                        put("target", source.toTargetJson())
                        put("direction", JsonPrimitive(event.scrollDirection()))
                        put("amount", JsonPrimitive(DEFAULT_SCROLL_AMOUNT))
                    },
                ),
            )
        }

        RecordingEventType.WINDOW_CHANGED -> MappedRecordingAction(
            RecordedAction(
                type = "ui.wait",
                params = buildJsonObject {
                    put("kind", JsonPrimitive("package"))
                    put("operator", JsonPrimitive("equals"))
                    put("expected", JsonPrimitive(event.packageName))
                    put("timeoutMs", JsonPrimitive(DEFAULT_WAIT_TIMEOUT_MS))
                },
            ),
        )
    }

    private fun mapText(event: RecordingEvent): MappedRecordingAction? {
        val source = event.source ?: return null
        val sensitive = event.sensitive || source.state.password || source.state.sensitive
        val target = source.toTargetJson(includeText = !sensitive)
        if (sensitive) {
            val variableName = "input-${NodeFingerprint.create(source).take(12)}"
            return MappedRecordingAction(
                action = RecordedAction(
                    type = "ui.setText",
                    params = buildJsonObject {
                        put("target", target)
                        put("secretRef", JsonPrimitive(variableName))
                    },
                ),
                secretVariable = ScriptVariable(
                    name = variableName,
                    type = "secret",
                    sensitive = true,
                ),
            )
        }

        val text = event.text ?: source.text ?: return null
        return MappedRecordingAction(
            RecordedAction(
                type = "ui.setText",
                params = buildJsonObject {
                    put("target", target)
                    put("text", JsonPrimitive(text.take(MAX_RECORDED_TEXT_LENGTH)))
                },
            ),
        )
    }

    private fun RecordingEvent.scrollDirection(): String = when {
        scrollDeltaY > 0 -> "down"
        scrollDeltaY < 0 -> "up"
        scrollDeltaX > 0 -> "right"
        scrollDeltaX < 0 -> "left"
        else -> "forward"
    }

    private fun UiNodeSnapshot.toTargetJson(includeText: Boolean = true): JsonObject {
        val selectors = selectorCandidates(includeText)
        return buildJsonObject {
            packageName?.takeIf(String::isNotBlank)?.let {
                put("packageName", JsonPrimitive(it))
            }
            if (selectors.isNotEmpty()) {
                put("selectorCandidates", JsonArray(selectors))
            }
            put(
                "fingerprint",
                buildJsonObject {
                    packageName?.let { put("packageName", JsonPrimitive(it)) }
                    className?.let { put("className", JsonPrimitive(it)) }
                    resourceId?.let { put("resourceId", JsonPrimitive(it)) }
                    put("clickable", JsonPrimitive(state.clickable))
                    put("editable", JsonPrimitive(state.editable))
                    put("scrollable", JsonPrimitive(state.scrollable))
                },
            )
            put(
                "recordedBounds",
                buildJsonObject {
                    put("left", JsonPrimitive(bounds.left))
                    put("top", JsonPrimitive(bounds.top))
                    put("right", JsonPrimitive(bounds.right))
                    put("bottom", JsonPrimitive(bounds.bottom))
                },
            )
            put(
                "relativePoint",
                buildJsonObject {
                    put("x", JsonPrimitive(0.5))
                    put("y", JsonPrimitive(0.5))
                },
            )
        }
    }

    private fun UiNodeSnapshot.selectorCandidates(includeText: Boolean): List<JsonObject> =
        buildList {
            resourceId?.takeIf(String::isNotBlank)?.let {
                add(selector("resourceId", it, 0.45, required = true))
            }
            contentDescription?.takeIf(String::isNotBlank)?.let {
                add(selector("contentDescription", it, 0.25))
            }
            if (includeText) {
                text?.takeIf(String::isNotBlank)?.let {
                    add(selector("text", it.take(MAX_SELECTOR_TEXT_LENGTH), 0.20))
                }
            }
            role()?.takeIf(String::isNotBlank)?.let {
                add(selector("role", it, 0.10))
            }
            add(selector("fingerprint", NodeFingerprint.create(this@selectorCandidates), 0.15))
        }

    private fun UiNodeSnapshot.role(): String {
        val simpleName = className
            ?.substringAfterLast('.')
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when {
            "button" in simpleName -> "button"
            "edittext" in simpleName -> "textfield"
            "checkbox" in simpleName -> "checkbox"
            "radiobutton" in simpleName -> "radio"
            "switch" in simpleName -> "switch"
            "list" in simpleName || "recyclerview" in simpleName -> "list"
            "text" in simpleName -> "text"
            else -> simpleName
        }
    }

    private fun selector(
        strategy: String,
        value: String,
        weight: Double,
        required: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("strategy", JsonPrimitive(strategy))
        put("value", JsonPrimitive(value))
        put("weight", JsonPrimitive(weight))
        if (required) {
            put("required", JsonPrimitive(true))
        }
    }

    private companion object {
        const val DEFAULT_SCROLL_AMOUNT = 0.8
        const val DEFAULT_WAIT_TIMEOUT_MS = 5_000L
        const val MAX_RECORDED_TEXT_LENGTH = 10_000
        const val MAX_SELECTOR_TEXT_LENGTH = 1_024
    }
}
