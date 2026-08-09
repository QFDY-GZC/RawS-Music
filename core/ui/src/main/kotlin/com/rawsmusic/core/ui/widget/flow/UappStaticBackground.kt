package com.rawsmusic.core.ui.widget.flow

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.CoilArtworkRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal const val UAPP_DARK_ALBUM_BACKGROUND_BRIGHTNESS = 50
internal val UAPP_DARK_ALBUM_FALLBACK_ACCENT = Color(0xFF202125)

private val UappStaticAccentCache = ConcurrentHashMap<String, Color>()

/**
 * UAPP `C2087s6.m12287T()` album-background swatch selection.
 *
 * Order in the decompiled UAPP build:
 * Vibrant -> Muted -> LightMuted -> Dominant -> DarkVibrant -> DarkMuted.
 * Keep the raw swatch RGB. The dark-album transform is applied separately by
 * [uappDarkAlbumGradient].
 */
internal fun uappStaticAlbumAccent(bitmap: Bitmap): Color? {
    if (bitmap.isRecycled) return null
    val softwareCopy = if (
        android.os.Build.VERSION.SDK_INT >= 26 &&
        bitmap.config == Bitmap.Config.HARDWARE
    ) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        null
    }
    val source = softwareCopy ?: bitmap
    return try {
        val palette = Palette.from(source)
            .maximumColorCount(16)
            .generate()
        val swatch = palette.vibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.lightMutedSwatch
            ?: palette.dominantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.darkMutedSwatch
        swatch?.rgb?.let { rgb -> Color(rgb) }
    } catch (_: Throwable) {
        null
    } finally {
        softwareCopy?.recycle()
    }
}

/**
 * Exact color math from UAPP `C2087s6.m12274G()`.
 *
 * UAPP stores `BackgroundBrightness` as 0..200-ish UI units, adds 100, clamps the
 * resulting value to 100..300 and then mixes two HSV value targets. Its current
 * default is 50. Both MiniPlayer and CurrentSong use the same two endpoint colors.
 */
internal fun uappDarkAlbumGradient(
    accent: Color,
    brightness: Int = UAPP_DARK_ALBUM_BACKGROUND_BRIGHTNESS,
): List<Color> {
    val source = if (accent != Color.Unspecified) accent else UAPP_DARK_ALBUM_FALLBACK_ACCENT
    val adjusted = (brightness + 100).coerceIn(100, 300)
    val darkness = 1f - ((adjusted - 100) / 200f)
    val brightnessMix = 1f - darkness * darkness

    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(source.toArgbCompat(), hsv)
    val hue = hsv[0]
    val saturation = hsv[1]
    val value = hsv[2]

    val firstSaturation = 0.85f * saturation
    val secondSaturation = 0.55f * saturation
    val firstBaseValue = maxOf(0.62f * value, 0.22f)
    val secondBaseValue = maxOf(0.16f * value, 0.07f)
    val firstValue = lerp(firstBaseValue, 0.78f, brightnessMix)
    val secondValue = lerp(secondBaseValue, 0.36f, brightnessMix)

    return listOf(
        Color(AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, firstSaturation, firstValue))),
        Color(AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, secondSaturation, secondValue))),
    )
}

@Composable
internal fun rememberUappStaticAlbumAccent(
    coverKey: String?,
    sourceArtwork: Bitmap? = null,
): Color {
    val context = LocalContext.current
    val key = coverKey?.takeIf { it.isNotBlank() }
    var accent by remember(key) {
        mutableStateOf(key?.let { UappStaticAccentCache[it] } ?: UAPP_DARK_ALBUM_FALLBACK_ACCENT)
    }

    LaunchedEffect(key, sourceArtwork) {
        if (key == null) {
            accent = UAPP_DARK_ALBUM_FALLBACK_ACCENT
            return@LaunchedEffect
        }
        UappStaticAccentCache[key]?.let {
            accent = it
            return@LaunchedEffect
        }

        val provided = sourceArtwork
            ?.takeUnless { it.isRecycled }
            ?.let { bitmap -> withContext(Dispatchers.Default) { uappStaticAlbumAccent(bitmap) } }
        if (provided != null) {
            UappStaticAccentCache[key] = provided
            accent = provided
            return@LaunchedEffect
        }

        accent = UAPP_DARK_ALBUM_FALLBACK_ACCENT
        val bitmap = CoilArtworkRuntime.executeBitmap(
            context = context,
            key = key,
            width = 256,
            height = 256,
            surface = ArtworkSurface.Playback
        )
        if (bitmap != null && !bitmap.isRecycled) {
            val next = withContext(Dispatchers.Default) { uappStaticAlbumAccent(bitmap) }
            if (next != null) {
                UappStaticAccentCache[key] = next
                accent = next
            }
        }
    }
    return accent
}

private fun Color.toArgbCompat(): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val r = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val g = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val b = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction
