package dev.aiauto.android.bridge

// 功能用途：实现 AndroidBridgeMethods 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。

import android.content.Context
import android.os.Build

import dev.aiauto.android.BuildConfig
import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityError
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.AndroidReplayGateway
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.ReplayEngine
import dev.aiauto.android.automation.recording.ReplayReport
import dev.aiauto.android.automation.recording.ReplayStepResult
import dev.aiauto.android.automation.recording.SecretResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface AccessibilityBridgeGateway {
    fun isAvailable(): Boolean

    fun snapshot(expectedPackage: String?): AccessibilityResult<UiNodeSnapshot>

    fun execute(command: AccessibilityCommand): AccessibilityResult<ActionExecution>
}

object RuntimeAccessibilityBridgeGateway : AccessibilityBridgeGateway {
    override fun isAvailable(): Boolean = AccessibilityRuntime.isAvailable()

    override fun snapshot(expectedPackage: String?): AccessibilityResult<UiNodeSnapshot> =
        AccessibilityRuntime.snapshot(expectedPackage)

    override fun execute(
        command: AccessibilityCommand,
    ): AccessibilityResult<ActionExecution> = AccessibilityRuntime.execute(command)
}

interface RecordingBridgeGateway {
    fun isAvailable(): Boolean

    fun list(): List<AutomationScriptSummary>

    fun replay(scriptId: String): ReplayReport?
}

class RuntimeRecordingBridgeGateway(
    private val store: RecordingScriptStore,
) : RecordingBridgeGateway {
    private val replayEngine by lazy {
        ReplayEngine(
            gateway = AndroidReplayGateway(),
            secretResolver = object : SecretResolver {
                override fun resolve(alias: String): String? = null
            },
        )
    }

    override fun isAvailable(): Boolean = AccessibilityRuntime.isAvailable()

    override fun list(): List<AutomationScriptSummary> = store.list()

    override fun replay(scriptId: String): ReplayReport? =
        store.get(scriptId)?.let { script -> replayEngine.replay(script) }
}

