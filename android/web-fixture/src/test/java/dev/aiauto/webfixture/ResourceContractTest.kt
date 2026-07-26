package dev.aiauto.webfixture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试用途：验证离线 Web fixture 的固定页面和资源存在，防止构建产物退化为联网占位页。
 */
class ResourceContractTest {
    @Test
    fun requiredOfflineAssetsArePresent() {
        val assetRoot = locateModuleRoot().resolve("src/main/assets/web")

        assertTrue("missing offline asset root: $assetRoot", assetRoot.isDirectory)
        val requiredFiles = listOf(
            "full.html",
            "partial.html",
            "canvas.html",
            "detail.html",
            "iframe.html",
            "fixture.css",
            "fixture.js",
        )
        requiredFiles.forEach { name ->
            assertTrue("missing offline asset: $name", assetRoot.resolve(name).isFile)
        }
    }

    @Test
    fun pagesCoverRequiredActionsWithoutRemoteResources() {
        val assetRoot = locateModuleRoot().resolve("src/main/assets/web")
        val full = assetRoot.resolve("full.html").readText()
        val canvas = assetRoot.resolve("canvas.html").readText()
        val allResources = assetRoot.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }

        listOf("fixture-button", "fixture-input", "long-press-target", "scroll-target",
            "dynamic-container", "fixture-frame").forEach { id ->
            assertTrue("full mode is missing $id", full.contains("id=\"$id\""))
        }
        listOf("fixture-canvas", "canvas-result", "pointerdown", "pointerup").forEach { token ->
            assertTrue("canvas mode is missing $token", canvas.contains(token))
        }
        assertFalse("fixture resources must not reference HTTP", allResources.contains("http://"))
        assertFalse("fixture resources must not reference HTTPS", allResources.contains("https://"))
    }

    @Test
    fun manifestAndRuntimeKeepNetworkAndDebuggingDisabled() {
        val moduleRoot = locateModuleRoot()
        val manifest = moduleRoot.resolve("src/main/AndroidManifest.xml").readText()
        val networkConfig = moduleRoot.resolve(
            "src/main/res/xml/network_security_config.xml",
        ).readText()
        val layout = moduleRoot.resolve("src/main/res/layout/activity_main.xml").readText()
        val mainActivity = moduleRoot.resolve(
            "src/main/java/dev/aiauto/webfixture/MainActivity.java",
        ).readText()

        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(networkConfig.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(mainActivity.contains("setWebContentsDebuggingEnabled(false)"))
        assertTrue(mainActivity.contains("setBlockNetworkLoads(true)"))
        assertTrue(mainActivity.contains("onPageFinished"))
        assertTrue(mainActivity.contains("getOnBackPressedDispatcher()"))
        assertTrue(mainActivity.contains("handleOnBackPressed"))
        assertTrue(mainActivity.contains("webView.canGoBack()"))
        assertTrue(mainActivity.contains("webView.goBack()"))
        assertTrue(layout.contains("generation_status"))
        val webViewTag = requireNotNull(
            Regex("<WebView[\\s\\S]*?/>").find(layout)?.value,
        )
        assertFalse("WebView host descriptions collapse virtual children", webViewTag.contains("contentDescription"))
        assertTrue("WebView must activate its virtual node provider", webViewTag.contains("importantForAccessibility=\"yes\""))
    }

    @Test
    fun n43CapabilityProbeIsReadOnlyAndEmitsOnlyBoundedMetadata() {
        val probe = locateModuleRoot().resolve(
            "src/androidTest/java/dev/aiauto/webfixture/N43WebViewCapabilityProbeTest.kt",
        ).readText()

        assertTrue(probe.contains("N43_CAPABILITY_MATRIX"))
        assertTrue(probe.contains("actionList"))
        listOf(
            "performAction(",
            "UiDevice",
            "takeScreenshot",
            "ClipboardManager",
            "InputMethodManager",
            "executeShellCommand",
            "evaluateJavascript",
            "loadUrl(\"javascript:",
            "getBoundsInScreen",
            "\"text\"",
            "\"contentDescription\"",
            "\"bounds\"",
        ).forEach { forbidden ->
            assertFalse("capability probe contains forbidden API or field: $forbidden", probe.contains(forbidden))
        }
    }

    @Test
    fun n43SemanticCoverageClassifiesIframeWithoutUnsafeReplay() {
        val coverage = locateModuleRoot().resolve(
            "src/androidTest/java/dev/aiauto/webfixture/N43SemanticReplayDeviceTest.kt",
        ).readText()

        assertTrue(coverage.contains("N43_HYBRID_REQUIRED:longClick"))
        assertTrue(coverage.contains("N43_HYBRID_REQUIRED:iframePostcondition"))
        assertTrue(coverage.contains("N43_FULL_SEMANTIC:iframe"))
        assertTrue(coverage.contains("N43_HYBRID_REQUIRED:detailPostcondition"))
        assertTrue(coverage.contains("N43_FULL_SEMANTIC:detailPostcondition"))
        assertTrue(coverage.contains("scrollAndClickResult(iteration)"))
        assertTrue(coverage.contains("GLOBAL_ACTION_BACK"))
        listOf("Offline frame button", "Detail page action").forEach { target ->
            val invocation = "performAction(\"$target\""
            assertTrue(
                "$target action must be committed at most once before postcondition classification",
                coverage.windowed(invocation.length).count { it == invocation } == 1,
            )
        }
        listOf(
            "UiDevice",
            "takeScreenshot",
            "ClipboardManager",
            "InputMethodManager",
            "executeShellCommand",
            "evaluateJavascript",
            "loadUrl(\"javascript:",
            "ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT",
        ).forEach { forbidden ->
            assertFalse("semantic coverage contains forbidden fallback: $forbidden", coverage.contains(forbidden))
        }
    }

    private fun locateModuleRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is required to locate fixture resources"
        }
        val current = File(userDirectory).canonicalFile
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("src/test").isDirectory }
            ?: error("unable to locate web-fixture module root from $current")
    }
}
