package dev.aiauto.android.accessibility.action

/**
 * 功能用途：把 drag、连击、pinch/zoom、旋转、双指滚动与路径手势等高层意图
 * 编排为 Gesture.MultiStroke 的笔画时序，集中承担平台续接与多指规则。
 *
 * 为什么单独成文件：这些手势的难点不在派发而在时序编排——
 * drag 必须先满足系统长按阈值才会进入拖拽态，连击必须落在多击窗口内，
 * pinch 必须两指同时开始同时结束。把这些常量与推导集中在一处，
 * 才能保证 Android 侧与协议侧对「什么算一次 drag」有同一个定义。
 */

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.Gesture
import dev.aiauto.android.accessibility.model.GestureStroke
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.ScreenPoint

object CompositeGesturePlanner {
    /**
     * 编排拖拽：按住—停顿—移动—释放前的短暂停留。
     *
     * 分四段而不是一段 swipe，因为系统要先识别出长按才会进入拖拽态：
     * - 第 1 段在起点原地按住 [holdMs]，必须超过 ViewConfiguration 的长按阈值
     *   （默认 500ms，各 ROM 有差异），否则整个手势会被当成一次滑动；
     * - 第 2 段沿直线移动到终点；
     * - 第 3 段在终点原地停留，让接收方有时间处理 drop——很多列表在手指
     *   立刻抬起时会把这次拖拽判为取消。
     *
     * 三段同指续接，中途不抬手。
     */
    fun drag(
        start: ScreenPoint,
        end: ScreenPoint,
        holdMs: Long = DEFAULT_DRAG_HOLD_MS,
        moveMs: Long = DEFAULT_DRAG_MOVE_MS,
        settleMs: Long = DEFAULT_DRAG_SETTLE_MS,
    ): AccessibilityResult<Gesture.MultiStroke> {
        if (holdMs < MIN_DRAG_HOLD_MS || holdMs > MAX_SEGMENT_MS) {
            return invalidAction(
                "Drag hold must be between $MIN_DRAG_HOLD_MS and $MAX_SEGMENT_MS ms",
            )
        }
        if (moveMs < MIN_SEGMENT_MS || moveMs > MAX_SEGMENT_MS) {
            return invalidAction("Drag move must be between $MIN_SEGMENT_MS and $MAX_SEGMENT_MS ms")
        }
        if (settleMs < 0 || settleMs > MAX_SEGMENT_MS) {
            return invalidAction("Drag settle must be between 0 and $MAX_SEGMENT_MS ms")
        }
        val strokes = mutableListOf(
            GestureStroke(
                path = listOf(start, start),
                startTimeMs = 0,
                durationMs = holdMs,
                continued = true,
            ),
            GestureStroke(
                path = listOf(start, end),
                startTimeMs = 0,
                durationMs = moveMs,
                continued = settleMs > 0,
                continuation = true,
            ),
        )
        if (settleMs > 0) {
            strokes += GestureStroke(
                path = listOf(end, end),
                startTimeMs = 0,
                durationMs = settleMs,
                continued = false,
                continuation = true,
            )
        }
        return AccessibilityResult.Success(Gesture.MultiStroke(strokes))
    }

    /**
     * 编排 N 连击。
     *
     * 每次点击是独立的一笔（不续接）：连击的语义正是「抬手再落下」，
     * 用续接反而会变成一次长按。间隔通过 startTimeMs 表达，必须小于系统
     * 多击超时（ViewConfiguration 默认 300ms），否则会被识别成两次单击。
     */
    fun multiTap(
        point: ScreenPoint,
        taps: Int,
        tapDurationMs: Long = DEFAULT_TAP_MS,
        intervalMs: Long = DEFAULT_MULTI_TAP_INTERVAL_MS,
    ): AccessibilityResult<Gesture.MultiStroke> {
        if (taps < MIN_TAPS || taps > MAX_TAPS) {
            return invalidAction("Tap count must be between $MIN_TAPS and $MAX_TAPS")
        }
        if (tapDurationMs < MIN_SEGMENT_MS || tapDurationMs > MAX_TAP_MS) {
            return invalidAction("Tap duration must be between $MIN_SEGMENT_MS and $MAX_TAP_MS ms")
        }
        if (intervalMs < MIN_SEGMENT_MS || intervalMs > MAX_MULTI_TAP_INTERVAL_MS) {
            return invalidAction(
                "Tap interval must be between $MIN_SEGMENT_MS and " +
                    "$MAX_MULTI_TAP_INTERVAL_MS ms to stay inside the system multi-tap window",
            )
        }
        // 每次点击用不同的 pointerId：同一 pointerId 的多笔会被派发逻辑
        // 当作续接候选，而连击恰恰要求抬手。用独立 pointerId 表达「彼此无关的点击」，
        // 由 startTimeMs 承担时序。
        val strokes = (0 until taps).map { index ->
            GestureStroke(
                path = listOf(point, point),
                startTimeMs = index * (tapDurationMs + intervalMs),
                durationMs = tapDurationMs,
                pointerId = index,
            )
        }
        return AccessibilityResult.Success(Gesture.MultiStroke(strokes))
    }

