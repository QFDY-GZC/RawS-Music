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

        var sleepTimerSongs: Int
            get() = kv.decodeInt("player_sleep_timer_songs", 3).coerceIn(1, 99)
            set(value) { kv.encode("player_sleep_timer_songs", value.coerceIn(1, 99)) }

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

        /** USB DAC 音量模式：0=软件音量, 1=硬件音量, 2=数字固定 0dB */
        var usbVolumeMode: Int
            get() = kv.decodeInt("player_usb_volume_mode", 0).coerceIn(0, 2)
            set(value) { kv.encode("player_usb_volume_mode", value.coerceIn(0, 2)) }

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

        /** 禁用 DAC 时钟信息/时钟设置：仅故障排查使用 */
        var usbDisableDacClockInfo: Boolean
            get() = kv.decodeBool("usb_disable_dac_clock_info", false)
            set(value) { kv.encode("usb_disable_dac_clock_info", value) }

        /** 回放后释放 USB 带宽：暂停时把音频流接口降回 Alt 0 */
        var usbReleaseBandwidthAfterPlayback: Boolean
            get() = kv.decodeBool("usb_release_bandwidth_after_playback", false)
            set(value) { kv.encode("usb_release_bandwidth_after_playback", value) }

        /** DAC 预热事件：native init 后、开始写入音频前等待的毫秒数 */
        var usbDacPreheatMs: Int
            get() = kv.decodeInt("usb_dac_preheat_ms", 0).let { value ->
                if (value in listOf(0, 100, 200, 300, 400, 500, 800, 1000, 1500, 2000, 2500)) value else 0
            }
            set(value) {
                val normalized = if (value in listOf(0, 100, 200, 300, 400, 500, 800, 1000, 1500, 2000, 2500)) value else 0
                kv.encode("usb_dac_preheat_ms", normalized)
            }

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

        /** USB DSD 源输出方式：0=DoP, 1=Native DSD, 2=PCM 解码输出 */
        var usbDsdTransportMode: Int
            get() {
                val persisted = kv.decodeInt("usb_dsd_transport_mode", -1)
                if (persisted in 0..2) return persisted
                return if (kv.decodeBool("dsd_dop_enabled", false)) 0 else 1
            }
            set(value) {
                val normalized = value.coerceIn(0, 2)
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

        var lastUpdateNotesVersionCode: Long
            get() = kv.decodeLong("ui_update_notes_version_code", -1L)
            set(value) { kv.encode("ui_update_notes_version_code", value) }

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

        /** 普通播放页专辑图切换动画：0=透视切换，1=内倾轮播，2=平移。 */
        var playerArtworkAnimationStyle: Int
            get() = kv.decodeInt("ui_player_artwork_animation_style", 0).coerceIn(0, 2)
            set(value) { kv.encode("ui_player_artwork_animation_style", value.coerceIn(0, 2)) }

        /** 歌词页主歌词字号（sp）。 */
        var lyricFontSizeSp: Int
            get() = kv.decodeInt("ui_lyric_font_size_sp", 28).let { value ->
                if (value in listOf(24, 28, 32, 36, 40)) value else 28
            }
            set(value) {
                val normalized = if (value in listOf(24, 28, 32, 36, 40)) value else 28
                kv.encode("ui_lyric_font_size_sp", normalized)
            }

        /** 歌词页位置：0=靠左，1=居中，2=靠右。 */
        var lyricTextPosition: Int
            get() = kv.decodeInt("ui_lyric_text_position", 0).coerceIn(0, 2)
            set(value) { kv.encode("ui_lyric_text_position", value.coerceIn(0, 2)) }

        /** 歌词页是否启用远近层级模糊。 */
        var lyricBlurEnabled: Boolean
            get() = kv.decodeBool("ui_lyric_blur_enabled", true)
            set(value) { kv.encode("ui_lyric_blur_enabled", value) }

        /** 歌词页是否让非当前歌词也使用完整高亮色与不透明度。 */
        var lyricHighlightAllEnabled: Boolean
            get() = kv.decodeBool("ui_lyric_highlight_all_enabled", false)
            set(value) { kv.encode("ui_lyric_highlight_all_enabled", value) }

        /** 沉浸播放页进度条样式：0=普通，1=可视化波形，2=秒级柱状 */
        var immersiveProgressStyle: Int
            get() = kv.decodeInt("ui_immersive_progress_style", 0)
            set(value) { kv.encode("ui_immersive_progress_style", value.coerceIn(0, 2)) }

        /** 沉浸播放页可视化波形是否显示高潮段 */
        var immersiveClimaxEnabled: Boolean
            get() = kv.decodeBool("ui_immersive_climax_enabled", true)
            set(value) { kv.encode("ui_immersive_climax_enabled", value) }

        /** 沉浸播放页可视化进度条调试面板 */
        var immersiveWaveformDebugPanel: Boolean
            get() = kv.decodeBool("ui_immersive_waveform_debug_panel", false)
            set(value) { kv.encode("ui_immersive_waveform_debug_panel", value) }

        /** 沉浸播放页波形未播放区域颜色 */
        var immersiveWaveformRemainingColor: Int
            get() = kv.decodeInt("ui_immersive_waveform_remaining_color", 0xE6FFFFFF.toInt())
            set(value) { kv.encode("ui_immersive_waveform_remaining_color", value) }

        /** 沉浸播放页波形已播放区域颜色 */
        var immersiveWaveformPlayedColor: Int
            get() = kv.decodeInt("ui_immersive_waveform_played_color", 0x3DFFFFFF)
            set(value) { kv.encode("ui_immersive_waveform_played_color", value) }

        /** 沉浸播放页高潮段颜色，仅可视化进度条使用 */
        var immersiveWaveformClimaxColor: Int
            get() = kv.decodeInt("ui_immersive_waveform_climax_color", 0xFFFF3B30.toInt())
            set(value) { kv.encode("ui_immersive_waveform_climax_color", value) }

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
        /** 强制使用 ARGB_8888 软件位图（24位RGB+Alpha），便于取色/模糊背景并减少色带。 */
        var forceArgb8888: Boolean
            get() = kv.decodeBool("aa_8888", true)
            set(value) { kv.encode("aa_8888", value) }

        /** 使用 1024px 播放界面封面和 1440px 全屏封面层级；列表仍走低清层。 */
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

        /** 无真实专辑图时是否显示播放器内置默认专辑图。 */
        var useDefaultArtwork: Boolean
            get() = kv.decodeBool("aa_use_default_artwork", true)
            set(value) { kv.encode("aa_use_default_artwork", value) }

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

    object SpeakerOutputElasticity {
        /** 内部参数配置版本。 */
        var profileVersion: Int
            get() = kv.decodeInt("speaker_output_elasticity_profile_version", 1)
            set(value) { kv.encode("speaker_output_elasticity_profile_version", value.coerceAtLeast(1)) }

        /** 当前扬声器外放模式：0=弹性，1=澎湃，2=宽广。 */
        var modeCode: Int
            get() = kv.decodeInt("speaker_output_mode", 0).coerceIn(0, 2)
            set(value) { kv.encode("speaker_output_mode", value.coerceIn(0, 2)) }

        var isEnabled: Boolean
            get() = kv.decodeBool("speaker_output_elasticity_enabled", false)
            set(value) { kv.encode("speaker_output_elasticity_enabled", value) }

        /** 总体强度，0..100%。控制并行冲击、主体回收与峰值余量预留。 */
        var strengthPercent: Float
            get() = kv.decodeFloat("speaker_output_elasticity_strength", 82f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_elasticity_strength", value.coerceIn(0f, 100f)) }

        /** 检测器高通截止，50..250Hz。用于排除超低频和位移噪声。 */
        var detectorLowHz: Float
            get() = kv.decodeFloat("speaker_output_elasticity_detector_low_hz", 85f).coerceIn(50f, 250f)
            set(value) { kv.encode("speaker_output_elasticity_detector_low_hz", value.coerceIn(50f, 250f)) }

        /** 起音频段低通截止，400..2500Hz。覆盖鼓点主体与敲击前沿。 */
        var detectorHighHz: Float
            get() = kv.decodeFloat("speaker_output_elasticity_detector_high_hz", 1350f).coerceIn(400f, 2500f)
            set(value) { kv.encode("speaker_output_elasticity_detector_high_hz", value.coerceIn(400f, 2500f)) }

        /** 瞬态识别灵敏度，0..100%。不改变最大输出提升。 */
        var sensitivityPercent: Float
            get() = kv.decodeFloat("speaker_output_elasticity_sensitivity", 82f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_elasticity_sensitivity", value.coerceIn(0f, 100f)) }

        /** 检测门限，-72..-24dBFS。低于门限不触发，避免把底噪放大。 */
        var gateThresholdDb: Float
            get() = kv.decodeFloat("speaker_output_elasticity_gate_db", -50f).coerceIn(-72f, -24f)
            set(value) { kv.encode("speaker_output_elasticity_gate_db", value.coerceIn(-72f, -24f)) }

        /** 快速包络启动，0.2..5ms。越短越强调尖锐起音。 */
        var fastAttackMs: Float
            get() = kv.decodeFloat("speaker_output_elasticity_fast_attack_ms", 0.35f).coerceIn(0.2f, 5f)
            set(value) { kv.encode("speaker_output_elasticity_fast_attack_ms", value.coerceIn(0.2f, 5f)) }

        /** 快速包络释放，8..100ms。决定起音检测器保持时间。 */
        var fastReleaseMs: Float
            get() = kv.decodeFloat("speaker_output_elasticity_fast_release_ms", 20f).coerceIn(8f, 100f)
            set(value) { kv.encode("speaker_output_elasticity_fast_release_ms", value.coerceIn(8f, 100f)) }

        /** 慢速主体包络启动，4..80ms。 */
        var slowAttackMs: Float
            get() = kv.decodeFloat("speaker_output_elasticity_slow_attack_ms", 34f).coerceIn(4f, 80f)
            set(value) { kv.encode("speaker_output_elasticity_slow_attack_ms", value.coerceIn(4f, 80f)) }

        /** 慢速主体包络释放，40..500ms。 */
        var slowReleaseMs: Float
            get() = kv.decodeFloat("speaker_output_elasticity_slow_release_ms", 165f).coerceIn(40f, 500f)
            set(value) { kv.encode("speaker_output_elasticity_slow_release_ms", value.coerceIn(40f, 500f)) }

        /** 实际增益启动，0.2..10ms。越短冲击越硬。 */
        var gainAttackMs: Float
            get() = kv.decodeFloat("speaker_output_elasticity_gain_attack_ms", 0.3f).coerceIn(0.2f, 10f)
            set(value) { kv.encode("speaker_output_elasticity_gain_attack_ms", value.coerceIn(0.2f, 10f)) }

        /** 实际增益回落，10..250ms，是主要回弹速度参数。 */
        var gainReleaseMs: Float
            get() = kv.decodeFloat("speaker_output_elasticity_gain_release_ms", 62f).coerceIn(10f, 250f)
            set(value) { kv.encode("speaker_output_elasticity_gain_release_ms", value.coerceIn(10f, 250f)) }

        /** 起音频段最大并行提升，0..6dB；不再代表全频增益。 */
        var maxBoostDb: Float
            get() = kv.decodeFloat("speaker_output_elasticity_max_boost_db", 4.2f).coerceIn(0f, 6f)
            set(value) { kv.encode("speaker_output_elasticity_max_boost_db", value.coerceIn(0f, 6f)) }

        /** 峰值保护上限，-6..-0.1dBFS。接近上限时自动减少瞬态提升。 */
        var peakCeilingDb: Float
            get() = kv.decodeFloat("speaker_output_elasticity_peak_ceiling_db", -0.2f).coerceIn(-6f, -0.1f)
            set(value) { kv.encode("speaker_output_elasticity_peak_ceiling_db", value.coerceIn(-6f, -0.1f)) }
    }

    object SpeakerOutputPowerful {
        /** 内部参数配置版本。 */
        var profileVersion: Int
            get() = kv.decodeInt("speaker_output_powerful_profile_version", 1)
            set(value) { kv.encode("speaker_output_powerful_profile_version", value.coerceAtLeast(1)) }

        /** 总体强度，0..100%。统一缩放动态低频、密度、谐波与存在感。 */
        var strengthPercent: Float
            get() = kv.decodeFloat("speaker_output_powerful_strength", 84f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_powerful_strength", value.coerceIn(0f, 100f)) }

        /** 厚度频段高通，40..140Hz。用于过滤位移噪声与扬声器难以重放的超低频。 */
        var bodyLowHz: Float
            get() = kv.decodeFloat("speaker_output_powerful_body_low_hz", 65f).coerceIn(40f, 140f)
            set(value) { kv.encode("speaker_output_powerful_body_low_hz", value.coerceIn(40f, 140f)) }

        /** 厚度频段低通，180..700Hz。决定低频饱满感延伸到多高。 */
        var bodyHighHz: Float
            get() = kv.decodeFloat("speaker_output_powerful_body_high_hz", 390f).coerceIn(180f, 700f)
            set(value) { kv.encode("speaker_output_powerful_body_high_hz", value.coerceIn(180f, 700f)) }

        /** 动态低频最大并行提升，0..6dB。原曲低中频越密集，Native 会自动退让。 */
        var bassBoostDb: Float
            get() = kv.decodeFloat("speaker_output_powerful_bass_boost_db", 4f).coerceIn(0f, 6f)
            set(value) { kv.encode("speaker_output_powerful_bass_boost_db", value.coerceIn(0f, 6f)) }

        /** 低频谐波量，0..100%。增强小扬声器可感知低频，不直接强推超低频。 */
        var harmonicPercent: Float
            get() = kv.decodeFloat("speaker_output_powerful_harmonic_percent", 34f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_powerful_harmonic_percent", value.coerceIn(0f, 100f)) }

        /** 并行密度压缩阈值，-36..-6dBFS。越低，更多声音进入压缩分支。 */
        var compressorThresholdDb: Float
            get() = kv.decodeFloat("speaker_output_powerful_compressor_threshold_db", -20f).coerceIn(-36f, -6f)
            set(value) { kv.encode("speaker_output_powerful_compressor_threshold_db", value.coerceIn(-36f, -6f)) }

        /** 并行密度压缩比，1..8。1 表示不压缩。 */
        var compressorRatio: Float
            get() = kv.decodeFloat("speaker_output_powerful_compressor_ratio", 3.5f).coerceIn(1f, 8f)
            set(value) { kv.encode("speaker_output_powerful_compressor_ratio", value.coerceIn(1f, 8f)) }

        /** 压缩启动，2..80ms。较慢可保留鼓点前沿，较快会增加持续密度。 */
        var compressorAttackMs: Float
            get() = kv.decodeFloat("speaker_output_powerful_compressor_attack_ms", 10f).coerceIn(2f, 80f)
            set(value) { kv.encode("speaker_output_powerful_compressor_attack_ms", value.coerceIn(2f, 80f)) }

        /** 压缩释放，40..500ms。越长越厚，过长可能产生泵动。 */
        var compressorReleaseMs: Float
            get() = kv.decodeFloat("speaker_output_powerful_compressor_release_ms", 200f).coerceIn(40f, 500f)
            set(value) { kv.encode("speaker_output_powerful_compressor_release_ms", value.coerceIn(40f, 500f)) }

        /** 压缩分支混合比例，0..100%。保留原始信号，同时补充声音主体。 */
        var parallelMixPercent: Float
            get() = kv.decodeFloat("speaker_output_powerful_parallel_mix_percent", 48f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_powerful_parallel_mix_percent", value.coerceIn(0f, 100f)) }

        /** 压缩分支补偿增益，0..6dB。只作用于并行分支，不是全局音量。 */
        var makeupGainDb: Float
            get() = kv.decodeFloat("speaker_output_powerful_makeup_gain_db", 3.4f).coerceIn(0f, 6f)
            set(value) { kv.encode("speaker_output_powerful_makeup_gain_db", value.coerceIn(0f, 6f)) }

        /** 1.8..6.5kHz 存在感并行提升，0..4dB。用于避免声音变厚后发闷。 */
        var presenceBoostDb: Float
            get() = kv.decodeFloat("speaker_output_powerful_presence_boost_db", 1.3f).coerceIn(0f, 4f)
            set(value) { kv.encode("speaker_output_powerful_presence_boost_db", value.coerceIn(0f, 4f)) }

        /** 峰值保护上限，-6..-0.1dBFS。 */
        var peakCeilingDb: Float
            get() = kv.decodeFloat("speaker_output_powerful_peak_ceiling_db", -0.25f).coerceIn(-6f, -0.1f)
            set(value) { kv.encode("speaker_output_powerful_peak_ceiling_db", value.coerceIn(-6f, -0.1f)) }
    }

    object SpeakerOutputWide {
        /** 总体强度，0..100%。统一缩放宽度、低频收拢与去相关。 */
        var strengthPercent: Float
            get() = kv.decodeFloat("speaker_output_wide_strength", 76f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_wide_strength", value.coerceIn(0f, 100f)) }

        /** 侧声道扩展起始频率，300..2200Hz。 */
        var crossoverHz: Float
            get() = kv.decodeFloat("speaker_output_wide_crossover_hz", 760f).coerceIn(300f, 2200f)
            set(value) { kv.encode("speaker_output_wide_crossover_hz", value.coerceIn(300f, 2200f)) }

        /** 中高频侧声道最大提升，0..6dB。 */
        var widthDb: Float
            get() = kv.decodeFloat("speaker_output_wide_width_db", 3.2f).coerceIn(0f, 6f)
            set(value) { kv.encode("speaker_output_wide_width_db", value.coerceIn(0f, 6f)) }

        /** 轻量全通去相关混合，0..60%。 */
        var decorrelationPercent: Float
            get() = kv.decodeFloat("speaker_output_wide_decorrelation_percent", 18f).coerceIn(0f, 60f)
            set(value) { kv.encode("speaker_output_wide_decorrelation_percent", value.coerceIn(0f, 60f)) }

        /** 低于分频点的侧声道收拢量，0..100%。 */
        var bassCenterPercent: Float
            get() = kv.decodeFloat("speaker_output_wide_bass_center_percent", 58f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_wide_bass_center_percent", value.coerceIn(0f, 100f)) }

        /** 中心保护强度，0..100%。 */
        var centerProtectionPercent: Float
            get() = kv.decodeFloat("speaker_output_wide_center_protection_percent", 70f).coerceIn(0f, 100f)
            set(value) { kv.encode("speaker_output_wide_center_protection_percent", value.coerceIn(0f, 100f)) }

        /** 峰值保护上限，-6..-0.1dBFS。 */
        var peakCeilingDb: Float
            get() = kv.decodeFloat("speaker_output_wide_peak_ceiling_db", -0.25f).coerceIn(-6f, -0.1f)
            set(value) { kv.encode("speaker_output_wide_peak_ceiling_db", value.coerceIn(-6f, -0.1f)) }
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

    object FftConvolver {
        var isEnabled: Boolean
            get() = kv.decodeBool("fft_convolver_enabled", false)
            set(value) { kv.encode("fft_convolver_enabled", value) }

        /** Wet/processed signal level, 0.0 ~ 2.0. */
        var wet: Float
            get() = kv.decodeFloat("fft_convolver_wet", 1f).coerceIn(0f, 2f)
            set(value) { kv.encode("fft_convolver_wet", value.coerceIn(0f, 2f)) }

        /** Dry/original signal level, 0.0 ~ 2.0. */
        var dry: Float
            get() = kv.decodeFloat("fft_convolver_dry", 0f).coerceIn(0f, 2f)
            set(value) { kv.encode("fft_convolver_dry", value.coerceIn(0f, 2f)) }

        /** Convolved signal gain in dB. */
        var gainDb: Float
            get() = kv.decodeFloat("fft_convolver_gain_db", -6f).coerceIn(-24f, 24f)
            set(value) { kv.encode("fft_convolver_gain_db", value.coerceIn(-24f, 24f)) }

        /** Wet-path pre-delay in milliseconds. */
        var preDelayMs: Float
            get() = kv.decodeFloat("fft_convolver_pre_delay_ms", 0f).coerceIn(0f, 500f)
            set(value) { kv.encode("fft_convolver_pre_delay_ms", value.coerceIn(0f, 500f)) }

        /** Persistable Storage Access Framework document URI. */
        var irUri: String
            get() = kv.decodeString("fft_convolver_ir_uri", "") ?: ""
            set(value) { kv.encode("fft_convolver_ir_uri", value) }

        var irName: String
            get() = kv.decodeString("fft_convolver_ir_name", "") ?: ""
            set(value) { kv.encode("fft_convolver_ir_name", value) }

        var irSampleRate: Int
            get() = kv.decodeInt("fft_convolver_ir_sample_rate", 0).coerceAtLeast(0)
            set(value) { kv.encode("fft_convolver_ir_sample_rate", value.coerceAtLeast(0)) }

        var irChannels: Int
            get() = kv.decodeInt("fft_convolver_ir_channels", 0).coerceIn(0, 2)
            set(value) { kv.encode("fft_convolver_ir_channels", value.coerceIn(0, 2)) }

        var irFrames: Int
            get() = kv.decodeInt("fft_convolver_ir_frames", 0).coerceAtLeast(0)
            set(value) { kv.encode("fft_convolver_ir_frames", value.coerceAtLeast(0)) }
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
