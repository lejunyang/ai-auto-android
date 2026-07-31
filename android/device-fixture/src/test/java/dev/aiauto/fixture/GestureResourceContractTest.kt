package dev.aiauto.fixture

/**
 * 测试用途：验证原生测试列表使用平台滚动控件并报告真实位置，防止设备矩阵再次退化为零位移。
 */

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureResourceContractTest {
    @Test
    fun verticalFixtureUsesPlatformListInsteadOfRejectedScrollView() {
        val moduleRoot = locateModuleRoot()
        val layout = moduleRoot.resolve("src/main/res/layout/activity_main.xml").readText()
        val itemLayout = moduleRoot.resolve(
            "src/main/res/layout/vertical_scroll_item.xml",
        )
        val rejectedScrollView = moduleRoot.resolve(
            "src/main/java/dev/aiauto/fixture/DeterministicScrollView.kt",
        )

        assertTrue(layout.contains("<LinearLayout xmlns:android="))
        assertTrue(layout.contains("<ListView"))
        assertTrue(!layout.contains("DeterministicScrollView"))
        assertTrue(!rejectedScrollView.exists())
        assertTrue(layout.contains("android:id=\"@+id/vertical_scroll_target\""))
        assertTrue(layout.contains("android:contentDescription=\"@string/vertical_scroll_target_description\""))
        assertTrue(itemLayout.isFile)
        assertTrue(itemLayout.readText().contains("android:id=\"@+id/vertical_scroll_item_label\""))
        assertTrue(layout.contains("android:id=\"@+id/horizontal_swipe_target\""))
        assertTrue(layout.contains("android:layout_width=\"720dp\""))
    }

    @Test
    fun deviceTestUsesObservedListBoundsAndRealOffsets() {
        val moduleRoot = locateModuleRoot()
        val deviceTest = moduleRoot.resolve(
            "src/androidTest/java/dev/aiauto/fixture/NativeFixtureDeviceTest.kt",
        ).readText()
        val activity = moduleRoot.resolve(
            "src/main/java/dev/aiauto/fixture/MainActivity.kt",
        ).readText()

        assertTrue(deviceTest.contains("swipeVerticallyWithin(vertical.visibleBounds)"))
        assertTrue(deviceTest.contains("scenario == \"vertical\""))
        assertTrue(deviceTest.contains("setOf(\"all\", \"core\", \"vertical\", \"system\")"))
        assertTrue(deviceTest.contains("swipeHorizontallyWithin(horizontal.visibleBounds)"))
        assertTrue(deviceTest.contains("assertPositiveOffset"))
        assertTrue(activity.contains("VerticalListOffset.calculate"))
        assertTrue(activity.contains("offset > 0"))
        assertTrue(activity.contains("scrollX != oldScrollX"))
    }

    private fun locateModuleRoot(): File {
        val current = File(
            requireNotNull(System.getProperty("user.dir")) {
                "user.dir is required to locate device-fixture resources"
            },
        ).canonicalFile
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("src/test").isDirectory }
            ?: error("unable to locate device-fixture module root from $current")
    }
}
