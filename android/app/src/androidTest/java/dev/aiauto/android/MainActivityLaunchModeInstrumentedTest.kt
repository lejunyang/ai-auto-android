package dev.aiauto.android

/**
 * 测试用途：验证 Launcher 重入复用唯一主 Activity，并保留当前录制导航页面。
 */

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainActivityLaunchModeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    @Test
    fun mergedManifestUsesSingleTaskLaunchMode() {
        val activity = composeRule.activity
        val activityInfo = activity.packageManager.getActivityInfo(
            ComponentName(activity, MainActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
    }

    @Test
    fun launcherReentryKeepsActivityAndRecordingRoute() {
        val originalActivityId = System.identityHashCode(composeRule.activity)
        // 首页是 LazyColumn，「录制操作」在第 6 个 item 里。小屏（本矩阵统一 320x640）
        // 上它落在首屏之外，而 Lazy 列表**不组合**屏外内容，节点因此根本不存在——
        // 报错虽写作 "Failed to inject touch input"，真因是找不到节点而非注入失败。
        //
        // 用滚动到目标而非固定坐标或放宽断言：这样用例与屏幕高度、字体缩放、
        // 以及 API 35 起 edge-to-edge 压缩可用高度等因素解耦，任何屏幕上都成立。
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("录制操作"))
        composeRule.onNodeWithText("录制操作").performClick()
        composeRule.onNodeWithText("开始录制").performClick()
        composeRule.onNodeWithText("录制新流程").assertIsDisplayed()

        composeRule.activity.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = ComponentName(composeRule.activity, MainActivity::class.java)
            },
        )
        composeRule.waitForIdle()

        assertEquals(originalActivityId, System.identityHashCode(composeRule.activity))
        composeRule.onNodeWithText("录制新流程").assertIsDisplayed()
        composeRule.onNodeWithText("运行状态").assertDoesNotExist()
    }
}
