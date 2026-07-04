package com.rawsmusic.core.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ColorThemeMode(val value: Int) {
    MIUIX(0),
    MONET_AUTO(1),
    MONET_LIGHT(2),
    MONET_DARK(3);

    companion object {
        fun fromValue(value: Int): ColorThemeMode {
            return entries.getOrElse(value) { MIUIX }
        }
    }
}

@Immutable
data class RawMonetTokens(
    val colorScheme: ColorScheme,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val card: Color,
    val cardHigh: Color,
    val cardSelected: Color,
    val cardPressed: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentOn: Color,
    val accentContainer: Color,
    val accentContainerOn: Color,
    val progress: Color,
    val outline: Color,
    val divider: Color
)

val LocalRawMonet = staticCompositionLocalOf {
    fallbackRawMonetTokens(isDark = true)
}

val RawMonet: RawMonetTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalRawMonet.current

@Composable
fun RawMonetTheme(
    isDark: Boolean = isSystemInDarkTheme(),
    colorThemeMode: ColorThemeMode = ColorThemeMode.MIUIX,
    applySystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val useDynamic = colorThemeMode == ColorThemeMode.MONET_AUTO ||
        colorThemeMode == ColorThemeMode.MONET_LIGHT ||
        colorThemeMode == ColorThemeMode.MONET_DARK

    val effectiveIsDark = when (colorThemeMode) {
        ColorThemeMode.MONET_LIGHT -> false
        ColorThemeMode.MONET_DARK -> true
        else -> isDark
    }

    val colorScheme = remember(context, effectiveIsDark, useDynamic) {
        resolveColorScheme(context, effectiveIsDark, useDynamic)
    }

    val tokens = remember(colorScheme, effectiveIsDark) {
        colorScheme.toRawMonetTokens(effectiveIsDark)
    }

    if (applySystemBars) {
        RawSystemBars(
            background = tokens.background,
            isDark = tokens.isDark,
            navigationBarColor = tokens.background
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalRawMonet provides tokens, content = content)
    }
}

@Composable
fun rememberRawMonetColorScheme(
    isDark: Boolean = isSystemInDarkTheme(),
    useDynamic: Boolean = true
): ColorScheme {
    val context = LocalContext.current
    return remember(context, isDark, useDynamic) {
        resolveColorScheme(context, isDark, useDynamic)
    }
}

@Composable
fun rememberRawMonetTokens(
    isDark: Boolean = isSystemInDarkTheme(),
    useDynamic: Boolean = true
): RawMonetTokens {
    val scheme = rememberRawMonetColorScheme(isDark, useDynamic)
    return remember(scheme, isDark) { scheme.toRawMonetTokens(isDark) }
}

private fun resolveColorScheme(
    context: android.content.Context,
    isDark: Boolean,
    useDynamic: Boolean
): ColorScheme {
    return if (useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        fallbackColorScheme(isDark)
    }
}

private fun ColorScheme.toRawMonetTokens(isDark: Boolean): RawMonetTokens {
    val surfaceMixed = surface.blendWith(surfaceVariant, if (isDark) 0.18f else 0.26f)
    val surfaceHigh = surface.blendWith(primary, if (isDark) 0.12f else 0.08f)
    val pressed = surface.blendWith(primary, if (isDark) 0.24f else 0.16f)
    val outlineSoft = outline.copy(alpha = if (isDark) 0.38f else 0.46f)

    return RawMonetTokens(
        colorScheme = this,
        isDark = isDark,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        card = surfaceMixed,
        cardHigh = surfaceHigh,
        cardSelected = primaryContainer,
        cardPressed = pressed,
        textPrimary = onSurface,
        textSecondary = onSurfaceVariant,
        textDisabled = onSurfaceVariant.copy(alpha = 0.48f),
        accent = primary,
        accentOn = onPrimary,
        accentContainer = primaryContainer,
        accentContainerOn = onPrimaryContainer,
        progress = primary,
        outline = outlineSoft,
        divider = outline.copy(alpha = if (isDark) 0.24f else 0.18f)
    )
}

fun fallbackRawMonetTokens(isDark: Boolean): RawMonetTokens {
    return fallbackColorScheme(isDark).toRawMonetTokens(isDark)
}

private fun fallbackColorScheme(isDark: Boolean): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC),
            onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458),
            onSecondaryContainer = Color(0xFFE8DEF8),
            tertiary = Color(0xFFEFB8C8),
            onTertiary = Color(0xFF492532),
            tertiaryContainer = Color(0xFF633B48),
            onTertiaryContainer = Color(0xFFFFD8E4),
            background = Color(0xFF121212),
            onBackground = Color(0xFFE6E1E5),
            surface = Color(0xFF121212),
            onSurface = Color(0xFFE6E1E5),
            surfaceVariant = Color(0xFF49454F),
            onSurfaceVariant = Color(0xFFCAC4D0),
            outline = Color(0xFF938F99)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6750A4),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEADDFF),
            onPrimaryContainer = Color(0xFF21005D),
            secondary = Color(0xFF625B71),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE8DEF8),
            onSecondaryContainer = Color(0xFF1D192B),
            tertiary = Color(Color(0xFF7D5260).toArgb()),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFD8E4),
            onTertiaryContainer = Color(0xFF31111D),
            background = Color(0xFFFFFBFE),
            onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFFFBFE),
            onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFE7E0EC),
            onSurfaceVariant = Color(0xFF49454F),
            outline = Color(0xFF79747E)
        )
    }
}

@Composable
fun RawSystemBars(
    background: Color,
    isDark: Boolean,
    navigationBarColor: Color = background
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val window = activity.window

        // 所有主题统一走 edge-to-edge：状态栏透明，页面自己的背景铺到状态栏后面。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = navigationBarColor.toArgb()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val useLightIcons = isDark || background.luminance() <= 0.45f
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !useLightIcons
            isAppearanceLightNavigationBars = !useLightIcons
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun Color.blendWith(target: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    val inv = 1f - f
    return Color(
        red = red * inv + target.red * f,
        green = green * inv + target.green * f,
        blue = blue * inv + target.blue * f,
        alpha = alpha * inv + target.alpha * f
    )
}
