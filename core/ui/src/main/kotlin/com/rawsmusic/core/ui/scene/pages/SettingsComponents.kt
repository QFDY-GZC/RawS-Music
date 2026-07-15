package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.RawMonet
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页面共享组件。使用 miuix 标准标题栏、分组标题与偏好项。
 */

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

    val cs = MiuixTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    val surface = cs.background.blendForSettings(cs.primary, if (isDark) 0.10f else 0.045f)

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

@Composable
fun appFontFamily(): FontFamily {
    val tf = com.rawsmusic.module.data.prefs.FontManager.typeface
    return if (tf != null) FontFamily(tf) else FontFamily.Default
}

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
    ) {
        SmallTopAppBar(
            title = title,
            color = colors.background,
            titleColor = colors.onSurface,
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = colors.onSurface
                        )
                    }
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
            content = {
                Spacer(Modifier.height(8.dp))
                content()
                Spacer(Modifier.height(180.dp))
            }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
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
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
fun SettingsNavigationEntry(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = description,
        onClick = onClick
    )
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
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
