package dev.aiauto.android.automation.session.history

/**
 * 功能用途：定义会话运行历史记录的数据模型。
 *
 * 安全策略（默认不保存用户原文）：
 * - 始终保存：id、phase、targetPackage、stepCount、startTimeMs、endTimeMs（结构字段，无敏感内容）。
 * - task 字段保存固定摘要「自动化任务」，不保存用户输入的原始任务描述。
 *   原因：自由文本可能包含密码/验证码/API key 等敏感内容，无法可靠识别任意自由文本中的秘密。
 *   记录页显示「自动化任务」+ 目标 App + 时间 + 状态，无需原始 task。
 * - 默认不保存：completionSummary、failureMessage（模型输出可能包含页面文本/敏感内容，
 *   写入方应留 null；未来产品策略明确后可按需填充）。
 * - 不保存：audit 全文、原始截图、完整页面节点。
 *
 * 用户可删除单条或全部历史记录。
 */

import dev.aiauto.android.automation.session.SessionPhase
import kotlinx.serialization.Serializable

@Serializable
data class SessionHistoryRecord(
    /** 历史记录唯一标识，UUID 字符串。 */
    val id: String,
    /** 固定摘要「自动化任务」，不保存用户输入的原始任务描述（安全策略）。 */
    val task: String,
    /** 本次会话的目标应用包名。 */
    val targetPackage: String,
    /** 会话终态：Completed / Failed / Stopped。 */
    val phase: SessionPhase,
    /** 完成摘要，默认 null（模型输出可能含敏感内容，写入方应保守留空）。 */
    val completionSummary: String? = null,
    /** 失败原因，默认 null（同上）。 */
    val failureMessage: String? = null,
    /** 会话执行的步骤数。 */
    val stepCount: Int = 0,
    /** 会话开始时间，epoch millis。 */
    val startTimeMs: Long,
    /** 会话结束时间，epoch millis。 */
    val endTimeMs: Long,
) {
    companion object {
        /** 历史记录中 task 字段的固定摘要，不保存用户原文。 */
        const val TASK_SUMMARY = "自动化任务"

        /** 会话终态集合：只有这些 phase 才写入历史。 */
        val TERMINAL_PHASES = setOf(
            SessionPhase.Completed,
            SessionPhase.Failed,
            SessionPhase.Stopped,
        )
    }

    init {
        require(id.isNotBlank()) { "Session history id must not be blank" }
        require(task == TASK_SUMMARY) {
            "Session history task must be fixed summary for security, got: $task"
        }
        require(targetPackage.isNotBlank()) { "Session history targetPackage must not be blank" }
        require(phase in TERMINAL_PHASES) { "Session history phase must be terminal: $phase" }
        require(startTimeMs > 0) { "Session history startTimeMs must be positive" }
        require(endTimeMs >= startTimeMs) { "Session history endTimeMs must be >= startTimeMs" }
        require(stepCount >= 0) { "Session history stepCount must be non-negative" }
    }

    /** 人类可读的状态标签，用于 UI 展示。 */
    val statusLabel: String
        get() = when (phase) {
            SessionPhase.Completed -> "已完成"
            SessionPhase.Failed -> "失败"
            SessionPhase.Stopped -> "已停止"
            else -> phase.name
        }

    /** 是否需要用户关注（失败），用于记录页"需处理"筛选。 */
    val needsAttention: Boolean
        get() = phase == SessionPhase.Failed
}
