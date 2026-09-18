package dev.aiauto.android.automation.recording

/**
 * 功能用途：把录制步骤（RecordedStep）转成人类可读的中文短语，供 UI 三处使用：
 * 录制中步骤预览（RecordingSessionScreen）、脚本详情（RecordingDetailScreen）、
 * 技能编排时间轴（RecordingEditorScreen StepRail）。
 *
 * 设计原则：
 * - 纯 Kotlin，不依赖 Composable，可直接单测。
 * - 从 action.params["target"].selectorCandidates 按 contentDescription > text > resourceId
 *   优先级提取可读目标名；resourceId 取最后一段并去下划线美化。
 * - ui.wait 的包名从 params["expected"] 读取（录制引擎生成 {kind,operator,expected,timeoutMs}）。
 * - ui.scroll 的方向从 params["direction"] 读取（up/down/left/right）。
 * - appLabel 支持外部传入 packageName→label 映射（label 缓存），取不到再退回短包名。
 * - 取不到任何语义时兜底显示 action.type，不报错。
 */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 步骤可读化工具。所有方法为静态（companion object），无状态。
 */
object StepHumanizer {

    /**
     * 生成步骤主标题（中文短语），如"点击 闹钟"、"向下滑动"、"等待 时钟 打开"。
     * @param step 录制步骤
     * @param packageLabel 可选的 packageName→应用名映射（label 缓存），用于 wait/launch 显示可读名
     */
    fun humanize(step: RecordedStep, packageLabel: Map<String, String> = emptyMap()): String {
        val action = step.action
        val target = extractTarget(action.params)
        val targetName = readableTarget(target)
        return when (action.type) {
            "app.launch" -> "打开 ${appLabel(target?.packageName, packageLabel)}"
            "app.stop" -> "停止 ${appLabel(target?.packageName, packageLabel)}"
            "ui.click" -> if (targetName != null) "点击 $targetName" else "点击屏幕"
            "ui.longClick" -> if (targetName != null) "长按 $targetName" else "长按屏幕"
            "ui.tap" -> "点击屏幕坐标"
            "ui.swipe" -> "在屏幕上滑动"
            "ui.scroll" -> describeScroll(action.params, target)
            "ui.setText" -> describeTextInput(action, targetName)
            "ui.back" -> "按下返回键"
            "ui.home" -> "回到桌面"
            "ui.recents" -> "打开最近任务列表"
            "ui.wait" -> describeWait(action.params, packageLabel)
            "ui.assert" -> "断言界面状态"
            "task.finish" -> "结束任务"
            else -> "执行 ${action.type}"
        }
    }

    /**
     * 生成技术标识（灰色小字），优先显示实际贡献了可读名的 selector（contentDescription/text）。
     */
    fun techBadge(step: RecordedStep): String {
        val action = step.action
        val target = extractTarget(action.params)
        val contributing = target?.selectorCandidates?.firstOrNull {
            it.strategy == "contentDescription" || it.strategy == "text"
        }
        val selectorType = contributing?.let { " · ${it.strategy}" }.orEmpty()
        return "${action.type}$selectorType"
    }

    /** 提取 target JSON 对象。 */
    private fun extractTarget(params: JsonObject): TargetInfo? {
        val target = params["target"] as? JsonObject ?: return null
        val packageName = (target["packageName"] as? JsonPrimitive)?.contentOrNull
        val fingerprint = target["fingerprint"] as? JsonObject
        val resourceId = (fingerprint?.get("resourceId") as? JsonPrimitive)?.contentOrNull
        val selectors = (target["selectorCandidates"] as? JsonArray)?.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val strategy = (obj["strategy"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val value = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Selector(strategy, value)
        }
        return TargetInfo(packageName, resourceId, selectors ?: emptyList())
    }

    /** 按优先级取可读目标名：contentDescription > text > resourceId。 */
    private fun readableTarget(target: TargetInfo?): String? {
        if (target == null) return null
        val cd = target.selectorCandidates.firstOrNull { it.strategy == "contentDescription" }?.value
        val text = target.selectorCandidates.firstOrNull { it.strategy == "text" }?.value
        val resId = target.resourceId
        return when {
            !cd.isNullOrBlank() && cd.length <= 24 -> cd
            !text.isNullOrBlank() && text.length <= 24 -> text
            !resId.isNullOrBlank() -> beautifyResourceId(resId)
            !text.isNullOrBlank() -> text.take(20)
            else -> null
        }
    }

    /** resourceId 取最后一段、去下划线美化。 */
    private fun beautifyResourceId(resourceId: String): String {
        val lastSegment = resourceId.substringAfterLast('/').substringAfterLast('.')
        return lastSegment.replace('_', ' ').trim().ifBlank { resourceId }
    }

    /** scroll 带方向（从 params["direction"] 读取）。 */
    private fun describeScroll(params: JsonObject, target: TargetInfo?): String {
        val direction = (params["direction"] as? JsonPrimitive)?.contentOrNull
        val targetName = readableTarget(target)
        val directionText = when (direction) {
            "up", "UP" -> "向上滑动"
            "down", "DOWN" -> "向下滑动"
            "left", "LEFT" -> "向左滑动"
            "right", "RIGHT" -> "向右滑动"
            else -> "滚动屏幕"
        }
        return directionText + if (targetName != null) "（$targetName）" else ""
    }

    /** 文本输入：区分密钥变量。 */
    private fun describeTextInput(action: RecordedAction, targetName: String?): String {
        val hasSecretRef = action.params.containsKey("secretRef")
        val target = targetName ?: "控件"
        return if (hasSecretRef) "在 $target 输入密钥" else "在 $target 输入文本"
    }

    /** wait：从 params["expected"] 读取包名，显示"等待 应用名 打开"。 */
    private fun describeWait(params: JsonObject, packageLabel: Map<String, String>): String {
        val expected = (params["expected"] as? JsonPrimitive)?.contentOrNull
        return if (!expected.isNullOrBlank()) "等待 ${appLabel(expected, packageLabel)} 打开"
        else "等待界面条件满足"
    }

    /** 包名映射：优先 label 缓存，取不到取最后一段。 */
    private fun appLabel(packageName: String?, labelMap: Map<String, String>): String {
        if (packageName.isNullOrBlank()) return "应用"
        labelMap[packageName]?.let { return it }
        return packageName.substringAfterLast('.')
    }

    /** 内部数据类。 */
    private data class Selector(val strategy: String, val value: String)
    private data class TargetInfo(
        val packageName: String?,
        val resourceId: String?,
        val selectorCandidates: List<Selector>,
    )
}
