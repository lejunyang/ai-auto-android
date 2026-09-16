package dev.aiauto.android.ui.theme

/**
 * 功能用途：C 端随身 Agent UI 的设计 token 层。
 * 所有值从 docs/ui-prototype/index.html 通过 getComputedStyle 机器抽取，
 * 基准文件 artifacts/ui-validation/main-agent/prototype-tokens.json（411x914 视口，CSS px == dp）。
 * 本文件常量必须与基准 token 一致，漂移由 DesignTokenParityTest 拦住（make android-verify）。
 *
 * 卡片语言：有边框 + 冷白底 + 无阴影。Material 3 默认 Card 的 elevation/tonal 色和默认 shape
 * 都对不上，使用时必须用 border + containerColor 明确覆盖。
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

//region 颜色 token（sRGB hex，与原型 color.* 一一对应）

object AppColors {
    /** 页面背景（原型 background #FAFBFC）。 */
    const val BackgroundArgb = 0xFFFAFBFC
    val Background = Color(BackgroundArgb)

    /** 卡片表面（原型 surface #FFFFFF）。 */
    const val SurfaceArgb = 0xFFFFFFFF
    val Surface = Color(SurfaceArgb)

    /** 次级表面/底部导航（原型 surfaceMuted #fafbfc）。 */
    const val SurfaceMutedArgb = 0xFFFAFBFC
    val SurfaceMuted = Color(SurfaceMutedArgb)

    /** 主文字（原型 ink #17212E）。 */
    const val InkArgb = 0xFF17212E
    val Ink = Color(InkArgb)

    /** 次要文字（原型 muted #6B7788）。 */
    const val MutedArgb = 0xFF6B7788
    val Muted = Color(MutedArgb)

    /** 描边线（原型 line #E5EAF1）。 */
    const val LineArgb = 0xFFE5EAF1
    val Line = Color(LineArgb)

    /** 主色/克制蓝（原型 primary #2563EB）。 */
    const val PrimaryArgb = 0xFF2563EB
    val Primary = Color(PrimaryArgb)

    /** 主色容器（原型 primaryContainer #EEF4FF）。 */
    const val PrimaryContainerArgb = 0xFFEEF4FF
    val PrimaryContainer = Color(PrimaryContainerArgb)

    /** 成功色（原型 success #237863）。 */
    const val SuccessArgb = 0xFF237863
    val Success = Color(SuccessArgb)

    /** 警告色（原型 warning #9B6419）。 */
    const val WarningArgb = 0xFF9B6419
    val Warning = Color(WarningArgb)

    /** 危险/错误色（原型 danger #B63E49）。 */
    const val DangerArgb = 0xFFB63E49
    val Danger = Color(DangerArgb)

    //region 组件级颜色（原型 components.*.borderColor / background）

    /** composer 卡片描边（原型 #D9E3F3）。 */
    const val ComposerBorderArgb = 0xFFD9E3F3
    val ComposerBorder = Color(ComposerBorderArgb)

    /** 技能 tile 描边（原型 #E5EAF1）。 */
    const val SkillTileBorderArgb = 0xFFE5EAF1
    val SkillTileBorder = Color(SkillTileBorderArgb)

    /** teach 卡片背景（原型 #F3F6FC）。 */
    const val TeachBackgroundArgb = 0xFFF3F6FC
    val TeachBackground = Color(TeachBackgroundArgb)

    /** teach 卡片描边（原型 #CCD9EC）。 */
    const val TeachBorderArgb = 0xFFCCD9EC
    val TeachBorder = Color(TeachBorderArgb)

    /** tile 图标容器背景（原型 #EEF4FF）。 */
    const val TileIconBackgroundArgb = 0xFFEEF4FF
    val TileIconBackground = Color(TileIconBackgroundArgb)

    /** quickChip 背景（原型 #E8EDF4）。 */
    const val QuickChipBackgroundArgb = 0xFFE8EDF4
    val QuickChipBackground = Color(QuickChipBackgroundArgb)

    //endregion
}

