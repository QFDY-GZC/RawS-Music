package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode
import com.rawsmusic.core.ui.theme.RawMonet
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.ThemeManager
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页面共享组件。
 * 从 app 模块 LiquidGlassSettings.kt 提取到 core:ui，供所有 Compose 页面使用。
 */

// ============================================================================
// 主题颜色
// ============================================================================

data class ThemeColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryText: Color
)

@Composable
fun themeColors(): ThemeColors {
    val colorThemeMode = getCurrentColorThemeMode()

    // Monet 模式直接读 RawMonet tokens
    if (colorThemeMode != ColorThemeMode.MIUIX) {
        val monet = RawMonet
        return ThemeColors(
            background = monet.background,
            surface = monet.card,
            onSurface = monet.textPrimary,
            onSurfaceVariant = monet.textSecondary,
            outline = monet.outline,
            primary = monet.accent,
            onPrimary = monet.accentOn,
            primaryContainer = monet.accentContainer,
            onPrimaryContainer = monet.accentContainerOn,
            secondaryText = monet.textSecondary
        )
    }

    // MIUIx 模式读 MiuixTheme.colorScheme
    val cs = MiuixTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    val surface = cs.background.blendForSettings(cs.primary, if (isDark) 0.12f else 0.055f)

    return ThemeColors(
        background = cs.background,
        surface = surface,
        onSurface = cs.onBackground,
        onSurfaceVariant = cs.onSurfaceVariantSummary,
        outline = cs.onSurfaceVariantSummary.copy(alpha = if (isDark) 0.42f else 0.34f),
        primary = cs.primary,
        onPrimary = if (cs.primary.luminance() > 0.5f) Color.Black else Color.White,
        primaryContainer = cs.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        onPrimaryContainer = cs.onBackground,
        secondaryText = cs.onSurfaceVariantSummary
    )
}

private fun Color.blendForSettings(target: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    val inv = 1f - f
    return Color(
        red = red * inv + target.red * f,
        green = green * inv + target.green * f,
        blue = blue * inv + target.blue * f,
        alpha = alpha * inv + target.alpha * f
    )
}

// ============================================================================
// 字体
// ============================================================================

@Composable
fun appFontFamily(): FontFamily {
    val tf = com.rawsmusic.module.data.prefs.FontManager.typeface
    return if (tf != null) FontFamily(tf) else FontFamily.Default
}

// ============================================================================
// 页面脚手架
// ============================================================================

@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        if (onBack == null) {
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
                fontFamily = appFontFamily()
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("返回", color = colors.primary, fontSize = 14.sp, fontFamily = appFontFamily())
                }
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    fontFamily = appFontFamily()
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(22.dp))
        content()
        Spacer(Modifier.height(180.dp))
    }
}

// ============================================================================
// 通用组件
// ============================================================================

@Composable
fun SectionHeader(title: String) {
    val colors = themeColors()
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colors.secondaryText,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
        fontFamily = appFontFamily()
    )
}

@Composable
fun Divider() {
    val colors = themeColors()
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.outline.copy(alpha = 0.15f))
            .padding(vertical = 8.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SettingsNavigationEntry(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                fontFamily = appFontFamily()
            )
            Text(
                description,
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
        }
    }
}

@Composable
fun SettingsInfoEntry(
    title: String,
    description: String
) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = colors.onSurface,
            fontFamily = appFontFamily()
        )
        Text(
            description,
            fontSize = 11.sp,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 4.dp),
            fontFamily = appFontFamily()
        )
    }
}
