package com.rawsmusic.module.data.prefs

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.model.SortOrder
import com.tencent.mmkv.MMKV

object AppPreferences {

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()

    object Player {
        var lastSongId: Long
            get() = kv.decodeLong("player_last_song_id", -1)
            set(value) { kv.encode("player_last_song_id", value) }

        var lastSongPath: String
            get() = kv.decodeString("player_last_song_path", "") ?: ""
            set(value) { kv.encode("player_last_song_path", value) }

        var lastSongTitle: String
            get() = kv.decodeString("player_last_song_title", "") ?: ""
            set(value) { kv.encode("player_last_song_title", value) }

        var lastSongArtist: String
            get() = kv.decodeString("player_last_song_artist", "") ?: ""
            set(value) { kv.encode("player_last_song_artist", value) }

        var lastSongAlbum: String
            get() = kv.decodeString("player_last_song_album", "") ?: ""
            set(value) { kv.encode("player_last_song_album", value) }

        var lastSongAlbumArtPath: String
            get() = kv.decodeString("player_last_song_album_art", "") ?: ""
            set(value) { kv.encode("player_last_song_album_art", value) }

        var lastSongDuration: Long
            get() = kv.decodeLong("player_last_song_duration", 0)
            set(value) { kv.encode("player_last_song_duration", value) }

        var lastSongAlbumId: Long
            get() = kv.decodeLong("player_last_song_album_id", -1)
            set(value) { kv.encode("player_last_song_album_id", value) }

        var lastPosition: Long
            get() = kv.decodeLong("player_last_position", 0)
            set(value) { kv.encode("player_last_position", value) }

        var repeatMode: RepeatMode
            get() = RepeatMode.entries.getOrElse(
                kv.decodeInt("player_repeat_mode", RepeatMode.OFF.ordinal)
            ) { RepeatMode.OFF }
            set(value) { kv.encode("player_repeat_mode", value.ordinal) }

        var isShuffle: Boolean
            get() = kv.decodeBool("player_shuffle", false)
            set(value) { kv.encode("player_shuffle", value) }

        var playMode: PlayMode
            get() = PlayMode.entries.getOrElse(
                kv.decodeInt("player_play_mode", PlayMode.SHUFFLE_OFF.ordinal)
            ) { PlayMode.SHUFFLE_OFF }
            set(value) { kv.encode("player_play_mode", value.ordinal) }

        var volume: Float
            get() = kv.decodeFloat("player_volume", 1.0f)
            set(value) { kv.encode("player_volume", value.coerceIn(0f, 1f)) }

        var playQueueJson: String
            get() = kv.decodeString("player_queue_json", "") ?: ""
            set(value) { kv.encode("player_queue_json", value) }

        var currentQueueIndex: Int
            get() = kv.decodeInt("player_queue_index", -1)
            set(value) { kv.encode("player_queue_index", value) }

        /** 完整队列AudioFile数据（JSON），用于重启后恢复播放队列 */
        var playQueueSongsJson: String
            get() = kv.decodeString("player_queue_songs_json", "") ?: ""
            set(value) { kv.encode("player_queue_songs_json", value) }

        var crossfadeDuration: Int
            get() = kv.decodeInt("player_crossfade", 0)
            set(value) { kv.encode("player_crossfade", value) }

        /** 音频输出模式：OpenSL ES / AAudio / Direct HiRes */
        var audioOutputMode: AudioOutputMode
            get() = AudioOutputMode.entries.getOrElse(
                kv.decodeInt("player_audio_output_mode", AudioOutputMode.AAUDIO.ordinal)
            ) { AudioOutputMode.AAUDIO }
            set(value) { kv.encode("player_audio_output_mode", value.ordinal) }

        /** 目标采样率（Hz），0 表示跟随音源 */
        var targetSampleRate: Int
            get() = kv.decodeInt("player_target_sample_rate", 0)
            set(value) { kv.encode("player_target_sample_rate", value) }

