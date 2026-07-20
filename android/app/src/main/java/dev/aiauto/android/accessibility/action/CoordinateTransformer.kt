package dev.aiauto.android.accessibility.action

/**
 * 功能用途：实现 CoordinateTransformer 对应的无障碍动作路由与坐标规划，供受控设备操作复用。
 */

import kotlin.math.floor

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.Gesture
import dev.aiauto.android.accessibility.model.NormalizedPoint
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.UiBounds

object CoordinateTransformer {
    fun fromNormalized(
        point: NormalizedPoint,
        bounds: ScreenBounds,
    ): AccessibilityResult<ScreenPoint> {
        if (!validNormalizedPoint(point) || !validBounds(bounds)) {
            return invalidCoordinate("Normalized point or screen bounds are invalid")
        }
        val mapped = ScreenPoint(
            x = mapAxis(point.x, bounds.left, bounds.right),
            y = mapAxis(point.y, bounds.top, bounds.bottom),
        )
        return validate(mapped, bounds)
    }

    fun fromRelative(
        point: NormalizedPoint,
        recordedBounds: UiBounds,
        screenBounds: ScreenBounds,
    ): AccessibilityResult<ScreenPoint> {
        if (
            !validNormalizedPoint(point) ||
            recordedBounds.width <= 0 ||
            recordedBounds.height <= 0
        ) {
            return invalidCoordinate("Relative point or recorded bounds are invalid")
        }
        return validate(
            point = ScreenPoint(
                x = mapAxis(point.x, recordedBounds.left, recordedBounds.right),
                y = mapAxis(point.y, recordedBounds.top, recordedBounds.bottom),
            ),
            bounds = screenBounds,
        )
    }

    fun validate(
        point: ScreenPoint,
        bounds: ScreenBounds,
    ): AccessibilityResult<ScreenPoint> =
        if (validBounds(bounds) && bounds.contains(point)) {
            AccessibilityResult.Success(point)
        } else {
            invalidCoordinate(
                message = "Point (${point.x}, ${point.y}) is outside the current screen",
            )
        }

    private fun validNormalizedPoint(point: NormalizedPoint): Boolean =
        point.x.isFinite() &&
            point.y.isFinite() &&
            point.x in 0.0..1.0 &&
            point.y in 0.0..1.0

    private fun validBounds(bounds: ScreenBounds): Boolean =
        bounds.width > 0 && bounds.height > 0

    private fun mapAxis(value: Double, start: Int, end: Int): Int {
        val offset = floor(value * (end - start)).toInt()
        return (start + offset).coerceIn(start, end - 1)
    }

    private fun invalidCoordinate(message: String): AccessibilityResult.Failure =
        AccessibilityResult.Failure(
            code = AccessibilityErrorCode.INVALID_COORDINATE,
            message = message,
        )
}

object GesturePlanner {
    fun scroll(
        bounds: ScreenBounds,
        direction: ScrollDirection,
        amount: Double,
    ): AccessibilityResult<Gesture.Swipe> {
        if (
            bounds.width <= 0 ||
            bounds.height <= 0 ||
            !amount.isFinite() ||
            amount <= 0.0 ||
            amount > 1.0
        ) {
            return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.INVALID_COORDINATE,
                message = "Scroll bounds and amount must describe a visible screen region",
            )
        }

        val centerX = bounds.left + bounds.width / 2
        val centerY = bounds.top + bounds.height / 2
        val horizontalTravel = (bounds.width * USABLE_REGION * amount / 2).toInt()
            .coerceAtLeast(1)
        val verticalTravel = (bounds.height * USABLE_REGION * amount / 2).toInt()
            .coerceAtLeast(1)
        val (start, end) = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.FORWARD -> {
                ScreenPoint(centerX, centerY + verticalTravel) to
                    ScreenPoint(centerX, centerY - verticalTravel)
            }

            ScrollDirection.UP, ScrollDirection.BACKWARD -> {
                ScreenPoint(centerX, centerY - verticalTravel) to
                    ScreenPoint(centerX, centerY + verticalTravel)
            }

            ScrollDirection.RIGHT -> {
                ScreenPoint(centerX + horizontalTravel, centerY) to
                    ScreenPoint(centerX - horizontalTravel, centerY)
            }

            ScrollDirection.LEFT -> {
                ScreenPoint(centerX - horizontalTravel, centerY) to
                    ScreenPoint(centerX + horizontalTravel, centerY)
            }
        }
        return AccessibilityResult.Success(
            Gesture.Swipe(
                start = start,
                end = end,
                durationMs = DEFAULT_SCROLL_DURATION_MS,
            ),
        )
    }

    private const val USABLE_REGION = 0.8
    private const val DEFAULT_SCROLL_DURATION_MS = 400L
}
