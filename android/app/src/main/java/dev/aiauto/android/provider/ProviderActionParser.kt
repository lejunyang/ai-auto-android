package dev.aiauto.android.provider

/**
 * 功能用途：实现 ProviderActionParser 对应的 AI Provider 配置、调用、动作解析或敏感日志保护。
 */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class ProviderActionParser(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    },
) {
    fun parse(rawAction: String): ProviderAction {
        val root = runCatching { json.parseToJsonElement(rawAction).jsonObject }
            .getOrElse { throw ProviderActionParseException("Provider response must be one JSON object") }

        requireExactKeys(root, required = setOf("type", "params"))
        val type = root.string("type")
        val params = root.objectValue("params")

        when (type) {
            "app.launch" -> validateAppLaunch(params)
            "app.stop" -> validatePackageOnly(params)
            "ui.click" -> validateTargetOnly(params)
            "ui.longClick" -> validateLongClick(params)
            "ui.tap" -> validatePoint(params)
            "ui.swipe" -> validateSwipe(params)
            "ui.setText" -> validateSetText(params)
            "ui.scroll" -> validateScroll(params)
            "ui.back", "ui.home", "ui.recents" -> requireExactKeys(params, emptySet())
            "ui.wait", "ui.assert" -> validatePredicate(params)
            "task.finish" -> validateTaskFinish(params)
            else -> throw ProviderActionParseException("Unsupported action type: $type")
        }

        return ProviderAction(type = type, params = params)
    }

    private fun validateAppLaunch(params: JsonObject) {
        requireExactKeys(params, setOf("packageName"), setOf("activity"))
        requirePackageName(params.string("packageName"))
        params.optionalString("activity")?.requireLength("activity", 1, 512)
    }

    private fun validatePackageOnly(params: JsonObject) {
        requireExactKeys(params, setOf("packageName"))
        requirePackageName(params.string("packageName"))
    }

    private fun validateTargetOnly(params: JsonObject) {
        requireExactKeys(params, setOf("target"))
        validateTarget(params.objectValue("target"))
    }

    private fun validateLongClick(params: JsonObject) {
        requireExactKeys(params, setOf("target"), setOf("durationMs"))
        validateTarget(params.objectValue("target"))
        params.optionalInt("durationMs")?.requireRange("durationMs", 300, 10_000)
    }

    private fun validatePoint(point: JsonObject) {
        requireExactKeys(point, setOf("x", "y"))
        point.int("x").requireRange("x", 0, 100_000)
        point.int("y").requireRange("y", 0, 100_000)
    }

    private fun validateSwipe(params: JsonObject) {
        requireExactKeys(params, setOf("start", "end", "durationMs"))
        validatePoint(params.objectValue("start"))
        validatePoint(params.objectValue("end"))
        params.int("durationMs").requireRange("durationMs", 1, 60_000)
    }

    private fun validateSetText(params: JsonObject) {
        requireExactKeys(params, setOf("target"), setOf("text", "secretRef"))
        validateTarget(params.objectValue("target"))
        val text = params.optionalString("text")
        val secretRef = params.optionalString("secretRef")
        if ((text == null) == (secretRef == null)) {
            throw ProviderActionParseException("ui.setText requires exactly one of text or secretRef")
        }
        if (text != null && text.length > 10_000) {
            throw ProviderActionParseException("text exceeds 10000 characters")
        }
        if (secretRef != null && !SECRET_REF.matches(secretRef)) {
            throw ProviderActionParseException("secretRef has an invalid format")
        }
        secretRef?.requireLength("secretRef", 1, 128)
    }

    private fun validateScroll(params: JsonObject) {
        requireExactKeys(params, setOf("direction"), setOf("target", "amount"))
        params["target"]?.let { validateTarget(it.asObject("target")) }
        val direction = params.string("direction")
        if (direction !in SCROLL_DIRECTIONS) {
            throw ProviderActionParseException("Unsupported scroll direction: $direction")
        }
        params.optionalDouble("amount")?.let {
            if (it <= 0 || it > 1) {
                throw ProviderActionParseException("amount must be greater than 0 and at most 1")
            }
        }
    }

    private fun validatePredicate(params: JsonObject) {
        requireExactKeys(
            params,
            required = setOf("kind", "operator"),
            optional = setOf("target", "expected", "stableDurationMs", "timeoutMs"),
        )
        if (params.string("kind") !in PREDICATE_KINDS) {
            throw ProviderActionParseException("Unsupported predicate kind")
        }
        if (params.string("operator") !in PREDICATE_OPERATORS) {
            throw ProviderActionParseException("Unsupported predicate operator")
        }
        params["target"]?.let { validateTarget(it.asObject("target")) }
        params["expected"]?.let(::validateScalar)
        params.optionalInt("stableDurationMs")?.requireRange("stableDurationMs", 100, 30_000)
        params.optionalInt("timeoutMs")?.requireRange("timeoutMs", 1, 300_000)
    }

    private fun validateTaskFinish(params: JsonObject) {
        requireExactKeys(params, emptySet(), setOf("summary"))
        params.optionalString("summary")?.let {
            if (it.length > 4_096) {
                throw ProviderActionParseException("summary exceeds 4096 characters")
            }
        }
    }

    private fun validateTarget(target: JsonObject) {
        val allowed = setOf(
            "packageName",
            "selectorCandidates",
            "fingerprint",
            "recordedBounds",
            "relativePoint",
            "normalizedScreenPoint",
        )
        if (target.keys.any { it !in allowed }) {
            throw ProviderActionParseException("Target contains unsupported fields")
        }
        target.optionalString("packageName")?.let(::requirePackageName)
        val hasSelector = target["selectorCandidates"]?.let(::validateSelectorCandidates) != null
        target["fingerprint"]?.let(::validateFingerprint)
        target["recordedBounds"]?.let(::validateBounds)
        target["relativePoint"]?.let { validateNormalizedPoint(it, "relativePoint") }
        target["normalizedScreenPoint"]?.let {
            validateNormalizedPoint(it, "normalizedScreenPoint")
        }
        val hasRelativePoint = target["recordedBounds"] != null && target["relativePoint"] != null
        val hasScreenPoint = target["normalizedScreenPoint"] != null
        if (!hasSelector && !hasRelativePoint && !hasScreenPoint) {
            throw ProviderActionParseException("Target must contain a selector or coordinate fallback")
        }
    }

    private fun validateSelectorCandidates(element: JsonElement) {
        val candidates = element as? JsonArray
            ?: throw ProviderActionParseException("selectorCandidates must be an array")
        if (candidates.size !in 1..16) {
            throw ProviderActionParseException("selectorCandidates must contain between 1 and 16 items")
        }
        candidates.forEachIndexed { index, candidateElement ->
            val candidate = candidateElement.asObject("selectorCandidates[$index]")
            requireExactKeys(
                candidate,
                required = setOf("strategy", "value", "weight"),
                optional = setOf("required"),
            )
            val strategy = candidate.string("strategy")
            if (strategy !in SELECTOR_STRATEGIES) {
                throw ProviderActionParseException("Unsupported selector strategy: $strategy")
            }
            candidate.string("value").requireLength("value", 1, 1_024)
            candidate.double("weight").requireRange("weight", 0.0, 1.0)
            candidate.optionalBoolean("required")
        }
    }

    private fun validateFingerprint(element: JsonElement) {
        val fingerprint = element.asObject("fingerprint")
        if (fingerprint.size !in 1..32) {
            throw ProviderActionParseException("fingerprint must contain between 1 and 32 fields")
        }
        fingerprint.values.forEach(::validateScalar)
    }

    private fun validateBounds(element: JsonElement) {
        val bounds = element.asObject("recordedBounds")
        requireExactKeys(bounds, setOf("left", "top", "right", "bottom"))
        bounds.int("left").requireRange("left", 0, 100_000)
        bounds.int("top").requireRange("top", 0, 100_000)
        bounds.int("right").requireRange("right", 0, 100_000)
        bounds.int("bottom").requireRange("bottom", 0, 100_000)
    }

    private fun validateNormalizedPoint(element: JsonElement, name: String) {
        val point = element.asObject(name)
        requireExactKeys(point, setOf("x", "y"))
        point.double("x").requireRange("x", 0.0, 1.0)
        point.double("y").requireRange("y", 0.0, 1.0)
    }

    private fun validateScalar(element: JsonElement) {
        if (element is JsonNull) {
            return
        }
        val primitive = element as? JsonPrimitive
        if (primitive == null) {
            throw ProviderActionParseException("expected must be a scalar value")
        }
        if (!primitive.isString) {
            primitive.booleanOrNull ?: primitive.doubleOrNull
                ?: throw ProviderActionParseException("expected must be a scalar value")
        }
    }

    private fun requireExactKeys(
        value: JsonObject,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val missing = required - value.keys
        val extra = value.keys - required - optional
        if (missing.isNotEmpty()) {
            throw ProviderActionParseException("Missing fields: ${missing.sorted().joinToString()}")
        }
        if (extra.isNotEmpty()) {
            throw ProviderActionParseException("Unsupported fields: ${extra.sorted().joinToString()}")
        }
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw ProviderActionParseException("$name must be a string")

    private fun JsonObject.optionalString(name: String): String? {
        val value = this[name] ?: return null
        return (value as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw ProviderActionParseException("$name must be a string")
    }

    private fun JsonObject.int(name: String): Int =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?: throw ProviderActionParseException("$name must be an integer")

    private fun JsonObject.optionalInt(name: String): Int? {
        val value = this[name] ?: return null
        return (value as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?: throw ProviderActionParseException("$name must be an integer")
    }

    private fun JsonObject.optionalDouble(name: String): Double? {
        val value = this[name] ?: return null
        return (value as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.doubleOrNull
            ?: throw ProviderActionParseException("$name must be a number")
    }

    private fun JsonObject.double(name: String): Double =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.doubleOrNull
            ?: throw ProviderActionParseException("$name must be a number")

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = this[name] ?: return null
        return (value as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.booleanOrNull
            ?: throw ProviderActionParseException("$name must be a boolean")
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        this[name]?.asObject(name)
            ?: throw ProviderActionParseException("$name must be an object")

    private fun JsonElement.asObject(name: String): JsonObject =
        this as? JsonObject ?: throw ProviderActionParseException("$name must be an object")

    private fun Int.requireRange(name: String, minimum: Int, maximum: Int) {
        if (this !in minimum..maximum) {
            throw ProviderActionParseException("$name must be between $minimum and $maximum")
        }
    }

    private fun Double.requireRange(name: String, minimum: Double, maximum: Double) {
        if (!isFinite() || this < minimum || this > maximum) {
            throw ProviderActionParseException("$name must be between $minimum and $maximum")
        }
    }

    private fun String.requireLength(name: String, minimum: Int, maximum: Int) {
        if (length !in minimum..maximum) {
            throw ProviderActionParseException(
                "$name must contain between $minimum and $maximum characters",
            )
        }
    }

    private fun requirePackageName(packageName: String) {
        if (packageName.length !in 3..255 || !PACKAGE_NAME.matches(packageName)) {
            throw ProviderActionParseException("packageName has an invalid format")
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val SECRET_REF = Regex("^[A-Za-z][A-Za-z0-9._-]*$")
        val SELECTOR_STRATEGIES = setOf(
            "resourceId",
            "contentDescription",
            "text",
            "role",
            "ancestor",
            "fingerprint",
        )
        val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right", "forward", "backward")
        val PREDICATE_KINDS = setOf("node", "text", "package", "window", "uiStable")
        val PREDICATE_OPERATORS = setOf("exists", "notExists", "equals", "contains", "matches", "stable")
    }
}
