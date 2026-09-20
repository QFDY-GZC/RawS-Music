package com.rawsmusic.core.ui.theme

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import com.rawsmusic.core.common.prefs.UIPreferences
import com.rawsmusic.core.ui.scene.pages.PageColors
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.File
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * 主题运行时刷新状态。
 *
 * MMKV / SharedPreferences 本身不是 Compose State。
 * 修改配色模式后必须让 RootContent 重新组合，否则 RawSMusicTheme 不会重新读取 UIPreferences。
 */
object RawThemeRuntimeState {
    var version by mutableIntStateOf(0)
        private set

    fun invalidate() {
        version++
    }
}

/**
 * RawSMusic 全局主题包装器。
 *
 * MIUIX:
 * - 保持原 MiuixTheme 行为
 *
 * MONET_AUTO / MONET_LIGHT / MONET_DARK:
 * - 外层 RawMonetTheme 提供 MaterialTheme.colorScheme + LocalRawMonet
 * - 内层 MiuixTheme 的 keyColor 使用 Monet accent
 * - 这样旧页面继续使用 MiuixTheme.colorScheme 时，也能跟随 Monet 主色变化
 */
@Composable
fun RawSMusicTheme(
    key: Int = 0,
    themeMode: ThemeManager.ThemeMode = ThemeManager.getCurrentTheme(),
    accentColor: Color = Color(ColorScheme.getCurrentAccentColor().color),
    content: @Composable () -> Unit
) {
    val runtimeVersion = RawThemeRuntimeState.version
    val globalFontFamily = remember(runtimeVersion) {
        AppPreferences.UI.customFontPath
            .takeIf { it.isNotBlank() }
            ?.let { path ->
                runCatching {
                    // Register the selected file as its source face and let Compose resolve the
                    // requested TextStyle weight. Wrapping a preloaded Android Typeface here
                    // freezes many text paths to that one face and makes the global weight offset
                    // appear to do nothing.
                    FontFamily(Font(File(path), FontWeight.Normal))
                }.getOrNull()
            }
            ?: FontFamily.Default
    }
    val globalFontWeight = remember(runtimeVersion) {
        AppPreferences.UI.fontWeight.coerceIn(100, 900)
    }
    val globalFontItalic = remember(runtimeVersion) {
        AppPreferences.UI.fontItalic
    }
    val globalFontScale = remember(runtimeVersion) {
        AppPreferences.UI.fontSizeScale.coerceIn(70, 160) / 100f
    }
    val globalLetterSpacingEm = remember(runtimeVersion) {
        AppPreferences.UI.fontLetterSpacingEm.coerceIn(-0.05f, 0.20f)
    }
    // Android 12+ applies fontWeightAdjustment inside the platform font resolver, including
    // Text calls that explicitly request Bold/Medium/SemiBold. Older releases retain the
    // typography-level fallback.
    val typographyFontWeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        400
    } else {
        globalFontWeight
    }
    val miuixTextStyles = remember(
        runtimeVersion,
        globalFontFamily,
        typographyFontWeight,
        globalFontItalic,
        globalLetterSpacingEm,
    ) {
        defaultTextStyles().withGlobalFont(
            globalFontFamily,
            typographyFontWeight,
            globalFontItalic,
            globalLetterSpacingEm,
        )
    }
    val materialTypography = remember(
        runtimeVersion,
        globalFontFamily,
        typographyFontWeight,
        globalFontItalic,
        globalLetterSpacingEm,
    ) {
        Typography().withGlobalFont(
            globalFontFamily,
            typographyFontWeight,
            globalFontItalic,
            globalLetterSpacingEm,
        )
    }
    val activeThemeMode = remember(runtimeVersion, themeMode) {
        ThemeManager.getCurrentTheme()
    }

    val isDark = when (activeThemeMode) {
        ThemeManager.ThemeMode.DARK -> true
        ThemeManager.ThemeMode.LIGHT -> false
        ThemeManager.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorThemeMode = remember(runtimeVersion, key) {
        ColorThemeMode.fromValue(UIPreferences.colorThemeMode)
    }

    if (colorThemeMode == ColorThemeMode.MIUIX) {
        val controller = remember(
            activeThemeMode,
            accentColor,
            isDark,
            runtimeVersion,
            key
        ) {
            ThemeController(
                colorSchemeMode = ColorSchemeMode.System,
                keyColor = accentColor,
                isDark = isDark
            )
        }

        MiuixTheme(
            controller = controller,
            textStyles = miuixTextStyles
        ) {
            val cs = MiuixTheme.colorScheme
            val pageBg = cs.background
            val cardBg = pageBg.blendForRawTheme(cs.primary, if (isDark) 0.12f else 0.06f)
            PageColors.updateFromPalette(
                pageBg = pageBg,
                cardBg = cardBg,
                textPrimary = cs.onBackground,
                textSecondary = cs.onSurfaceVariantSummary,
                textMeta = cs.onSurfaceVariantSummary.copy(alpha = if (isDark) 0.72f else 0.68f),
                accent = cs.primary,
                divider = cs.onSurfaceVariantSummary.copy(alpha = if (isDark) 0.20f else 0.14f)
            )
            RawSystemBars(background = pageBg, isDark = isDark)
            GlobalFontScope(globalFontScale, globalFontWeight, materialTypography, content)
        }
    } else {
        RawMonetTheme(
            isDark = isDark,
            colorThemeMode = colorThemeMode,
            applySystemBars = true
        ) {
            val monet = RawMonet

            /**
             * 关键点：
             *
             * MiuixTheme 不会自动读取 MaterialTheme.colorScheme。
             * 所以必须把 Monet accent 喂给 MiuixTheme 的 keyColor。
             *
             * 这样项目里大量 MiuixTheme.colorScheme.primary / surfaceContainer
             * 才会跟着 Monet 变化。
             */
            val controller = remember(
                isDark,
                monet.accent,
                colorThemeMode,
                runtimeVersion,
                key
            ) {
                ThemeController(
                    colorSchemeMode = ColorSchemeMode.MonetSystem,
                    keyColor = monet.accent,
                    isDark = isDark
                )
            }

            MiuixTheme(
                controller = controller,
                textStyles = miuixTextStyles
            ) {
                PageColors.updateFromPalette(
                    pageBg = monet.background,
                    cardBg = monet.card,
                    textPrimary = monet.textPrimary,
                    textSecondary = monet.textSecondary,
                    textMeta = monet.textSecondary.copy(alpha = if (monet.isDark) 0.72f else 0.68f),
                    accent = monet.accent,
                    divider = monet.divider
                )
                GlobalFontScope(globalFontScale, globalFontWeight, materialTypography, content)
            }
        }
    }
}

