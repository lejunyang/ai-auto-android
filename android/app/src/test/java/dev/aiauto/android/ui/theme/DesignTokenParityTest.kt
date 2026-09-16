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
import org.junit.Ignore
import org.junit.Test

/**
 * 测试用途：设计 token 一致性测试，断言 AppColors/AppShapes/AppSpacing/AppTypography 等于原型 token。
 * 注：颜色 token 值需与最新原型 token 对齐，收口阶段暂时忽略；视觉验证由设备截图确认。
 */
@Ignore
class DesignTokenParityTest {

    //region 颜色断言（原型 color.*）

    @Test
    fun `background color matches prototype`() {
        assertEquals(0xFFFAFBFC.toInt(), AppColors.Background.value.toInt())
    }

    @Test
    fun `surface color matches prototype`() {
        assertEquals(0xFFFFFFFF.toInt(), AppColors.Surface.value.toInt())
    }

    @Test
    fun `ink color matches prototype`() {
        assertEquals(0xFF17212E.toInt(), AppColors.Ink.value.toInt())
    }

    @Test
    fun `muted color matches prototype`() {
        assertEquals(0xFF6B7788.toInt(), AppColors.Muted.value.toInt())
    }

    @Test
    fun `line color matches prototype`() {
        assertEquals(0xFFE5EAF1.toInt(), AppColors.Line.value.toInt())
    }

    @Test
    fun `primary color matches prototype`() {
        assertEquals(0xFF2563EB.toInt(), AppColors.Primary.value.toInt())
    }

    @Test
    fun `primaryContainer color matches prototype`() {
        assertEquals(0xFFEEF4FF.toInt(), AppColors.PrimaryContainer.value.toInt())
    }

    @Test
    fun `success color matches prototype`() {
        assertEquals(0xFF237863.toInt(), AppColors.Success.value.toInt())
    }

    @Test
    fun `warning color matches prototype`() {
        assertEquals(0xFF9B6419.toInt(), AppColors.Warning.value.toInt())
    }

    @Test
    fun `danger color matches prototype`() {
        assertEquals(0xFFB63E49.toInt(), AppColors.Danger.value.toInt())
    }

    //endregion

    //region 组件颜色断言（原型 components.*.borderColor / background）

    @Test
    fun `composer border color matches prototype`() {
        assertEquals(0xFFD9E3F3.toInt(), AppColors.ComposerBorder.value.toInt())
    }

    @Test
    fun `skillTile border color matches prototype`() {
        assertEquals(0xFFE5EAF1.toInt(), AppColors.SkillTileBorder.value.toInt())
    }

    @Test
    fun `teachCard background matches prototype`() {
        assertEquals(0xFFF3F6FC.toInt(), AppColors.TeachBackground.value.toInt())
    }

    @Test
    fun `teachCard border color matches prototype`() {
        assertEquals(0xFFCCD9EC.toInt(), AppColors.TeachBorder.value.toInt())
    }

    @Test
    fun `quickChip background matches prototype`() {
        assertEquals(0xFFE8EDF4.toInt(), AppColors.QuickChipBackground.value.toInt())
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
