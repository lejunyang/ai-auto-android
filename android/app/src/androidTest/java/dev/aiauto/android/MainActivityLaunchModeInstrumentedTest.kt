package dev.aiauto.android

/**
 * 设备测试用途：验证 Launcher 重入复用唯一主 Activity，并保留当前录制导航页面。
 */

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