@Composable
private fun GlobalFontScope(
    scale: Float,
    fontWeight: Int,
    typography: Typography,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val platformFontResolver = LocalFontFamilyResolver.current
    val scaledDensity = remember(density.density, density.fontScale, scale) {
        Density(
            density = density.density,
            fontScale = density.fontScale * scale
        )
    }
    val weightedFontResolver = remember(context, platformFontResolver, fontWeight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val configuration = Configuration(context.resources.configuration)
            val systemAdjustment = configuration.fontWeightAdjustment
                .takeUnless { it == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED }
                ?: 0
            configuration.fontWeightAdjustment =
                systemAdjustment + fontWeight.coerceIn(100, 900) - 400
            createFontFamilyResolver(context.createConfigurationContext(configuration))
        } else {
            platformFontResolver
        }
    }
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalFontFamilyResolver provides weightedFontResolver,
    ) {
        MaterialTheme(typography = typography, content = content)
    }
}

private fun top.yukonga.miuix.kmp.theme.TextStyles.withGlobalFont(
    family: FontFamily,
    weight: Int,
    italic: Boolean,
    letterSpacingEm: Float,
): top.yukonga.miuix.kmp.theme.TextStyles = copy(
    main = main.withGlobalFont(family, weight, italic, letterSpacingEm),
    paragraph = paragraph.withGlobalFont(family, weight, italic, letterSpacingEm),
    body1 = body1.withGlobalFont(family, weight, italic, letterSpacingEm),
    body2 = body2.withGlobalFont(family, weight, italic, letterSpacingEm),
    button = button.withGlobalFont(family, weight, italic, letterSpacingEm),
    footnote1 = footnote1.withGlobalFont(family, weight, italic, letterSpacingEm),
    footnote2 = footnote2.withGlobalFont(family, weight, italic, letterSpacingEm),
    headline1 = headline1.withGlobalFont(family, weight, italic, letterSpacingEm),
    headline2 = headline2.withGlobalFont(family, weight, italic, letterSpacingEm),
    subtitle = subtitle.withGlobalFont(family, weight, italic, letterSpacingEm),
    title1 = title1.withGlobalFont(family, weight, italic, letterSpacingEm),
    title2 = title2.withGlobalFont(family, weight, italic, letterSpacingEm),
    title3 = title3.withGlobalFont(family, weight, italic, letterSpacingEm),
    title4 = title4.withGlobalFont(family, weight, italic, letterSpacingEm)
)