//endregion

//region 圆角 token（dp，与原型 radius.* / components.*.radius 一致）

object AppShapes {
    /** composer 卡片圆角（原型 24dp）。 */
    val Composer = 24.dp

    /** 技能 tile 圆角（原型 18dp）。 */
    val SkillTile = 18.dp

    /** teach 卡片圆角（原型 18dp）。 */
    val TeachCard = 18.dp

    /** tag/chip 圆角（原型 chip 7dp）。 */
    val Tag = 7.dp

    /** quickChip 圆角（原型 10dp）。 */
    val QuickChip = 10.dp

    /** tile 图标容器圆角（原型 iconBox 11dp）。 */
    val IconBox = 11.dp
}

//endregion

//region 间距 token（dp，与原型 layout.* / components.*.padding 一致）

object AppSpacing {
    /** 屏幕左右内边距（原型 screenPaddingLeft/Right 20dp）。 */
    val ScreenHorizontal = 20.dp

    /** section 标题上边距（原型 sectionHeadMarginTop 10dp）。 */
    val SectionHeadMarginTop = 10.dp

    /** 技能 tile 网格间距（原型 tileGap 10dp）。 */
    val TileGap = 10.dp

    /** 底部导航高度（原型 navHeight 72dp）。 */
    val NavHeight = 72.dp

    /** 底部导航图标尺寸（原型 navIconSize 20dp）。 */
    val NavIconSize = 20.dp

    /** 页头高度（原型 headerHeight 60dp）。 */
    val HeaderHeight = 60.dp

    /** composer 卡片内边距（原型 components.composer.padding* 16dp）。 */
    val ComposerPadding = 16.dp

    /** 技能 tile 内边距（原型 components.skillTile.padding* 12dp）。 */
    val SkillTilePadding = 12.dp

    /** teach 卡片内边距（原型 components.teachCard.padding* 16dp）。 */
    val TeachCardPadding = 16.dp

    /** 最小点击区（原型 minTouchTarget.min 44dp）。 */
    val MinTouchTarget = 44.dp
}

//endregion

//region 文字 token（sp/weight/lineHeight，与原型 typography.* 一致）

object AppTypographyTokens {
    /** 问候大标题"有什么，可以交给我？"（原型 27sp/620/37.8/-1）。 */
    val GreetingTitleFontSize = 27.sp
    val GreetingTitleLineHeight = 37.8.sp
    val GreetingTitleLetterSpacing = (-1).sp

    /** 问候小字"你好，今天想做点什么"（原型 12sp/400/18）。 */
    val GreetingHelloFontSize = 12.sp
    val GreetingHelloLineHeight = 18.sp

    /** section 标题"常用技能"/"最近活动"（原型 16sp/650/24/-0.56）。 */
    val SectionTitleFontSize = 16.sp
    val SectionTitleLineHeight = 24.sp
    val SectionTitleLetterSpacing = (-0.56).sp

    /** 技能 tile 标题（原型 13sp/700/19.5）。 */
    val TileTitleFontSize = 13.sp
    val TileTitleLineHeight = 19.5.sp

    /** 技能 tile 元信息/描述（原型 12sp/400/18）。 */
    val TileMetaFontSize = 12.sp
    val TileMetaLineHeight = 18.sp

    /** composer 输入文字（原型 16sp/400/26.4）。 */
    val ComposerInputFontSize = 16.sp
    val ComposerInputLineHeight = 26.4.sp

    /** 底部导航标签（原型 12sp/400/18，选中色 #2563EB）。 */
    val NavLabelFontSize = 12.sp
    val NavLabelLineHeight = 18.sp

    /** tag 文字（原型 12sp/400/16.8）。 */
    val TagFontSize = 12.sp
    val TagLineHeight = 16.8.sp
}

//endregion
