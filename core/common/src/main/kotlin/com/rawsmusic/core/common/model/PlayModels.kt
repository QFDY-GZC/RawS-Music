package com.rawsmusic.core.common.model

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

/**
 * 播放模式：4种组合
 * - SHUFFLE_OFF: 随机播放关闭（顺序播放）
 * - SHUFFLE_ALL: 随机播放全部（随机播放所有歌曲）
 * - SHUFFLE_SONG: 随机播放歌曲（按分类顺序）
 * - SHUFFLE_BOTH: 随机歌曲/分类
 */
enum class PlayMode {
    SHUFFLE_OFF,
    SHUFFLE_ALL,
    SHUFFLE_SONG,
    SHUFFLE_BOTH;

    /** 是否为高亮状态（除 SHUFFLE_OFF 外全部高亮） */
    val isHighlight: Boolean get() = this != SHUFFLE_OFF

    companion object {
        fun cycle(current: PlayMode): PlayMode {
            return entries[(current.ordinal + 1) % entries.size]
        }
    }
}

enum class SortOrder {
    TITLE_ASC,
    TITLE_DESC,
    ARTIST_ASC,
    ARTIST_DESC,
    ALBUM_ASC,
    ALBUM_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
    DURATION_ASC,
    DURATION_DESC,
    YEAR_ASC,
    YEAR_DESC
}

enum class PlayState {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR
}

/**
 * 音频输出模式
 * OPENSL_ES — OpenSL ES（传统，兼容性好，Android 4.1+）
 * AAUDIO — AAudio（低延迟，Android 8.1+，自动回退到 OpenSL ES）
 * DIRECT — Direct HiRes 输出（绕过系统混音，支持高采样率，Android 8+，需有线/USB设备）
 */
enum class AudioOutputMode {
    OPENSL_ES,
    AAUDIO,
    DIRECT
}

data class PlayQueue(
    val songs: List<AudioFile> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffle: Boolean = false
) {
    val currentSong: AudioFile?
        get() = if (currentIndex in songs.indices) songs[currentIndex] else null

    val size: Int get() = songs.size

    fun isEmpty(): Boolean = songs.isEmpty()
}

data class EqualizerPreset(
    val id: Long = 0,
    val name: String = "",
    val bandLevels: List<Int> = emptyList(),
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val isBuiltIn: Boolean = false
)

data class PlayStats(
    val totalSongs: Int = 0,
    val totalDuration: Long = 0L,
    val totalSize: Long = 0L,
    val formatDistribution: Map<String, Int> = emptyMap(),
    val artistDistribution: Map<String, Int> = emptyMap(),
    val albumDistribution: Map<String, Int> = emptyMap()
)
