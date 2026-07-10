package com.rawsmusic.core.common.model

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

/**
 * 随机播放模式
 * - OFF: 关闭随机（顺序播放）
 * - SONGS: 按歌曲随机
 */
enum class ShuffleMode {
    OFF,
    SONGS;

    val isOn: Boolean get() = this == SONGS

    companion object {
        fun fromBoolean(isShuffle: Boolean): ShuffleMode =
            if (isShuffle) SONGS else OFF
    }
}

/**
 * 播放模式：4种组合
 * - SEQUENTIAL: 顺序播放（shuffle=OFF, repeat=ALL）
 * - SHUFFLE_ALL: 全部随机循环（shuffle=ON, repeat=ALL）
 * - SHUFFLE_ONCE: 随机播放一遍（shuffle=ON, repeat=OFF）
 * - REPEAT_ONE: 单曲循环（shuffle=OFF, repeat=ONE）
 */
enum class PlayMode {
    SEQUENTIAL,
    SHUFFLE_ALL,
    SHUFFLE_ONCE,
    REPEAT_ONE;

    val isHighlight: Boolean get() = this != SEQUENTIAL

    val shuffleMode: ShuffleMode
        get() = when (this) {
            SEQUENTIAL, REPEAT_ONE -> ShuffleMode.OFF
            SHUFFLE_ALL, SHUFFLE_ONCE -> ShuffleMode.SONGS
        }

    val repeatMode: RepeatMode
        get() = when (this) {
            SEQUENTIAL -> RepeatMode.ALL
            SHUFFLE_ALL -> RepeatMode.ALL
            SHUFFLE_ONCE -> RepeatMode.OFF
            REPEAT_ONE -> RepeatMode.ONE
        }

    companion object {
        fun from(shuffleMode: ShuffleMode, repeatMode: RepeatMode): PlayMode {
            return when {
                shuffleMode == ShuffleMode.OFF && repeatMode == RepeatMode.ALL -> SEQUENTIAL
                shuffleMode == ShuffleMode.SONGS && repeatMode == RepeatMode.ALL -> SHUFFLE_ALL
                shuffleMode == ShuffleMode.SONGS && repeatMode == RepeatMode.OFF -> SHUFFLE_ONCE
                shuffleMode == ShuffleMode.OFF && repeatMode == RepeatMode.ONE -> REPEAT_ONE
                else -> SEQUENTIAL
            }
        }

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
    YEAR_DESC,
    PLAYBACK_INFO,
    FILE_NAME_ASC,
    FILE_NAME_DESC,
    PATH_ASC,
    PATH_DESC,
    PLAYBACK_INFO_DESC
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
    val isShuffle: Boolean = false,
    val originalSongs: List<AudioFile> = emptyList()
) {
    val currentSong: AudioFile?
        get() = if (currentIndex in songs.indices) songs[currentIndex] else null

    val size: Int get() = songs.size

    fun isEmpty(): Boolean = songs.isEmpty()

    fun hasOriginalOrder(): Boolean = originalSongs.isNotEmpty()
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
