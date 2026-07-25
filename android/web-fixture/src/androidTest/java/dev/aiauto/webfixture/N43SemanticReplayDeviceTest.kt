package dev.aiauto.webfixture

/**
 * 测试用途：在真实 WebView 虚拟无障碍树上重复验证多动作、多页面语义回放与零坐标降级边界。
 */

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class N43SemanticReplayDeviceTest {
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
    fun repeatFullSemanticReplay() {
        verifyRuntime()
        val repeat = InstrumentationRegistry.getArguments()
            .getString(REPEAT_ARGUMENT, "1")
            .toIntOrNull()
            ?: error("$REPEAT_ARGUMENT must be an integer")
        require(repeat in 1..100) { "$REPEAT_ARGUMENT must be in 1..100" }

        launch()
        repeat(repeat) { iteration ->
            resetAndAwaitReady(iteration)
            clickAndAwait("Fixture click button", "Fixture state clicked", iteration)
            setTextAndAwait("Fixture text input", "n43-$iteration", "Fixture state input-n43-$iteration")
            longClickAndAwait("Fixture long press target", "Fixture state long-pressed", iteration)
            clickAndAwait("Add dynamic control", "Fixture state dynamic-added", iteration)
            clickAndAwait("Dynamic result button", "Fixture state dynamic-clicked", iteration)
            clickAndAwait("Offline frame button", "FRAME:clicked", iteration)
            scrollAndClickResult(iteration)
            clickAndAwait("Open fixture detail", "Fixture detail state", iteration)
            clickAndAwait("Detail page action", "STATE:detail-clicked", iteration)
            assertTrue(
                "iteration $iteration failed to perform typed global Back",
                instrumentation.uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )
            awaitNames(iteration, "Full semantic fixture", "Fixture state ready")
            assertOffline(iteration)
        }
    }

    private fun launch() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("mode", "full")
        }
        activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        awaitNames(-1, "Full semantic fixture", "Fixture state ready")
    }

    private fun verifyRuntime() {
        val expected = EXPECTED_WEBVIEW[Build.VERSION.SDK_INT]
            ?: error("N43 only accepts fixed API 30, 33, or 34 profiles")
        val actual = WebView.getCurrentWebViewPackage()?.versionName
            ?: error("Current WebView package is unavailable")
        check(actual == expected) {
            "WebView version drift for API ${Build.VERSION.SDK_INT}: $actual != $expected"
        }
    }

    private fun resetAndAwaitReady(iteration: Int) {
        performAction("Reset current fixture state", AccessibilityNodeInfo.ACTION_CLICK, null)
        awaitNames(iteration, "Full semantic fixture", "Fixture state ready")
        assertOffline(iteration)
    }

    private fun clickAndAwait(name: String, expected: String, iteration: Int) {
        performAction(name, AccessibilityNodeInfo.ACTION_CLICK, null)
        awaitNames(iteration, expected)
    }

    private fun setTextAndAwait(
        name: String,
        value: String,
        expected: String,
    ) {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value,
            )
        }
        performAction(name, AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        awaitNames(-1, expected)
    }

    private fun longClickAndAwait(name: String, expected: String, iteration: Int) {
        performAction(name, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
        awaitNames(iteration, expected)
    }

    private fun scrollAndClickResult(iteration: Int) {
        val root = freshRoot()
        val scrollNode = root.useTree { node ->
            findFirst(node) { candidate ->
                candidate.isScrollable &&
                    (
                        candidate.actionList.any {
                            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }
                    )
            }?.let(AccessibilityNodeInfo::obtain)
        } ?: throw AssertionError("iteration $iteration has no semantic scroll node")
        scrollNode.useTree { node ->
            assertTrue(
                "iteration $iteration semantic scroll action failed",
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
            )
        }
        performAction("Fixture scroll result", AccessibilityNodeInfo.ACTION_CLICK, null)
        awaitNames(iteration, "Fixture state scrolled")
    }

    private fun performAction(name: String, action: Int, arguments: Bundle?) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val result = root?.useTree { node ->
                findFirst(node) { it.accessibleName() == name }?.let { target ->
                    val supported = target.actionList.any { it.id == action }
                    supported && target.performAction(action, arguments)
                }
            } == true
            if (result) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("semantic action $action unavailable for node: $name")
    }

    private fun awaitNames(iteration: Int, vararg expected: String): Set<String> {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        var observed = emptySet<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            observed = collectNames()
            if (expected.all(observed::contains)) {
                return observed
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "iteration $iteration missing ${expected.toList()} from semantic tree: $observed",
        )
    }

    private fun assertOffline(iteration: Int) {
        assertTrue(
            "iteration $iteration changed the offline network counter",
            collectNames().contains("NETWORK_REJECTED:0"),
        )
    }

    private fun collectNames(): Set<String> =
        freshRoot().useTree { root ->
            buildSet { collectNames(root, this) }
        }

    private fun collectNames(node: AccessibilityNodeInfo, output: MutableSet<String>) {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(output::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(output::add)
        repeat(node.childCount) { index ->
            node.getChild(index)?.useTree { child -> collectNames(child, output) }
        }
    }

    private fun findFirst(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        repeat(node.childCount) { index ->
            val found = node.getChild(index)?.useTree { child ->
                findFirst(child, predicate)?.let(AccessibilityNodeInfo::obtain)
            }
            if (found != null) return found
        }
        return null
    }

    private fun freshRoot(): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("WebView semantic root is unavailable")
    }

    private fun AccessibilityNodeInfo.accessibleName(): String? =
        contentDescription?.toString() ?: text?.toString()

    @Suppress("DEPRECATION")
    private inline fun <T> AccessibilityNodeInfo.useTree(
        block: (AccessibilityNodeInfo) -> T,
    ): T = try {
        refresh()
        block(this)
    } finally {
        recycle()
    }

    private companion object {
        const val REPEAT_ARGUMENT = "n43Repeat"
        const val ACTION_TIMEOUT_MS = 15_000L
        const val POLL_MS = 200L
        val EXPECTED_WEBVIEW = mapOf(
            30 to "91.0.4472.114",
            33 to "109.0.5414.123",
            34 to "113.0.5672.136",
        )
    }
}
