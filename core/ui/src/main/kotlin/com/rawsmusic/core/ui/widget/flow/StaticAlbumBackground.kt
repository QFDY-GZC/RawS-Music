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
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkBitmapRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal const val STATIC_ALBUM_BACKGROUND_BRIGHTNESS = 50
internal val STATIC_ALBUM_FALLBACK_ACCENT = Color(0xFF202125)

private val StaticAccentCache = ConcurrentHashMap<String, Color>()

/**
 * Album-background swatch selection ordered from vivid to subdued colors:
 * Vibrant -> Muted -> LightMuted -> Dominant -> DarkVibrant -> DarkMuted.
 * Keep the raw swatch RGB. The dark-album transform is applied separately by
 * [darkAlbumGradient].
 */
internal fun staticAlbumAccent(bitmap: Bitmap): Color? {
    if (bitmap.isRecycled) return null
    if (
        android.os.Build.VERSION.SDK_INT >= 26 &&
        bitmap.config == Bitmap.Config.HARDWARE
    ) return null
    return try {
        val palette = Palette.from(bitmap)
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
    }
}

/**
 * Produces two dark HSV endpoints from an artwork accent and a user brightness value.
 */
internal fun darkAlbumGradient(
    accent: Color,
    brightness: Int = STATIC_ALBUM_BACKGROUND_BRIGHTNESS,
): List<Color> {
    val source = if (accent != Color.Unspecified) accent else STATIC_ALBUM_FALLBACK_ACCENT
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
internal fun rememberStaticAlbumAccent(
    coverKey: String?,
    sourceArtwork: Bitmap? = null,
): Color {
    val context = LocalContext.current
    val key = coverKey?.takeIf { it.isNotBlank() }
    var accent by remember(key) {
        mutableStateOf(key?.let { StaticAccentCache[it] } ?: STATIC_ALBUM_FALLBACK_ACCENT)
    }

    LaunchedEffect(key, sourceArtwork) {
        if (key == null) {
            accent = STATIC_ALBUM_FALLBACK_ACCENT
            return@LaunchedEffect
        }
        StaticAccentCache[key]?.let {
            accent = it
            return@LaunchedEffect
        }

        val provided = sourceArtwork
            ?.takeUnless { it.isRecycled }
            ?.takeIf {
                !(android.os.Build.VERSION.SDK_INT >= 26 && it.config == Bitmap.Config.HARDWARE)
            }
            ?.let { bitmap -> withContext(Dispatchers.Default) { staticAlbumAccent(bitmap) } }
        if (provided != null) {
            StaticAccentCache[key] = provided
            accent = provided
            return@LaunchedEffect
        }

        accent = STATIC_ALBUM_FALLBACK_ACCENT
        val bitmap = ArtworkBitmapRuntime.executePixelAnalysisBitmap(
            context = context,
            key = key,
            targetSide = 96,
        )
        if (bitmap != null && !bitmap.isRecycled) {
            val next = withContext(Dispatchers.Default) { staticAlbumAccent(bitmap) }
            if (next != null) {
                StaticAccentCache[key] = next
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
