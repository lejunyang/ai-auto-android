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
    /**
     * 页面背景（原型 --bg #EEF1F5 冷灰蓝）。
     * 注意：这是原型的真实值，早期误用 #FAFBFC（surfaceMuted）导致背景偏白。
     */
    const val BackgroundArgb = 0xFFEEF1F5
    val Background = Color(BackgroundArgb)

    /** 卡片表面（原型 --surface #FFFFFF）。 */
    const val SurfaceArgb = 0xFFFFFFFF
    val Surface = Color(SurfaceArgb)

    /** 次级表面/底部导航背景（原型底栏实测 #FAFBFC）。 */
    const val SurfaceMutedArgb = 0xFFFAFBFC
    val SurfaceMuted = Color(SurfaceMutedArgb)

    /** 主文字（原型 --ink #17212E）。 */
    const val InkArgb = 0xFF17212E
    val Ink = Color(InkArgb)

    /** 次要文字（原型 --muted #6B7788）。 */
    const val MutedArgb = 0xFF6B7788
    val Muted = Color(MutedArgb)

    /** 描边线（原型 --line #E5EAF1）。 */
    const val LineArgb = 0xFFE5EAF1
    val Line = Color(LineArgb)

    /** 主色/克制蓝（原型 --blue #2563EB）。 */
    const val PrimaryArgb = 0xFF2563EB
    val Primary = Color(PrimaryArgb)

    /** 主色容器（原型 tag/tileIcon 背景 #EEF4FF）。 */
    const val PrimaryContainerArgb = 0xFFEEF4FF
    val PrimaryContainer = Color(PrimaryContainerArgb)

    /** 成功色（原型 --green #237863）。 */
    const val SuccessArgb = 0xFF237863
    val Success = Color(SuccessArgb)

    /** 成功标签底色（原型 .tag.green background #EAF6F0）。 */
    const val SuccessContainerArgb = 0xFFEAF6F0
    val SuccessContainer = Color(SuccessContainerArgb)

    /** 警告色（原型 --amber #9B6419）。 */
    const val WarningArgb = 0xFF9B6419
    val Warning = Color(WarningArgb)

    /** 警告标签底色（原型 .tag.amber background #FFF3DF）。 */
    const val WarningTagContainerArgb = 0xFFFFF3DF
    val WarningTagContainer = Color(WarningTagContainerArgb)

    /** 危险/错误色（原型 --red #B63E49）。 */
    const val DangerArgb = 0xFFB63E49
    val Danger = Color(DangerArgb)

    //region 组件级颜色（原型逐元素实测值）

    /** composer 卡片描边（原型实测 #D9E3F3）。 */
    const val ComposerBorderArgb = 0xFFD9E3F3
    val ComposerBorder = Color(ComposerBorderArgb)

    /** 技能 tile 描边（原型实测 #E5EAF1）。 */
    const val SkillTileBorderArgb = 0xFFE5EAF1
    val SkillTileBorder = Color(SkillTileBorderArgb)

    /** teach 卡片背景（原型实测 #F3F6FC）。 */
    const val TeachBackgroundArgb = 0xFFF3F6FC
    val TeachBackground = Color(TeachBackgroundArgb)

    /** teach 卡片描边（原型实测 #CCD9EC，虚线）。 */
    const val TeachBorderArgb = 0xFFCCD9EC
    val TeachBorder = Color(TeachBorderArgb)

    /** tile 图标容器背景（原型实测 #EEF4FF）。 */
    const val TileIconBackgroundArgb = 0xFFEEF4FF
    val TileIconBackground = Color(TileIconBackgroundArgb)

    /** tile 图标容器背景-薄荷绿变体（原型实测 #ECF5F2）。 */
    const val TileIconMintBackgroundArgb = 0xFFECF5F2
    val TileIconMintBackground = Color(TileIconMintBackgroundArgb)

    /** quickChip 背景（原型实测 #E8EDF4）。 */
    const val QuickChipBackgroundArgb = 0xFFE8EDF4
    val QuickChipBackground = Color(QuickChipBackgroundArgb)

    /** quickChip 文字（原型实测 #5D6C82）。 */
    const val QuickChipTextArgb = 0xFF5D6C82
    val QuickChipText = Color(QuickChipTextArgb)

    /** filter-tab 未选中背景（原型实测 #EAF0F7）。 */
    const val FilterTabBackgroundArgb = 0xFFEAF0F7
    val FilterTabBackground = Color(FilterTabBackgroundArgb)

    /** filter-tab 未选中文字（原型实测 #6C7B90）。 */
    const val FilterTabTextArgb = 0xFF6C7B90
    val FilterTabText = Color(FilterTabTextArgb)

    /** composer 内"选择应用"pill 背景（原型实测 #F5F7FA）。 */
    const val ComposerPillBackgroundArgb = 0xFFF5F7FA
    val ComposerPillBackground = Color(ComposerPillBackgroundArgb)

    /** composer 内 pill 文字（原型实测 #6D7889）。 */
    const val ComposerPillTextArgb = 0xFF6D7889
    val ComposerPillText = Color(ComposerPillTextArgb)

    /** composer 输入框 placeholder 文字（原型实测 #909CAB）。 */
    const val ComposerPlaceholderArgb = 0xFF909CAB
    val ComposerPlaceholder = Color(ComposerPlaceholderArgb)

    /** tinyOrbit（首页右侧 auto 图标容器）描边（原型实测 #C9D8F6）。 */
    const val TinyOrbitBorderArgb = 0xFFC9D8F6
    val TinyOrbitBorder = Color(TinyOrbitBorderArgb)

    /** 底部导航未选中文字（原型实测 #7A8595）。 */
    const val NavInactiveArgb = 0xFF7A8595
    val NavInactive = Color(NavInactiveArgb)

    /** 底部导航选中态胶囊背景（原型实测 #E8EFFD）。 */
    const val NavActiveBackgroundArgb = 0xFFE8EFFD
    val NavActiveBackground = Color(NavActiveBackgroundArgb)

    /** notice 文字（原型实测 #587198）。 */
    const val NoticeTextArgb = 0xFF587198
    val NoticeText = Color(NoticeTextArgb)

    /** notice 背景（原型实测 #EEF4FF，与 primaryContainer 同值）。 */
    const val NoticeBackgroundArgb = 0xFFEEF4FF
    val NoticeBackground = Color(NoticeBackgroundArgb)

    /** amber notice 背景（原型 .notice.amber 实测 #FFF4E3），用于高影响披露。 */
    const val WarningContainerArgb = 0xFFFFF4E3
    val WarningContainer = Color(WarningContainerArgb)

    /** amber notice 文字（原型实测 #956725）。 */
    const val WarningNoticeTextArgb = 0xFF956725
    val WarningNoticeText = Color(WarningNoticeTextArgb)

    //endregion
}

