package com.rawsmusic.module.data.prefs

internal fun normalizeInlineLyricFontSize(value: Int): Int =
    if (value in listOf(24, 28, 32, 36, 40)) value else 28
