package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LyricColorSource(val persistedValue: Int) {
    DEFAULT(0),
    SOLID(1),
    ALBUM_ART(2),
    RAINBOW(3);

    companion object {
        fun from(value: Int): LyricColorSource =
            entries.firstOrNull { it.persistedValue == value } ?: DEFAULT
    }
}

enum class LyricColorPreset(
    val persistedValue: Int,
    val argb: Int,
) {
    ROSE(0, 0xFFFF6B81.toInt()),
    CORAL(1, 0xFFFF8A65.toInt()),
    AMBER(2, 0xFFFFC857.toInt()),
    MINT(3, 0xFF55D6A8.toInt()),
    SKY(4, 0xFF55C7E8.toInt()),
    BLUE(5, 0xFF5B8CFF.toInt()),
    VIOLET(6, 0xFF9B7BFF.toInt()),
    PINK(7, 0xFFFF74C8.toInt());

    companion object {
        fun fromColor(argb: Int): LyricColorPreset? = entries.firstOrNull { it.argb == argb }
    }
}

data class LyricColorSettings(
    val source: LyricColorSource = LyricColorSource.DEFAULT,
    val solidColorArgb: Int = LyricColorPreset.BLUE.argb,
    val applyToLyricPage: Boolean = true,
    val applyToMiniPlayer: Boolean = true,
    val applyToHome: Boolean = true,
)

object LyricColorPreferences {
    private const val KEY_SOURCE = "ui_lyric_color_source_v1"
    private const val KEY_SOLID_COLOR = "ui_lyric_color_solid_argb_v1"
    private const val KEY_APPLY_LYRIC_PAGE = "ui_lyric_color_apply_lyric_page_v1"
    private const val KEY_APPLY_MINI_PLAYER = "ui_lyric_color_apply_mini_player_v1"
    private const val KEY_APPLY_HOME = "ui_lyric_color_apply_home_v1"

    private val _settings = MutableStateFlow(load())
    val settings = _settings.asStateFlow()

    fun setRainbowEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                source = if (enabled) LyricColorSource.RAINBOW else LyricColorSource.DEFAULT,
            )
        }
    }

    fun setAlbumArtEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                source = if (enabled) LyricColorSource.ALBUM_ART else LyricColorSource.DEFAULT,
            )
        }
    }

    fun selectPreset(preset: LyricColorPreset) {
        setSolidColor(preset.argb)
    }

    fun setSolidColor(argb: Int) {
        update { current ->
            current.copy(
                source = LyricColorSource.SOLID,
                solidColorArgb = argb,
            )
        }
    }

    fun resetToDefault() {
        update { current -> current.copy(source = LyricColorSource.DEFAULT) }
    }

    fun setApplyToLyricPage(enabled: Boolean) {
        update { it.copy(applyToLyricPage = enabled) }
    }

    fun setApplyToMiniPlayer(enabled: Boolean) {
        update { it.copy(applyToMiniPlayer = enabled) }
    }

    fun setApplyToHome(enabled: Boolean) {
        update { it.copy(applyToHome = enabled) }
    }

    private fun update(transform: (LyricColorSettings) -> LyricColorSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        AppPreferences.storage.encode(KEY_SOURCE, next.source.persistedValue)
        AppPreferences.storage.encode(KEY_SOLID_COLOR, next.solidColorArgb)
        AppPreferences.storage.encode(KEY_APPLY_LYRIC_PAGE, next.applyToLyricPage)
        AppPreferences.storage.encode(KEY_APPLY_MINI_PLAYER, next.applyToMiniPlayer)
        AppPreferences.storage.encode(KEY_APPLY_HOME, next.applyToHome)
    }

    private fun load(): LyricColorSettings = LyricColorSettings(
        source = LyricColorSource.from(
            AppPreferences.storage.decodeInt(KEY_SOURCE, LyricColorSource.DEFAULT.persistedValue),
        ),
        solidColorArgb = AppPreferences.storage.decodeInt(KEY_SOLID_COLOR, LyricColorPreset.BLUE.argb),
        applyToLyricPage = AppPreferences.storage.decodeBool(KEY_APPLY_LYRIC_PAGE, true),
        applyToMiniPlayer = AppPreferences.storage.decodeBool(KEY_APPLY_MINI_PLAYER, true),
        applyToHome = AppPreferences.storage.decodeBool(KEY_APPLY_HOME, true),
    )
}
