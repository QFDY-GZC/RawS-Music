package com.rawsmusic.core.common.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AudioFile(
    val id: Long = 0,
    val path: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = -1,
    val duration: Long = 0,
    val sampleRate: Int = 0,
    val bitRate: Int = 0,
    val bitsPerSample: Int = 0,
    val format: String = "",
    val fileSize: Long = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val albumArtPath: String = "",
    val genre: String = "",
    val composer: String = "",
    val discNumber: Int = 0,
    val channelCount: Int = 0,
    val bpm: Int = 0,
    val albumArtist: String = "",
    val encodingFormat: String = "",
    val isFavorite: Boolean = false,
    val trackGain: Float = 0f,
    val trackPeak: Float = 1.0f,
    val albumGain: Float = 0f,
    val albumPeak: Float = 1.0f,
    val cueOffsetMs: Long = 0L,
    val cueEndMs: Long = 0L,
    val cueTrackIndex: Int = 0
) : Parcelable {

    val displayName: String
        get() = title.ifBlank { path.substringAfterLast("/", "").substringBeforeLast(".") }

    /**
     * 封面 key：用于 BitmapProvider 加载封面。
     *
     * 真实音频路径始终是权威身份，BitmapProvider 会先探测当前文件的内嵌图，再考虑
     * albumArtPath / folder.jpg。只有没有真实音频路径的虚拟条目才直接使用外置封面。
     */
    val coverKey: String
        get() = resolveAudioFirstArtworkKey(
            audioPath = path,
            fileSize = fileSize,
            dateModified = dateModified,
            externalArtworkPath = albumArtPath
        )

    val extension: String
        get() = path.substringAfterLast(".", "").uppercase()

    val isHighQuality: Boolean
        get() = bitRate >= 320000 || format.equals("FLAC", true) || format.equals("WAV", true)

    val isHiRes: Boolean
        get() = (isLosslessFormat && bitsPerSample >= 24 && bitsPerSample > 0) ||
                isDsdFormat

    /** 无损格式判定 */
    private val isLosslessFormat: Boolean
        get() = format.equals("FLAC", true) || format.equals("WAV", true) ||
                format.equals("AIFF", true) || format.equals("ALAC", true) ||
                format.equals("APE", true) || format.equals("OGGFLAC", true)

    /** DSD 格式判定 */
    val isDsdFormat: Boolean
        get() = format.equals("DSD", true) || format.equals("DSF", true) || format.equals("DFF", true)
}
