package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ruby occupies the existing line/paragraph gap, rather than adding a second full gap. */
internal object LyricRubySpacingSpec {
    fun flowGapPx(lineGapPx: Int, rubyHeightPx: Int): Int =
        (lineGapPx - rubyHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
    fun leadingPaddingPx(paddingPx: Int, rubyHeightPx: Int): Int =
        (paddingPx - rubyHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
}

@Composable
internal fun lyricRubyLaneHeightPx(primarySizeSp: Int, fontFamily: FontFamily?): Int {
    val size = (primarySizeSp * .45f).sp
    val style = LocalTextStyle.current.merge(TextStyle(localeList = lyricGlyphLocales("かな"), fontSize = size,
        lineHeight = size, fontFamily = fontFamily, fontWeight = FontWeight.Medium,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)))
    val height = rememberLyricInkBounds("かな", style).height
    return height + with(LocalDensity.current) { 2.dp.roundToPx() }
}
