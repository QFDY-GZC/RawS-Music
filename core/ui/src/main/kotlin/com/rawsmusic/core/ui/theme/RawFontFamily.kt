package com.rawsmusic.core.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.rawsmusic.module.data.prefs.FontFileInspector
import java.io.File

private val RawSemanticFontWeights = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

/**
 * Global UI family whose Compose-facing weight stays semantic while the loaded Typeface carries
 * the user's global weight offset. This is deliberately independent of
 * Configuration.fontWeightAdjustment: that platform field models a system/user accessibility
 * preference and several OEM font stacks do not apply it consistently to file-backed/variable
 * fonts.
 */
fun rawGlobalFontFamily(
    path: String,
    globalWeight: Int,
    allowOutOfRange: Boolean = false,
): FontFamily {
    val delta = globalWeight.coerceIn(100, 900) - FontWeight.Normal.weight
    val variableRange = if (
        path.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    ) {
        FontFileInspector.inspect(path).weightRange
    } else {
        null
    }
    val fonts = buildList(RawSemanticFontWeights.size * 2) {
        for (style in listOf(FontStyle.Normal, FontStyle.Italic)) {
            for (semanticWeight in RawSemanticFontWeights) {
                val requestedPhysicalWeight = (semanticWeight + delta).coerceIn(100, 900)
                val physicalWeight = variableRange?.let { range ->
                    if (allowOutOfRange) {
                        requestedPhysicalWeight
                    } else {
                        requestedPhysicalWeight.coerceIn(range.first, range.last)
                    }
                } ?: requestedPhysicalWeight
                add(
                    RawMappedAndroidFont(
                        path = path,
                        physicalWeight = physicalWeight,
                        weight = FontWeight(semanticWeight),
                        style = style,
                        variableWeightRange = variableRange,
                    )
                )
            }
        }
    }
    return FontFamily(fonts)
}

/**
 * Build a file-backed family without throwing away a variable font's `wght` axis.
 *
 * A single `Font(file, Normal)` fixes a variable file at its Normal variation and leaves later
 * weight requests to synthesis. That can fake a heavier face but cannot synthesize a lighter one.
 * If the file really contains `fvar/wght`, publish one semantic entry per Android weight and bind
 * each entry to the matching axis value. Static files deliberately keep the old one-face behavior
 * so Compose may still use nearest-face/fake-bold fallback instead of being told that nine
 * identical outlines are nine real weights.
 */
fun rawFileFontFamily(
    path: String,
    allowOutOfRange: Boolean = false,
): FontFamily {
    if (path.isBlank()) return FontFamily.Default
    val file = File(path)
    if (!file.isFile) return FontFamily.Default

    val weightRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        FontFileInspector.inspect(path).weightRange
    } else {
        null
    }

    return runCatching {
        if (weightRange != null) {
            FontFamily(
                RawSemanticFontWeights.map { semanticWeight ->
                    val axisWeight = if (allowOutOfRange) {
                        semanticWeight
                    } else {
                        semanticWeight.coerceIn(weightRange.first, weightRange.last)
                    }
                    Font(
                        file = file,
                        weight = FontWeight(semanticWeight),
                        style = FontStyle.Normal,
                        variationSettings = FontVariation.Settings(
                            FontVariation.weight(axisWeight),
                        ),
                    )
                }
            )
        } else {
            FontFamily(Font(file, FontWeight.Normal))
        }
    }.getOrDefault(FontFamily.Default)
}

private class RawMappedAndroidFont(
    val path: String,
    val physicalWeight: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
    val variableWeightRange: IntRange?,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = RawMappedTypefaceLoader,
    variationSettings = FontVariation.Settings(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawMappedAndroidFont) return false
        return path == other.path &&
            physicalWeight == other.physicalWeight &&
            weight == other.weight &&
            style == other.style &&
            variableWeightRange == other.variableWeightRange
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + physicalWeight
        result = 31 * result + weight.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + (variableWeightRange?.hashCode() ?: 0)
        return result
    }
}

private object RawMappedTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val mapped = font as? RawMappedAndroidFont ?: return null
        val italic = mapped.style == FontStyle.Italic
        return runCatching {
            if (mapped.path.isBlank()) {
                resolveSystemTypeface(mapped.physicalWeight, italic)
            } else if (
                mapped.variableWeightRange != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                Typeface.Builder(mapped.path)
                    .setFontVariationSettings("'wght' ${mapped.physicalWeight}")
                    .setWeight(mapped.physicalWeight)
                    .setItalic(italic)
                    .setFallback("sans-serif")
                    .build()
            } else {
                val base = Typeface.createFromFile(mapped.path)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(base, mapped.physicalWeight, italic)
                } else {
                    Typeface.create(base, legacyStyle(mapped.physicalWeight, italic))
                }
            }
        }.getOrElse {
            resolveSystemTypeface(mapped.physicalWeight, italic)
        }
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        loadBlocking(context, font)

    private fun resolveSystemTypeface(weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, weight, italic)
        } else {
            // Numeric Typeface.create(weight) arrives in API 28. Keep pre-28 compatible with the
            // platform aliases instead of collapsing the whole 100..900 slider to one face.
            val familyName = when {
                weight <= 150 -> "sans-serif-thin"
                weight <= 350 -> "sans-serif-light"
                weight <= 450 -> "sans-serif"
                weight <= 650 -> "sans-serif-medium"
                weight <= 750 -> "sans-serif"
                else -> "sans-serif-black"
            }
            Typeface.create(
                familyName,
                when {
                    weight >= 700 && italic -> Typeface.BOLD_ITALIC
                    weight >= 700 -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }

    private fun legacyStyle(weight: Int, italic: Boolean): Int = when {
        weight >= 600 && italic -> Typeface.BOLD_ITALIC
        weight >= 600 -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
}
