package dev.aiauto.android.accessibility.model

/**
 * 功能用途：定义 AccessibilityModels 中无障碍观察、选择器与动作执行共享的领域模型。
 */

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
    // 以下为动作面扩展新增。它们都是 AccessibilityNodeInfo 早已提供的能力，
    // 此前没有出现在这个枚举里，因此既不会被快照上报也无法被路由执行。
    FOCUS,
    CLEAR_FOCUS,
    SELECT,
    CLEAR_SELECTION,
    EXPAND,
    COLLAPSE,
    DISMISS,
    COPY,
    CUT,
    PASTE,
    SET_SELECTION,
    SET_PROGRESS,
    SCROLL_TO_POSITION,
}

/**
 * 全局动作。
 *
 * BACK / HOME / RECENTS 之外的项各自要求不同的最低 API：
 * 通知栏与快捷设置自 API 16，锁屏与截屏自 API 28（P），
 * 电源菜单自 API 21。路由层按运行时 API 判定，不支持时明确失败而非静默忽略。
 */
enum class GlobalAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN,
    TAKE_SCREENSHOT,
    POWER_DIALOG,
    DISMISS_NOTIFICATION_SHADE,
    KEYBOARD_SHORTCUTS,
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

/**
 * 节点在无障碍树中的位置。
 *
 * [windowId] 为 null 表示「活动窗口」，与多窗口模型引入前的语义完全一致——
 * 既有录制脚本与回放报告里的 matchedPath 不带窗口标识，必须继续可用。
 * 非 null 时表示该路径要在 [UiWindowSnapshot.windowId] 对应的窗口内解析，
 * 这是弹窗、输入法、分屏等非活动窗口能被定位的前提。
 */
data class NodePath(
    val indices: List<Int>,
    val windowId: Int? = null,
)

/**
 * 窗口类别。
 *
 * 直接对应 AccessibilityWindowInfo 的 TYPE_* 常量，额外把画中画单独标出——
 * 平台没有为画中画定义窗口类型，它是 APPLICATION 窗口的一个布尔属性，
 * 但对调用方而言「这是个画中画」与「这是主应用窗口」的处理方式完全不同。
 */
enum class WindowKind {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY_OVERLAY,
    SPLIT_SCREEN_DIVIDER,
    MAGNIFICATION_OVERLAY,
    PICTURE_IN_PICTURE,
    UNKNOWN,
}

/**
 * 单个窗口的快照。
 *
 * [layer] 是平台给出的 z 序，数值越大越靠上。它必须保留而不能只按列表顺序表达：
 * 对话框与其宿主 Activity 同为 APPLICATION 窗口，只有 layer 能区分谁盖住谁，
 * 而「点到最上层那个」是坐标动作能否命中的关键。
 */
