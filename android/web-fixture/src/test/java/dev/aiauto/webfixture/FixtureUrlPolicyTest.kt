package dev.aiauto.webfixture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试用途：验证 WebView 仅允许固定离线资源路径，阻止公网、路径穿越和相似前缀绕过。
 */
class FixtureUrlPolicyTest {
    @Test
    fun onlyCanonicalFixtureAssetsAreAllowed() {
        assertTrue(FixtureUrlPolicy.isAllowed("file:///android_asset/web/full.html"))
        assertTrue(FixtureUrlPolicy.isAllowed("file:///android_asset/web/detail.html#result"))

        assertFalse(FixtureUrlPolicy.isAllowed("https://example.com/full.html"))
        assertFalse(FixtureUrlPolicy.isAllowed("http://127.0.0.1/full.html"))
        assertFalse(FixtureUrlPolicy.isAllowed("file:///android_asset/web/../secret.txt"))
        assertFalse(FixtureUrlPolicy.isAllowed("file:///android_asset/web/%2e%2e/secret.txt"))
        assertFalse(FixtureUrlPolicy.isAllowed("file:///android_asset/web//full.html"))
        assertFalse(FixtureUrlPolicy.isAllowed("file:///android_asset/web-escape/full.html"))
        assertFalse(FixtureUrlPolicy.isAllowed("content://dev.aiauto.webfixture/full.html"))
    }
}