//endregion

//region 圆角 token（dp，与原型 radius.* / components.*.radius 一致）

object AppShapes {
    /** composer 卡片圆角（原型实测 24dp）。 */
    val Composer = 24.dp

    /** 技能 tile 圆角（原型实测 18dp）。 */
    val SkillTile = 18.dp

    /** teach 卡片圆角（原型实测 18dp）。 */
    val TeachCard = 18.dp

    /** tag/chip 圆角（原型实测 7dp）。 */
    val Tag = 7.dp

    /** quickChip 圆角（原型实测 10dp）。 */
    val QuickChip = 10.dp

    /** tile 图标容器圆角（原型实测 11dp）。 */
    val IconBox = 11.dp

    /** 通用卡片圆角（原型 --radius 22dp）。 */
    val Card = 22.dp

    /** composer 内 pill 圆角（原型实测 9dp）。 */
    val ComposerPill = 9.dp

    /** 圆形图标按钮圆角（原型实测 14dp）。 */
    val IconButton = 14.dp

    /** 我的页 logo 圆角（原型实测 12dp；settings 页覆写为 18dp）。 */
    val Logo = 12.dp

    /** 我的页 logo 圆角（settings 视图实测 18dp，52x52 尺寸）。 */
    val SettingsLogo = 18.dp

    /** tinyOrbit（首页右侧 auto 容器）圆角（原型实测 14dp）。 */
    val TinyOrbit = 14.dp
}

//endregion

//region 间距 token（dp，与原型 layout.* / components.*.padding 一致）

object AppSpacing {
    /** 屏幕左右内边距（原型实测 20dp）。 */
    val ScreenHorizontal = 20.dp

    /** section 标题上边距（原型实测 10dp）。 */
    val SectionHeadMarginTop = 10.dp

    /** 技能 tile 网格间距（原型实测 10dp，2 列各 180.5dp）。 */
    val TileGap = 10.dp

    /** 底部导航高度（原型实测 72dp）。 */
    val NavHeight = 68.dp

    /** 底部导航图标尺寸（原型实测 20dp）。 */
    val NavIconSize = 20.dp

    /** 底部导航项高度（原型实测 60dp）。 */
    val NavItemHeight = 60.dp

    /** 页头高度（原型实测 60dp）。 */
    val HeaderHeight = 60.dp

    /** composer 卡片内边距（原型实测 16dp）。 */
    val ComposerPadding = 16.dp

    /** composer 高度（原型实测 139dp）。 */
    val ComposerHeight = 139.dp

    /** composer 底部操作行高度（原型实测 44dp）。 */
    val ComposerFootHeight = 44.dp

