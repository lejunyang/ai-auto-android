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

private val LightColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A42),
    secondary = Color(0xFF545F71),
    tertiary = Color(0xFF6D5677),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE1E2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF16458F),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBBC7DB),
    tertiary = Color(0xFFD8BDE3),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474F),
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