        /** 目标比特深度（0=自动, 16, 24, 32） */
        var targetBitDepth: Int
            get() = kv.decodeInt("player_target_bit_depth", 0)
            set(value) { kv.encode("player_target_bit_depth", value) }

        /** 是否启用回放增益（ReplayGain） */
        var replayGainEnabled: Boolean
            get() = kv.decodeBool("player_replay_gain_enabled", false)
            set(value) { kv.encode("player_replay_gain_enabled", value) }

        /** 回放增益模式：0=关闭, 1=音轨, 2=专辑 */
        var replayGainMode: Int
            get() = kv.decodeInt("player_replay_gain_mode", 1)
            set(value) { kv.encode("player_replay_gain_mode", value.coerceIn(0, 2)) }

        var volumeNormalizationEnabled: Boolean
            get() = kv.decodeBool("player_volume_normalization", false)
            set(value) { kv.encode("player_volume_normalization", value) }

        var gaplessPlaybackEnabled: Boolean
            get() = kv.decodeBool("player_gapless", true)
            set(value) { kv.encode("player_gapless", value) }

        var sleepTimerMode: Int
            get() = kv.decodeInt("player_sleep_timer_mode", 0)
            set(value) { kv.encode("player_sleep_timer_mode", value) }

        var sleepTimerMinutes: Int
            get() = kv.decodeInt("player_sleep_timer_minutes", 30)
            set(value) { kv.encode("player_sleep_timer_minutes", value) }

        var stopAfterCurrent: Boolean
            get() = kv.decodeBool("player_stop_after_current", false)
            set(value) { kv.encode("player_stop_after_current", value) }

        /** USB DAC Bit-perfect 模式：不改 PCM，不做软件音量，不碰 Feature Unit */
        var bitPerfectEnabled: Boolean
            get() = kv.decodeBool("player_bit_perfect", false)
            set(value) { kv.encode("player_bit_perfect", value) }

        /** USB DAC 硬件 Feature Unit 控制（实验性）：默认关闭，避免某些 DAC 左右声道硬件音量异常 */
        var hardwareFeatureUnitEnabled: Boolean
            get() = kv.decodeBool("player_hw_feature_unit", false)
            set(value) { kv.encode("player_hw_feature_unit", value) }

        /** USB DAC 硬件音量线性值（0..1），用于实体键控制后持久化恢复 */
        var usbHardwareVolume: Float
            get() = kv.decodeFloat("player_usb_hw_volume", 0.8f)
            set(value) { kv.encode("player_usb_hw_volume", value.coerceIn(0f, 1f)) }

        // ========== USB DAC 高级设置（参考 Neutron Player） ==========

        /** 跳过 AudioControl interface（不操作 Feature Unit）— 参考 Neutron USBNoCIface */
        var usbNoControlInterface: Boolean
            get() = kv.decodeBool("usb_no_ci", false)
            set(value) { kv.encode("usb_no_ci", value) }

        /** 强制 UAC1 协议（绕过 UAC2 时钟控制问题） — 参考 Neutron USBForceUac1 */
        var usbForceUac1: Boolean
            get() = kv.decodeBool("usb_force_uac1", false)
            set(value) { kv.encode("usb_force_uac1", value) }

        /** 线性音量曲线（避免对数曲线的精度损失） — 参考 Neutron USBLinearVolume */
        var usbLinearVolume: Boolean
            get() = kv.decodeBool("usb_linear_volume", false)
            set(value) { kv.encode("usb_linear_volume", value) }

        /** 用硬件音量替代软件音量 — 参考 Neutron USBReplaceVolume */
        var usbReplaceVolume: Boolean
            get() = kv.decodeBool("usb_replace_vol", false)
            set(value) { kv.encode("usb_replace_vol", value) }

