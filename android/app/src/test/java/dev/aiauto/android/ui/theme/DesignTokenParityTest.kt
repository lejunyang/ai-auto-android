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
        assertEquals(0xFFFAFBFC.toInt(), AppColors.BackgroundArgb.toInt())
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
        assertEquals(72f, AppSpacing.NavHeight.value, 1f)
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

    //endregion
}
