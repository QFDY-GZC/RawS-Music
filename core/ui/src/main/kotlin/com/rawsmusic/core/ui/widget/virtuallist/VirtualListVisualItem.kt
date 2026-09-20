package com.rawsmusic.core.ui.widget.virtuallist

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.SampleRateNormalizer
import java.io.File
import java.util.Locale

/**
 * 所有集合页共用的 VirtualList item。
 *
 * sharedCoverElementId 只表示“这个 item 的封面身份”。
 * 是否真正参与大封面 hero 转场，由 ComposeGenericVirtualList.sharedCoverSceneId 是否非空决定。
 */
interface VirtualListVisualItem {
    val stableId: Long
    val stableKey: String
    val sharedCoverElementId: String
    val coverKey: String
    val title: String
    val subtitle: String
    val meta: String
}

internal const val VIRTUAL_LIST_COLLECTION_ENCODING = "raws_collection"
internal const val VIRTUAL_LIST_COLLECTION_OTHER_ENCODING = "raws_collection_other"
internal const val VIRTUAL_LIST_FOLDER_ENCODING = "raws_collection_folder"

internal enum class VirtualListArtworkRadiusType {
    TRACK,
    ALBUM,
    OTHER,
}

internal fun AudioFile.virtualListArtworkRadiusType(): VirtualListArtworkRadiusType = when (encodingFormat) {
    VIRTUAL_LIST_COLLECTION_OTHER_ENCODING -> VirtualListArtworkRadiusType.OTHER
    VIRTUAL_LIST_COLLECTION_ENCODING,
    VIRTUAL_LIST_FOLDER_ENCODING -> VirtualListArtworkRadiusType.ALBUM
    else -> VirtualListArtworkRadiusType.TRACK
}

internal fun AudioFile.hasVirtualListCollectionMetaIcon(): Boolean =
    encodingFormat == VIRTUAL_LIST_COLLECTION_ENCODING ||
        encodingFormat == VIRTUAL_LIST_COLLECTION_OTHER_ENCODING ||
        encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING


internal fun <S, T> retainedMappedList(
    source: List<S>,
    transform: (S) -> T,
): List<T> = object : AbstractList<T>() {
    private val realized = HashMap<Int, T>()

    override val size: Int
        get() = source.size

    override fun get(index: Int): T {
        if (index !in source.indices) throw IndexOutOfBoundsException("index=$index size=${source.size}")
        return realized.getOrPut(index) { transform(source[index]) }
    }
}

data class SongVirtualListItem(
    val song: AudioFile
) : VirtualListVisualItem {

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
        get() = formatVirtualListSongMeta(song)
}

/**
 * The third song-list line intentionally contains source information only.
 * Keep it short so the retained list renderer does not spend unnecessary travel time on it.
 * Marquee animation follows holder visibility: visible overflow can keep moving while the list or
 * retained scene is in motion, matching Reference's ArtworkItemNode/MarqueeTextNode ownership.
 */
internal fun formatVirtualListSongMeta(song: AudioFile): String {
    val format = song.format
        .ifBlank { song.encodingFormat }
        .ifBlank { song.extension }
        .trim()
        .uppercase(Locale.ROOT)
    val sampleRate = SampleRateNormalizer.formatKhz(
        sampleRate = song.sampleRate,
        codecName = song.encodingFormat,
        formatName = song.format,
        filePath = song.path
    )
    return buildList {
        format.takeIf { it.isNotBlank() }?.let(::add)
        song.bitsPerSample
            .takeIf { it > 0 }
            ?.let { add("${it}bit") }
        sampleRate.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("｜")
}

data class AlbumVirtualListItem(
    val key: String,
    val name: String,
    val artist: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:album:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知专辑" }
    override val subtitle: String get() = artist.ifBlank { "未知艺术家" }
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class ArtistVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val albumCount: Int,
    val totalDurationMs: Long
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:artist:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知艺术家" }
    override val subtitle: String get() = "$albumCount 张专辑"
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class GenreVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:genre:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知流派" }
    override val subtitle: String get() = "流派"
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class YearVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:year:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知年份" }
    override val subtitle: String get() = "年份"
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class ComposerVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:composer:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name.ifBlank { "未知作曲家" }
    override val subtitle: String get() = "作曲家"
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class FolderVirtualListItem(
    val path: String,
    val name: String,
    val parentName: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long,
    val folderId: Long = stableVirtualListHash64(path),
    val parentFolderId: Long = 0L,
    val childFolderCount: Int = 0,
    /** Hierarchy rows expose Reference-style CatImage/folder glyph; flat Folders keeps its old presentation. */
    val showFolderGlyph: Boolean = false,
) : VirtualListVisualItem {

    override val stableId: Long get() = folderId
    override val stableKey: String get() = path
    override val sharedCoverElementId: String get() = "cover:folder:$stableId"
    override val coverKey: String get() = cover
    override val title: String get() = name
    override val subtitle: String get() = parentName.ifBlank { "Music" }
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

fun stableVirtualListHash64(value: String): Long {
    var result = 1125899906842597L
    for (c in value) {
        result = 31L * result + c.code
    }
    return result
}

fun formatVirtualListDuration(ms: Long): String {
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