    /** 技能 tile 内边距（原型实测 12dp）。 */
    val SkillTilePadding = 12.dp

    /** 技能 tile 高度（原型实测 112.5dp）。 */
    val SkillTileHeight = 112.5.dp

    /** tile 图标尺寸（原型实测 35dp）。 */
    val TileIconSize = 35.dp

    /** teach 卡片内边距（原型实测 16dp）。 */
    val TeachCardPadding = 16.dp

    /** teach 卡片高度（原型实测 73.5dp）。 */
    val TeachCardHeight = 73.5.dp

    /** quickChip 高度（原型实测 44dp）。 */
    val QuickChipHeight = 44.dp

    /** 图标按钮尺寸（原型实测 44dp，满足最小点击区）。 */
    val IconButtonSize = 44.dp

    /** tinyOrbit 尺寸（原型 .tiny-orbit CSS width/height 42px）。 */
    val TinyOrbitSize = 42.dp

    /** 我的页 logo 尺寸（settings 视图实测 52dp）。 */
    val SettingsLogoSize = 52.dp

    /** 设置行高度（原型实测 65dp）。 */
    val SettingRowHeight = 65.dp

    /** Switch 点击区（原型实测 44x44dp）。 */
    val SwitchTouchSize = 44.dp

    /** 主按钮高度（原型实测 46dp）。 */
    val PrimaryButtonHeight = 46.dp

    /** 最小点击区（原型实测 minHeight=44dp，无低于此值的元素）。 */
    val MinTouchTarget = 44.dp
}

//endregion

//region 文字 token（sp/weight/lineHeight，与原型 typography.* 一致）

object AppTypographyTokens {
    /** 问候大标题"有什么，可以交给我？"（原型实测 27sp/620/37.8/-1）。 */
    val GreetingTitleFontSize = 27.sp
    val GreetingTitleLineHeight = 37.8.sp
    val GreetingTitleLetterSpacing = (-1).sp

    /** 问候小字"你好，今天想做点什么"（原型实测 12sp/400/18）。 */
    val GreetingHelloFontSize = 12.sp
    val GreetingHelloLineHeight = 18.sp

    /** section 标题"常用技能"/"最近活动"（原型实测 16sp/650/24/-0.6）。 */
    val SectionTitleFontSize = 16.sp
    val SectionTitleLineHeight = 24.sp
    val SectionTitleLetterSpacing = (-0.6).sp

    /** 技能 tile 标题（原型实测 13sp/700/19.5）。 */
    val TileTitleFontSize = 13.sp
    val TileTitleLineHeight = 19.5.sp

    /** 技能 tile 元信息/描述（原型实测 12sp/400/18）。 */
    val TileMetaFontSize = 12.sp
    val TileMetaLineHeight = 18.sp

    /** composer 输入文字（原型实测 16sp/400/26.4）。 */
    val ComposerInputFontSize = 16.sp
    val ComposerInputLineHeight = 26.4.sp

    /** composer 内 pill 文字（原型实测 11sp/400/16.5）。 */
    val ComposerPillFontSize = 11.sp
    val ComposerPillLineHeight = 16.5.sp

    /** quickChip 文字（原型实测 11sp/400/16.5）。 */
    val QuickChipFontSize = 11.sp
    val QuickChipLineHeight = 16.5.sp

    /** 底部导航标签（原型实测 12sp/400/18）。 */
    val NavLabelFontSize = 12.sp
    val NavLabelLineHeight = 18.sp

    /** tag 文字（原型实测 12sp/400/16.8）。 */
    val TagFontSize = 12.sp
    val TagLineHeight = 16.8.sp

    /** 页头标题（原型实测 appHeader 内 14sp 基准；标题元素随视图变化）。 */
    val HeaderBaseFontSize = 14.sp
    val HeaderBaseLineHeight = 21.sp

    /** 二级页大标题 page-heading（原型实测 24sp/700/36/-0.8）。 */
    val PageHeadingFontSize = 24.sp
    val PageHeadingLineHeight = 36.sp
    val PageHeadingLetterSpacing = (-0.8).sp

    /** 二级页描述 page-desc（原型实测 14sp/400/25.2）。 */
    val PageDescFontSize = 14.sp
    val PageDescLineHeight = 25.2.sp

    /** note 小字说明（原型实测 14sp/400/25.2）。 */
    val NoteFontSize = 14.sp
    val NoteLineHeight = 25.2.sp

    /** 主按钮文字（原型实测 13sp/600/19.5）。 */
    val PrimaryButtonFontSize = 13.sp
    val PrimaryButtonLineHeight = 19.5.sp

    /** 文字按钮 text-btn（原型实测 12sp/400/18）。 */
    val TextBtnFontSize = 12.sp
    val TextBtnLineHeight = 18.sp
}

//endregion
