package com.rawsmusic.core.ui.theme

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.rawsmusic.core.common.prefs.UIPreferences
import com.rawsmusic.core.ui.scene.pages.PageColors
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

    val isDark = when (themeMode) {
        ThemeManager.ThemeMode.DARK -> true
        ThemeManager.ThemeMode.LIGHT -> false
        ThemeManager.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorThemeMode = remember(runtimeVersion, key) {
        ColorThemeMode.fromValue(UIPreferences.colorThemeMode)
    }

    if (colorThemeMode == ColorThemeMode.MIUIX) {
        val controller = remember(
            themeMode,
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

        val textStyles = remember { defaultTextStyles() }

        MiuixTheme(
            controller = controller,
            textStyles = textStyles
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
            content()
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

            val textStyles = remember { defaultTextStyles() }

            MiuixTheme(
                controller = controller,
                textStyles = textStyles
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
                content()
            }
        }
    }
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
