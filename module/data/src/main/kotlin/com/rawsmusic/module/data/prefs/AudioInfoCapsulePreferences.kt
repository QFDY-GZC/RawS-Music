package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Content behavior for the playback-screen audio information capsule. */
enum class AudioInfoCapsuleContentStyle(val prefValue: Int) {
    CYCLE(0),
    PLAYBACK_CHAIN(1);

    companion object {
        fun fromPref(value: Int): AudioInfoCapsuleContentStyle =
            entries.firstOrNull { it.prefValue == value } ?: CYCLE
    }
}

/** Visual preferences for the playback-screen audio information capsule. */
enum class AudioInfoCapsuleBackgroundStyle(val prefValue: Int) {
    CLASSIC(0),
    TRANSPARENT(1),
    ALBUM_COLOR(2),
    THEME_COLOR(3),
    CUSTOM_COLOR(4);

    companion object {
        fun fromPref(value: Int): AudioInfoCapsuleBackgroundStyle =
            entries.firstOrNull { it.prefValue == value } ?: CLASSIC
    }
}

object AudioInfoCapsulePreferences {
    private const val DEFAULT_CUSTOM_COLOR: Int = 0xFF6B5A70.toInt()
    private const val DEFAULT_CUSTOM_ALPHA_PERCENT: Int = 68
    private const val DEFAULT_POPUP_BACKGROUND_COLOR: Int = 0xFF2D2D2D.toInt()

    private const val KEY_VISIBLE = "ui_audio_info_capsule_visible_v1"
    private const val KEY_CONTENT_STYLE = "ui_audio_info_capsule_content_style_v1"
    private const val KEY_BACKGROUND_STYLE = "ui_audio_info_capsule_background_style_v1"
    private const val KEY_CUSTOM_COLOR = "ui_audio_info_capsule_custom_color_v1"
    private const val KEY_CUSTOM_ALPHA_PERCENT = "ui_audio_info_capsule_custom_alpha_percent_v1"
    private const val KEY_POPUP_BACKGROUND_COLOR = "ui_audio_info_popup_background_color_v1"

    private val _visible = MutableStateFlow(
        AppPreferences.storage.decodeBool(KEY_VISIBLE, true)
    )
    val visible = _visible.asStateFlow()

    var isVisible: Boolean
        get() = _visible.value
        set(value) {
            if (_visible.value == value) return
            _visible.value = value
            AppPreferences.storage.encode(KEY_VISIBLE, value)
        }


    private val _contentStyle = MutableStateFlow(
        AudioInfoCapsuleContentStyle.fromPref(
            AppPreferences.storage.decodeInt(KEY_CONTENT_STYLE, AudioInfoCapsuleContentStyle.CYCLE.prefValue)
        )
    )
    val contentStyle = _contentStyle.asStateFlow()

    var contentStyleValue: AudioInfoCapsuleContentStyle
        get() = _contentStyle.value
        set(value) {
            if (_contentStyle.value == value) return
            _contentStyle.value = value
            AppPreferences.storage.encode(KEY_CONTENT_STYLE, value.prefValue)
        }

    private val _backgroundStyle = MutableStateFlow(
        AudioInfoCapsuleBackgroundStyle.fromPref(
            AppPreferences.storage.decodeInt(KEY_BACKGROUND_STYLE, AudioInfoCapsuleBackgroundStyle.CLASSIC.prefValue)
        )
    )
    val backgroundStyle = _backgroundStyle.asStateFlow()

    var backgroundStyleValue: AudioInfoCapsuleBackgroundStyle
        get() = _backgroundStyle.value
        set(value) {
            if (_backgroundStyle.value == value) return
            _backgroundStyle.value = value
            AppPreferences.storage.encode(KEY_BACKGROUND_STYLE, value.prefValue)
        }

    private val _customColor = MutableStateFlow(
        AppPreferences.storage.decodeInt(KEY_CUSTOM_COLOR, DEFAULT_CUSTOM_COLOR)
    )
    val customColor = _customColor.asStateFlow()

    var customColorArgb: Int
        get() = _customColor.value
        set(value) {
            if (_customColor.value == value) return
            _customColor.value = value
            AppPreferences.storage.encode(KEY_CUSTOM_COLOR, value)
        }

    private val _customAlphaPercent = MutableStateFlow(
        AppPreferences.storage.decodeInt(KEY_CUSTOM_ALPHA_PERCENT, DEFAULT_CUSTOM_ALPHA_PERCENT)
            .coerceIn(0, 100)
    )
    val customAlphaPercent = _customAlphaPercent.asStateFlow()

    var customAlphaPercentValue: Int
        get() = _customAlphaPercent.value
        set(value) {
            val normalized = value.coerceIn(0, 100)
            if (_customAlphaPercent.value == normalized) return
            _customAlphaPercent.value = normalized
            AppPreferences.storage.encode(KEY_CUSTOM_ALPHA_PERCENT, normalized)
        }

    private val _popupBackgroundColor = MutableStateFlow(
        AppPreferences.storage.decodeInt(KEY_POPUP_BACKGROUND_COLOR, DEFAULT_POPUP_BACKGROUND_COLOR)
    )
    val popupBackgroundColor = _popupBackgroundColor.asStateFlow()

    var popupBackgroundColorArgb: Int
        get() = _popupBackgroundColor.value
        set(value) {
            if (_popupBackgroundColor.value == value) return
            _popupBackgroundColor.value = value
            AppPreferences.storage.encode(KEY_POPUP_BACKGROUND_COLOR, value)
        }

}