data class UiWindowSnapshot(
    val windowId: Int,
    val kind: WindowKind,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: ScreenBounds,
    val root: UiNodeSnapshot,
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

    data class Tap(
        val point: ScreenPoint,
        val durationMs: Long = 100,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    data class Swipe(
        val start: ScreenPoint,
        val end: ScreenPoint,
        val durationMs: Long,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    data class Navigate(val action: GlobalAction) : AccessibilityCommand

    /**
     * 拖拽：在起点长按后移动到终点。
     *
     * 起点与终点都可以是选择器目标或坐标——列表重排通常按元素定位，
     * 画布拖动通常按坐标。两者都为空时无法执行。
     */
    data class Drag(
        val start: DragAnchor,
        val end: DragAnchor,
        val holdMs: Long = 600,
        val moveMs: Long = 500,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    /** N 连击。 */
    data class MultiTap(
        val target: NodeTarget? = null,
        val point: ScreenPoint? = null,
        val taps: Int = 2,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    /** 双指缩放：scale < 1 捏合，> 1 放大。 */
    data class Pinch(
        val center: ScreenPoint? = null,
        val target: NodeTarget? = null,
        val scale: Double,
        val durationMs: Long = 500,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    /** 双指旋转。 */
    data class Rotate(
        val center: ScreenPoint? = null,
        val target: NodeTarget? = null,
        val degrees: Double,
        val durationMs: Long = 600,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    /** 双指同向滚动。 */
    data class TwoFingerScroll(
        val center: ScreenPoint? = null,
        val target: NodeTarget? = null,
        val deltaX: Int,
        val deltaY: Int,
        val durationMs: Long = 400,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    /** 单指连续路径手势（图案解锁等）。 */
    data class PathGesture(
        val points: List<ScreenPoint>,
        val durationMs: Long = 800,
        val expectedPackage: String? = null,
    ) : AccessibilityCommand

    /**
     * 滚动直到目标可见或判据成立。
     *
     * 与 [Scroll] 的区别在于终止条件：Scroll 滚固定幅度一次，
     * 这里反复滚动直到目标出现，因此需要最大轮次与无进展检测。
     */
    data class ScrollIntoView(
        val target: NodeTarget,
        val container: NodeTarget? = null,
        val direction: ScrollDirection = ScrollDirection.FORWARD,
        val maxScrolls: Int = 12,
        val amount: Double = 0.6,
    ) : AccessibilityCommand

    /** 文本选择。endIndex 为 null 表示选到末尾。 */
    data class SelectText(
        val target: NodeTarget,
        val startIndex: Int,
        val endIndex: Int? = null,
    ) : AccessibilityCommand

    /** 剪贴板动作：复制、剪切、粘贴。 */
    data class Clipboard(
        val target: NodeTarget,
        val operation: ClipboardOperation,
    ) : AccessibilityCommand

    /** 单个节点动作，用于 FOCUS / SELECT / EXPAND 等无额外参数的能力。 */
    data class PerformNodeAction(
        val target: NodeTarget,
        val action: NodeAction,
    ) : AccessibilityCommand

    /** 给 SeekBar 等可调节控件设值。 */
    data class SetProgress(
        val target: NodeTarget,
        val value: Float,
    ) : AccessibilityCommand

    /** 让列表滚动到指定下标位置。 */
    data class ScrollToPosition(
        val target: NodeTarget,
        val row: Int,
        val column: Int = 0,
    ) : AccessibilityCommand

    /** 按键。走无障碍全局动作或输入法通道，不依赖 ADB。 */
    data class PressKey(
        val key: DeviceKey,
        val repeatCount: Int = 1,
    ) : AccessibilityCommand

    /**
     * 聚焦当前输入控件，对应协议的 ime.show。
     *
     * 无参数：聚焦的是系统当前的输入焦点节点，不接受选择器。
     * 语义是「聚焦输入控件」而非「显示键盘」——键盘通常随焦点出现，
     * 但无障碍侧无法查询也无法保证键盘可见性。
     */
    data object ImeShow : AccessibilityCommand

    /**
     * 写入剪贴板文本，对应协议的 clipboard.set。
     *
     * 无 target：写的是系统剪贴板，不是某个节点。与 ui.copy / ui.cut / ui.paste
     * 不同——那三个是对焦点节点执行节点动作，本项直接走 ClipboardManager。
     *
     * 刻意没有对应的读取命令：Android 无 READ_CLIPBOARD 权限，API 29+ 要求应用
     * 持有输入焦点或是默认输入法才能读，而自动化要读的正是别的 App 的剪贴板，
     * 两个条件互斥。详见 spec 与 ClipboardGateway 的注释。
     */
    data class ClipboardSet(val text: String) : AccessibilityCommand
}

/** 拖拽端点：选择器或坐标二选一。 */
data class DragAnchor(
    val target: NodeTarget? = null,
    val point: ScreenPoint? = null,
)

/** 剪贴板操作类别。 */
enum class ClipboardOperation {
    COPY,
    CUT,
    PASTE,
}

/**
 * 可通过无障碍通道发出的按键。
 *
 * 这里刻意不是「任意 keycode」：无障碍服务本身没有通用按键 API，
 * 每个键都要映射到具体机制（全局动作、IME 通道或节点动作），
 * 开放任意 keycode 会得到大量静默失败的键。
 */
enum class DeviceKey {
    BACK,
    HOME,
    RECENTS,
    ENTER,
    TAB,
    DELETE,
    FORWARD_DELETE,
    ESCAPE,
    SPACE,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_CENTER,
    MOVE_HOME,
    MOVE_END,
    PAGE_UP,
    PAGE_DOWN,
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MUTE,
}

/**
 * 手势原语。
 *
 * Tap 与 Swipe 是既有的两类，保持不变以免影响已录制脚本。
 * [MultiStroke] 是新增的通用形态：它能表达单笔无法表达的一切——
 * 多指（pinch / zoom / 旋转 / 双指滚动）、分段时序（drag 的按住—停顿—移动）、
 * 续接（连击之间保持接触状态）与任意折线路径（图案解锁）。
 *
 * 之所以不把 Tap / Swipe 直接实现成 MultiStroke 的特例：两者已有稳定的
 * 事件预算与归因语义，改写会牵动录制回放的既有行为。
 */
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

    /**
     * 由多笔笔画组成的复合手势。
     *
     * 同一 [GestureStroke.pointerId] 的多笔按 [GestureStroke.startTimeMs] 顺序
     * 通过 willContinue 续接，形成一条不抬手的连续轨迹；不同 pointerId 的笔画
     * 并行派发，构成多指手势。
     */
    data class MultiStroke(
        val strokes: List<GestureStroke>,
    ) : Gesture
}

/**
 * 一笔笔画。
 *
 * [path] 是屏幕坐标的折线，至少两个点（单点由 [ScreenPoint] 重复表达，
 * 平台要求 Path 必须有实际内容）。
 * [pointerId] 标识这一笔属于哪根手指；同一手指的连续笔画通过续接拼成完整轨迹。
 * [continued] 表示这一笔之后还有同指后续笔画，即抬手前还要继续——
 * 这正是 drag 的「按住—停顿—移动」与连击「不抬手」能被表达的机制。
 * [continuation] 表示这一笔是前一笔的延续，起点必须等于前一笔终点。
 */
data class GestureStroke(
    val path: List<ScreenPoint>,
    val startTimeMs: Long,
    val durationMs: Long,
    val pointerId: Int = 0,
    val continued: Boolean = false,
    val continuation: Boolean = false,
)

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