        /** 强制 1ms 包间隔 — 参考 Neutron USBForce1MsPacket */
        var usbForce1MsPacket: Boolean
            get() = kv.decodeBool("usb_force_1ms", false)
            set(value) { kv.encode("usb_force_1ms", value) }
    }

    object Sort {
        var songSortOrder: SortOrder
            get() = SortOrder.entries.getOrElse(
                kv.decodeInt("sort_songs", SortOrder.TITLE_ASC.ordinal)
            ) { SortOrder.TITLE_ASC }
            set(value) { kv.encode("sort_songs", value.ordinal) }

        var artistSortOrder: SortOrder
            get() = SortOrder.entries.getOrElse(
                kv.decodeInt("sort_artists", SortOrder.ARTIST_ASC.ordinal)
            ) { SortOrder.ARTIST_ASC }
            set(value) { kv.encode("sort_artists", value.ordinal) }

        var albumSortOrder: SortOrder
            get() = SortOrder.entries.getOrElse(
                kv.decodeInt("sort_albums", SortOrder.ALBUM_ASC.ordinal)
            ) { SortOrder.ALBUM_ASC }
            set(value) { kv.encode("sort_albums", value.ordinal) }
    }

    object UI {
        var themeMode: Int
            get() = kv.decodeInt("ui_theme_mode", 0)
            set(value) { kv.encode("ui_theme_mode", value) }

        var accentColor: Int
            get() = kv.decodeInt("ui_accent_color", 0)
            set(value) { kv.encode("ui_accent_color", value) }

        var isBlurEnabled: Boolean
            get() = kv.decodeBool("ui_blur_enabled", true)
            set(value) { kv.encode("ui_blur_enabled", value) }

        var blurRadius: Int
            get() = kv.decodeInt("ui_blur_radius", 20)
            set(value) { kv.encode("ui_blur_radius", value) }

        var isBottomBarEnabled: Boolean
            get() = kv.decodeBool("ui_bottom_bar_enabled", true)
            set(value) { kv.encode("ui_bottom_bar_enabled", value) }

