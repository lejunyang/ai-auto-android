package dev.aiauto.android.accessibility.model

// 功能用途：定义 AccessibilityModels 中无障碍观察、选择器与动作执行共享的领域模型。

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun center(): ScreenPoint = ScreenPoint(
        x = left + width / 2,
        y = top + height / 2,
    )
}

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun contains(point: ScreenPoint): Boolean =
        point.x in left until right && point.y in top until bottom
}

data class ScreenPoint(
    val x: Int,
    val y: Int,
)

data class NormalizedPoint(
    val x: Double,
    val y: Double,
)

enum class NodeAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
}

data class UiNodeState(
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = false,
    val editable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val longClickable: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
    val selected: Boolean = false,
    val visibleToUser: Boolean = false,
    val sensitive: Boolean = false,
)

data class UiNodeSnapshot(
    val packageName: String?,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: UiBounds,
    val actions: Set<NodeAction>,
    val state: UiNodeState,
    val children: List<UiNodeSnapshot>,
)

class AccessibilityScreenshot(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
) : AutoCloseable {
    override fun close() {
        pngBytes.fill(0)
    }
}

data class NodePath(
    val indices: List<Int>,
)

enum class SelectorStrategy {
    RESOURCE_ID,
    CONTENT_DESCRIPTION,
    TEXT,
    ROLE,
    ANCESTOR,
    FINGERPRINT,
}

data class SelectorCandidate(
    val strategy: SelectorStrategy,
    val value: String,
    val weight: Double,
    val required: Boolean = false,
)

data class NodeTarget(
    val packageName: String? = null,
    val selectorCandidates: List<SelectorCandidate> = emptyList(),
    val fingerprint: Map<String, String?> = emptyMap(),
    val recordedBounds: UiBounds? = null,
    val relativePoint: NormalizedPoint? = null,
    val normalizedScreenPoint: NormalizedPoint? = null,
)

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    FORWARD,
    BACKWARD,
}

enum class GlobalAction {
    BACK,
    HOME,
    RECENTS,
}

sealed interface AccessibilityCommand {
    data class Click(val target: NodeTarget) : AccessibilityCommand

    data class LongClick(
        val target: NodeTarget,
        val durationMs: Long = 600,
    ) : AccessibilityCommand

    data class SetText(
        val target: NodeTarget,
        val text: String,
    ) : AccessibilityCommand

    data class Scroll(
        val direction: ScrollDirection,
        val target: NodeTarget? = null,
        val amount: Double = 0.8,
    ) : AccessibilityCommand

    data class Tap(val point: ScreenPoint) : AccessibilityCommand

    data class Swipe(
        val start: ScreenPoint,
        val end: ScreenPoint,
        val durationMs: Long,
    ) : AccessibilityCommand

    data class Navigate(val action: GlobalAction) : AccessibilityCommand
}

sealed interface Gesture {
    data class Tap(
        val point: ScreenPoint,
        val durationMs: Long,
    ) : Gesture

    data class Swipe(
        val start: ScreenPoint,
        val end: ScreenPoint,
        val durationMs: Long,
    ) : Gesture
}

enum class ActionRoute {
    NODE_ACTION,
    NODE_GESTURE,
    COORDINATE_GESTURE,
    SCREEN_GESTURE,
    GLOBAL_ACTION,
}

data class ActionExecution(
    val route: ActionRoute,
    val matchedPath: NodePath? = null,
    val matchScore: Double? = null,
)

enum class AccessibilityErrorCode {
    SERVICE_DISABLED,
    DISCLOSURE_REQUIRED,
    TARGET_PACKAGES_NOT_CONFIGURED,
    PACKAGE_NOT_ALLOWED,
    WINDOW_UNAVAILABLE,
    SNAPSHOT_FAILED,
    SCREENSHOT_NOT_SUPPORTED,
    SCREENSHOT_NOT_AUTHORIZED,
    SCREENSHOT_SENSITIVE_CONTENT,
    SCREENSHOT_SECURE_WINDOW,
    SCREENSHOT_FAILED,
    SELECTOR_NOT_FOUND,
    SELECTOR_AMBIGUOUS,
    INVALID_COORDINATE,
    INVALID_ACTION,
    ACTION_NOT_SUPPORTED,
    ACTION_FAILED,
    GESTURE_FAILED,
    GLOBAL_ACTION_FAILED,
}

data class AccessibilityError(
    val code: AccessibilityErrorCode,
    val message: String,
    val retryable: Boolean,
    val details: Map<String, String> = emptyMap(),
)

sealed interface AccessibilityResult<out T> {
    data class Success<T>(val value: T) : AccessibilityResult<T>

    data class Failure(val error: AccessibilityError) : AccessibilityResult<Nothing> {
        constructor(
            code: AccessibilityErrorCode,
            message: String,
            retryable: Boolean = false,
            details: Map<String, String> = emptyMap(),
        ) : this(
            AccessibilityError(
                code = code,
                message = message,
                retryable = retryable,
                details = details,
            ),
        )
    }
}
