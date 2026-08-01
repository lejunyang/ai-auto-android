package dev.aiauto.android.ui.recording

/**
 * 测试用途：通过生产 RecordingHost 与真实 store 验证 N41 详情编辑入口的设备交互。
 */

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingControllerState
import dev.aiauto.android.automation.recording.RecordingCoordinator
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.ScriptEnvironment
import dev.aiauto.android.automation.recording.ScriptEnvironmentProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingEditorRealEntryDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var store: RecordingScriptStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = RecordingScriptStore.from(context)
        deleteOwnedScripts()
    }

    @After
    fun tearDown() {
        deleteOwnedScripts()
    }

    @Test
    fun realDetailEntryAutomatesStepHistoryDiscardAndScriptCopy() {
        val script = seedScript("N41 设备编辑操作")
        val viewModel = createViewModel()
        setHostContent(viewModel)
        openRealDetail(viewModel, script.id)
        composeRule.onNodeWithText("编辑").performClick()
        waitForText(script.name)
        composeRule.onAllNodesWithTag(RecordingTestTags.EDITOR_OBSERVATION)
            .assertCountEquals(0)

        stepAction(FIRST_STEP_ID, "下移").performScrollTo().performClick()
        composeRule.onNodeWithText("1. ui.back", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        stepAction(FIRST_STEP_ID, "停用").performScrollTo().performClick()
        stepAction(FIRST_STEP_ID, "启用").assertIsDisplayed()

        stepAction(FIRST_STEP_ID, "复制").performClick()
        composeRule.onNodeWithText("3. ui.click", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        stepAction(SECOND_STEP_ID, "删除").performScrollTo().performClick()
        composeRule.onAllNodes(
            hasTestTag(RecordingTestTags.editorStep(SECOND_STEP_ID)),
        ).assertCountEquals(0)
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_UNDO)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RecordingTestTags.editorStep(SECOND_STEP_ID))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_REDO).performClick()
        composeRule.onAllNodes(
            hasTestTag(RecordingTestTags.editorStep(SECOND_STEP_ID)),
        ).assertCountEquals(0)

        composeRule.onNodeWithText("有未保存更改")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("返回").performScrollTo().performClick()
        composeRule.onNodeWithText("丢弃未保存更改？").assertIsDisplayed()
        composeRule.onNodeWithText("继续编辑").performClick()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("丢弃").performClick()
        composeRule.onNodeWithText("编辑").assertIsDisplayed()

        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_COPY)
            .performScrollTo()
            .performClick()
        waitForText("${script.name} 副本")
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            store.list().any { it.name == "${script.name} 副本" }
        }
        val copiedId = store.list().firstOrNull { it.name == "${script.name} 副本" }?.id
        assertNotNull(copiedId?.let(store::get))
    }

    @Test
    fun realStoreConflictAndDryRunFocusRemainVisible() {
        val script = seedScript("N41 设备冲突")
        val viewModel = createViewModel()
        setHostContent(viewModel)
        openRealDetail(viewModel, script.id)
        composeRule.onNodeWithText("编辑").performClick()
        waitForText(script.name)

        stepAction(FIRST_STEP_ID, "停用").performScrollTo().performClick()
        val diskCurrent = requireNotNull(store.get(script.id))
        store.save(
            diskCurrent.copy(name = "${diskCurrent.name} 磁盘版本"),
            expectedRevision = diskCurrent.revision,
        )

        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_SAVE)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(
            "revision 冲突：expected=1, current=2",
            substring = true,
        ).performScrollTo().assertIsDisplayed()

        stepAction(FIRST_STEP_ID, "启用").performScrollTo().performClick()
        composeRule.onNodeWithTag(RecordingTestTags.EDITOR_DRY_RUN)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(
            "$FIRST_STEP_ID: FAILED",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1. ui.click · 编辑中", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun createViewModel(): RecordingViewModel = RecordingViewModel(
        coordinator = StoreBackedRecordingCoordinator(store),
        environmentProvider = ScriptEnvironmentProvider {
            ScriptEnvironment(
                apiLevel = 34,
                logicalWidth = 1080,
                logicalHeight = 2400,
                densityDpi = 420,
                rotation = 0,
                locale = "zh-CN",
                fontScale = 1.0f,
                appVersion = "debug-test",
            )
        },
    )

    private fun setHostContent(viewModel: RecordingViewModel) {
        composeRule.setContent {
            MaterialTheme {
                RecordingHost(viewModel = viewModel, onBack = {})
            }
        }
    }

    private fun openRealDetail(viewModel: RecordingViewModel, scriptId: String) {
        composeRule.runOnIdle { viewModel.openScript(scriptId) }
        waitForText(OWNED_SCRIPT_PREFIX)
        composeRule.onNodeWithText("编辑").assertIsDisplayed()
    }

    private fun stepAction(stepId: String, label: String): SemanticsNodeInteraction =
        composeRule.onNode(
            matcher = hasText(label) and hasAnyAncestor(
                hasTestTag(RecordingTestTags.editorStep(stepId)),
            ),
            useUnmergedTree = true,
        )

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun seedScript(name: String): AutomationScript {
        val script = AutomationScript(
            id = SCRIPT_ID,
            name = name,
            targetPackages = listOf(context.packageName),
            createdAt = "2026-08-01T00:00:00Z",
            environment = ScriptEnvironment(
                apiLevel = 34,
                logicalWidth = 1080,
                logicalHeight = 2400,
            ),
            steps = listOf(
                RecordedStep(
                    id = FIRST_STEP_ID,
                    action = RecordedAction(
                        type = "ui.click",
                        params = buildJsonObject {
                            put(
                                "target",
                                buildJsonObject {
                                    put("packageName", JsonPrimitive(context.packageName))
                                    put(
                                        "selectorCandidates",
                                        JsonArray(
                                            listOf(
                                                JsonObject(
                                                    mapOf(
                                                        "strategy" to JsonPrimitive(
                                                            "contentDescription",
                                                        ),
                                                        "value" to JsonPrimitive(
                                                            "n41-missing-target",
                                                        ),
                                                        "weight" to JsonPrimitive(1.0),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    ),
                ),
                RecordedStep(
                    id = SECOND_STEP_ID,
                    action = RecordedAction(type = "ui.back", params = JsonObject(emptyMap())),
                ),
            ),
        )
        store.save(script)
        return script
    }

    private fun deleteOwnedScripts() {
        store.list()
            .filter { it.name.startsWith(OWNED_SCRIPT_PREFIX) }
            .forEach { store.delete(it.id) }
    }

    private class StoreBackedRecordingCoordinator(
        private val store: RecordingScriptStore,
    ) : RecordingCoordinator {
        private val mutableState = MutableStateFlow(
            RecordingControllerState(scripts = store.list()),
        )
        override val state: StateFlow<RecordingControllerState> = mutableState

        override fun start(
            name: String,
            targetPackages: Set<String>,
            environment: ScriptEnvironment,
        ) = Unit

        override fun pause() = Unit

        override fun resume() = Unit

        override fun cancelRecording() = Unit

        override fun finish(name: String) = Unit

        override fun refresh() {
            mutableState.value = mutableState.value.copy(scripts = store.list())
        }

        override fun select(id: String) {
            mutableState.value = mutableState.value.copy(
                scripts = store.list(),
                selectedScript = store.get(id),
                busy = false,
                errorMessage = null,
            )
        }

        override fun delete(id: String) {
            store.delete(id)
            mutableState.value = mutableState.value.copy(
                scripts = store.list(),
                selectedScript = null,
            )
        }

        override fun replay(
            script: AutomationScript,
            secrets: Map<String, String>,
        ) = Unit

        override fun close() = Unit
    }

    private companion object {
        const val OWNED_SCRIPT_PREFIX = "N41 设备"
        const val SCRIPT_ID = "41000000-0000-4000-8000-000000000001"
        const val FIRST_STEP_ID = "41000000-0000-4000-8000-000000000011"
        const val SECOND_STEP_ID = "41000000-0000-4000-8000-000000000012"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
