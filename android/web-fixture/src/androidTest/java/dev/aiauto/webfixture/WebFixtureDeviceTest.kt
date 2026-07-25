package dev.aiauto.webfixture

/**
 * 测试用途：在 UiAutomation 无障碍会话内验证三种离线 WebView 模式的虚拟节点、截图和零网络状态。
 */

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebFixtureDeviceTest {
    private val instrumentation: Instrumentation =
        InstrumentationRegistry.getInstrumentation()
    private var activity: Activity? = null

    @After
    fun closeFixture() {
        activity?.runOnUiThread { activity?.finishAndRemoveTask() }
        instrumentation.waitForIdleSync()
        activity = null
    }

    @Test
    fun fullModeExposesInteractiveVirtualNodesAndScreenshot() {
        launch("full")

        val texts = awaitTexts(
            "Full semantic fixture",
            "Fixture click button",
            "Fixture text",
        )

        assertTrue(texts.contains("Fixture state ready"))
        assertTrue(texts.contains("NETWORK_REJECTED:0"))
        clickNode("Fixture click button")
        awaitTexts("Fixture state clicked")
        clickNode("Reset current fixture state")
        awaitTexts("Fixture state ready", "NETWORK_REJECTED:0")
        assertScreenshot()
    }

    @Test
    fun partialModeKeepsSemanticControlsAndOneVisualRegion() {
        launch("partial")

        val texts = awaitTexts(
            "Partial semantic fixture",
            "Visible semantic action",
            "Visible input",
            "Visual-only partial region",
        )

        assertTrue(texts.contains("Fixture state ready"))
        assertTrue(texts.contains("MODE:partial"))
        assertTrue(texts.contains("NETWORK_REJECTED:0"))
        assertScreenshot()
    }

    @Test
    fun canvasModeExposesOnlyTheMachineReadableCanvasTarget() {
        launch("canvas")

        val texts = awaitTexts("Canvas fixture canvas-result ready")

        assertEquals(1, texts.count { it == "Canvas fixture canvas-result ready" })
        assertTrue(
            texts.none {
                it == "Fixture click button" || it == "Visible semantic action"
            },
        )
        assertTrue(texts.contains("MODE:canvas"))
        assertTrue(texts.contains("NETWORK_REJECTED:0"))
        assertScreenshot()
    }

    private fun launch(mode: String) {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("mode", mode)
        }
        activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
    }

    private fun awaitTexts(vararg expected: String): List<String> {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var observed = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            observed = collectTexts()
            if (expected.all(observed::contains)) {
                return observed
            }
            SystemClock.sleep(250)
        }
        throw AssertionError("Missing ${expected.toList()} from accessibility texts: $observed")
    }

    private fun collectTexts(): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        return root.useTree { node ->
            buildList {
                node.text?.toString()?.takeIf(String::isNotBlank)?.let(::add)
                node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(::add)
                repeat(node.childCount) { index ->
                    node.getChild(index)?.useTree { child -> addAll(collectTexts(child)) }
                }
            }
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(::add)
        repeat(node.childCount) { index ->
            node.getChild(index)?.useTree { child -> addAll(collectTexts(child)) }
        }
    }

    private fun clickNode(accessibleName: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.useTree { node -> clickNode(node, accessibleName) }) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("Unable to click accessibility node: $accessibleName")
    }

    private fun clickNode(
        node: AccessibilityNodeInfo,
        accessibleName: String,
    ): Boolean {
        val name = node.contentDescription?.toString() ?: node.text?.toString()
        if (name == accessibleName && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        repeat(node.childCount) { index ->
            val matched = node.getChild(index)?.useTree { child ->
                clickNode(child, accessibleName)
            } == true
            if (matched) {
                return true
            }
        }
        return false
    }

    private fun assertScreenshot() {
        val screenshot: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        screenshot?.let {
            assertTrue(it.width > 0)
            assertTrue(it.height > 0)
            it.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <T> AccessibilityNodeInfo.useTree(
        block: (AccessibilityNodeInfo) -> T,
    ): T = try {
        refresh()
        block(this)
    } finally {
        recycle()
    }
}
