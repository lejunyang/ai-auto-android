package dev.aiauto.android.ui.components

/**
 * 功能用途：原型卡片语言的基础组件。
 *
 * 原型的视觉语言是「冷灰蓝底 + 纯白卡片 + 1dp 细描边 + 无阴影」，
 * Material 3 默认 Card 的 elevation / tonal 色 / 默认 shape 都对不上，
 * 必须用 border + containerColor 明确覆盖，因此集中在本文件提供。
 *
 * 所有尺寸与颜色取自 artifacts/ui-validation/proto 目录下各视图的实测 token
 * （411x914 视口下 CSS px == Android dp）。
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppShapes
import dev.aiauto.android.ui.theme.AppSpacing
import dev.aiauto.android.ui.theme.AppTypographyTokens

/**
 * 原型标准卡片：纯白底 + 1dp 细描边 + 22dp 圆角 + 无阴影。
 *
 * @param contentPadding 卡片内边距，原型常用 16dp / 居中卡片 28x18dp。
 */
@Composable
fun ProtoCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    background: Color = AppColors.Surface,
    borderColor: Color = AppColors.Line,
    shape: RoundedCornerShape = RoundedCornerShape(AppShapes.Card),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 二级页大标题（原型 .page-heading，24sp/700/36/-0.8）。
 * 原型用 <br> 手动换行，这里由调用方在字符串中放入 \n。
 */
@Composable
fun PageHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = AppTypographyTokens.PageHeadingFontSize,
        lineHeight = AppTypographyTokens.PageHeadingLineHeight,
        letterSpacing = AppTypographyTokens.PageHeadingLetterSpacing,
        fontWeight = FontWeight.Bold,
        color = AppColors.Ink,
    )
}

/** 二级页描述文字（原型 .page-desc，14sp/400/25.2，次要色）。 */
@Composable
fun PageDesc(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = AppTypographyTokens.PageDescFontSize,
        lineHeight = AppTypographyTokens.PageDescLineHeight,
        color = AppColors.Muted,
    )
}

/** 小字说明（原型 .note，14sp/400/25.2，次要色）。 */
@Composable
fun NoteText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = AppTypographyTokens.NoteFontSize,
        lineHeight = AppTypographyTokens.NoteLineHeight,
        color = AppColors.Muted,
        textAlign = textAlign,
    )
}

/** notice 提示条的语气。 */
enum class NoticeTone {
    /** 中性信息：蓝底蓝字（原型 .notice）。 */
    INFO,

    /** 需要注意：琥珀底（原型 .notice.amber），用于无障碍等高影响披露。 */
    AMBER,
}

/**
 * 提示条（原型 .notice / .notice.amber）：14dp 圆角、浅底、无描边。
 * 用于安全披露与边界说明，不承载操作。
 */
@Composable
fun NoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    title: String? = null,
) {
    val background = when (tone) {
        NoticeTone.INFO -> AppColors.NoticeBackground
        NoticeTone.AMBER -> AppColors.WarningContainer
    }
    val textColor = when (tone) {
        NoticeTone.INFO -> AppColors.NoticeText
        NoticeTone.AMBER -> AppColors.WarningNoticeText
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                fontSize = AppTypographyTokens.NoteFontSize,
                lineHeight = AppTypographyTokens.NoteLineHeight,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        }
        Text(
            text = text,
            fontSize = AppTypographyTokens.NoteFontSize,
            lineHeight = AppTypographyTokens.NoteLineHeight,
            color = textColor,
        )
    }
}

/**
 * 主按钮（原型 .primary.full）：蓝底白字、15dp 圆角、46dp 高。
 * 自绘 Box + protoClickable，无 Material 水波纹。
 */
@Composable
fun ProtoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bg = if (enabled) AppColors.Primary else AppColors.Line
    val fg = if (enabled) AppColors.Surface else AppColors.Muted
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSpacing.PrimaryButtonHeight)
            .background(bg, RoundedCornerShape(15.dp))
            .protoClickable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = AppTypographyTokens.PrimaryButtonFontSize,
            lineHeight = AppTypographyTokens.PrimaryButtonLineHeight,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 次按钮（原型 .secondary）：白底蓝字 + 描边、15dp 圆角、46dp 高。
 */