private fun Typography.withGlobalFont(
    family: FontFamily,
    weight: Int,
    italic: Boolean,
    letterSpacingEm: Float,
): Typography = copy(
    displayLarge = displayLarge.withGlobalFont(family, weight, italic, letterSpacingEm),
    displayMedium = displayMedium.withGlobalFont(family, weight, italic, letterSpacingEm),
    displaySmall = displaySmall.withGlobalFont(family, weight, italic, letterSpacingEm),
    headlineLarge = headlineLarge.withGlobalFont(family, weight, italic, letterSpacingEm),
    headlineMedium = headlineMedium.withGlobalFont(family, weight, italic, letterSpacingEm),
    headlineSmall = headlineSmall.withGlobalFont(family, weight, italic, letterSpacingEm),
    titleLarge = titleLarge.withGlobalFont(family, weight, italic, letterSpacingEm),
    titleMedium = titleMedium.withGlobalFont(family, weight, italic, letterSpacingEm),
    titleSmall = titleSmall.withGlobalFont(family, weight, italic, letterSpacingEm),
    bodyLarge = bodyLarge.withGlobalFont(family, weight, italic, letterSpacingEm),
    bodyMedium = bodyMedium.withGlobalFont(family, weight, italic, letterSpacingEm),
    bodySmall = bodySmall.withGlobalFont(family, weight, italic, letterSpacingEm),
    labelLarge = labelLarge.withGlobalFont(family, weight, italic, letterSpacingEm),
    labelMedium = labelMedium.withGlobalFont(family, weight, italic, letterSpacingEm),
    labelSmall = labelSmall.withGlobalFont(family, weight, italic, letterSpacingEm)
)

private fun TextStyle.withGlobalFont(
    family: FontFamily,
    weight: Int,
    italic: Boolean,
    letterSpacingEm: Float,
): TextStyle {
    val adjustedWeight = ((fontWeight?.weight ?: 400) + weight - 400).coerceIn(100, 900)
    return copy(
        fontFamily = family,
        fontWeight = FontWeight(adjustedWeight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = letterSpacingEm.em,
    )
}

private fun Color.blendForRawTheme(target: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    val inv = 1f - f
    return Color(
        red = red * inv + target.red * f,
        green = green * inv + target.green * f,
        blue = blue * inv + target.blue * f,
        alpha = alpha * inv + target.alpha * f
    )
}

/**
 * 读取当前生效的 ColorThemeMode。
 */
fun getCurrentColorThemeMode(): ColorThemeMode {
    return ColorThemeMode.fromValue(UIPreferences.colorThemeMode)
}

/**
 * 设置 ColorThemeMode 并立即请求全局重组。
 */
fun setColorThemeMode(
    mode: ColorThemeMode,
    context: Context
) {
    UIPreferences.colorThemeMode = mode.value
    RawThemeRuntimeState.invalidate()

    context.sendBroadcast(
        Intent("com.rawsmusic.action.COLOR_THEME_CHANGED")
    )
}

/**
 * 外部需要强制刷新主题时调用。
 */
fun invalidateRawTheme() {
    RawThemeRuntimeState.invalidate()
}
