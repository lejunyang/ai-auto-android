package dev.aiauto.android.ui.components

/**
 * 功能用途：自定义 stroke 描边风格图标，与原型 SVG 一致。
 * 不使用 Material Icons（实心风格不符），用 ImageVector.Builder + addPathNodes 构建。
 * stroke-width 1.65、round cap/join、fill none；settings 的两个圆为 fill。
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
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

    /** 技能 tab：两个圆角矩形 + 箭头（flow），stroke 描边。 */
    val Flow: ImageVector
        get() = _flow ?: run {
            val icon = ImageVector.Builder(
                name = "Flow",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 左上圆角矩形：x=3 y=3 w=7 h=5 rx=1.5
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
                // 右下圆角矩形：x=14 y=16 w=7 h=5 rx=1.5
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
                // 箭头：从左上矩形指向右下矩形
                addPath(
                    pathData = addPathNodes("M6.5 8v10h7.5"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                addPath(
                    pathData = addPathNodes("M12 16l2 2-2 2"),
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

    /** 我的 tab：两条横线 + 两个实心圆（settings），圆为 fill 不 stroke。 */
    val Settings: ImageVector
        get() = _settings ?: run {
            val icon = ImageVector.Builder(
                name = "Settings",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 上横线
                addPath(
                    pathData = addPathNodes("M4 7h16"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
                // 下横线
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

    /** 图标缓存，避免每次重组重建 ImageVector。 */
    private var _spark: ImageVector? = null
    private var _flow: ImageVector? = null
    private var _clock: ImageVector? = null
    private var _settings: ImageVector? = null
}
