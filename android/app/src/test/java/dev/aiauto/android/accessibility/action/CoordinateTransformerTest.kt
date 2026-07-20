package dev.aiauto.android.accessibility.action

// 测试用途：验证 CoordinateTransformer 的功能契约、失败语义及自动化安全边界。

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.NormalizedPoint
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformerTest {
    @Test
    fun `maps normalized corners into the inclusive screen pixel range`() {
        val bounds = ScreenBounds(left = 10, top = 20, right = 110, bottom = 220)

        assertEquals(
            ScreenPoint(x = 10, y = 20),
            CoordinateTransformer.fromNormalized(NormalizedPoint(0.0, 0.0), bounds)
                .successValue(),
        )
        assertEquals(
            ScreenPoint(x = 109, y = 219),
            CoordinateTransformer.fromNormalized(NormalizedPoint(1.0, 1.0), bounds)
                .successValue(),
        )
    }

    @Test
    fun `rejects non finite and out of range normalized coordinates`() {
        val bounds = ScreenBounds(left = 0, top = 0, right = 100, bottom = 100)

        assertTrue(
            CoordinateTransformer.fromNormalized(NormalizedPoint(Double.NaN, 0.5), bounds) is
                AccessibilityResult.Failure,
        )
        assertTrue(
            CoordinateTransformer.fromNormalized(NormalizedPoint(0.5, 1.01), bounds) is
                AccessibilityResult.Failure,
        )
    }

    @Test
    fun `validates absolute coordinates against half open screen bounds`() {
        val bounds = ScreenBounds(left = 0, top = 0, right = 1080, bottom = 2400)

        assertEquals(
            ScreenPoint(x = 1079, y = 2399),
            CoordinateTransformer.validate(ScreenPoint(1079, 2399), bounds).successValue(),
        )
        assertTrue(
            CoordinateTransformer.validate(ScreenPoint(1080, 2399), bounds) is
                AccessibilityResult.Failure,
        )
        assertTrue(
            CoordinateTransformer.validate(ScreenPoint(-1, 0), bounds) is
                AccessibilityResult.Failure,
        )
    }

    @Test
    fun `maps a relative point inside recorded bounds and validates the result`() {
        val result = CoordinateTransformer.fromRelative(
            point = NormalizedPoint(x = 0.25, y = 0.75),
            recordedBounds = UiBounds(left = 100, top = 200, right = 300, bottom = 400),
            screenBounds = ScreenBounds(left = 0, top = 0, right = 1080, bottom = 2400),
        )

        assertEquals(ScreenPoint(x = 150, y = 350), result.successValue())
    }

    @Test
    fun `plans scroll down as an upward finger swipe inside the supplied bounds`() {
        val gesture = GesturePlanner.scroll(
            bounds = ScreenBounds(left = 0, top = 0, right = 1000, bottom = 2000),
            direction = ScrollDirection.DOWN,
            amount = 0.5,
        ).successValue()

        assertTrue(gesture.start.y > gesture.end.y)
        assertEquals(500, gesture.start.x)
        assertEquals(500, gesture.end.x)
    }

    private fun <T> AccessibilityResult<T>.successValue(): T {
        assertTrue(this is AccessibilityResult.Success)
        return (this as AccessibilityResult.Success).value
    }
}