        var scanPaths: List<String>
            get() {
                val json = kv.decodeString("ui_scan_paths", "") ?: ""
                if (json.isBlank()) return emptyList()
                return try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(json, type)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            set(value) { kv.encode("ui_scan_paths", gson.toJson(value)) }

        /** 用户添加的原始根目录（用于重建目录树，保留父子关系） */
        var rootScanPaths: List<String>
            get() {
                val json = kv.decodeString("ui_root_scan_paths", "") ?: ""
                if (json.isBlank()) return emptyList()
                return try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(json, type)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            set(value) { kv.encode("ui_root_scan_paths", gson.toJson(value)) }

        var lastSelectedFolderPath: String
            get() = kv.decodeString("ui_last_selected_folder", "") ?: ""
            set(value) { kv.encode("ui_last_selected_folder", value) }

        var lastScanTime: Long
            get() = kv.decodeLong("ui_last_scan_time", 0)
            set(value) { kv.encode("ui_last_scan_time", value) }

        /** 应用版本号，用于覆盖安装后检测版本变化并触发重扫 */
        var appVersion: String
            get() = kv.decodeString("ui_app_version", "") ?: ""
            set(value) { kv.encode("ui_app_version", value) }

        var customFontPath: String
            get() = kv.decodeString("ui_custom_font_path", "") ?: ""
            set(value) { kv.encode("ui_custom_font_path", value) }

        var fontWeight: Int
            get() = kv.decodeInt("ui_font_weight_v2", 400)
            set(value) { kv.encode("ui_font_weight_v2", value) }

        var fontItalic: Boolean
            get() = kv.decodeBool("ui_font_italic_v2", false)
            set(value) { kv.encode("ui_font_italic_v2", value) }

        var fontSizeScale: Int
            get() = kv.decodeInt("ui_font_size_scale", 100)
            set(value) { kv.encode("ui_font_size_scale", value) }

        var isImmersiveEnabled: Boolean
            get() = kv.decodeBool("ui_immersive_enabled", true)
            set(value) { kv.encode("ui_immersive_enabled", value) }

        var isMiniCoverEnabled: Boolean
            get() = kv.decodeBool("ui_mini_cover_enabled", true)
            set(value) { kv.encode("ui_mini_cover_enabled", value) }

        var isPlayPageMemoryEnabled: Boolean
            get() = kv.decodeBool("ui_play_page_memory_enabled", true)
            set(value) { kv.encode("ui_play_page_memory_enabled", value) }

        /** 信笺模式：弧形轮播+单行歌词飞入+音频可视化 */
        var isLetterModeEnabled: Boolean
            get() = kv.decodeBool("ui_letter_mode_enabled", false)
            set(value) { kv.encode("ui_letter_mode_enabled", value) }

        /** 关闭流动光效果 */
        var isFlowingLightDisabled: Boolean
            get() = kv.decodeBool("ui_flowing_light_disabled", false)
            set(value) { kv.encode("ui_flowing_light_disabled", value) }

        var lastScene: String
            get() = kv.decodeString("ui_last_scene", "MAIN") ?: "MAIN"
            set(value) { kv.encode("ui_last_scene", value) }
    }

    object Equalizer {
        var isEnabled: Boolean
            get() = kv.decodeBool("eq_enabled", false)
            set(value) { kv.encode("eq_enabled", value) }

        var currentPresetId: Long
            get() = kv.decodeLong("eq_preset_id", -1)
            set(value) { kv.encode("eq_preset_id", value) }

        var bandLevels: List<Int>
            get() {
                val json = kv.decodeString("eq_band_levels", "") ?: ""
                if (json.isBlank()) return emptyList()
                return try {
                    val type = object : TypeToken<List<Int>>() {}.type
                    gson.fromJson(json, type)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            set(value) { kv.encode("eq_band_levels", gson.toJson(value)) }

        var bassBoost: Int
            get() = kv.decodeInt("eq_bass_boost", 0)
            set(value) { kv.encode("eq_bass_boost", value) }

        var virtualizer: Int
            get() = kv.decodeInt("eq_virtualizer", 0)
            set(value) { kv.encode("eq_virtualizer", value) }

        var channelBalance: Float
            get() = kv.decodeFloat("eq_channel_balance", 0.5f)
            set(value) { kv.encode("eq_channel_balance", value.coerceIn(0f, 1f)) }

        var loudnessEnhance: Int
            get() = kv.decodeInt("eq_loudness", 0)
            set(value) { kv.encode("eq_loudness", value) }

        // ========== 互馈 (Crossfeed) ==========
        var crossfeedEnabled: Boolean
            get() = kv.decodeBool("eq_crossfeed_enabled", false)
            set(value) { kv.encode("eq_crossfeed_enabled", value) }

        /** 高通截止频率 (Hz)，默认 300 */
        var crossfeedLowCut: Int
            get() = kv.decodeInt("eq_crossfeed_low_cut", 300)
            set(value) { kv.encode("eq_crossfeed_low_cut", value) }

        /** 低通截止频率 (Hz)，默认 2000 */
        var crossfeedHighCut: Int
            get() = kv.decodeInt("eq_crossfeed_high_cut", 2000)
            set(value) { kv.encode("eq_crossfeed_high_cut", value) }

        /** 衰减量 (dB * 10)，默认 60 (即 6.0dB) */
        var crossfeedAttenuation: Int
            get() = kv.decodeInt("eq_crossfeed_attenuation", 60)
            set(value) { kv.encode("eq_crossfeed_attenuation", value) }
    }

    object Lyrics {
        var tickerEnabled: Boolean
            get() = kv.decodeBool("lyrics_ticker_enabled", false)
            set(value) { kv.encode("lyrics_ticker_enabled", value) }

        var tickerHideNotification: Boolean
            get() = kv.decodeBool("lyrics_ticker_hide_notification", false)
            set(value) { kv.encode("lyrics_ticker_hide_notification", value) }

        var samsungFloatingLyricTranslation: Boolean
            get() = kv.decodeBool("lyrics_samsung_floating_translation", false)
            set(value) { kv.encode("lyrics_samsung_floating_translation", value) }

        var lyricGetterEnabled: Boolean
            get() = kv.decodeBool("lyrics_lyric_getter_enabled", false)
            set(value) { kv.encode("lyrics_lyric_getter_enabled", value) }

        var bluetoothLyricEnabled: Boolean
            get() = kv.decodeBool("lyrics_bluetooth_enabled", false)
            set(value) { kv.encode("lyrics_bluetooth_enabled", value) }

        var bluetoothLyricTranslation: Boolean
            get() = kv.decodeBool("lyrics_bluetooth_translation", false)
            set(value) { kv.encode("lyrics_bluetooth_translation", value) }

        var latencyOffset: Int
            get() = kv.decodeInt("lyrics_latency_offset", 0)
            set(value) { kv.encode("lyrics_latency_offset", value) }
    }

    object Lyricon {
        var enabled: Boolean
            get() = kv.decodeBool("lyricon_enabled", false)
            set(value) { kv.encode("lyricon_enabled", value) }

        var displayTranslation: Boolean
            get() = kv.decodeBool("lyricon_display_translation", true)
            set(value) { kv.encode("lyricon_display_translation", value) }

        var displayRoma: Boolean
            get() = kv.decodeBool("lyricon_display_roma", false)
            set(value) { kv.encode("lyricon_display_roma", value) }
    }

    object LyricFont {
        var fontName: String
            get() = kv.decodeString("lyric_font_name", "") ?: ""
            set(value) { kv.encode("lyric_font_name", value) }

        var fontPath: String
            get() = kv.decodeString("lyric_font_path", "") ?: ""
            set(value) { kv.encode("lyric_font_path", value) }

        var fontWeight: Int
            get() = kv.decodeInt("lyric_font_weight", 800).coerceIn(100, 900)
            set(value) { kv.encode("lyric_font_weight", value.coerceIn(100, 900)) }

        var fontScale: Int
            get() = kv.decodeInt("lyric_font_scale", 100).coerceIn(75, 130)
            set(value) { kv.encode("lyric_font_scale", value.coerceIn(75, 130)) }
    }

    object PEQ {
        var isEnabled: Boolean
            get() = kv.decodeBool("peq_enabled", false)
            set(value) { kv.encode("peq_enabled", value) }

        var filtersJson: String
            get() = kv.decodeString("peq_filters_json", "") ?: ""
            set(value) { kv.encode("peq_filters_json", value) }

        var preamp: Float
            get() = kv.decodeFloat("peq_preamp", 0f)
            set(value) { kv.encode("peq_preamp", value) }

        var presetName: String
            get() = kv.decodeString("peq_preset_name", "自定义") ?: "自定义"
            set(value) { kv.encode("peq_preset_name", value) }
    }

    object WebDav {
        var url: String
            get() = kv.decodeString("webdav_url", "") ?: ""
            set(value) { kv.encode("webdav_url", value) }

        var username: String
            get() = kv.decodeString("webdav_username", "") ?: ""
            set(value) { kv.encode("webdav_username", value) }

        var password: String
            get() = kv.decodeString("webdav_password", "") ?: ""
            set(value) { kv.encode("webdav_password", value) }

        var lastUrl: String
            get() = kv.decodeString("webdav_last_url", "") ?: ""
            set(value) { kv.encode("webdav_last_url", value) }

        var authMode: Int
            get() = kv.decodeInt("webdav_auth_mode", 0)
            set(value) { kv.encode("webdav_auth_mode", value) }
    }
}
