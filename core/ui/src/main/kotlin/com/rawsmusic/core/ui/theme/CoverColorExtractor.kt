package com.rawsmusic.core.ui.theme

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

object CoverColorExtractor {

    data class CoverColors(
        val primary: Int = 0xFF1A1A2E.toInt(),
        val dark: Int = 0xFF0D0D1A.toInt(),
        val statusBar: Int = 0xFF1A1A2E.toInt(),
        val lyricBg: Int = 0xFF101020.toInt(),
        val accent: Int = 0xFFE8D5B7.toInt(),
        val isExtracted: Boolean = false
    )

    fun extract(bitmap: Bitmap?): CoverColors {
        if (bitmap == null || bitmap.isRecycled) return CoverColors()

        return try {
            val palette = Palette.from(bitmap)
                .maximumColorCount(32)
                .resizeBitmapArea(500)
                .generate()

            val vibrant = palette.vibrantSwatch
            val lightVibrant = palette.lightVibrantSwatch
            val darkVibrant = palette.darkVibrantSwatch
            val dominant = palette.dominantSwatch
            val muted = palette.mutedSwatch
            val lightMuted = palette.lightMutedSwatch
            val darkMuted = palette.darkMutedSwatch

            val candidates = listOfNotNull(
                vibrant,
                lightVibrant,
                darkVibrant,
                dominant,
                muted,
                lightMuted,
                darkMuted
            )

            if (candidates.isEmpty()) return CoverColors()

            val selectedColors = selectDiverseColors(candidates)

            val primaryColor = darken(selectedColors[0], 0.15f)
            val darkColor = darken(selectedColors[0], 0.30f)
            val statusColor = darken(selectedColors[0], 0.20f)
            val lyricColor = darken(selectedColors.getOrElse(1) { selectedColors[0] }, 0.35f)
            val accentColor = lighten(selectedColors.getOrElse(2) { selectedColors[0] }, 0.20f)

            CoverColors(
                primary = ensureDarkEnough(primaryColor),
                dark = ensureDarkEnough(darkColor),
                statusBar = ensureDarkEnough(statusColor),
                lyricBg = ensureDarkEnough(lyricColor),
                accent = accentColor,
                isExtracted = true
            )
        } catch (_: Exception) {
            CoverColors()
        }
    }

    private fun selectDiverseColors(swatches: List<Palette.Swatch>): List<Int> {
        val sorted = swatches.sortedByDescending { it.population }

        val result = mutableListOf<Int>()
        val usedHues = mutableListOf<Float>()

        for (swatch in sorted) {
            val rgb = swatch.rgb
            val hsl = FloatArray(3)
            Color.colorToHSV(rgb, hsl)
            val hue = hsl[0]

            val isDiverse = usedHues.all { existingHue ->
                val diff = Math.abs(hue - existingHue)
                val hueDiff = minOf(diff, 360f - diff)
                hueDiff > 30f
            }

            if (isDiverse || result.isEmpty()) {
                result.add(rgb)
                usedHues.add(hue)
            }

            if (result.size >= 3) break
        }

        while (result.size < 3) {
            result.add(result.last())
        }

        return result
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun ensureDarkEnough(color: Int): Int {
        val hsl = FloatArray(3)
        Color.colorToHSV(color, hsl)
        if (hsl[2] > 0.35f) {
            hsl[2] = 0.35f
            return Color.HSVToColor(hsl)
        }
        return color
    }
}
