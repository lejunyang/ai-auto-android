package dev.aiauto.android.ui.theme

/**
 * 功能用途：设计 token 一致性测试。
 * 断言 AppColors / AppShapes / AppSpacing / AppTypography 常量等于
 * artifacts/ui-validation/main-agent/prototype-tokens.json 的实测值。
 * 漂移由 make android-verify 拦住，不靠看图。
 *
 * 数据来源：prototype-tokens.json（411x914 视口，CSS px == dp）。
 * 容差：颜色完全一致；圆角/间距 ±1dp；字号 ±0.5sp。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试用途：设计 token 一致性测试，断言 AppColors/AppShapes/AppSpacing/AppTypography 等于原型 token。
 * 数据来源：artifacts/ui-validation/main-agent/prototype-tokens.json（411x914 视口，CSS px == dp）。
 */
class DesignTokenParityTest {

    //region 颜色断言（原型 color.*，比较 Argb 常量避免 Compose Color 单元测试依赖）

    @Test
    fun `background color matches prototype`() {
        // 原型 --bg 实测 #EEF1F5（冷灰蓝），不是 #FAFBFC。
        assertEquals(0xFFEEF1F5.toInt(), AppColors.BackgroundArgb.toInt())
    }

    @Test
    fun `surface color matches prototype`() {
        assertEquals(0xFFFFFFFF.toInt(), AppColors.SurfaceArgb.toInt())
    }

    @Test
    fun `ink color matches prototype`() {
        assertEquals(0xFF17212E.toInt(), AppColors.InkArgb.toInt())
    }

    @Test
    fun `muted color matches prototype`() {
        assertEquals(0xFF6B7788.toInt(), AppColors.MutedArgb.toInt())
    }

    @Test
    fun `line color matches prototype`() {
        assertEquals(0xFFE5EAF1.toInt(), AppColors.LineArgb.toInt())
    }

    @Test
    fun `primary color matches prototype`() {
        assertEquals(0xFF2563EB.toInt(), AppColors.PrimaryArgb.toInt())
    }

    @Test
    fun `primaryContainer color matches prototype`() {
        assertEquals(0xFFEEF4FF.toInt(), AppColors.PrimaryContainerArgb.toInt())
    }

    @Test
    fun `success color matches prototype`() {
        assertEquals(0xFF237863.toInt(), AppColors.SuccessArgb.toInt())
    }

    @Test
    fun `warning color matches prototype`() {
        assertEquals(0xFF9B6419.toInt(), AppColors.WarningArgb.toInt())
    }

    @Test
    fun `danger color matches prototype`() {
        assertEquals(0xFFB63E49.toInt(), AppColors.DangerArgb.toInt())
    }

    //endregion

    //region 组件颜色断言（原型 components.*.borderColor / background）

    @Test
    fun `composer border color matches prototype`() {
        assertEquals(0xFFD9E3F3.toInt(), AppColors.ComposerBorderArgb.toInt())
    }

    @Test
    fun `skillTile border color matches prototype`() {
        assertEquals(0xFFE5EAF1.toInt(), AppColors.SkillTileBorderArgb.toInt())
    }

    @Test
    fun `teachCard background matches prototype`() {
        assertEquals(0xFFF3F6FC.toInt(), AppColors.TeachBackgroundArgb.toInt())
    }

    @Test
    fun `teachCard border color matches prototype`() {
        assertEquals(0xFFCCD9EC.toInt(), AppColors.TeachBorderArgb.toInt())
    }

    @Test
    fun `quickChip background matches prototype`() {
        assertEquals(0xFFE8EDF4.toInt(), AppColors.QuickChipBackgroundArgb.toInt())
    }

    @Test
    fun `quickChip text color matches prototype`() {
        assertEquals(0xFF5D6C82.toInt(), AppColors.QuickChipTextArgb.toInt())
    }

    @Test
    fun `tileIcon mint background matches prototype`() {
        assertEquals(0xFFECF5F2.toInt(), AppColors.TileIconMintBackgroundArgb.toInt())
    }

