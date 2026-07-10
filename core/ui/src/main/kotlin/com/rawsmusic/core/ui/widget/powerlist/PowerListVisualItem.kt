package com.rawsmusic.core.ui.widget.powerlist

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.BitrateNormalizer
import com.rawsmusic.core.common.utils.SampleRateNormalizer
import java.io.File

/**
 * 所有集合页共用的 PowerList item。
 *
 * sharedCoverElementId 只表示“这个 item 的封面身份”。
 * 是否真正参与大封面 hero 转场，由 ComposeGenericPowerList.sharedCoverSceneId 是否非空决定。
 */
interface PowerListVisualItem {
    val stableId: Long
    val stableKey: String
    val sharedCoverElementId: String
    val coverKey: String
    val title: String
    val subtitle: String
    val meta: String
}

data class SongPowerListItem(
    val song: AudioFile
) : PowerListVisualItem {

    override val stableId: Long get() = song.id
    override val stableKey: String get() = "${song.id}_${song.path}"
    override val sharedCoverElementId: String get() = "cover:song:$stableId"
    override val coverKey: String get() = song.coverKey

    override val title: String
        get() = song.displayName.ifBlank {
            File(song.path).nameWithoutExtension.ifBlank { "Unknown" }
        }

    override val subtitle: String
        get() = song.artist.ifBlank { song.album.ifBlank { "Music" } }

    override val meta: String
        get() = buildString {
            if (song.duration > 0L) append(formatPowerListDuration(song.duration))
            if (song.sampleRate > 0) {
                val normalizedSampleRate = SampleRateNormalizer.formatKhz(
                    sampleRate = song.sampleRate,
                    codecName = song.encodingFormat,
                    formatName = song.format,
                    filePath = song.path
                )
                if (normalizedSampleRate.isNotBlank()) {
                    if (isNotBlank()) append(" · ")
                    append(normalizedSampleRate)
                }
            }
            if (song.bitsPerSample > 0) {
                if (isNotBlank()) append(" · ")
                append(song.bitsPerSample)
                append("bit")
            }
            val bitrateText = BitrateNormalizer
                .formatKbps(
                    rawBitrate = song.bitRate,
                    durationMs = song.duration,
                    fileSizeBytes = song.fileSize,
                    codecName = song.encodingFormat,
                    formatName = song.format,
                    filePath = song.path
                )
                .takeIf { it != "未知" }
                ?.replace(" ", "")
            if (!bitrateText.isNullOrBlank()) {
                if (isNotBlank()) append(" · ")
                append(bitrateText)
            }
            if (song.format.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append(song.format.uppercase())
            }
        }
}

data class AlbumPowerListItem(
    val key: String,
    val name: String,
    val artist: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : PowerListVisualItem {

    override val stableId: Long get() = stablePowerListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:album:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知专辑" }
    override val subtitle: String get() = artist.ifBlank { "未知艺术家" }
    override val meta: String get() = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}"
}

data class ArtistPowerListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val albumCount: Int,
    val totalDurationMs: Long
) : PowerListVisualItem {

    override val stableId: Long get() = stablePowerListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:artist:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知艺术家" }
    override val subtitle: String get() = "$albumCount 张专辑"
    override val meta: String get() = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}"
}

data class GenrePowerListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : PowerListVisualItem {

    override val stableId: Long get() = stablePowerListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:genre:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知流派" }
    override val subtitle: String get() = "流派"
    override val meta: String get() = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}"
}

data class YearPowerListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : PowerListVisualItem {

    override val stableId: Long get() = stablePowerListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:year:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知年份" }
    override val subtitle: String get() = "年份"
    override val meta: String get() = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}"
}

data class ComposerPowerListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : PowerListVisualItem {

    override val stableId: Long get() = stablePowerListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:composer:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知作曲家" }
    override val subtitle: String get() = "作曲家"
    override val meta: String get() = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}"
}

data class FolderPowerListItem(
    val path: String,
    val name: String,
    val parentName: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : PowerListVisualItem {

    override val stableId: Long get() = stablePowerListHash64(path)
    override val stableKey: String get() = path
    override val sharedCoverElementId: String get() = "cover:folder:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name
    override val subtitle: String get() = parentName.ifBlank { "Music" }
    override val meta: String get() = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}"
}

fun stablePowerListHash64(value: String): Long {
    var result = 1125899906842597L
    for (c in value) {
        result = 31L * result + c.code
    }
    return result
}

fun formatPowerListDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
