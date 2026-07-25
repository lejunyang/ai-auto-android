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
        val webViewTag = requireNotNull(
            Regex("<WebView[\\s\\S]*?/>").find(layout)?.value,
        )
        assertFalse("WebView host descriptions collapse virtual children", webViewTag.contains("contentDescription"))
        assertTrue("WebView must activate its virtual node provider", webViewTag.contains("importantForAccessibility=\"yes\""))
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
