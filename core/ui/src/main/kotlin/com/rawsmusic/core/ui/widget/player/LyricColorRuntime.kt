package com.rawsmusic.core.ui.widget.player

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.rawsmusic.module.data.prefs.LyricColorPreferences
import com.rawsmusic.module.data.prefs.LyricColorSettings
import com.rawsmusic.module.data.prefs.LyricColorSource

internal enum class LyricColorSurface {
    LYRIC_PAGE,
    MINI_PLAYER,
    HOME,
}

internal data class ResolvedLyricColor(
    val primary: Color,
    val dim: Color,
    val secondary: Color,
    val rainbow: Boolean,
)

@Composable
internal fun rememberResolvedLyricColor(
    surface: LyricColorSurface,
    fallbackPrimary: Color,
    albumAccent: Color,
): ResolvedLyricColor {
    val settings by LyricColorPreferences.settings.collectAsState()
    return resolveLyricColor(
        settings = settings,
        surface = surface,
        fallbackPrimary = fallbackPrimary,
        albumAccent = albumAccent,
    )
}

internal fun resolveLyricColor(
    settings: LyricColorSettings,
    surface: LyricColorSurface,
    fallbackPrimary: Color,
    albumAccent: Color,
): ResolvedLyricColor {
    val applies = when (surface) {
        LyricColorSurface.LYRIC_PAGE -> settings.applyToLyricPage
        LyricColorSurface.MINI_PLAYER -> settings.applyToMiniPlayer
        LyricColorSurface.HOME -> settings.applyToHome
    }
    if (!applies || settings.source == LyricColorSource.DEFAULT) {
        return ResolvedLyricColor(
            primary = fallbackPrimary,
            dim = fallbackPrimary.copy(alpha = fallbackPrimary.alpha * 0.42f),
            secondary = fallbackPrimary.copy(alpha = fallbackPrimary.alpha * 0.70f),
            rainbow = false,
        )
    }

    val rainbow = settings.source == LyricColorSource.RAINBOW
    val primary = when (settings.source) {
        LyricColorSource.DEFAULT -> fallbackPrimary
        LyricColorSource.SOLID -> Color(settings.solidColorArgb)
        LyricColorSource.ALBUM_ART -> readableAlbumLyricColor(albumAccent, fallbackPrimary)
        // Rainbow is a spatial gradient, not a time-varying hue. Keep only the alpha/contrast
        // envelope here; the actual RGB is supplied by [lyricRainbowMask] / SharedMarqueeText.
        LyricColorSource.RAINBOW -> fallbackPrimary
    }
    return ResolvedLyricColor(
        primary = primary,
        dim = primary.copy(alpha = primary.alpha * 0.42f),
        secondary = primary.copy(alpha = primary.alpha * 0.70f),
        rainbow = rainbow,
    )
}

internal val LyricRainbowColors = listOf(
    Color(0xFFFF3B30),
    Color(0xFFFF9500),
    Color(0xFFFFCC00),
    Color(0xFF34C759),
    Color(0xFF32ADE6),
    Color(0xFF007AFF),
    Color(0xFF5856D6),
    Color(0xFFAF52DE),
    Color(0xFFFF2D55),
)
private val LyricRainbowBrush = Brush.horizontalGradient(LyricRainbowColors)

/** Static, spatial rainbow. No timeline or animation clock participates. */
internal fun Modifier.lyricRainbowMask(enabled: Boolean): Modifier {
    if (!enabled) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = LyricRainbowBrush,
                blendMode = BlendMode.SrcIn,
            )
        }
}

/**
 * Preserve the album swatch hue while pulling it toward the contrast direction already selected
 * by the surface. Dark/album-backed surfaces normally use a light fallback, so dark swatches are
 * lifted. A light-theme lyric page uses a dark fallback and therefore receives the inverse
 * treatment instead of blindly turning every swatch pastel.
 */
private fun readableAlbumLyricColor(albumAccent: Color, fallbackPrimary: Color): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(albumAccent.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtMost(0.78f)
    if (fallbackPrimary.luminance() >= 0.5f) {
        hsv[2] = hsv[2].coerceAtLeast(0.86f)
    } else {
        hsv[2] = hsv[2].coerceIn(0.30f, 0.52f)
    }
    return Color(AndroidColor.HSVToColor((albumAccent.alpha * 255f).toInt().coerceIn(0, 255), hsv))
}