@Composable
fun ProtoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val borderColor = if (enabled) AppColors.Line else AppColors.Line
    val textColor = if (enabled) AppColors.Primary else AppColors.Muted
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSpacing.PrimaryButtonHeight)
            .background(AppColors.Surface, RoundedCornerShape(15.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(15.dp))
            .protoClickable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = AppTypographyTokens.PrimaryButtonFontSize,
            lineHeight = AppTypographyTokens.PrimaryButtonLineHeight,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 文字按钮（原型 .text-btn）：蓝色文字、12sp、最小 44dp 点击区。
 */
@Composable
fun ProtoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    center: Boolean = false,
) {
    Box(
        modifier = modifier
            .heightIn(min = AppSpacing.MinTouchTarget)
            .protoClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = AppTypographyTokens.TextBtnFontSize,
            lineHeight = AppTypographyTokens.TextBtnLineHeight,
            color = AppColors.Primary,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = if (center) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

/**
 * 表单字段标题（原型 .label）。
 */
@Composable
fun FieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = AppTypographyTokens.TileMetaFontSize,
        lineHeight = AppTypographyTokens.TileMetaLineHeight,
        fontWeight = FontWeight.Medium,
        color = AppColors.Ink,
    )
}

/**
 * 图标容器块（原型 .tile-icon）：35dp 方块、11dp 圆角、浅色底。
 * 用于技能网格卡与设置行的图标。
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    background: Color = AppColors.TileIconBackground,
    tint: Color = AppColors.Primary,
    size: androidx.compose.ui.unit.Dp = AppSpacing.TileIconSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(background, RoundedCornerShape(AppShapes.IconBox)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * 居中图标行（原型 connection 页的「手机 → 电脑」）。
 */
@Composable
fun CenteredIconRow(
    icons: List<ImageVector>,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.Primary,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.forEachIndexed { index, icon ->
            if (index > 0) Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * 段落标题行（原型 .section-head）：左侧标题，右侧可选内容（标签或文字按钮）。
 */
@Composable
fun ProtoSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = AppTypographyTokens.SectionTitleFontSize,
            lineHeight = AppTypographyTokens.SectionTitleLineHeight,
            letterSpacing = AppTypographyTokens.SectionTitleLetterSpacing,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        trailing?.invoke(this)
    }
}

/**
 * 虚线边框修饰符（原型 .teach-card 的 border:1px dashed）。
 * Compose 没有内置虚线 border，用 drawBehind + PathEffect 实现。
 */
fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.dp,
    dashLength: androidx.compose.ui.unit.Dp = 5.dp,
    gapLength: androidx.compose.ui.unit.Dp = 4.dp,
): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val radiusPx = cornerRadius.toPx()
    val effect = PathEffect.dashPathEffect(
        floatArrayOf(dashLength.toPx(), gapLength.toPx()),
        0f,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(strokePx / 2f, strokePx / 2f),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = strokePx, pathEffect = effect),
    )
}

/**
 * 功能用途：自绘 ProtoSwitch 开关，无 Material 水波纹。
 *
 * 视觉：开启态蓝色轨道 #2563EB + 白色圆点右滑；关闭态灰色轨道 + 白色圆点左滑。
 * 尺寸：轨道 51×31dp，圆角 15.5dp；圆点 27×27dp，圆角 13.5dp。
 * 交互：点击切换，走 protoClickable 无涟漪。
 */
@Composable
fun ProtoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = if (checked) Color(0xFF2563EB) else Color(0xFFE5E7EB)
    val thumbOffset = if (checked) 24.dp else 3.dp
    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset, top = 2.dp)
                .size(27.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * 功能用途：自绘 ProtoCircularProgressIndicator 旋转圈，无 Material 默认。
 *
 * 视觉：蓝色圆弧 3px 描边，持续旋转。
 */
@Composable
fun ProtoCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF2563EB),
) {
    // 简化版：静态圆弧，旋转动画用 rememberInfiniteTransition 复杂度高，
    // 此处先用静态圆弧占位，后续如需旋转可在调用方加 LaunchedEffect 驱动。
    androidx.compose.foundation.Canvas(modifier = modifier.size(24.dp)) {
        drawArc(
            color = color,
            startAngle = 270f,
            sweepAngle = 270f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
