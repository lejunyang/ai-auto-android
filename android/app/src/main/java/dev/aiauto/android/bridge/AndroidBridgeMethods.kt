package dev.aiauto.android.bridge

/**
 * 功能用途：实现 AndroidBridgeMethods 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。
 */

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
    private val attestedVisual: AttestedVisualActionBridgeGateway =
        UnavailableAttestedVisualActionBridgeGateway,
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
            // ui.foreground 与 ui.snapshot 共用同一条无障碍通道和同一份目标包白名单，
            // 因此可用性判据完全一致。它存在的意义是让调用方在不拉取整棵节点树的前提下
            // 就能确认"当前在哪个界面"——自愈判断只需要这一个事实，
            // 而完整快照在深层界面上可能有数千节点。
            BridgeCapability(
                name = "ui.foreground",
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
            // 动作面扩展后的能力必须单独声明，而不是塞进 action.execute。
            // 调用方需要在发命令前知道「这台设备能不能做多指手势」——
            // 多指依赖 canPerformGestures 与平台的多 stroke 支持，
            // 而节点级扩展动作只依赖无障碍服务本身，两者可用性并不总是一致。
            BridgeCapability(
                name = "action.gesture.composite",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = accessibilityReason,
                limits = mapOf(
                    "maxPointers" to 2L,
                    "maxStrokeRounds" to 16L,
                    "maxPathPoints" to 32L,
                ),
            ),
            BridgeCapability(
                name = "action.node.extended",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = accessibilityReason,
            ),
            // ime.show 单独声明，因为它的语义弱于名字的字面含义：实际做的是对当前
            // 输入焦点节点执行 ACTION_FOCUS，键盘是否弹出由输入法决定。无障碍侧既没有
            // 打开键盘的 API，也无法查询键盘可见性，所以这里必须让调用方在发命令前就
            // 知道拿到的是「聚焦」而不是一个确定的键盘开关。
            BridgeCapability(
                name = "action.ime.show",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = if (accessibilityAvailable) {
                    "Focuses the editable control; keyboard visibility is not guaranteed " +
                        "and cannot be verified."
                } else {
                    accessibilityReason
                },
            ),
            BridgeCapability(
                name = "action.text.editing",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = accessibilityReason,
            ),
            // 按键只覆盖光标移动：其余键在无障碍通道上无法可靠发出，
            // 见 AndroidAccessibilityBackend.pressKey 的说明。
            // 如实声明覆盖范围，让调用方知道何时该走 ADB 通道。
            BridgeCapability(
                name = "action.pressKey",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = if (accessibilityAvailable) {
                    "Only MOVE_HOME and MOVE_END are delivered over accessibility; " +
                        "other keys require the ADB direct channel."
                } else {
                    accessibilityReason
                },
            ),
            // 通知栏与快捷设置在所有支持的 API 上可用；锁屏与截屏要求 API 28+，
            // 收起通知栏要求 API 34+。这里按运行时版本如实上报，
            // 不支持的项让调用方提前知道而不是发出去再失败。
            BridgeCapability(
                name = "action.global.extended",
                available = accessibilityAvailable,
                permission = if (accessibilityAvailable) "granted" else "denied",
                reason = accessibilityReason,
                limits = mapOf("apiLevel" to Build.VERSION.SDK_INT.toLong()),
            ),
            BridgeCapability(
                name = "visual.action.execute",
                available = attestedVisual.isAvailable(),
                permission = if (attestedVisual.isAvailable()) "granted" else "denied",
                reason = if (attestedVisual.isAvailable()) {
                    null
                } else {
                    "Attested visual actions require an authorized debug disposable emulator."
                },
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
        "ui.foreground" -> foreground(params)
        "action.execute" -> execute(params)
        "visual.action.execute" -> attestedVisual.execute(params)
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

    /**
     * 返回当前活动界面的包名与窗口类名，不返回任何节点树。
     *
     * 与 ui.snapshot 的区别是刻意的：自愈判断只需要"现在在哪"这一个事实，
     * 而完整快照在深层界面上可能有数千节点。这里取快照根节点的 packageName 与
     * className 后立即丢弃其余内容，因此仍受同一份目标包白名单约束，
     * 不会因为"只读一点点"而绕开权限边界。
     *
     * maxDepth 固定为 1：底层 snapshot 会构造整棵树，但序列化时只取根节点一层，
     * 避免把子节点文本带出——那些可能包含密码或验证码。
     */
    private fun foreground(params: JsonObject): JsonObject {
        requireKeys(params, required = emptySet(), optional = setOf("targetPackage"))
        requireAccessibility()
        val targetPackage = params.optionalString("targetPackage")
        if (
            targetPackage != null &&
            (targetPackage.length !in 3..255 || !PACKAGE_NAME.matches(targetPackage))
        ) {
            throw invalidArgument("targetPackage has an invalid Android package name.")
        }
        return when (val result = accessibility.snapshot(targetPackage)) {
            is AccessibilityResult.Failure -> throw result.error.toBridgeException()
            is AccessibilityResult.Success -> buildJsonObject {
                put("package", result.value.packageName.orEmpty())
                put("className", result.value.className.orEmpty())
                // source 与 Go 侧 adb 通道的字段同名同义：调用方据此判断可信度。
                // 桥侧来自无障碍活动窗口，比 dumpsys 文本解析更直接。
                put("source", "activeWindow")
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
        NodeAction.FOCUS -> "focus"
        NodeAction.CLEAR_FOCUS -> "clearFocus"
        NodeAction.SELECT -> "select"
        NodeAction.CLEAR_SELECTION -> "clearSelection"
        NodeAction.EXPAND -> "expand"
        NodeAction.COLLAPSE -> "collapse"
        NodeAction.DISMISS -> "dismiss"
        NodeAction.COPY -> "copy"
        NodeAction.CUT -> "cut"
        NodeAction.PASTE -> "paste"
        NodeAction.SET_SELECTION -> "setSelection"
        NodeAction.SET_PROGRESS -> "setProgress"
        NodeAction.SCROLL_TO_POSITION -> "scrollToPosition"
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
            attestedVisual = AndroidAttestedVisualActionGateway.production(applicationContext),
        )
    }
}