    /**
     * 编排双指缩放。
     *
     * [scale] 小于 1 是捏合（pinch in），大于 1 是放大（zoom out）。
     * 两指沿同一条过中心的直线对称移动，起止时间完全一致——不同步会被识别成
     * 两次独立滑动而不是缩放。
     *
     * 起始半径取可用区域短边的固定比例：太小则两指初始距离不足，系统区分不出
     * 缩放意图；太大则缩放后的落点会越界。
     */
    fun pinch(
        center: ScreenPoint,
        scale: Double,
        bounds: ScreenBounds,
        durationMs: Long = DEFAULT_PINCH_MS,
    ): AccessibilityResult<Gesture.MultiStroke> {
        if (!scale.isFinite() || scale < MIN_SCALE || scale > MAX_SCALE || scale == 1.0) {
            return invalidAction(
                "Scale must be between $MIN_SCALE and $MAX_SCALE and must not equal 1",
            )
        }
        if (durationMs < MIN_PINCH_MS || durationMs > MAX_SEGMENT_MS) {
            return invalidAction("Pinch duration must be between $MIN_PINCH_MS and $MAX_SEGMENT_MS ms")
        }
        if (bounds.width <= 0 || bounds.height <= 0) {
            return invalidCoordinate("Pinch bounds must describe a visible screen region")
        }

        val shortEdge = minOf(bounds.width, bounds.height)
        val startRadius = (shortEdge * PINCH_START_RADIUS_RATIO).roundToInt()
            .coerceAtLeast(MIN_PINCH_RADIUS_PX)
        val endRadius = (startRadius * scale).roundToInt().coerceAtLeast(MIN_PINCH_RADIUS_PX)
        val maxRadius = (shortEdge / 2.0 * PINCH_MAX_RADIUS_RATIO).roundToInt()
        if (startRadius > maxRadius || endRadius > maxRadius) {
            return invalidCoordinate(
                "The requested scale moves the pinch pointers outside the usable region",
            )
        }

        // 沿水平轴对称：水平方向在竖屏设备上可用空间更充裕，
        // 且不会与系统边缘返回手势冲突（那是屏幕左右极窄边缘）。
        val first = radialStroke(center, -startRadius, -endRadius, durationMs, pointerId = 0)
        val second = radialStroke(center, startRadius, endRadius, durationMs, pointerId = 1)
        return validatedMultiStroke(listOf(first, second), bounds)
    }

    /**
     * 编排双指旋转。
     *
     * 两指绕中心保持等距同时转过 [degrees]。路径按固定角步长离散化为折线：
     * 平台 Path 只接受直线段，步长过大时轨迹会明显偏离圆弧，
     * 接收方可能识别不出旋转。
     */
    fun rotate(
        center: ScreenPoint,
        degrees: Double,
        bounds: ScreenBounds,
        durationMs: Long = DEFAULT_ROTATE_MS,
    ): AccessibilityResult<Gesture.MultiStroke> {
        if (!degrees.isFinite() || degrees == 0.0 ||
            degrees < -MAX_ROTATE_DEGREES || degrees > MAX_ROTATE_DEGREES
        ) {
            return invalidAction(
                "Rotation must be a non-zero angle within " +
                    "±$MAX_ROTATE_DEGREES degrees",
            )
        }
        if (durationMs < MIN_PINCH_MS || durationMs > MAX_SEGMENT_MS) {
            return invalidAction(
                "Rotate duration must be between $MIN_PINCH_MS and $MAX_SEGMENT_MS ms",
            )
        }
        if (bounds.width <= 0 || bounds.height <= 0) {
            return invalidCoordinate("Rotate bounds must describe a visible screen region")
        }

        val shortEdge = minOf(bounds.width, bounds.height)
        val radius = (shortEdge * PINCH_START_RADIUS_RATIO).roundToInt()
            .coerceAtLeast(MIN_PINCH_RADIUS_PX)
        val steps = (kotlin.math.abs(degrees) / ROTATE_DEGREES_PER_STEP)
            .toInt()
            .coerceIn(MIN_ROTATE_STEPS, MAX_ROTATE_STEPS)
        val strokes = listOf(0.0, PI).mapIndexed { pointerId, baseAngle ->
            GestureStroke(
                path = (0..steps).map { step ->
                    val angle = baseAngle + Math.toRadians(degrees) * step / steps
                    ScreenPoint(
                        x = center.x + (radius * cos(angle)).roundToInt(),
                        y = center.y + (radius * sin(angle)).roundToInt(),
                    )
                },
                startTimeMs = 0,
                durationMs = durationMs,
                pointerId = pointerId,
            )
        }
        return validatedMultiStroke(strokes, bounds)
    }

