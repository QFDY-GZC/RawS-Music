package com.rawsmusic.core.ui.widget.player

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

internal data class LyricInkBounds(val top: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** The same resolved face/density as Compose, measured at the glyph ink rather than font box. */
@Composable
internal fun rememberLyricInkBounds(text: String, style: TextStyle): LyricInkBounds {
    val density = LocalDensity.current
    val face by LocalFontFamilyResolver.current.resolve(style.fontFamily,
        style.fontWeight ?: FontWeight.Normal, style.fontStyle ?: FontStyle.Normal,
        style.fontSynthesis ?: FontSynthesis.All)
    val sizePx = with(density) { style.fontSize.toPx() }
    val language = style.localeList?.get(0)?.toLanguageTag() ?: "ja"
    return remember(text, face, sizePx, language) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face as? Typeface ?: Typeface.DEFAULT
            textSize = sizePx
            textLocale = Locale.forLanguageTag(language)
        }
        val sample = text.replace('\n', ' ')
        val bounds = Rect()
        if (sample.isNotBlank()) paint.getTextBounds(sample, 0, sample.length, bounds)
        if (bounds.height() > 0) LyricInkBounds(bounds.top - 1, bounds.bottom + 1)
        else LyricInkBounds(paint.fontMetricsInt.ascent, paint.fontMetricsInt.descent)
    }
}
