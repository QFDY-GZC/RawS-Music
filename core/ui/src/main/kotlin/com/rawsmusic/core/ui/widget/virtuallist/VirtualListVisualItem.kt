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
internal const val VIRTUAL_LIST_ARTIST_ALL_SONGS_ENCODING = "raws_artist_all_songs"

// Stable grouping sentinels inherited by older library snapshots. Never render these directly;
// presentation code supplies locale-specific labels to the item models below.
internal const val VIRTUAL_LIST_UNKNOWN_ALBUM_SENTINEL = "未知专辑"
internal const val VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL = "未知艺术家"
internal const val VIRTUAL_LIST_UNKNOWN_GENRE_SENTINEL = "未知流派"
internal const val VIRTUAL_LIST_UNKNOWN_YEAR_SENTINEL = "未知年份"
internal const val VIRTUAL_LIST_UNKNOWN_COMPOSER_SENTINEL = "未知作曲家"

internal enum class VirtualListArtworkRadiusType {
    TRACK,
    ALBUM,
    OTHER,
    ARTIST_ALL_SONGS,
}

internal fun AudioFile.virtualListArtworkRadiusType(): VirtualListArtworkRadiusType = when (encodingFormat) {
    VIRTUAL_LIST_ARTIST_ALL_SONGS_ENCODING -> VirtualListArtworkRadiusType.ARTIST_ALL_SONGS
    VIRTUAL_LIST_COLLECTION_OTHER_ENCODING -> VirtualListArtworkRadiusType.OTHER
    VIRTUAL_LIST_COLLECTION_ENCODING,
    VIRTUAL_LIST_FOLDER_ENCODING -> VirtualListArtworkRadiusType.ALBUM
    else -> VirtualListArtworkRadiusType.TRACK
}

internal fun AudioFile.hasVirtualListCollectionMetaIcon(): Boolean =
    encodingFormat == VIRTUAL_LIST_COLLECTION_ENCODING ||
        encodingFormat == VIRTUAL_LIST_COLLECTION_OTHER_ENCODING ||
        encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING ||
        encodingFormat == VIRTUAL_LIST_ARTIST_ALL_SONGS_ENCODING


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
    val totalDurationMs: Long,
    val unknownAlbumLabel: String,
    val unknownArtistLabel: String,
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:album:$stableId"
    override val coverKey: String get() = cover
    override val title: String
        get() = name.takeUnless { it == VIRTUAL_LIST_UNKNOWN_ALBUM_SENTINEL }
            .orEmpty().ifBlank { unknownAlbumLabel }
    override val subtitle: String
        get() = artist.takeUnless { it == VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL }
            .orEmpty().ifBlank { unknownArtistLabel }
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

/**
 * Artist-detail provider item for the all-songs entry.
 *
 * This is deliberately not an album and not a fake song. It exists as a dedicated provider item
 * immediately before the artist's first real album.
 */
data class ArtistAllSongsVirtualListItem(
    val artistKey: String,
    override val title: String,
    val cover: String,
    val songCountText: String,
) : VirtualListVisualItem {
    override val stableId: Long = stableVirtualListHash64("artist-all-songs:$artistKey")
    override val stableKey: String = "artist-all-songs:$artistKey"
    override val sharedCoverElementId: String = "cover:artist-all-songs:${stableVirtualListHash64(artistKey)}"
    override val coverKey: String = cover
    override val subtitle: String = ""
    override val meta: String = songCountText
}

data class ArtistVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val albumCount: Int,
    val totalDurationMs: Long,
    val unknownArtistLabel: String,
    val albumCountLabel: String,
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:artist:$stableId"
    override val coverKey: String get() = cover
    override val title: String
        get() = name.takeUnless { it == VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL }
            .orEmpty().ifBlank { unknownArtistLabel }
    override val subtitle: String get() = albumCountLabel
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class GenreVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long,
    val unknownGenreLabel: String,
    val categoryLabel: String,
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:genre:$stableId"
    override val coverKey: String get() = cover
    override val title: String
        get() = name.takeUnless { it == VIRTUAL_LIST_UNKNOWN_GENRE_SENTINEL }
            .orEmpty().ifBlank { unknownGenreLabel }
    override val subtitle: String get() = categoryLabel
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class YearVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long,
    val unknownYearLabel: String,
    val categoryLabel: String,
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:year:$stableId"
    override val coverKey: String get() = cover
    override val title: String
        get() = name.takeUnless { it == VIRTUAL_LIST_UNKNOWN_YEAR_SENTINEL }
            .orEmpty().ifBlank { unknownYearLabel }
    override val subtitle: String get() = categoryLabel
    override val meta: String get() = "$songCount | ${formatVirtualListDuration(totalDurationMs)}"
}

data class ComposerVirtualListItem(
    val key: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val totalDurationMs: Long,
    val unknownComposerLabel: String,
    val categoryLabel: String,
) : VirtualListVisualItem {

    override val stableId: Long get() = stableVirtualListHash64(key)
    override val stableKey: String get() = key
    override val sharedCoverElementId: String get() = "cover:composer:$stableId"
    override val coverKey: String get() = cover
    override val title: String
        get() = name.takeUnless { it == VIRTUAL_LIST_UNKNOWN_COMPOSER_SENTINEL }
            .orEmpty().ifBlank { unknownComposerLabel }
    override val subtitle: String get() = categoryLabel
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
