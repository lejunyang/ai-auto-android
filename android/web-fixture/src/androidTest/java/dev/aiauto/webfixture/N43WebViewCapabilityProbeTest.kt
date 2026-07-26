package dev.aiauto.webfixture

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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 测试用途：验证固定 API/WebView 虚拟节点的只读 action matrix，并区分能力缺失与动作无效果。
 */
@RunWith(AndroidJUnit4::class)
class N43WebViewCapabilityProbeTest {
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
    fun reportReadOnlySemanticCapabilityMatrix() {
        val webViewVersion = verifyRuntime()
        launch()
        val summaries = NODE_ALIASES.flatMap { (alias, accessibleName) ->
            matchingNodes { node -> node.accessibleName() == accessibleName }
                .toSummaries(alias)
        } + matchingNodes(::isInputNode).toSummaries("input") +
            matchingNodes { node -> node.isScrollable }.toSummaries("scrollContainer")
        val report = JSONObject()
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("webViewVersion", webViewVersion)
            .put(
                "nodes",
                JSONArray().apply {
                    summaries.forEach { summary ->
                        put(
                            JSONObject()
                                .put("alias", summary.alias)
                                .put("matchCount", summary.matchCount)
                                .put("present", summary.present)
                                .put("className", summary.className)
                                .put("editable", summary.editable)
                                .put("focusable", summary.focusable)
                                .put("focused", summary.focused)
                                .put("actions", JSONArray(summary.actions)),
                        )
                    }
                },
            )
        instrumentation.sendStatus(
            STATUS_IN_PROGRESS,
            Bundle().apply {
                putString(
                    Instrumentation.REPORT_KEY_STREAMRESULT,
                    "\nN43_CAPABILITY_MATRIX:${report}\n",
                )
            },
        )
    }

    private fun verifyRuntime(): String {
        val expected = EXPECTED_WEBVIEW[Build.VERSION.SDK_INT]
            ?: error("N43 probe only accepts fixed API 30, 33, or 34 profiles")
        val actual = WebView.getCurrentWebViewPackage()?.versionName
            ?: error("Current WebView package is unavailable")
        check(actual == expected) {
            "WebView version drift for API ${Build.VERSION.SDK_INT}: $actual != $expected"
        }
        return actual
    }

    private fun launch() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("mode", "full")
        }
        activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching { uniqueNode("Fixture click button").recycle() }.isSuccess) {
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("WebView virtual nodes are unavailable")
    }

    private fun uniqueNode(accessibleName: String): AccessibilityNodeInfo {
        val matches = matchingNodes { node -> node.accessibleName() == accessibleName }
        if (matches.size != 1) {
            matches.forEach(AccessibilityNodeInfo::recycle)
            throw AssertionError("Expected one $accessibleName node but found ${matches.size}")
        }
        return matches.single()
    }

    private fun matchingNodes(
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("WebView semantic root is unavailable")
        return root.useTree { node ->
            val matches = mutableListOf<AccessibilityNodeInfo>()
            collectMatchingNodes(node, predicate, matches)
            matches
        }
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        matches: MutableList<AccessibilityNodeInfo>,
    ) {
        if (
            node.packageName?.toString() == FIXTURE_PACKAGE &&
            node.isVisibleToUser &&
            node.isEnabled &&
            predicate(node)
        ) {
            matches += AccessibilityNodeInfo.obtain(node)
        }
        repeat(node.childCount) { index ->
            node.getChild(index)?.useTree { child ->
                collectMatchingNodes(child, predicate, matches)
            }
        }
    }

    private fun isInputNode(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.endsWith("EditText") == true ||
            node.isEditable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    private fun AccessibilityNodeInfo.accessibleName(): String? =
        contentDescription?.toString() ?: text?.toString()

    private fun AccessibilityNodeInfo.summary(alias: String) = NodeSummary(
        alias = alias,
        matchCount = 1,
        present = true,
        className = className?.toString().orEmpty(),
        editable = isEditable,
        focusable = isFocusable,
        focused = isFocused,
        actions = actionList.map(AccessibilityNodeInfo.AccessibilityAction::getId).sorted(),
    )

    private fun List<AccessibilityNodeInfo>.toSummaries(alias: String): List<NodeSummary> {
        if (isEmpty()) return listOf(NodeSummary.missing(alias))
        val count = size
        return mapIndexed { index, node ->
            node.useTree { candidate ->
                candidate.summary(if (count == 1) alias else "$alias.${index + 1}")
                    .copy(matchCount = count)
            }
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

    private data class NodeSummary(
        val alias: String,
        val matchCount: Int,
        val present: Boolean,
        val className: String,
        val editable: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val actions: List<Int>,
    ) {
        companion object {
            fun missing(alias: String) = NodeSummary(
                alias = alias,
                matchCount = 0,
                present = false,
                className = "",
                editable = false,
                focusable = false,
                focused = false,
                actions = emptyList(),
            )
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "dev.aiauto.webfixture"
        const val STATUS_IN_PROGRESS = 2
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 200L
        val NODE_ALIASES = linkedMapOf(
            "click" to "Fixture click button",
            "longClick" to "Fixture long press target",
            "scrollResult" to "Fixture scroll result",
        )
        val EXPECTED_WEBVIEW = mapOf(
            30 to "91.0.4472.114",
            33 to "109.0.5414.123",
            34 to "113.0.5672.136",
        )
    }
}
