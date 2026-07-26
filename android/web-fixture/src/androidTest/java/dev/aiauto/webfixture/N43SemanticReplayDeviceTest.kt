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
    fun reportSemanticCoverageAndHybridBoundaries() {
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
            setTextAndAwait("n43-$iteration", "Fixture state input-n43-$iteration")
            assertLongClickRequiresHybrid(iteration)
            clickAndAwait("Add dynamic control", "Fixture state dynamic-added", iteration)
            clickAndAwait("Dynamic result button", "Fixture state dynamic-clicked", iteration)
            clickIframeAndClassifyPostcondition(iteration)
            scrollAndClickResult(iteration)
            clickAndAwait("Open fixture detail", "Fixture detail state", iteration)
            clickDetailAndClassifyPostcondition()
            val detailGeneration = currentGeneration()
            assertTrue(
                "iteration $iteration failed to perform typed global Back",
                instrumentation.uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )
            awaitGenerationAfter(detailGeneration, iteration)
            awaitNames(iteration, "Full semantic fixture")
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
        awaitGenerationAfter(0, -1)
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
        val previousGeneration = currentGeneration()
        performAction("Reset current fixture state", AccessibilityNodeInfo.ACTION_CLICK, null)
        awaitGenerationAfter(previousGeneration, iteration)
        awaitNames(iteration, "Full semantic fixture", "Fixture state ready")
        assertOffline(iteration)
    }

    private fun clickAndAwait(name: String, expected: String, iteration: Int) {
        performAction(name, AccessibilityNodeInfo.ACTION_CLICK, null)
        awaitNames(iteration, expected)
    }

    private fun setTextAndAwait(
        value: String,
        expected: String,
    ) {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value,
            )
        }
        performUniqueInputAction(arguments)
        awaitNames(-1, expected)
    }

    private fun performUniqueInputAction(arguments: Bundle) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val result = root?.useTree { node ->
                val matches = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(node, matches) { candidate ->
                    candidate.packageName?.toString() == FIXTURE_PACKAGE &&
                        candidate.className?.toString()?.endsWith("EditText") == true &&
                        candidate.isVisibleToUser &&
                        candidate.isEnabled &&
                        candidate.isEditable &&
                        candidate.actionList.any {
                            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                        }
                }
                try {
                    matches.size == 1 &&
                        matches.single().performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            arguments,
                        )
                } finally {
                    matches.forEach(AccessibilityNodeInfo::recycle)
                }
            } == true
            if (result) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("unique editable WebView input is unavailable")
    }

    private fun assertLongClickRequiresHybrid(iteration: Int) {
        val root = freshRoot()
        val actions = root.useTree { node ->
            findFirst(node) {
                it.accessibleName() == "Fixture long press target"
            }?.actionList?.map(AccessibilityNodeInfo.AccessibilityAction::getId)?.sorted()
        } ?: throw AssertionError("iteration $iteration long-click target is unavailable")
        assertTrue(
            "iteration $iteration unexpectedly exposes semantic long-click: $actions",
            AccessibilityNodeInfo.ACTION_LONG_CLICK !in actions,
        )
        reportCapability("N43_HYBRID_REQUIRED:longClick")
    }

    private fun clickIframeAndClassifyPostcondition(iteration: Int) {
        // iframe click 只提交一次；后置不可见时必须转视觉验证，不能盲目重放动作。
        performAction("Offline frame button", AccessibilityNodeInfo.ACTION_CLICK, null)
        if (awaitName("FRAME:clicked", POSTCONDITION_TIMEOUT_MS)) {
            reportCapability("N43_FULL_SEMANTIC:iframe")
        } else {
            reportCapability("N43_HYBRID_REQUIRED:iframePostcondition")
        }
    }

    private fun clickDetailAndClassifyPostcondition() {
        // 页面内动作与 iframe 使用同一单次提交规则，分类后继续验证全局 Back。
        performAction("Detail page action", AccessibilityNodeInfo.ACTION_CLICK, null)
        if (awaitName("STATE:detail-clicked", POSTCONDITION_TIMEOUT_MS)) {
            reportCapability("N43_FULL_SEMANTIC:detailPostcondition")
        } else {
            reportCapability("N43_HYBRID_REQUIRED:detailPostcondition")
        }
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

    private fun awaitName(expected: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (collectNames().contains(expected)) {
                return true
            }
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun reportCapability(message: String) {
        instrumentation.sendStatus(
            STATUS_IN_PROGRESS,
            Bundle().apply {
                putString(
                    Instrumentation.REPORT_KEY_STREAMRESULT,
                    "\n$message\n",
                )
            },
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

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ) {
        if (predicate(node)) {
            output += AccessibilityNodeInfo.obtain(node)
        }
        repeat(node.childCount) { index ->
            node.getChild(index)?.useTree { child ->
                collectNodes(child, output, predicate)
            }
        }
    }

    private fun currentGeneration(): Int =
        collectNames()
            .firstOrNull { it.startsWith(GENERATION_PREFIX) }
            ?.removePrefix(GENERATION_PREFIX)
            ?.toIntOrNull()
            ?: throw AssertionError("WebView generation state is unavailable")

    private fun awaitGenerationAfter(previous: Int, iteration: Int): Int {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val generation = runCatching(::currentGeneration).getOrNull()
            if (generation != null && generation > previous) {
                return generation
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "iteration $iteration did not commit a WebView generation after $previous",
        )
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
        const val FIXTURE_PACKAGE = "dev.aiauto.webfixture"
        const val GENERATION_PREFIX = "LOAD_GENERATION:"
        const val STATUS_IN_PROGRESS = 2
        const val ACTION_TIMEOUT_MS = 15_000L
        const val POSTCONDITION_TIMEOUT_MS = 3_000L
        const val POLL_MS = 200L
        val EXPECTED_WEBVIEW = mapOf(
            30 to "91.0.4472.114",
            33 to "109.0.5414.123",
            34 to "113.0.5672.136",
        )
    }
}
