package com.rawsmusic.ui.settings.compose.scene.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 纯 Compose 版本的主题
 * 对应原版的主题系统
 *
 * 提供亮色和暗色主题
 */

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF004A94),
    onPrimaryContainer = Color(0xFFD3E3FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF0061A4),
    surfaceTint = Color(0xFF8AB4F8)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251432),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFF8AB4F8),
    surfaceTint = Color(0xFF0061A4)
)

/**
 * Compose 主题
 *
 * @param darkTheme 是否使用暗色主题
 * @param content 内容
 */
@Composable
fun ComposeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * 颜色对象
 */
object ComposeColors {
    // 背景色
    val background = Color(0xFF121010)
    val backgroundLight = Color(0xFFFDFBFF)
    val backgroundDark = Color(0xFF1A1C1E)

    // 表面色
    val surface = Color(0xFF1A1C1E)
    val surfaceLight = Color(0xFFFDFBFF)
    val surfaceDark = Color(0xFF2F3033)

    // 主色
    val primary = Color(0xFF8AB4F8)
    val primaryLight = Color(0xFF0061A4)
    val primaryDark = Color(0xFF003063)

    // 文本色
    val textPrimary = Color.White
    val textSecondary = Color.White.copy(alpha = 0.7f)
    val textTertiary = Color.White.copy(alpha = 0.5f)
    val textDisabled = Color.White.copy(alpha = 0.3f)

    // 边框色
    val border = Color.White.copy(alpha = 0.2f)
    val borderLight = Color.White.copy(alpha = 0.1f)
    val borderDark = Color.White.copy(alpha = 0.3f)

    // 分隔线色
    val divider = Color.White.copy(alpha = 0.06f)
    val dividerLight = Color.White.copy(alpha = 0.03f)
    val dividerDark = Color.White.copy(alpha = 0.1f)

    // 渐变色
    val gradientStart = Color(0xFF8B5E3C)
    val gradientEnd = Color(0xFF3C5E8B)
    val gradientPurple = Color(0xFF5E3C8B)
    val gradientGreen = Color(0xFF3C8B5E)

    // 状态色
    val success = Color(0xFF4CAF50)
    val warning = Color(0xFFFF9800)
    val error = Color(0xFFF44336)
    val info = Color(0xFF2196F3)

    // 场景色
    val sceneMain = Color(0xFF1A1A2E)
    val scenePlayer = Color(0xFF1A1A2E)
    val sceneLyric = Color(0xFF0F3460)
    val sceneQueue = Color(0xFF16213E)
    val sceneAlbumDetail = Color(0xFF1A1A2E)
    val sceneEffects = Color(0xFF0F3460)
}