    @Test
    fun `composerPill background matches prototype`() {
        assertEquals(0xFFF5F7FA.toInt(), AppColors.ComposerPillBackgroundArgb.toInt())
    }

    @Test
    fun `composerPill text color matches prototype`() {
        assertEquals(0xFF6D7889.toInt(), AppColors.ComposerPillTextArgb.toInt())
    }

    @Test
    fun `tinyOrbit border matches prototype`() {
        assertEquals(0xFFC9D8F6.toInt(), AppColors.TinyOrbitBorderArgb.toInt())
    }

    @Test
    fun `nav inactive color matches prototype`() {
        assertEquals(0xFF7A8595.toInt(), AppColors.NavInactiveArgb.toInt())
    }

    @Test
    fun `notice text color matches prototype`() {
        assertEquals(0xFF587198.toInt(), AppColors.NoticeTextArgb.toInt())
    }

    //endregion

    //region 圆角断言（原型 radius.* / components.*.radius）

    @Test
    fun `composer radius matches prototype`() {
        assertEquals(24f, AppShapes.Composer.value, 1f)
    }

    @Test
    fun `skillTile radius matches prototype`() {
        assertEquals(18f, AppShapes.SkillTile.value, 1f)
    }

    @Test
    fun `teachCard radius matches prototype`() {
        assertEquals(18f, AppShapes.TeachCard.value, 1f)
    }

    @Test
    fun `tag radius matches prototype`() {
        assertEquals(7f, AppShapes.Tag.value, 1f)
    }

    @Test
    fun `quickChip radius matches prototype`() {
        assertEquals(10f, AppShapes.QuickChip.value, 1f)
    }

    @Test
    fun `iconBox radius matches prototype`() {
        assertEquals(11f, AppShapes.IconBox.value, 1f)
    }

    @Test
    fun `card radius matches prototype`() {
        // 原型 --radius 22dp（通用卡片）。
        assertEquals(22f, AppShapes.Card.value, 1f)
    }

    @Test
    fun `composerPill radius matches prototype`() {
        assertEquals(9f, AppShapes.ComposerPill.value, 1f)
    }

    @Test
    fun `iconButton radius matches prototype`() {
        assertEquals(14f, AppShapes.IconButton.value, 1f)
    }

    @Test
    fun `settingsLogo radius matches prototype`() {
        assertEquals(18f, AppShapes.SettingsLogo.value, 1f)
    }

    //endregion

    //region 间距断言（原型 layout.* / components.*.padding）

    @Test
    fun `screen horizontal padding matches prototype`() {
        assertEquals(20f, AppSpacing.ScreenHorizontal.value, 1f)
    }

    @Test
    fun `tile gap matches prototype`() {
        assertEquals(10f, AppSpacing.TileGap.value, 1f)
    }

    @Test
    fun `nav height matches prototype`() {
        // 原型手机模式媒体查询(max-width:680px)未覆盖.bottom-nav的height，沿用桌面68px；
        // 总高=68dp内容区+系统手势条inset(navigationBarsPadding)。
        assertEquals(68f, AppSpacing.NavHeight.value, 1f)
    }

    @Test
    fun `nav icon size matches prototype`() {
        assertEquals(20f, AppSpacing.NavIconSize.value, 1f)
    }

    @Test
    fun `header height matches prototype`() {
        assertEquals(60f, AppSpacing.HeaderHeight.value, 1f)
    }

    @Test
    fun `composer padding matches prototype`() {
        assertEquals(16f, AppSpacing.ComposerPadding.value, 1f)
    }

    @Test
    fun `skillTile padding matches prototype`() {
        assertEquals(12f, AppSpacing.SkillTilePadding.value, 1f)
    }

    @Test
    fun `min touch target matches prototype`() {
        assertEquals(44f, AppSpacing.MinTouchTarget.value, 1f)
    }

    @Test
    fun `composer height matches prototype`() {
        assertEquals(139f, AppSpacing.ComposerHeight.value, 1f)
    }