    /**
     * 编排双指同向滚动。
     *
     * 与单指滑动的区别不是冗余：地图、部分文档视图与嵌套滚动容器把单指滑动
     * 解释为平移或选中，只有双指同向滑动才触发外层滚动。
     */
    fun twoFingerScroll(
        center: ScreenPoint,
        deltaX: Int,
        deltaY: Int,
        bounds: ScreenBounds,
        durationMs: Long = DEFAULT_TWO_FINGER_SCROLL_MS,
    ): AccessibilityResult<Gesture.MultiStroke> {
        if (deltaX == 0 && deltaY == 0) {
            return invalidAction("Two-finger scroll requires a non-zero delta")
        }
        if (durationMs < MIN_PINCH_MS || durationMs > MAX_SEGMENT_MS) {
            return invalidAction(
                "Two-finger scroll duration must be between $MIN_PINCH_MS and $MAX_SEGMENT_MS ms",
            )
        }
        if (bounds.width <= 0 || bounds.height <= 0) {
            return invalidCoordinate(
                "Two-finger scroll bounds must describe a visible screen region",
            )
        }
        // 两指沿垂直于位移方向分开：竖直滚动时左右分开，水平滚动时上下分开。
        // 若沿位移方向排列，两指会前后重叠成一条线，部分接收方判不出双指。
        val separation = (minOf(bounds.width, bounds.height) * TWO_FINGER_SEPARATION_RATIO)
            .roundToInt()
            .coerceAtLeast(MIN_PINCH_RADIUS_PX)
        val perpendicular = if (hypot(deltaX.toDouble(), 0.0) >= hypot(0.0, deltaY.toDouble())) {
            ScreenPoint(x = 0, y = separation)
        } else {
            ScreenPoint(x = separation, y = 0)
        }
        val strokes = listOf(1, -1).mapIndexed { pointerId, sign ->
            val origin = ScreenPoint(
                x = center.x + perpendicular.x * sign / 2,
                y = center.y + perpendicular.y * sign / 2,
            )
            GestureStroke(
                path = listOf(
                    origin,
                    ScreenPoint(x = origin.x + deltaX, y = origin.y + deltaY),
                ),
                startTimeMs = 0,
                durationMs = durationMs,
                pointerId = pointerId,
            )
        }
        return validatedMultiStroke(strokes, bounds)
    }

    /**
     * 编排单指连续路径手势（图案解锁等）。
     *
     * 整条路径必须一笔完成、中途不抬手，因此是单个 stroke 的多点折线，
     * 而不是多笔续接——图案解锁在手指抬起时即判定结束。
     */
    fun path(
        points: List<ScreenPoint>,
        bounds: ScreenBounds,
        durationMs: Long = DEFAULT_PATH_MS,
    ): AccessibilityResult<Gesture.MultiStroke> {
        if (points.size < MIN_PATH_POINTS || points.size > MAX_PATH_POINTS) {
            return invalidAction(
                "Path must contain between $MIN_PATH_POINTS and $MAX_PATH_POINTS points",
            )
        }
        if (durationMs < MIN_SEGMENT_MS || durationMs > MAX_SEGMENT_MS) {
            return invalidAction("Path duration must be between $MIN_SEGMENT_MS and $MAX_SEGMENT_MS ms")
        }
        if (bounds.width <= 0 || bounds.height <= 0) {
            return invalidCoordinate("Path bounds must describe a visible screen region")
        }
        return validatedMultiStroke(
            listOf(
                GestureStroke(
                    path = points,
                    startTimeMs = 0,
                    durationMs = durationMs,
                ),
            ),
            bounds,
        )
    }

