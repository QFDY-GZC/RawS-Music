package com.rawsmusic.module.data.prefs

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.model.SortOrder
import com.tencent.mmkv.MMKV

object AppPreferences {

    private val kv by lazy { MMKV.defaultMMKV() }
    val storage: MMKV get() = kv
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

        var lastPlayStateOrdinal: Int
            get() = kv.decodeInt("player_last_play_state", PlayState.IDLE.ordinal)
            set(value) { kv.encode("player_last_play_state", value) }

        var lastUsbExclusiveActive: Boolean
            get() = kv.decodeBool("player_last_usb_exclusive_active", false)
            set(value) { kv.encode("player_last_usb_exclusive_active", value) }

        /** 是否保存并恢复上次音轨进度 */
        var trackProgressMemoryEnabled: Boolean
            get() = kv.decodeBool("player_track_progress_memory_enabled", true)
            set(value) { kv.encode("player_track_progress_memory_enabled", value) }

        /** 播放次数计入阈值：音轨播放到至少 N% 后才计入播放次数。 */
        var playCountThresholdPercent: Int
            get() = kv.decodeInt("player_play_count_threshold_percent", 60).coerceIn(1, 100)
            set(value) { kv.encode("player_play_count_threshold_percent", value.coerceIn(1, 100)) }