    @Test
    fun `skillTile height matches prototype`() {
        assertEquals(112.5f, AppSpacing.SkillTileHeight.value, 1f)
    }

    @Test
    fun `tileIcon size matches prototype`() {
        assertEquals(35f, AppSpacing.TileIconSize.value, 1f)
    }

    @Test
    fun `teachCard height matches prototype`() {
        assertEquals(73.5f, AppSpacing.TeachCardHeight.value, 1f)
    }

    @Test
    fun `navItem height matches prototype`() {
        assertEquals(60f, AppSpacing.NavItemHeight.value, 1f)
    }

    @Test
    fun `settingsLogo size matches prototype`() {
        assertEquals(52f, AppSpacing.SettingsLogoSize.value, 1f)
    }

    @Test
    fun `settingRow height matches prototype`() {
        assertEquals(65f, AppSpacing.SettingRowHeight.value, 1f)
    }

    @Test
    fun `primaryButton height matches prototype`() {
        assertEquals(46f, AppSpacing.PrimaryButtonHeight.value, 1f)
    }

    @Test
    fun `iconButton size meets min touch target`() {
        assertEquals(44f, AppSpacing.IconButtonSize.value, 1f)
    }

    //endregion

    //region 字号断言（原型 typography.*.fontSize）

    @Test
    fun `greetingTitle font size matches prototype`() {
        assertEquals(27f, AppTypographyTokens.GreetingTitleFontSize.value, 0.5f)
    }

    @Test
    fun `greetingHello font size matches prototype`() {
        assertEquals(12f, AppTypographyTokens.GreetingHelloFontSize.value, 0.5f)
    }

    @Test
    fun `sectionTitle font size matches prototype`() {
        assertEquals(16f, AppTypographyTokens.SectionTitleFontSize.value, 0.5f)
    }

    @Test
    fun `tileTitle font size matches prototype`() {
        assertEquals(13f, AppTypographyTokens.TileTitleFontSize.value, 0.5f)
    }

    @Test
    fun `tileMeta font size matches prototype`() {
        assertEquals(12f, AppTypographyTokens.TileMetaFontSize.value, 0.5f)
    }

    @Test
    fun `composerInput font size matches prototype`() {
        assertEquals(16f, AppTypographyTokens.ComposerInputFontSize.value, 0.5f)
    }

    @Test
    fun `navLabel font size matches prototype`() {
        assertEquals(12f, AppTypographyTokens.NavLabelFontSize.value, 0.5f)
    }

    @Test
    fun `tag font size matches prototype`() {
        assertEquals(12f, AppTypographyTokens.TagFontSize.value, 0.5f)
    }

    @Test
    fun `pageHeading font size matches prototype`() {
        assertEquals(24f, AppTypographyTokens.PageHeadingFontSize.value, 0.5f)
    }

    @Test
    fun `pageDesc font size matches prototype`() {
        assertEquals(14f, AppTypographyTokens.PageDescFontSize.value, 0.5f)
    }

    @Test
    fun `note font size matches prototype`() {
        assertEquals(14f, AppTypographyTokens.NoteFontSize.value, 0.5f)
    }

    @Test
    fun `primaryButton font size matches prototype`() {
        assertEquals(13f, AppTypographyTokens.PrimaryButtonFontSize.value, 0.5f)
    }

    @Test
    fun `quickChip font size matches prototype`() {
        assertEquals(11f, AppTypographyTokens.QuickChipFontSize.value, 0.5f)
    }

    @Test
    fun `composerPill font size matches prototype`() {
        assertEquals(11f, AppTypographyTokens.ComposerPillFontSize.value, 0.5f)
    }

    @Test
    fun `sectionTitle letterSpacing matches prototype`() {
        assertEquals(-0.6f, AppTypographyTokens.SectionTitleLetterSpacing.value, 0.1f)
    }

    @Test
    fun `pageHeading letterSpacing matches prototype`() {
        assertEquals(-0.8f, AppTypographyTokens.PageHeadingLetterSpacing.value, 0.1f)
    }

    //endregion
}
