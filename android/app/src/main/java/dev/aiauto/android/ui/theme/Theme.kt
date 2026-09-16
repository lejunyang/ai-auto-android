package dev.aiauto.android.ui.theme

/**
 * 功能用途：定义 Compose Material 主题、动态配色与文字样式。
 */

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 固定回退配色采用冷白背景、石墨文字与克制蓝主色，与 C 端原型视觉一致；
// API 31 及以上仍优先使用系统动态取色，此处仅作为低版本与关闭动态取色时的兜底。
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5AA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FA),
    onPrimaryContainer = Color(0xFF0A1E3D),
    secondary = Color(0xFF556070),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E0EA),
    onSecondaryContainer = Color(0xFF141C28),
    tertiary = Color(0xFF6B5878),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1A1D22),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF1A1D22),
    surfaceVariant = Color(0xFFE4E7EC),
    onSurfaceVariant = Color(0xFF474C56),
    surfaceContainerLow = Color(0xFFEFF1F4),
    surfaceContainer = Color(0xFFE8EBEF),
    outline = Color(0xFF777E8A),
)

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
    // 动态取色（Material You）自 API 31 起提供，而本模块 minSdk 为 30，
    // 因此必须做版本分支。把 API 31 调用抽到带 @RequiresApi 注解的独立函数里，
    // 而不是内联在 when/if 条件中：AGP 9.1.1 的 lint 流分析穿不过内联的
    // SDK_INT 守卫（无论写成复合条件还是嵌套 if 都仍报 NewApi），
    // 而注解声明是 lint 官方支持的表达方式，语义也更明确。
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicColorScheme(darkTheme)
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

/**
 * 功能用途：读取系统动态取色方案，仅在 API 31 及以上调用。
 *
 * 调用方必须自行完成版本检查；注解让 lint 与阅读者都能看到该前提。
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun dynamicColorScheme(darkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
}