class AndroidBridgeMethods(
    context: Context,
    private val recording: RecordingBridgeGateway,
    private val accessibility: AccessibilityBridgeGateway =
        RuntimeAccessibilityBridgeGateway,
    private val commandParser: AccessibilityCommandJsonParser =
        AccessibilityCommandJsonParser(),
) : BridgeMethodHandler {
    private val applicationContext = context.applicationContext

    override fun capabilities(): List<BridgeCapability> {
        val accessibilityAvailable = accessibility.isAvailable()
        val recordingAvailable = recording.isAvailable()
        val accessibilityReason = if (accessibilityAvailable) {
            null
        } else {
            "Enable and configure the accessibility service in the Android app."
        }
        return listOf(
            BridgeCapability(
                name = "bridge.rpc",
                available = true,
                limits = mapOf(
                    "maxMessageBytes" to BridgeLimits.MAX_MESSAGE_BYTES.toLong(),
                    "maxConcurrentRequests" to
                        BridgeLimits.MAX_CONCURRENT_REQUESTS.toLong(),
                ),
            ),
            BridgeCapability(name = "device.info", available = true),
            BridgeCapability(name = "recording.list", available = true),
            BridgeCapability(
                name = "ui.snapshot",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = accessibilityReason,
            ),
            BridgeCapability(
                name = "action.execute",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = accessibilityReason,
            ),
            BridgeCapability(
                name = "recording.replay",
                available = recordingAvailable,
                permission = if (recordingAvailable) "granted" else "denied",
                reason = if (recordingAvailable) null else accessibilityReason,
            ),
        )
    }

    override fun handle(method: String, params: JsonObject): JsonObject = when (method) {
        "device.info" -> deviceInfo(params)
        "ui.snapshot" -> snapshot(params)
        "action.execute" -> execute(params)
        "recording.replay" -> replay(params)
        "recording.list" -> recordingList(params)

        else -> throw BridgeException(
            code = BridgeErrorCode.PROTOCOL_ERROR,
            message = "The requested bridge method is not supported.",
        )
    }

    private fun deviceInfo(params: JsonObject): JsonObject {
        requireKeys(params, emptySet(), emptySet())
        return buildJsonObject {
            put("manufacturer", Build.MANUFACTURER.orEmpty())
            put("model", Build.MODEL.orEmpty())
            put("androidVersion", Build.VERSION.RELEASE.orEmpty())
            put("apiLevel", Build.VERSION.SDK_INT)
            put("appPackage", applicationContext.packageName)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("capabilities", capabilitiesToJson())
        }
    }

    private fun recordingList(params: JsonObject): JsonObject {
        requireKeys(params, emptySet(), emptySet())
        return buildJsonObject {
            put(
                "recordings",
                buildJsonArray {
                    recording.list().forEach { summary -> add(summary.toJson()) }
                },
            )
        }
    }

    private fun snapshot(params: JsonObject): JsonObject {
        requireKeys(
            params,
            required = emptySet(),
            optional = setOf("targetPackage", "maxDepth"),
        )
        requireAccessibility()
        val targetPackage = params.optionalString("targetPackage")
        if (
            targetPackage != null &&
            (targetPackage.length !in 3..255 || !PACKAGE_NAME.matches(targetPackage))
        ) {
            throw invalidArgument("targetPackage has an invalid Android package name.")
        }
        val maxDepth = params["maxDepth"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SNAPSHOT_DEPTH
        if (maxDepth !in 1..100) {
            throw invalidArgument("maxDepth must be between 1 and 100.")
        }
        return when (val result = accessibility.snapshot(targetPackage)) {
            is AccessibilityResult.Failure -> throw result.error.toBridgeException()
            is AccessibilityResult.Success -> buildJsonObject {
                put("root", result.value.toJson(depth = 1, maxDepth = maxDepth))
                put("maxDepth", maxDepth)
            }
        }
    }

    private fun execute(params: JsonObject): JsonObject {
        requireKeys(
            params,
            required = setOf("action", "idempotencyKey"),
            optional = emptySet(),
        )
        requireAccessibility()
        val idempotencyKey = params.requiredString("idempotencyKey")
        if (!UUID.matches(idempotencyKey)) {
            throw invalidArgument("idempotencyKey must be a UUID.")
        }
        val action = params["action"] as? JsonObject
            ?: throw invalidArgument("action must be an object.")
        val command = commandParser.parse(action)
        return when (val result = accessibility.execute(command)) {
            is AccessibilityResult.Failure -> throw result.error.toBridgeException()
            is AccessibilityResult.Success -> result.value.toJson()
        }
    }

    private fun replay(params: JsonObject): JsonObject {
        requireKeys(
            params,
            required = setOf("scriptId", "idempotencyKey"),
            optional = emptySet(),
        )
        if (!recording.isAvailable()) {
            throw BridgeException(
                code = BridgeErrorCode.CAPABILITY_UNAVAILABLE,
                message = "Recording replay requires the accessibility service.",
                retryable = true,
            )
        }
        val scriptId = params.requiredString("scriptId")
        val idempotencyKey = params.requiredString("idempotencyKey")
        if (!UUID.matches(scriptId)) {
            throw invalidArgument("scriptId must be a UUID.")
        }
        if (!UUID.matches(idempotencyKey)) {
            throw invalidArgument("idempotencyKey must be a UUID.")
        }
        return recording.replay(scriptId)?.toJson()
            ?: throw BridgeException(
                code = BridgeErrorCode.SCRIPT_NOT_FOUND,
                message = "The requested recording script was not found.",
            )
    }

    private fun requireAccessibility() {
        if (!accessibility.isAvailable()) {
            throw BridgeException(
                code = BridgeErrorCode.CAPABILITY_UNAVAILABLE,
                message = "The accessibility service is not available.",
                retryable = true,
            )
        }
    }

    private fun capabilitiesToJson(): JsonArray = buildJsonArray {
        capabilities().forEach { capability ->
            add(
                buildJsonObject {
                    put("name", capability.name)
                    put("version", BridgeProtocol.SERVER_VERSION)
                    put("available", capability.available)
                    put("permission", capability.permission)
                    capability.reason?.let { put("reason", it) }
                },
            )
        }
    }

    private fun ActionExecution.toJson(): JsonObject = buildJsonObject {
        put("route", route.toProtocolName())
        matchedPath?.let { path ->
            put(
                "matchedPath",
                buildJsonArray {
                    path.indices.forEach { index -> add(JsonPrimitive(index)) }
                },
            )
        }
        matchScore?.let { put("matchScore", it) }
    }

    private fun ReplayReport.toJson(): JsonObject = buildJsonObject {
        put("scriptId", scriptId)
        put("startedAtMs", startedAtMs)
        put("finishedAtMs", finishedAtMs)
        put("succeeded", succeeded)
        put("requiresIntervention", requiresIntervention)
        put(
            "steps",
            buildJsonArray {
                steps.forEach { step -> add(step.toJson()) }
            },
        )
    }

    private fun ReplayStepResult.toJson(): JsonObject = buildJsonObject {
        put("stepId", stepId)
        put("status", status.name.lowercase())
        put("attempts", attempts)
        route?.let { put("route", it) }
        matchScore?.let { put("matchScore", it) }
        errorCode?.let { put("errorCode", it) }
        message?.let { put("message", it) }
    }

    private fun AutomationScriptSummary.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put(
            "targetPackages",
            buildJsonArray {
                targetPackages.forEach { targetPackage ->
                    add(JsonPrimitive(targetPackage))
                }
            },
        )
        put("stepCount", stepCount)
        put("createdAt", createdAt)
        put(
            "requirements",
            buildJsonObject {
                put("minApiLevel", requirements.minApiLevel)
                put(
                    "capabilities",
                    buildJsonArray {
                        requirements.capabilities.forEach { capability ->
                            add(JsonPrimitive(capability))
                        }
                    },
                )
            },
        )
    }

    private fun UiNodeSnapshot.toJson(depth: Int, maxDepth: Int): JsonObject =
        buildJsonObject {
            packageName?.let { put("packageName", it) }
            className?.let { put("className", it) }
            resourceId?.let { put("resourceId", it) }
            text?.let { put("text", it) }
            contentDescription?.let { put("contentDescription", it) }
            put(
                "bounds",
                buildJsonObject {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                },
            )
            put(
                "actions",
                buildJsonArray {
                    actions.sortedBy(NodeAction::name).forEach { action ->
                        add(JsonPrimitive(action.toProtocolName()))
                    }
                },
            )
            put(
                "state",
                buildJsonObject {
                    put("checkable", state.checkable)
                    put("checked", state.checked)
                    put("clickable", state.clickable)
                    put("enabled", state.enabled)
                    put("editable", state.editable)
                    put("focusable", state.focusable)
                    put("focused", state.focused)
                    put("longClickable", state.longClickable)
                    put("password", state.password)
                    put("scrollable", state.scrollable)
                    put("selected", state.selected)
                    put("visibleToUser", state.visibleToUser)
                    put("sensitive", state.sensitive)
                },
            )
            put(
                "children",
                buildJsonArray {
                    if (depth < maxDepth) {
                        children.forEach { child ->
                            add(child.toJson(depth = depth + 1, maxDepth = maxDepth))
                        }
                    }
                },
            )
        }

    private fun AccessibilityError.toBridgeException(): BridgeException {
        val bridgeCode = when (code) {
            AccessibilityErrorCode.SERVICE_DISABLED,
            AccessibilityErrorCode.WINDOW_UNAVAILABLE,
            AccessibilityErrorCode.SNAPSHOT_FAILED,
            AccessibilityErrorCode.SCREENSHOT_NOT_SUPPORTED,
            AccessibilityErrorCode.SCREENSHOT_SECURE_WINDOW,
            AccessibilityErrorCode.SCREENSHOT_FAILED,
            AccessibilityErrorCode.ACTION_NOT_SUPPORTED,
            -> BridgeErrorCode.CAPABILITY_UNAVAILABLE

            AccessibilityErrorCode.DISCLOSURE_REQUIRED,
            AccessibilityErrorCode.TARGET_PACKAGES_NOT_CONFIGURED,
            AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
            AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED,
            AccessibilityErrorCode.SCREENSHOT_SENSITIVE_CONTENT,
            -> BridgeErrorCode.PERMISSION_DENIED

            AccessibilityErrorCode.SELECTOR_NOT_FOUND -> BridgeErrorCode.SELECTOR_NOT_FOUND
            AccessibilityErrorCode.SELECTOR_AMBIGUOUS -> BridgeErrorCode.SELECTOR_AMBIGUOUS
            AccessibilityErrorCode.INVALID_COORDINATE,
            AccessibilityErrorCode.INVALID_ACTION,
            -> BridgeErrorCode.INVALID_ARGUMENT

            AccessibilityErrorCode.ACTION_FAILED,
            AccessibilityErrorCode.GESTURE_FAILED,
            AccessibilityErrorCode.GLOBAL_ACTION_FAILED,
            -> BridgeErrorCode.ACTION_FAILED
        }
        return BridgeException(
            code = bridgeCode,
            message = message,
            retryable = retryable,
            details = details,
        )
    }

    private fun ActionRoute.toProtocolName(): String = when (this) {
        ActionRoute.NODE_ACTION -> "nodeAction"
        ActionRoute.NODE_GESTURE -> "nodeGesture"
        ActionRoute.COORDINATE_GESTURE -> "coordinateGesture"
        ActionRoute.SCREEN_GESTURE -> "screenGesture"
        ActionRoute.GLOBAL_ACTION -> "globalAction"
    }

    private fun NodeAction.toProtocolName(): String = when (this) {
        NodeAction.CLICK -> "click"
        NodeAction.LONG_CLICK -> "longClick"
        NodeAction.SET_TEXT -> "setText"
        NodeAction.SCROLL_FORWARD -> "scrollForward"
        NodeAction.SCROLL_BACKWARD -> "scrollBackward"
        NodeAction.SCROLL_UP -> "scrollUp"
        NodeAction.SCROLL_DOWN -> "scrollDown"
        NodeAction.SCROLL_LEFT -> "scrollLeft"
        NodeAction.SCROLL_RIGHT -> "scrollRight"
    }

    private fun requireKeys(
        value: JsonObject,
        required: Set<String>,
        optional: Set<String>,
    ) {
        if ((required - value.keys).isNotEmpty() || (value.keys - required - optional).isNotEmpty()) {
            throw invalidArgument("Method parameters contain missing or unsupported fields.")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: throw invalidArgument("$name must be a string.")

    private fun JsonObject.optionalString(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: if (containsKey(name)) {
                throw invalidArgument("$name must be a string.")
            } else {
                null
            }

    private fun invalidArgument(message: String): BridgeException =
        BridgeException(
            code = BridgeErrorCode.INVALID_ARGUMENT,
            message = message,
        )

    private companion object {
        const val DEFAULT_SNAPSHOT_DEPTH = 64
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val UUID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
    }
}

internal object AndroidBridgeMethodsFactory {
    fun create(context: Context): AndroidBridgeMethods {
        val applicationContext = context.applicationContext
        val store = RecordingScriptStore.from(applicationContext)
        return AndroidBridgeMethods(
            context = applicationContext,
            recording = RuntimeRecordingBridgeGateway(store),
        )
    }
}
