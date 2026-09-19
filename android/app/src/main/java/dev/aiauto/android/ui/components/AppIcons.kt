package dev.aiauto.android.ui.components

/**
 * 功能用途：自定义 stroke 描边风格图标，与原型 SVG 一致。
 * 不使用 Material Icons（实心风格不符），用 ImageVector.Builder + addPathNodes 构建。
 * 所有 stroke 图标统一用 SolidColor(Color.Black)，由 Icon 的 tint 控制实际颜色（选中/未选中）。
 * stroke-width 1.65、round cap/join、fill none；settings 的两个圆为 fill。
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 自定义图标容器，与原型 SVG stroke 描边风格一致。 */
object AppIcons {
    /** 助理 tab：四角星（spark），stroke 描边。 */
    val Spark: ImageVector
        get() = _spark ?: run {
            val icon = ImageVector.Builder(
                name = "Spark",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData = addPathNodes(
                        "M12 3l2.6 6.4L21 12l-6.4 2.6L12 21l-2.6-6.4L3 12l6.4-2.6L12 3z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _spark = icon
            icon
        }

    /** 技能 tab：两个横向圆角矩形(7×5) + 箭头（flow），stroke 描边。 */
    val Flow: ImageVector
        get() = _flow ?: run {
            val icon = ImageVector.Builder(
                name = "Flow",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 左上圆角矩形：x=3 y=3 w=7 h=5 rx=1.5（严格横向比例）
                addPath(
                    pathData = addPathNodes(
                        "M4.5 3h4c.83 0 1.5.67 1.5 1.5v2c0 .83-.67 1.5-1.5 1.5h-4C3.67 8 3 7.33 3 6.5v-2C3 3.67 3.67 3 4.5 3z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 右下圆角矩形：x=14 y=16 w=7 h=5 rx=1.5（严格横向比例）
                addPath(
                    pathData = addPathNodes(
                        "M15.5 16h4c.83 0 1.5.67 1.5 1.5v2c0 .83-.67 1.5-1.5 1.5h-4c-.83 0-1.5-.67-1.5-1.5v-2c0-.83.67-1.5 1.5-1.5z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 连接箭头：从左上矩形指向右下矩形
                addPath(
                    pathData = addPathNodes("M6.5 8v10h7.5m-2-2l2 2-2 2"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _flow = icon
            icon
        }

    /** 记录 tab：时钟（clock），stroke 描边。 */
    val Clock: ImageVector
        get() = _clock ?: run {
            val icon = ImageVector.Builder(
                name = "Clock",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 外圆：cx=12 cy=12 r=9
                addPath(
                    pathData = addPathNodes(
                        "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 指针：从 12 点到 12 点 +5，再到 3:30
                addPath(
                    pathData = addPathNodes("M12 7v5l3.5 2"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _clock = icon
            icon
        }

    /** 我的 tab：两条横线 + 两个实心圆（settings），横线 stroke 跟随 tint，圆为 fill。 */
    val Settings: ImageVector
        get() = _settings ?: run {
            val icon = ImageVector.Builder(
                name = "Settings",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 上横线（stroke 由 Icon tint 控制颜色）
                addPath(
                    pathData = addPathNodes("M4 7h16"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 下横线（stroke 由 Icon tint 控制颜色）
                addPath(
                    pathData = addPathNodes("M4 17h16"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 左上实心圆：cx=9 cy=7 r=3
                addPath(
                    pathData = addPathNodes(
                        "M12 7a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
                    ),
                    fill = SolidColor(Color.Black),
                    stroke = null,
                )
                // 右下实心圆：cx=15 cy=17 r=3
                addPath(
                    pathData = addPathNodes(
                        "M18 17a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
                    ),
                    fill = SolidColor(Color.Black),
                    stroke = null,
                )
            }.build()
            _settings = icon
            icon
        }

    /** 发送按钮：向上箭头（up），stroke 描边。原型 icon('up')。 */
    val Up: ImageVector
        get() = _up ?: run {
            val icon = ImageVector.Builder(
                name = "Up",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData = addPathNodes("M12 19V5m-6 6l6-6 6 6"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _up = icon
            icon
        }

    /** 选择应用 pill：极简手机轮廓（phone），stroke 描边。原型 icon('phone')。 */
    val Phone: ImageVector
        get() = _phone ?: run {
            val icon = ImageVector.Builder(
                name = "Phone",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 手机外框：rect x=6 y=2 width=12 height=20 rx=3
                addPath(
                    pathData = addPathNodes(
                        "M9 2h6c1.66 0 3 1.34 3 3v14c0 1.66-1.34 3-3 3H9c-1.66 0-3-1.34-3-3V5c0-1.66 1.34-3 3-3z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 底部横线：M10 18h4
                addPath(
                    pathData = addPathNodes("M10 18h4"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _phone = icon
            icon
        }

    /** 语音输入：麦克风（mic），stroke 描边。原型 icon('mic')。 */
    val Mic: ImageVector
        get() = _mic ?: run {
            val icon = ImageVector.Builder(
                name = "Mic",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 麦克风主体：rect x=9 y=3 width=6 height=12 rx=3
                addPath(
                    pathData = addPathNodes(
                        "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 支架弧线：M5 11v1a7 7 0 0 0 14 0v-1
                addPath(
                    pathData = addPathNodes("M5 11v1a7 7 0 0 0 14 0v-1"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 底部：M12 19v3m-3 0h6
                addPath(
                    pathData = addPathNodes("M12 19v3m-3 0h6"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _mic = icon
            icon
        }

    /** 设置行：盾牌（shield），stroke 描边。用于"允许操作的应用"入口。 */
    val Shield: ImageVector
        get() = _shield ?: run {
            val icon = ImageVector.Builder(
                name = "Shield",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData = addPathNodes("M12 3l8 3v5c0 4.5-3.5 8-8 9-4.5-1-8-4.5-8-9V6l8-3z"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _shield = icon
            icon
        }

    /** 设置行：显示器（monitor），stroke 描边。用于"连接电脑"入口。 */
    val Monitor: ImageVector
        get() = _monitor ?: run {
            val icon = ImageVector.Builder(
                name = "Monitor",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 屏幕：rect x=3 y=4 width=18 height=12 rx=2
                addPath(
                    pathData = addPathNodes(
                        "M5 4h14c1.1 0 2 .9 2 2v8c0 1.1-.9 2-2 2H5c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 底座支架：M12 18v3
                addPath(
                    pathData = addPathNodes("M12 18v3"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 底座：M8 21h8
                addPath(
                    pathData = addPathNodes("M8 21h8"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _monitor = icon
            icon
        }

    /**
     * 品牌符号（auto）：三角形 + 中竖线，stroke 描边。原型 icon('auto')，
     * 用于我的页蓝底 logo、问候右侧 tiny-orbit、悬浮球；区别于助理 tab 的四角星 spark。
     */
    val Auto: ImageVector
        get() = _auto ?: run {
            val icon = ImageVector.Builder(
                name = "Auto",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 三角形外轮廓：左下(5,17) → 顶点(12,5) → 右下(19,17) → 回到底边左端
                addPath(
                    pathData = addPathNodes("M5 17L12 5l7 12H5"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 中竖线：从顶点(12,5) 向下到(12,20)，穿出底边
                addPath(
                    pathData = addPathNodes("M12 5V20"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _auto = icon
            icon
        }

    /** 技能卡/技能网格：书本（book），stroke 描边。原型 icon('book')，代表"读取/来源应用"。 */
    val Book: ImageVector
        get() = _book ?: run {
            val icon = ImageVector.Builder(
                name = "Book",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 打开的书本外轮廓 + 中缝
                addPath(
                    pathData = addPathNodes(
                        "M3 5c4-2 7-1 9 1 2-2 5-3 9-1v15c-4-2-7-1-9 0-2-1-5-2-9 0V5zM12 6v14",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _book = icon
            icon
        }

    /** 技能卡第二应用：便签（note），stroke 描边。原型 icon('note')，代表"整理/目标应用"。 */
    val Note: ImageVector
        get() = _note ?: run {
            val icon = ImageVector.Builder(
                name = "Note",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 带折角的文档
                addPath(
                    pathData = addPathNodes(
                        "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6z",
                    ),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 折角
                addPath(
                    pathData = addPathNodes("M14 3v6h6"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 文本行
                addPath(
                    pathData = addPathNodes("M8 13h8M8 17h5"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _note = icon
            icon
        }

    /** 勾选圈：对勾（check），stroke 描边，用于应用选择行选中态。 */
    val Check: ImageVector
        get() = _check ?: run {
            val icon = ImageVector.Builder(
                name = "Check",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData = addPathNodes("M5 12.5l4.5 4.5L19 7"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2.4f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }.build()
            _check = icon
            icon
        }

    /** 图标缓存，避免每次重组重建 ImageVector。 */
    private var _spark: ImageVector? = null
    private var _flow: ImageVector? = null
    private var _clock: ImageVector? = null
    private var _settings: ImageVector? = null
    private var _up: ImageVector? = null
    private var _phone: ImageVector? = null
    private var _mic: ImageVector? = null
    private var _shield: ImageVector? = null
    private var _monitor: ImageVector? = null
    private var _auto: ImageVector? = null
    private var _book: ImageVector? = null
    private var _note: ImageVector? = null
    private var _check: ImageVector? = null
}