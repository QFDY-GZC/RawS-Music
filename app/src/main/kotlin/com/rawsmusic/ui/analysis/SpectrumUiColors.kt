package com.rawsmusic.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SpectrumUiColors(
    val background: Color, val surface: Color, val text: Color, val muted: Color,
    val line: Color, val accent: Color
)

@Composable
internal fun spectrumUiColors(): SpectrumUiColors {
    val scheme = MiuixTheme.colorScheme
    return SpectrumUiColors(scheme.background, scheme.surfaceContainer,
        scheme.onBackground, scheme.onSurfaceVariantSummary,
        scheme.onBackground.copy(alpha = 0.12f),
        if (scheme.background.luminance() < 0.5f) Color(0xff82b4ff) else Color(0xff2563db))
}