    private fun radialStroke(
        center: ScreenPoint,
        startOffset: Int,
        endOffset: Int,
        durationMs: Long,
        pointerId: Int,
    ) = GestureStroke(
        path = listOf(
            ScreenPoint(x = center.x + startOffset, y = center.y),
            ScreenPoint(x = center.x + endOffset, y = center.y),
        ),
        startTimeMs = 0,
        durationMs = durationMs,
        pointerId = pointerId,
    )

    /**
     * 校验所有笔画的每个点都落在屏幕内后再返回手势。
     *
     * 越界点必须在派发前拒绝：平台对越界坐标的处理是静默截断，
     * 手势会「成功」但作用在错误位置，比明确失败更难排查。
     */
    private fun validatedMultiStroke(
        strokes: List<GestureStroke>,
        bounds: ScreenBounds,
    ): AccessibilityResult<Gesture.MultiStroke> {
        strokes.forEach { stroke ->
            stroke.path.forEach { point ->
                if (!bounds.contains(point)) {
                    return invalidCoordinate(
                        "Gesture point (${point.x}, ${point.y}) is outside the current screen",
                    )
                }
            }
        }
        return AccessibilityResult.Success(Gesture.MultiStroke(strokes))
    }

    private fun invalidAction(message: String): AccessibilityResult.Failure =
        AccessibilityResult.Failure(
            code = AccessibilityErrorCode.INVALID_ACTION,
            message = message,
        )

    private fun invalidCoordinate(message: String): AccessibilityResult.Failure =
        AccessibilityResult.Failure(
            code = AccessibilityErrorCode.INVALID_COORDINATE,
            message = message,
        )

    // drag 的按住时长下限取 400ms。系统默认长按阈值是 500ms，但部分 ROM 更低；
    // 400 是「允许调用方在快速设备上调低」与「不至于退化成滑动」之间的折中，
    // 默认值仍取高于 500 的 600ms。
    private const val MIN_DRAG_HOLD_MS = 400L
    private const val DEFAULT_DRAG_HOLD_MS = 600L
    private const val DEFAULT_DRAG_MOVE_MS = 500L
    // drop 前的停留取 200ms：低于此值时部分列表会把拖拽判为取消。
    private const val DEFAULT_DRAG_SETTLE_MS = 200L

    private const val MIN_SEGMENT_MS = 1L
    private const val MAX_SEGMENT_MS = 10_000L

    private const val DEFAULT_TAP_MS = 60L
    private const val MAX_TAP_MS = 300L
    private const val MIN_TAPS = 2
    // 连击上限取 5：三连击已属罕见，5 留出余量；再多会超出系统多击计数。
    private const val MAX_TAPS = 5
    // 多击间隔默认 80ms、上限 250ms：系统多击超时默认 300ms，
    // 贴着上限会在慢设备上被拆成两次单击。
    private const val DEFAULT_MULTI_TAP_INTERVAL_MS = 80L
    private const val MAX_MULTI_TAP_INTERVAL_MS = 250L

    private const val MIN_SCALE = 0.2
    private const val MAX_SCALE = 5.0
    private const val MIN_PINCH_MS = 100L
    private const val DEFAULT_PINCH_MS = 500L
    // 起始半径取短边的 15%：在 320px 短边上约 48px，两指间距约 96px，
    // 足以让系统区分出缩放意图。
    private const val PINCH_START_RADIUS_RATIO = 0.15
    private const val PINCH_MAX_RADIUS_RATIO = 0.9
    private const val MIN_PINCH_RADIUS_PX = 8

    private const val DEFAULT_ROTATE_MS = 600L
    private const val MAX_ROTATE_DEGREES = 360.0
    // 每 15 度一段：圆弧与折线的最大偏差约为半径的 0.9%，肉眼与识别器都无感。
    private const val ROTATE_DEGREES_PER_STEP = 15.0
    private const val MIN_ROTATE_STEPS = 2
    private const val MAX_ROTATE_STEPS = 24

    private const val DEFAULT_TWO_FINGER_SCROLL_MS = 400L
    private const val TWO_FINGER_SEPARATION_RATIO = 0.2

    private const val DEFAULT_PATH_MS = 800L
    private const val MIN_PATH_POINTS = 2
    // 路径点上限取 32：图案解锁最多 9 点，手绘轨迹按 15 度离散也远低于此。
    private const val MAX_PATH_POINTS = 32
}
