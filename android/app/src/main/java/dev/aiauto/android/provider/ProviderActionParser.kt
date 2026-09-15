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

        return validate(ProviderAction(type = type, params = params))
    }

    fun validate(action: ProviderAction): ProviderAction {
        val type = action.type
        val params = action.params
        when (type) {
            "app.launch" -> validateAppLaunch(params)
            "app.stop" -> validatePackageOnly(params)
            "ui.click" -> validateTargetOnly(params)
            "ui.longClick" -> validateLongClick(params)
            "ui.tap" -> validateVisualOnly(params)
            "ui.swipe" -> validateSwipe(params)
            "ui.setText" -> validateSetText(params)
            "ui.scroll" -> validateScroll(params)
            "ui.back", "ui.home", "ui.recents" -> requireExactKeys(params, emptySet())
            "ui.wait", "ui.assert" -> validatePredicate(params)
            "task.finish" -> validateTaskFinish(params)
            // 以下为动作面扩展新增。每一项都必须在这里显式校验：
            // 缺失校验时 provider 返回的畸形参数会一路带到无障碍层，
            // 在那里报出来的错误无法定位到是哪个字段不合法。
            "ui.drag" -> validateDrag(params)
            "ui.multiTap" -> validateMultiTap(params)
            "ui.pinch" -> validatePinch(params)
            "ui.rotate" -> validateRotate(params)
            "ui.twoFingerScroll" -> validateTwoFingerScroll(params)
            "ui.pathGesture" -> validatePathGesture(params)
            "ui.scrollIntoView" -> validateScrollIntoView(params)
            "ui.selectText" -> validateSelectText(params)
            "ui.copy", "ui.cut", "ui.paste" -> validateTargetOnly(params)
            "ui.nodeAction" -> validateNodeAction(params)
            "ui.setProgress" -> validateSetProgress(params)
            "ui.scrollToPosition" -> validateScrollToPosition(params)
            "ui.pressKey" -> validatePressKey(params)
            "ui.notifications",
            "ui.quickSettings",
            "ui.lockScreen",
            "ui.powerDialog",
            "ui.dismissNotificationShade",
            "device.wake",
            "device.unlock",
            "ime.show",
            "ime.hide",
            "clipboard.get",
            -> requireExactKeys(params, emptySet())

            "device.rotate" -> validateDeviceRotate(params)
            "ime.select" -> validateImeSelect(params)
            "clipboard.set" -> validateClipboardSet(params)
            "app.deeplink" -> validateDeeplink(params)
            "file.push" -> validateFilePush(params)
            "file.pull" -> validateFilePull(params)
            else -> throw ProviderActionParseException("Unsupported action type: $type")
        }

        return action
    }

    private fun validateDrag(params: JsonObject) {
        requireExactKeys(params, setOf("start", "end"), setOf("holdMs", "moveMs"))
        validateDragAnchor(params.objectValue("start"), "start")
        validateDragAnchor(params.objectValue("end"), "end")
        params.optionalInt("holdMs")?.requireRange("holdMs", 400, 10_000)
        params.optionalInt("moveMs")?.requireRange("moveMs", 1, 10_000)
    }

    private fun validateDragAnchor(anchor: JsonObject, name: String) {
        requireExactKeys(anchor, emptySet(), setOf("target", "point"))
        val target = anchor["target"]
        val point = anchor["point"]
        if ((target == null) == (point == null)) {
            throw ProviderActionParseException(
                "$name requires exactly one of target or point",
            )
        }
        target?.let { validateTarget(it.asObject("$name.target")) }
        point?.let { validateScreenPoint(it.asObject("$name.point"), "$name.point") }
    }

    private fun validateMultiTap(params: JsonObject) {
        requireExactKeys(params, setOf("taps"), setOf("target", "point"))
        requireTargetOrPoint(params, "ui.multiTap")
        params.int("taps").requireRange("taps", 2, 5)
    }

    private fun validatePinch(params: JsonObject) {
        requireExactKeys(params, setOf("scale"), setOf("target", "center", "durationMs"))
        params["target"]?.let { validateTarget(it.asObject("target")) }
        params["center"]?.let { validateScreenPoint(it.asObject("center"), "center") }
        val scale = params.double("scale")
        scale.requireRange("scale", 0.2, 5.0)
        if (scale == 1.0) {
            throw ProviderActionParseException("scale must not equal 1")
        }
        params.optionalInt("durationMs")?.requireRange("durationMs", 100, 10_000)
    }

    private fun validateRotate(params: JsonObject) {
        requireExactKeys(params, setOf("degrees"), setOf("target", "center", "durationMs"))
        params["target"]?.let { validateTarget(it.asObject("target")) }
        params["center"]?.let { validateScreenPoint(it.asObject("center"), "center") }
        val degrees = params.double("degrees")
        degrees.requireRange("degrees", -360.0, 360.0)
        if (degrees == 0.0) {
            throw ProviderActionParseException("degrees must not be zero")
        }
        params.optionalInt("durationMs")?.requireRange("durationMs", 100, 10_000)
    }

    private fun validateTwoFingerScroll(params: JsonObject) {
        requireExactKeys(
            params,
            setOf("deltaX", "deltaY"),
            setOf("target", "center", "durationMs"),
        )
        params["target"]?.let { validateTarget(it.asObject("target")) }
        params["center"]?.let { validateScreenPoint(it.asObject("center"), "center") }
        val deltaX = params.int("deltaX")
        val deltaY = params.int("deltaY")
        deltaX.requireRange("deltaX", -100_000, 100_000)
        deltaY.requireRange("deltaY", -100_000, 100_000)
        if (deltaX == 0 && deltaY == 0) {
            throw ProviderActionParseException("Two-finger scroll requires a non-zero delta")
        }
        params.optionalInt("durationMs")?.requireRange("durationMs", 100, 10_000)
    }

    private fun validatePathGesture(params: JsonObject) {
        requireExactKeys(params, setOf("points"), setOf("durationMs"))
        val points = params["points"] as? JsonArray
            ?: throw ProviderActionParseException("points must be an array")
        if (points.size !in 2..32) {
            throw ProviderActionParseException("points must contain between 2 and 32 items")
        }
        points.forEachIndexed { index, element ->
            validateScreenPoint(element.asObject("points[$index]"), "points[$index]")
        }
        params.optionalInt("durationMs")?.requireRange("durationMs", 1, 10_000)
    }

    private fun validateScrollIntoView(params: JsonObject) {
        requireExactKeys(
            params,
            setOf("target"),
            setOf("container", "direction", "maxScrolls", "amount"),
        )
        validateTarget(params.objectValue("target"))
        params["container"]?.let { validateTarget(it.asObject("container")) }
        params.optionalString("direction")?.let { direction ->
            if (direction !in SCROLL_DIRECTIONS) {
                throw ProviderActionParseException("Unsupported scroll direction: $direction")
            }
        }
        params.optionalInt("maxScrolls")?.requireRange("maxScrolls", 1, 50)
        params.optionalDouble("amount")?.let {
            if (it <= 0 || it > 1) {
                throw ProviderActionParseException("amount must be greater than 0 and at most 1")
            }
        }
    }

    private fun validateSelectText(params: JsonObject) {
        requireExactKeys(params, setOf("target", "startIndex"), setOf("endIndex"))
        validateTarget(params.objectValue("target"))
        val start = params.int("startIndex")
        start.requireRange("startIndex", 0, 10_000)
        params.optionalInt("endIndex")?.let { end ->
            end.requireRange("endIndex", 0, 10_000)
            if (end < start) {
                throw ProviderActionParseException("endIndex must not be smaller than startIndex")
            }
        }
    }

    private fun validateNodeAction(params: JsonObject) {
        requireExactKeys(params, setOf("target", "action"))
        validateTarget(params.objectValue("target"))
        val action = params.string("action")
        if (action !in SIMPLE_NODE_ACTIONS) {
            throw ProviderActionParseException("Unsupported node action: $action")
        }
    }

    private fun validateSetProgress(params: JsonObject) {
        requireExactKeys(params, setOf("target", "value"))
        validateTarget(params.objectValue("target"))
        val value = params.double("value")
        if (!value.isFinite()) {
            throw ProviderActionParseException("value must be a finite number")
        }
    }

    private fun validateScrollToPosition(params: JsonObject) {
        requireExactKeys(params, setOf("target", "row"), setOf("column"))
        validateTarget(params.objectValue("target"))
        params.int("row").requireRange("row", 0, 100_000)
        params.optionalInt("column")?.requireRange("column", 0, 100_000)
    }

    private fun validatePressKey(params: JsonObject) {
        requireExactKeys(params, setOf("key"), setOf("repeatCount"))
        val key = params.string("key")
        if (key !in DEVICE_KEYS) {
            throw ProviderActionParseException("Unsupported key: $key")
        }
        params.optionalInt("repeatCount")?.requireRange("repeatCount", 1, 64)
    }

    private fun validateDeviceRotate(params: JsonObject) {
        requireExactKeys(params, setOf("orientation"))
        val orientation = params.string("orientation")
        if (orientation !in ORIENTATIONS) {
            throw ProviderActionParseException("Unsupported orientation: $orientation")
        }
    }

    private fun validateImeSelect(params: JsonObject) {
        requireExactKeys(params, setOf("id"))
        val id = params.string("id")
        if (id.length !in 3..512 || !IME_ID.matches(id)) {
            throw ProviderActionParseException("id must be a package/component input method id")
        }
    }

    private fun validateClipboardSet(params: JsonObject) {
        requireExactKeys(params, setOf("text"))
        if (params.string("text").length > 10_000) {
            throw ProviderActionParseException("text exceeds 10000 characters")
        }
    }

    private fun validateDeeplink(params: JsonObject) {
        requireExactKeys(params, setOf("uri"), setOf("packageName"))
        params.string("uri").requireLength("uri", 3, 2_048)
        params.optionalString("packageName")?.let(::requirePackageName)
    }

    private fun validateFilePush(params: JsonObject) {
        requireExactKeys(params, setOf("localPath", "remotePath"))
        params.string("localPath").requireLength("localPath", 1, 4_096)
        params.string("remotePath").requireLength("remotePath", 1, 4_096)
    }

    private fun validateFilePull(params: JsonObject) {
        requireExactKeys(params, setOf("remotePath", "localPath"))
        params.string("remotePath").requireLength("remotePath", 1, 4_096)
        params.string("localPath").requireLength("localPath", 1, 4_096)
    }

    private fun requireTargetOrPoint(params: JsonObject, actionType: String) {
        val target = params["target"]
        val point = params["point"]
        if (target == null && point == null) {
            throw ProviderActionParseException("$actionType requires a target or a point")
        }
        target?.let { validateTarget(it.asObject("target")) }
        point?.let { validateScreenPoint(it.asObject("point"), "point") }
    }

    /**
     * 校验屏幕像素坐标点。
     *
     * 与 validatePoint 的归一化坐标不同：手势的 point 是设备像素，
     * 上限沿用协议 common.schema.json 的 coordinate 定义（0..100000）。
     */
    private fun validateScreenPoint(point: JsonObject, name: String) {
        requireExactKeys(point, setOf("x", "y"))
        point.int("x").requireRange("$name.x", 0, 100_000)
        point.int("y").requireRange("$name.y", 0, 100_000)
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
        requireExactKeys(params, emptySet(), setOf("target", "visualTarget", "durationMs"))
        val semantic = params["target"]
        val visual = params["visualTarget"]
        if ((semantic == null) == (visual == null)) {
            throw ProviderActionParseException(
                "ui.longClick requires exactly one of target or visualTarget",
            )
        }
        semantic?.let { validateTarget(it.asObject("target")) }
        visual?.let { validateVisualTarget(it.asObject("visualTarget")) }
        params.optionalInt("durationMs")?.requireRange("durationMs", 300, 10_000)
    }

    private fun validatePoint(point: JsonObject, name: String = "point") {
        requireExactKeys(point, setOf("x", "y"))
        point.double("x").requireRange("$name.x", 0.0, 1.0)
        point.double("y").requireRange("$name.y", 0.0, 1.0)
    }

    private fun validateVisualOnly(params: JsonObject) {
        requireExactKeys(params, setOf("visualTarget"))
        validateVisualTarget(params.objectValue("visualTarget"))
    }

    private fun validateSwipe(params: JsonObject) {
        requireExactKeys(params, setOf("visualTarget", "start", "end", "durationMs"))
        validateVisualTarget(params.objectValue("visualTarget"))
        validatePoint(params.objectValue("start"), "start")
        validatePoint(params.objectValue("end"), "end")
        params.int("durationMs").requireRange("durationMs", 1, 60_000)
    }

    private fun validateVisualTarget(target: JsonObject) {
        requireExactKeys(
            target,
            setOf(
                "packageName",
                "observationId",
                "imageSha256",
                "candidateId",
                "source",
                "confidence",
                "point",
                "bounds",
            ),
        )
        requirePackageName(target.string("packageName"))
        for (name in listOf("observationId", "candidateId")) {
            if (!UUID.matches(target.string(name))) {
                throw ProviderActionParseException("$name must be a UUID")
            }
        }
        if (!SHA256.matches(target.string("imageSha256"))) {
            throw ProviderActionParseException("imageSha256 must be lowercase SHA-256")
        }
        if (target.string("source") != "model") {
            throw ProviderActionParseException("visualTarget source must be model")
        }
        target.double("confidence").requireRange("confidence", 0.70, 1.0)
        val point = target.objectValue("point")
        val bounds = target.objectValue("bounds")
        validatePoint(point)
        validateNormalizedBounds(bounds)
        val x = point.double("x")
        val y = point.double("y")
        if (
            x !in bounds.double("left")..bounds.double("right") ||
            y !in bounds.double("top")..bounds.double("bottom")
        ) {
            throw ProviderActionParseException("visualTarget point must be inside bounds")
        }
    }

    private fun validateNormalizedBounds(bounds: JsonObject) {
        requireExactKeys(bounds, setOf("left", "top", "right", "bottom"))
        val left = bounds.double("left")
        val top = bounds.double("top")
        val right = bounds.double("right")
        val bottom = bounds.double("bottom")
        if (
            left !in 0.0..1.0 ||
            top !in 0.0..1.0 ||
            right !in 0.0..1.0 ||
            bottom !in 0.0..1.0 ||
            right <= left ||
            bottom <= top
        ) {
            throw ProviderActionParseException("visualTarget bounds are invalid")
        }
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
        val UUID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
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

        /** ui.nodeAction 允许的无参数节点动作。带参数的动作各有专用 type。 */
        val SIMPLE_NODE_ACTIONS = setOf(
            "focus",
            "clearFocus",
            "select",
            "clearSelection",
            "expand",
            "collapse",
            "dismiss",
        )

        val DEVICE_KEYS = setOf(
            "BACK",
            "HOME",
            "RECENTS",
            "ENTER",
            "TAB",
            "ESCAPE",
            "SPACE",
            "DELETE",
            "FORWARD_DEL",
            "DPAD_UP",
            "DPAD_DOWN",
            "DPAD_LEFT",
            "DPAD_RIGHT",
            "DPAD_CENTER",
            "MOVE_HOME",
            "MOVE_END",
            "PAGE_UP",
            "PAGE_DOWN",
            "VOLUME_UP",
            "VOLUME_DOWN",
            "VOLUME_MUTE",
        )

        val ORIENTATIONS = setOf("portrait", "landscape", "auto")

        /** 输入法 id 形如 `包名/组件名`，与 `ime_list` 输出格式一致。 */
        val IME_ID = Regex(
            "^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+$",
        )
    }
}
