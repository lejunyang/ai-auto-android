package dev.aiauto.fixture

/**
 * 测试用途：锁定原生 Fixture 的可滚动内容、嵌套手势归属和方向，防止设备矩阵再次退化为零位移。
 */

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureResourceContractTest {
    @Test
    fun fixtureKeepsScrollableContentAndNestedGestureOwnership() {
        val moduleRoot = locateModuleRoot()
        val layout = moduleRoot.resolve("src/main/res/layout/activity_main.xml").readText()
        val scrollView = moduleRoot.resolve(
            "src/main/java/dev/aiauto/fixture/DeterministicScrollView.kt",
        ).readText()

        assertTrue(layout.contains("android:id=\"@+id/fixture_scroll_container\""))
        assertTrue(layout.contains("<dev.aiauto.fixture.DeterministicScrollView"))
        assertTrue(layout.contains("android:id=\"@+id/vertical_scroll_target\""))
        assertTrue(layout.contains("android:layout_height=\"100dp\""))
        assertTrue(layout.contains("android:layout_height=\"240dp\""))
        assertTrue(layout.contains("android:id=\"@+id/horizontal_swipe_target\""))
        assertTrue(layout.contains("android:layout_width=\"720dp\""))
        assertTrue(scrollView.contains("requestDisallowInterceptTouchEvent(true)"))
    }

    @Test
    fun deviceTestUsesObservedBoundsAndRealOffsets() {
        val moduleRoot = locateModuleRoot()
        val deviceTest = moduleRoot.resolve(
            "src/androidTest/java/dev/aiauto/fixture/NativeFixtureDeviceTest.kt",
        ).readText()
        val activity = moduleRoot.resolve(
            "src/main/java/dev/aiauto/fixture/MainActivity.kt",
        ).readText()
        val scrollView = moduleRoot.resolve(
            "src/main/java/dev/aiauto/fixture/DeterministicScrollView.kt",
        ).readText()

        assertTrue(deviceTest.contains("OUTER_REVEAL_DIRECTION = Direction.UP"))
        assertTrue(deviceTest.contains("swipeVerticallyWithin(vertical.visibleBounds)"))
        assertTrue(deviceTest.contains("swipeHorizontallyWithin(horizontal.visibleBounds)"))
        assertTrue(deviceTest.contains("assertPositiveOffset"))
        assertTrue(scrollView.contains("scrollBy(0, delta)"))
        assertTrue(activity.contains("scrollY != oldScrollY"))
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