        /** 是否启用播放次数统计 */
        var playCountEnabled: Boolean
            get() = kv.decodeBool("player_play_count_enabled", true)
            set(value) { kv.encode("player_play_count_enabled", value) }

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
                kv.decodeInt("player_play_mode", PlayMode.SEQUENTIAL.ordinal)
            ) { PlayMode.SEQUENTIAL }
            set(value) { kv.encode("player_play_mode", value.ordinal) }

        /** shuffle 前的原始队列顺序（JSON），用于关闭 shuffle 时恢复 */
        var originalQueueSongsJson: String
            get() = kv.decodeString("player_original_queue_json", "") ?: ""
            set(value) { kv.encode("player_original_queue_json", value) }

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

        /** 目标比特深度/输出格式（0=自动, 16, 24, 32, 3201=Float32, 3224=32(8.24)） */
        var targetBitDepth: Int
            get() = kv.decodeInt("player_target_bit_depth", 0)
            set(value) {
                val normalized = when (value) {
                    0, 16, 24, 32, 3201, 3224 -> value
                    else -> 0
                }
                kv.encode("player_target_bit_depth", normalized)
            }

        /** USB DAC 独占模式 PCM 重采样目标采样率（Hz），0 表示跟随音源/自动 */
        var usbTargetSampleRate: Int
            get() = kv.decodeInt("player_usb_target_sample_rate", 0)
            set(value) { kv.encode("player_usb_target_sample_rate", value) }

        /** USB DAC 独占模式 PCM 重采样目标位深/输出格式（0=自动, 16, 24, 32, 3201=Float32, 3224=32(8.24)） */
        var usbTargetBitDepth: Int
            get() = kv.decodeInt("player_usb_target_bit_depth", 0)
            set(value) {
                val normalized = when (value) {
                    0, 16, 24, 32, 3201, 3224 -> value
                    else -> 0
                }
                kv.encode("player_usb_target_bit_depth", normalized)
            }

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

        /** USB DAC 硬件音量步进值（0..60），1 step = 1 dB，用于 MediaSession VolumeProvider */
        var usbHardwareVolumeStep: Int
            get() = kv.decodeInt("player_usb_hw_volume_step", 48)
            set(value) { kv.encode("player_usb_hw_volume_step", value.coerceIn(0, 60)) }

        // ========== USB DAC 高级设置 ==========

        /** 跳过 AudioControl interface（不操作 Feature Unit） */
        var usbNoControlInterface: Boolean
            get() = kv.decodeBool("usb_no_ci", false)
            set(value) { kv.encode("usb_no_ci", value) }

        /** 强制 UAC1 协议（绕过 UAC2 时钟控制问题） */
        var usbForceUac1: Boolean
            get() = kv.decodeBool("usb_force_uac1", false)
            set(value) { kv.encode("usb_force_uac1", value) }

        /** 线性音量曲线（避免对数曲线的精度损失） */
        var usbLinearVolume: Boolean
            get() = kv.decodeBool("usb_linear_volume", false)
            set(value) { kv.encode("usb_linear_volume", value) }

        /** 用硬件音量替代软件音量 */
        var usbReplaceVolume: Boolean
            get() = kv.decodeBool("usb_replace_vol", false)
            set(value) { kv.encode("usb_replace_vol", value) }

        /** 强制 1ms 包间隔 */
        var usbForce1MsPacket: Boolean
            get() = kv.decodeBool("usb_force_1ms", false)
            set(value) { kv.encode("usb_force_1ms", value) }

        /** USB 独占兼容模式：优先选择更稳定的 44.1/48kHz、16/24bit 输出 */
        var usbSafeExclusiveMode: Boolean
            get() = kv.decodeBool("usb_safe_exclusive_mode", true)
            set(value) { kv.encode("usb_safe_exclusive_mode", value) }

        /** USB PCM 输出模式：0=自动 1=16bit 2=packed24 3=24in32 4=32bit */
        var usbPcmOutputMode: Int
            get() = kv.decodeInt("player_usb_pcm_output_mode", 0).coerceIn(0, 4)
            set(value) { kv.encode("player_usb_pcm_output_mode", value.coerceIn(0, 4)) }

        // ========== PCM→DSD 转换 ==========

        /** 启用 PCM→DSD 实时转换 */
        var dsdConversionEnabled: Boolean
            get() = kv.decodeBool("dsd_conversion_enabled", false)
            set(value) { kv.encode("dsd_conversion_enabled", value) }

        /** DSD 输出倍率：64=DSD64, 128=DSD128, 256=DSD256, 512=DSD512 */
        var dsdRate: Int
            get() = kv.decodeInt("dsd_rate", 64)
            set(value) {
                val valid = if (value in listOf(64, 128, 256, 512)) value else 64
                kv.encode("dsd_rate", valid)
            }

        /** 转换算法类型：0=Standard, 1=HighQuality, 2=LowLatency */
        var dsdConversionType: Int
            get() = kv.decodeInt("dsd_conversion_type", 2)
            set(value) { kv.encode("dsd_conversion_type", value.coerceIn(0, 2)) }

        // ========== Safe Core 偏好 ==========

        /** USB Safe Core 模式：禁用 VID/PID 硬编码、HID、DSD，只走标准 UAC 路径 */
        var usbSafeCoreMode: Boolean
            get() = kv.decodeBool("usb_safe_core_mode", true)
            set(value) { kv.encode("usb_safe_core_mode", value) }

        /** USB HID 遥控启用（Safe Core 下默认 false） */
        var usbHidRemoteEnabled: Boolean
            get() = kv.decodeBool("usb_hid_remote_enabled", false)
            set(value) { kv.encode("usb_hid_remote_enabled", value) }

        /** 启用三角抖动（Triangular Dither） */
        var dsdDitherEnabled: Boolean
            get() = kv.decodeBool("dsd_dither_enabled", false)
            set(value) { kv.encode("dsd_dither_enabled", value) }

        /** USB DSD 传输方式：0=DoP, 1=Native DSD */
        var usbDsdTransportMode: Int
            get() {
                val persisted = kv.decodeInt("usb_dsd_transport_mode", -1)
                if (persisted in 0..1) return persisted
                return if (kv.decodeBool("dsd_dop_enabled", false)) 0 else 1
            }
            set(value) {
                val normalized = value.coerceIn(0, 1)
                kv.encode("usb_dsd_transport_mode", normalized)
                kv.encode("dsd_dop_enabled", normalized == 0)
            }

        /** 启用 DoP（DSD over PCM）封装输出 */
        var dsdDopEnabled: Boolean
            get() = usbDsdTransportMode == 0
            set(value) { usbDsdTransportMode = if (value) 0 else 1 }

        // ========== 蓝牙 SCO 通话信道输出 ==========

        /** 蓝牙 SCO 模式：0=关闭, 1=自动检测, 2=强制开启 */
        var bluetoothScoMode: Int
            get() = kv.decodeInt("bt_sco_mode", 1)  // 默认自动检测
            set(value) { kv.encode("bt_sco_mode", value.coerceIn(0, 2)) }

        /** SCO 模式下是否降采样到 16kHz（兼容性更好，音质更低） */
        var bluetoothScoDownsample: Boolean
            get() = kv.decodeBool("bt_sco_downsample", true)
            set(value) { kv.encode("bt_sco_downsample", value) }
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
            get() = kv.decodeInt("ui_theme_mode", 1)  // 默认亮色主题
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
            get() = kv.decodeBool("ui_immersive_enabled", false)
            set(value) { kv.encode("ui_immersive_enabled", value) }

        var isMiniCoverEnabled: Boolean
            get() = kv.decodeBool("ui_mini_cover_enabled", true)
            set(value) { kv.encode("ui_mini_cover_enabled", value) }

        var isPlayPageMemoryEnabled: Boolean
            get() = kv.decodeBool("ui_play_page_memory_enabled", true)
            set(value) { kv.encode("ui_play_page_memory_enabled", value) }

        /** 音频可视化：播放界面底部显示音频频谱动画 */
        var isAudioVisualizerEnabled: Boolean
            get() = kv.decodeBool("ui_audio_visualizer_enabled", false)
            set(value) { kv.encode("ui_audio_visualizer_enabled", value) }

        /** 关闭流动光效果 */
        var isFlowingLightDisabled: Boolean
            get() = kv.decodeBool("ui_flowing_light_disabled", false)
            set(value) { kv.encode("ui_flowing_light_disabled", value) }

        /** 默认背景：亮色模式白底黑字，暗色模式纯黑底白字 */
        var isDefaultBackgroundEnabled: Boolean
            get() = kv.decodeBool("ui_default_bg_enabled", false)
            set(value) { kv.encode("ui_default_bg_enabled", value) }

        var lastScene: String
            get() = kv.decodeString("ui_last_scene", "MAIN") ?: "MAIN"
            set(value) { kv.encode("ui_last_scene", value) }

        var wasInFragmentMode: Boolean
            get() = kv.decodeBool("ui_was_in_fragment_mode", false)
            set(value) { kv.encode("ui_was_in_fragment_mode", value) }

        var lastFragmentDest: Int
            get() = kv.decodeInt("ui_last_fragment_dest", -1)
            set(value) { kv.encode("ui_last_fragment_dest", value) }

        // 卡片取色持久化
        var cardColorPrimary: Int
            get() = kv.decodeInt("ui_card_color_primary", 0)
            set(value) { kv.encode("ui_card_color_primary", value) }

        var cardColorDark: Int
            get() = kv.decodeInt("ui_card_color_dark", 0)
            set(value) { kv.encode("ui_card_color_dark", value) }

        var cardColorLyricBg: Int
            get() = kv.decodeInt("ui_card_color_lyric_bg", 0)
            set(value) { kv.encode("ui_card_color_lyric_bg", value) }

        var cardColorSongPath: String
            get() = kv.decodeString("ui_card_color_song_path", "") ?: ""
            set(value) { kv.encode("ui_card_color_song_path", value) }
    }

    /** 媒体库扫描与统计偏好 */
    object Scanner {
        /** 传统文件访问方式：不只依赖 MediaStore，允许从外部存储目录递归扫描。 */
        var legacyFileAccessEnabled: Boolean
            get() = kv.decodeBool("scan_legacy_file_access", false)
            set(value) { kv.encode("scan_legacy_file_access", value) }

        /** 过滤时长过短的曲目。0=从不忽略；1..60=忽略小于 N 秒的音轨。 */
        var minTrackDurationSeconds: Int
            get() = kv.decodeInt("scan_min_track_duration_seconds", 0).coerceIn(0, 60)
            set(value) { kv.encode("scan_min_track_duration_seconds", value.coerceIn(0, 60)) }

        /** 忽略视频容器/视频格式，避免 MP4/MKV/WEBM 等误入音乐库。 */
        var ignoreVideoFormats: Boolean
            get() = kv.decodeBool("scan_ignore_video_formats", true)
            set(value) { kv.encode("scan_ignore_video_formats", value) }

        /** 用户手动选择的 SAF 音乐文件夹 URI 集合 */
        private const val KEY_MUSIC_FOLDER_URIS = "scanner_music_folder_uris"

        var musicFolderUris: Set<String>
            get() {
                val raw = kv.decodeString(KEY_MUSIC_FOLDER_URIS, "").orEmpty()
                if (raw.isBlank()) return emptySet()
                return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            set(value) { kv.encode(KEY_MUSIC_FOLDER_URIS, value.joinToString("\n")) }

        fun addMusicFolderUri(uri: String) {
            musicFolderUris = musicFolderUris + uri
        }

        fun removeMusicFolderUri(uri: String) {
            musicFolderUris = musicFolderUris - uri
        }

        fun clearMusicFolderUris() {
            musicFolderUris = emptySet()
        }
    }

    /** 专辑图偏好设置 */
    object AlbumArt {
        /** 强制使用 ARGB_8888（24位RGB+Alpha） */
        var forceArgb8888: Boolean
            get() = kv.decodeBool("aa_8888", true)
            set(value) { kv.encode("aa_8888", value) }

        /** 使用更高分辨率封面 */
        var useHigherRes: Boolean
            get() = kv.decodeBool("aa_higher_res", true)
            set(value) { kv.encode("aa_higher_res", value) }

        /** 下载高清封面（在线搜索时） */
        var downloadHd: Boolean
            get() = kv.decodeBool("aa_download_hd", true)
            set(value) { kv.encode("aa_download_hd", value) }

        /** 始终显示封面（不使用默认图） */
        var alwaysShowCover: Boolean
            get() = kv.decodeBool("aa_always", true)
            set(value) { kv.encode("aa_always", value) }

        /** 封面切换动画 */
        var coverAnimation: Boolean
            get() = kv.decodeBool("aa_anim", true)
            set(value) { kv.encode("aa_anim", value) }
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
            get() = kv.decodeBool("lyrics_ticker_enabled", true)
            set(value) { kv.encode("lyrics_ticker_enabled", value) }

        var tickerHideNotification: Boolean
            get() = kv.decodeBool("lyrics_ticker_hide_notification", false)
            set(value) { kv.encode("lyrics_ticker_hide_notification", value) }

        var tickerHeadsUpLyrics: Boolean
            get() = kv.decodeBool("lyrics_ticker_heads_up", false)
            set(value) { kv.encode("lyrics_ticker_heads_up", value) }

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
            get() = kv.decodeFloat("peq_preamp", 0f).coerceIn(-12f, 12f)
            set(value) { kv.encode("peq_preamp", value.coerceIn(-12f, 12f)) }

        var presetName: String
            get() = kv.decodeString("peq_preset_name", "自定义") ?: "自定义"
            set(value) { kv.encode("peq_preset_name", value) }

        var bandCount: Int
            get() = kv.decodeInt("peq_band_count", 10).coerceIn(10, 40)
            set(value) { kv.encode("peq_band_count", value.coerceIn(10, 40)) }
    }

    object Compressor {
        var isEnabled: Boolean
            get() = kv.decodeBool("compressor_enabled", false)
            set(value) { kv.encode("compressor_enabled", value) }

        /** 阈值 (dB)，范围 -60 ~ 0 */
        var thresholdDB: Float
            get() = kv.decodeFloat("compressor_threshold", -20f)
            set(value) { kv.encode("compressor_threshold", value.coerceIn(-60f, 0f)) }

        /** 压缩比，范围 1 ~ 20 */
        var ratio: Float
            get() = kv.decodeFloat("compressor_ratio", 4f)
            set(value) { kv.encode("compressor_ratio", value.coerceIn(1f, 20f)) }

        /** 启动时间 (ms)，范围 0.1 ~ 100 */
        var attackMs: Float
            get() = kv.decodeFloat("compressor_attack", 10f)
            set(value) { kv.encode("compressor_attack", value.coerceIn(0.1f, 100f)) }

        /** 释放时间 (ms)，范围 10 ~ 1000 */
        var releaseMs: Float
            get() = kv.decodeFloat("compressor_release", 200f)
            set(value) { kv.encode("compressor_release", value.coerceIn(10f, 1000f)) }

        /** 补偿增益 (dB)，范围 0 ~ 24 */
        var makeupGainDB: Float
            get() = kv.decodeFloat("compressor_makeup", 0f)
            set(value) { kv.encode("compressor_makeup", value.coerceIn(0f, 24f)) }

        /** 拐点宽度 (dB)，范围 0 ~ 30 */
        var kneeWidthDB: Float
            get() = kv.decodeFloat("compressor_knee", 6f)
            set(value) { kv.encode("compressor_knee", value.coerceIn(0f, 30f)) }

        /** 检测模式: 0=Peak, 1=RMS */
        var detectionMode: Int
            get() = kv.decodeInt("compressor_detection", 1)
            set(value) { kv.encode("compressor_detection", value.coerceIn(0, 1)) }
    }

    object BassBoost {
        var isEnabled: Boolean
            get() = kv.decodeBool("bass_boost_enabled", false)
            set(value) { kv.encode("bass_boost_enabled", value) }

        /** 增益 (dB)，范围 -12 ~ +12 */
        var gainDB: Float
            get() = kv.decodeFloat("bass_boost_gain", 0f)
            set(value) { kv.encode("bass_boost_gain", value.coerceIn(-12f, 12f)) }

        /** 转折频率 (Hz)，范围 50 ~ 500 */
        var frequency: Float
            get() = kv.decodeFloat("bass_boost_freq", 100f)
            set(value) { kv.encode("bass_boost_freq", value.coerceIn(50f, 500f)) }
    }

    object TrebleBoost {
        var isEnabled: Boolean
            get() = kv.decodeBool("treble_boost_enabled", false)
            set(value) { kv.encode("treble_boost_enabled", value) }

        /** 增益 (dB)，范围 -12 ~ +12 */
        var gainDB: Float
            get() = kv.decodeFloat("treble_boost_gain", 0f)
            set(value) { kv.encode("treble_boost_gain", value.coerceIn(-12f, 12f)) }

        /** 转折频率 (Hz)，范围 2000 ~ 16000 */
        var frequency: Float
            get() = kv.decodeFloat("treble_boost_freq", 8000f)
            set(value) { kv.encode("treble_boost_freq", value.coerceIn(2000f, 16000f)) }
    }

    object Surround360 {
        var isEnabled: Boolean
            get() = kv.decodeBool("surround_360_enabled", false)
            set(value) { kv.encode("surround_360_enabled", value) }

        /** 效果强度 (0~100) */
        var intensity: Float
            get() = kv.decodeFloat("surround_360_intensity", 50f)
            set(value) { kv.encode("surround_360_intensity", value.coerceIn(0f, 100f)) }

        /** 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左 */
        var azimuthDeg: Float
            get() = kv.decodeFloat("surround_360_azimuth", 0f)
            set(value) { kv.encode("surround_360_azimuth", value.coerceIn(0f, 360f)) }

        /** 自动旋转速度 (度/秒), 0=静止 */
        var rotationSpeed: Float
            get() = kv.decodeFloat("surround_360_rotation_speed", 30f)
            set(value) { kv.encode("surround_360_rotation_speed", value.coerceIn(0f, 360f)) }
    }

    object Panoramic360 {
        var isEnabled: Boolean
            get() = kv.decodeBool("panoramic_360_enabled", false)
            set(value) { kv.encode("panoramic_360_enabled", value) }

        /** 效果强度 (0~100) */
        var intensity: Float
            get() = kv.decodeFloat("panoramic_360_intensity", 50f)
            set(value) { kv.encode("panoramic_360_intensity", value.coerceIn(0f, 100f)) }

        /** 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左 */
        var azimuthDeg: Float
            get() = kv.decodeFloat("panoramic_360_azimuth", 0f)
            set(value) { kv.encode("panoramic_360_azimuth", value.coerceIn(0f, 360f)) }

        /** 仰角 (-90~+90°), 正=上方, 负=下方 */
        var elevationDeg: Float
            get() = kv.decodeFloat("panoramic_360_elevation", 0f)
            set(value) { kv.encode("panoramic_360_elevation", value.coerceIn(-90f, 90f)) }
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
