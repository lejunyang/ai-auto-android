package dev.aiauto.android.ui.recording

/**
 * 测试用途：在 Compose instrumentation 中验证 N41 工具栏、步骤表单和未保存确认交互。
 *
 * 关于确认框的模态性：本类**不**断言「弹框存在时点不到身后内容」。实测发现
 * `performTouchInput` 直接在 Compose owner 层注入事件，绕过了真正拦截触摸的
 * Dialog 窗口层，因此即便弹框存在，注入的点击仍会到达身后的节点。这是测试
 * 注入路径的性质，不代表用户能穿透弹框点到下面——用它写断言会得到一个
 * 「测试通过但与真实行为无关」的假结论，所以刻意不写。若将来要覆盖模态性，
 * 需要走 UiAutomator 之类的真实注入路径。
 */

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingEditorScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorToolbarStepFormAndDiscardDialogAreAutomatable() {
        val first = step("step-one")
        val second = step("step-two")
        var selected: String? = null
        var submitCalls = 0
        var discardCalls = 0
        val script = AutomationScript(
            id = "script",
            name = "Editor",
            targetPackages = listOf("com.example"),
            createdAt = "2026-07-26T00:00:00Z",
            steps = listOf(first, second),
        )

        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    RecordingEditorUiState(
                        script = script,
                        selectedStepId = first.id,
                        stepForm = RecordingStepFormState(selectorValue = "Save"),
                        // 初始态刻意**不**弹确认框：AlertDialog 是模态的，它存在时
                        // 遮挡并拦截身后内容的触摸是正确行为，不是缺陷。若一开始就
                        // 置 true，后面点步骤行会被对话框吃掉，用例便在测「模态对话框
                        // 是否失效」而非它声称要测的工具栏与表单交互。
                        showDiscardConfirmation = false,
                        canUndo = true,
                        isDirty = true,
                    ),
                )
            }
            MaterialTheme {
                RecordingEditorScreen(
                    state = state,
                    observationHolder = null,
                    onSelectStep = {
                        selected = it
                        state = state.copy(selectedStepId = it)
                    },
                    onMoveStep = { _, _ -> },
                    onSetEnabled = { _, _ -> },
                    onDeleteStep = {},
                    onDuplicateStep = {},
                    onUpdateForm = { transform ->
                        state = state.copy(stepForm = state.stepForm?.let(transform))
                    },
                    onSubmitForm = { submitCalls += 1 },
                    onObservationSelection = {},
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onCopy = {},
                    onDryRun = {},
                    onBack = {
                        // 走真实入口把确认框弹起来，而不是让用例直接摆出弹框初始态：
                        // 这样顺带覆盖了「isDirty 时返回应拦截」这一行为。
                        state = state.copy(showDiscardConfirmation = true)
                    },
                    onDiscard = { discardCalls += 1 },
                    onDismissDiscard = {
                        state = state.copy(showDiscardConfirmation = false)
                    },
                )
            }
        }

        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_UNDO)
            .assertIsEnabled()
            .assertIsDisplayed()
        // 点卡片标题行而非卡片中心。
        //
        // EditorStepCard 把「整卡可点选中」与内部的上移/下移/停用/复制/删除按钮行
        // 叠在一起，而按钮行正好横跨卡片垂直中线：实测卡片中心 (160,393) 落在
        // 「停用」按钮 (156-214, 384-424) 内，点击被子按钮消费，onSelectStep 收不到。
        // performClick 默认点中心，所以这不是坐标算错，是中心恰好被子控件占据。
        //
        // 因此改点标题文本所在的上部区域。这是真实用户选中步骤时点的位置，
        // 也与卡片高度、按钮数量解耦。
        composeRule.onNodeWithTag(RecordingTestTags.editorStep(second.id))
            .performTouchInput { click(Offset(centerX, 12f)) }
        // 表单在滚动列的下方，小屏上位于视口外：节点已组合（故能 fetch 到），
        // 但点击落在视口边界而非按钮上。这里必须先滚动，与上面「中心被子控件
        // 占据」是两个不同原因，修法也不同。
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(RecordingTestTags.EDITOR_FORM_SUBMIT))
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_FORM_SUBMIT)
            .performClick()

        // 内容区交互做完后再弹确认框，顺序与真实使用一致。
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("返回"))
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("丢弃").assertIsDisplayed()
        composeRule.onNodeWithText("丢弃").performClick()

        composeRule.runOnIdle {
            assertEquals(second.id, selected)
            assertEquals(1, submitCalls)
            assertEquals(1, discardCalls)
        }
    }

    private fun step(id: String) = RecordedStep(
        id = id,
        action = RecordedAction("ui.click", JsonObject(emptyMap())),
    )
}
