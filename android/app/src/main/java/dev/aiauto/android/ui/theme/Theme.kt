package dev.aiauto.android.ui.theme

/**
 * 功能用途：定义 Compose Material 主题与固定配色。
 *
 * 设计决策：本 App 采用已确认的 C 端原型配色（冷白/石墨/克制蓝）作为默认，
 * 不使用 Material You 动态取色——动态取色会在 API 31+ 设备上完全偏离原型视觉，
 * 且本次不提供动态色开关。固定配色保证所有设备上视觉一致、可按原型核对。
 */

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 浅色方案：冷灰蓝背景、石墨文字、克制蓝主色，与 C 端原型视觉一致。
// 原型 CSS 变量：--bg:#eef1f5 --paper:#fafbfc --surface:#fff --ink:#17212e
// --muted:#6b7788 --line:#e5eaf1 --blue:#2563eb --pale:#eef4ff
private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF4FF),
    onPrimaryContainer = Color(0xFF0A1E3D),
    secondary = Color(0xFF556070),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E0EA),
    onSecondaryContainer = Color(0xFF141C28),
    tertiary = Color(0xFF6B5878),
    background = AppColors.Background,
    onBackground = AppColors.Ink,
    // 原型卡片是纯白 #FFFFFF（--surface），不是 #FAFBFC（那是底栏背景）。
    surface = AppColors.Surface,
    onSurface = AppColors.Ink,
    surfaceVariant = AppColors.Line,
    onSurfaceVariant = AppColors.Muted,
    surfaceContainerLow = Color(0xFFF2F4F8),
    surfaceContainer = Color(0xFFE8ECF2),
    surfaceContainerHigh = Color(0xFFDCE1EA),
    outline = AppColors.Line,
    outlineVariant = Color(0xFFD5DBE4),
)

// 深色方案：深石墨背景、冷蓝主色，与浅色方案对应。
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C4F5),
    onPrimary = Color(0xFF0A2E5C),
    primaryContainer = Color(0xFF1A3F75),
    onPrimaryContainer = Color(0xFFD6E3FA),
    secondary = Color(0xFFBDC5D0),
    onSecondary = Color(0xFF242B36),
    secondaryContainer = Color(0xFF3A4250),
    onSecondaryContainer = Color(0xFFD9E0EA),
    tertiary = Color(0xFFD4BDE0),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE3E5E9),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE3E5E9),
    surfaceVariant = Color(0xFF33373F),
    onSurfaceVariant = Color(0xFFB3B9C2),
    surfaceContainerLow = Color(0xFF1B1E23),
    surfaceContainer = Color(0xFF23262C),
    outline = Color(0xFF8D939D),
)

@Composable
fun AiAutoAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 始终使用固定配色，不接入系统动态取色，确保视觉与已确认原型一致。
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
