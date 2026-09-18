package dev.aiauto.android.automation.recording

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试用途：验证 StepHumanizer 步骤可读化逻辑。
 * 覆盖：click 按 contentDescription/text 优先级、wait 从 expected 取包名、
 * scroll 四方向、setText 普通/密钥、back/home/task.finish、无语义兜底。
 */
class StepHumanizerTest {

    /** 构造带 selectorCandidates 的 target params。 */
    private fun clickParams(vararg selectors: Pair<String, String>, packageName: String? = null): JsonObject {
        val selectorList = selectors.map { (strategy, value) ->
            JsonObject(mapOf("strategy" to JsonPrimitive(strategy), "value" to JsonPrimitive(value)))
        }
        val targetMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "selectorCandidates" to JsonArray(selectorList)
        )
        packageName?.let { targetMap["packageName"] = JsonPrimitive(it) }
        return buildJsonObject { put("target", JsonObject(targetMap)) }
    }

    @Test
    fun `click 按 contentDescription 优先`() {
        val step = RecordedStep(
            id = "1",
            action = RecordedAction("ui.click", clickParams("contentDescription" to "闹钟", "text" to "闹钟")),
        )
        assertEquals("点击 闹钟", StepHumanizer.humanize(step))
    }

    @Test
    fun `click contentDescription 缺失时回退 text`() {
        val step = RecordedStep(
            id = "1",
            action = RecordedAction("ui.click", clickParams("text" to "秒表")),
        )
        assertEquals("点击 秒表", StepHumanizer.humanize(step))
    }

    @Test
    fun `click 无语义时兜底点击屏幕`() {
        val step = RecordedStep(
            id = "1",
            action = RecordedAction("ui.click", buildJsonObject {}),
        )
        assertEquals("点击屏幕", StepHumanizer.humanize(step))
    }

    @Test
    fun `wait 从 expected 取包名`() {
        val params = buildJsonObject {
            put("kind", "package")
            put("operator", "equals")
            put("expected", "com.google.android.deskclock")
            put("timeoutMs", 5000)
        }
        val step = RecordedStep(id = "1", action = RecordedAction("ui.wait", params))
        assertEquals("等待 deskclock 打开", StepHumanizer.humanize(step))
    }

    @Test
    fun `wait 带 label 映射显示可读名`() {
        val params = buildJsonObject {
            put("kind", "package")
            put("operator", "equals")
            put("expected", "com.google.android.deskclock")
        }
        val step = RecordedStep(id = "1", action = RecordedAction("ui.wait", params))
        assertEquals("等待 时钟 打开", StepHumanizer.humanize(step, mapOf("com.google.android.deskclock" to "时钟")))
    }

    @Test
    fun `scroll 向下带方向`() {
        val params = buildJsonObject { put("direction", "down") }
        val step = RecordedStep(id = "1", action = RecordedAction("ui.scroll", params))
        assertEquals("向下滑动", StepHumanizer.humanize(step))
    }

    @Test
    fun `scroll 向上带方向`() {
        val params = buildJsonObject { put("direction", "up") }
        val step = RecordedStep(id = "1", action = RecordedAction("ui.scroll", params))
        assertEquals("向上滑动", StepHumanizer.humanize(step))
    }

    @Test
    fun `setText 普通输入`() {
        val step = RecordedStep(id = "1", action = RecordedAction("ui.setText", buildJsonObject {}))
        assertTrue(StepHumanizer.humanize(step).startsWith("在 "))
    }

    @Test
    fun `setText 密钥输入`() {
        val params = buildJsonObject { put("secretRef", "REF") }
        val step = RecordedStep(id = "1", action = RecordedAction("ui.setText", params))
        assertTrue(StepHumanizer.humanize(step).contains("密钥"))
    }

    @Test
    fun `back home task finish`() {
        assertEquals("按下返回键", StepHumanizer.humanize(RecordedStep("1", action = RecordedAction("ui.back", buildJsonObject {}))))
        assertEquals("回到桌面", StepHumanizer.humanize(RecordedStep("1", action = RecordedAction("ui.home", buildJsonObject {}))))
        assertEquals("结束任务", StepHumanizer.humanize(RecordedStep("1", action = RecordedAction("task.finish", buildJsonObject {}))))
    }

    @Test
    fun `techBadge 优先 contentDescription`() {
        val step = RecordedStep(
            id = "1",
            action = RecordedAction("ui.click", clickParams("contentDescription" to "闹钟", "resourceId" to "btn_alarm")),
        )
        assertEquals("ui.click · contentDescription", StepHumanizer.techBadge(step))
    }
}