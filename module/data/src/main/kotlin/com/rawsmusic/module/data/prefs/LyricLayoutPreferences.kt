package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LyricTopLayoutStyle(val persistedValue: Int) {
    Current(0),
    ScrollWithLyrics(1),
    TitleOnly(2);

    companion object {
        fun fromPersistedValue(value: Int): LyricTopLayoutStyle =
            entries.firstOrNull { it.persistedValue == value } ?: Current
    }
}

/**
 * Runtime-observable lyric layout preferences.
 *
 * Keep layout-only values out of the large AppPreferences.UI bucket so player UI and
 * settings can share one live source of truth while a separate Settings activity is open.
 */
object LyricLayoutPreferences {
    const val MIN_BOTTOM_PADDING_DP = 40
    const val MAX_BOTTOM_PADDING_DP = 220
    const val DEFAULT_BOTTOM_PADDING_DP = 120

    private const val KEY_BOTTOM_PADDING_DP = "ui_lyric_bottom_padding_dp"
    private const val KEY_TOP_LAYOUT_STYLE = "ui_lyric_top_layout_style_v1"

    private val _bottomPaddingDp = MutableStateFlow(loadBottomPaddingDp())
    val bottomPaddingDp = _bottomPaddingDp.asStateFlow()

    private val _topLayoutStyle = MutableStateFlow(loadTopLayoutStyle())
    val topLayoutStyle = _topLayoutStyle.asStateFlow()

    var tailBottomPaddingDp: Int
        get() = _bottomPaddingDp.value
        set(value) {
            val normalized = value.coerceIn(MIN_BOTTOM_PADDING_DP, MAX_BOTTOM_PADDING_DP)
            if (_bottomPaddingDp.value == normalized) return
            _bottomPaddingDp.value = normalized
            AppPreferences.storage.encode(KEY_BOTTOM_PADDING_DP, normalized)
        }

    var lyricTopLayoutStyle: LyricTopLayoutStyle
        get() = _topLayoutStyle.value
        set(value) {
            if (_topLayoutStyle.value == value) return
            _topLayoutStyle.value = value
            AppPreferences.storage.encode(KEY_TOP_LAYOUT_STYLE, value.persistedValue)
        }

    private fun loadBottomPaddingDp(): Int = AppPreferences.storage
        .decodeInt(KEY_BOTTOM_PADDING_DP, DEFAULT_BOTTOM_PADDING_DP)
        .coerceIn(MIN_BOTTOM_PADDING_DP, MAX_BOTTOM_PADDING_DP)

    private fun loadTopLayoutStyle(): LyricTopLayoutStyle = LyricTopLayoutStyle.fromPersistedValue(
        AppPreferences.storage.decodeInt(KEY_TOP_LAYOUT_STYLE, LyricTopLayoutStyle.Current.persistedValue)
    )
}
