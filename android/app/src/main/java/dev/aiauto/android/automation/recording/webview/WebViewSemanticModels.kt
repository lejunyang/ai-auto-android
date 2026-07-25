package dev.aiauto.android.automation.recording.webview

/**
 * 功能用途：定义 WebView 虚拟节点能力矩阵、语义计划和跨页面回放的独立领域契约。
 */

import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.RecordedAction

/** 固定一次兼容性结论所依赖的 Android API 与系统 WebView 版本。 */
data class WebViewRuntime(
    val apiLevel: Int,
    val webViewVersion: String,
)

/** WebView、小程序和 Canvas 共用的四级兼容结果。 */
enum class WebViewCompatibilityLevel {
    FULL_SEMANTIC,
    HYBRID,
    VISUAL_ONLY,
    UNSUPPORTED,
}

/** N42 页面需要验证的语义能力，不包含任何 DOM 私有接口。 */
enum class WebViewSemanticCapability {
    CLICK,
    INPUT,
    LONG_CLICK,
    SCROLL,
    NAVIGATE,
    DYNAMIC_DOM,
    IFRAME,
}

/** 页面能力要求同时约束唯一语义目标及节点声明的动作能力。 */
data class WebViewSemanticRequirement(
    val capability: WebViewSemanticCapability,
    val target: NodeTarget,
    val requiredAction: NodeAction,
)

/** 页面契约声明关键语义节点和必须显式降级的视觉区域。 */
data class WebViewPageContract(
    val pageId: String,
    val requirements: List<WebViewSemanticRequirement>,
    val visualRegionCount: Int = 0,
)

/** 单次语义观察绑定运行时、页面、有效期和可验证性，禁止跨观察复用节点。 */
data class WebViewSemanticObservation(
    val observationId: String,
    val runtime: WebViewRuntime,
    val packageName: String,
    val pageId: String,
    val root: UiNodeSnapshot,
    val visualSurfaceAvailable: Boolean,
    val postconditionsVerifiable: Boolean,
    val restricted: Boolean,
    val capturedAtMs: Long,
    val expiresAtMs: Long,
)

/** 能力矩阵条目保留缺失项和降级原因，避免将单页成功泛化为通用支持。 */
data class WebViewCapabilityMatrixEntry(
    val runtime: WebViewRuntime,
    val pageId: String,
    val level: WebViewCompatibilityLevel,
    val availableCapabilities: Set<WebViewSemanticCapability>,
    val missingCapabilities: Set<WebViewSemanticCapability>,
    val reasons: Set<String>,
)

/** N43 只允许转换为现有语义动作或 Back，不提供坐标动作入口。 */
sealed interface WebViewSemanticIntent {
    data class Click(val target: NodeTarget) : WebViewSemanticIntent

    data class SetText(
        val target: NodeTarget,
        val text: String,
    ) : WebViewSemanticIntent

    data class LongClick(
        val target: NodeTarget,
        val durationMs: Long = 650L,
    ) : WebViewSemanticIntent

    data class Scroll(
        val target: NodeTarget,
        val direction: ScrollDirection,
    ) : WebViewSemanticIntent

    data class Navigate(val target: NodeTarget) : WebViewSemanticIntent

    data object Back : WebViewSemanticIntent
}

/** 稳定错误码区分语义缺失、上下文漂移和编排端口故障。 */
enum class WebViewSemanticErrorCode {
    SEMANTIC_UNAVAILABLE,
    SELECTOR_AMBIGUOUS,
    PACKAGE_DRIFT,
    PAGE_DRIFT,
    RUNTIME_DRIFT,
    OBSERVATION_EXPIRED,
    OBSERVATION_STALE,
    PRECONDITION_FAILED,
    POSTCONDITION_FAILED,
    SNAPSHOT_FAILED,
    ACTION_FAILED,
    SAVE_FAILED,
    RESET_FAILED,
}

/** 已验证的计划绑定观察 ID，调用方必须在同一观察有效期内提交。 */
data class WebViewSemanticPlan(
    val observationId: String,
    val action: RecordedAction,
)

/** 计划结果显式携带降级等级，拒绝结果不包含可执行动作。 */
sealed interface WebViewSemanticPlanResult {
    data class Ready(
        val plan: WebViewSemanticPlan,
        val level: WebViewCompatibilityLevel,
    ) : WebViewSemanticPlanResult

    data class Rejected(
        val code: WebViewSemanticErrorCode,
        val level: WebViewCompatibilityLevel,
        val message: String,
    ) : WebViewSemanticPlanResult
}

/** 跨页面流程固定目标包和运行时，不能在 API 或 WebView 漂移后继续执行。 */
data class WebViewRecordedFlow(
    val id: String,
    val targetPackage: String,
    val runtime: WebViewRuntime,
    val steps: List<WebViewRecordedStep>,
)

/** 每一步绑定动作前页面/状态及动作后页面/状态。 */
data class WebViewRecordedStep(
    val id: String,
    val expectedPageId: String,
    val expectedState: String,
    val intent: WebViewSemanticIntent,
    val postcondition: WebViewPostcondition,
)

/** 回放后置条件只接受当前 Accessibility 观察可以验证的状态。 */
data class WebViewPostcondition(
    val pageId: String,
    val state: String,
)

/** 流程保存端口由调用方接入正式存储或 test-only 内存实现。 */
fun interface WebViewFlowStore {
    fun save(flow: WebViewRecordedFlow): Boolean
}

/** fixture reset 端口必须在回放首个观察前完成且返回明确结果。 */
fun interface WebViewResetPort {
    fun reset(targetPackage: String): Boolean
}

/** 页面身份端口从语义快照提供页面、状态和当前 WebView runtime。 */
interface WebViewPageIdentity {
    fun identify(root: UiNodeSnapshot): String?

    fun runtime(root: UiNodeSnapshot): WebViewRuntime

    fun containsState(root: UiNodeSnapshot, expectedState: String): Boolean
}

/** 单步结果用于检查动作路由，不扩展 recording core 的共享报告模型。 */
data class WebViewReplayStepResult(
    val stepId: String,
    val route: String,
)

/** 回放报告在失败时保留稳定错误和兼容等级，不伪装为成功。 */
data class WebViewReplayReport(
    val succeeded: Boolean,
    val steps: List<WebViewReplayStepResult>,
    val errorCode: WebViewSemanticErrorCode? = null,
    val level: WebViewCompatibilityLevel? = null,
    val message: String? = null,
)
