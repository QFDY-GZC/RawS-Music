package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.powerlist.AlbumPowerListItem
import com.rawsmusic.core.ui.widget.powerlist.ComposeGenericPowerList
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.formatPowerListDuration
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.stablePowerListHash64
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AlbumsPage(
    songs: List<AudioFile> = emptyList(),
    selectedAlbumKey: String? = null,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    powerListState: ComposePowerListState = rememberComposePowerListState("albums"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("album_detail_songs")
    val albums by remember(songs) {
        derivedStateOf { songs.toAlbumGroups() }
    }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.TITLE_ASC) }
    val sortedAlbums = remember(albums, sortOrder) { albums.sortedFor(sortOrder) }

    if (selectedAlbumKey.isNullOrBlank()) {
        AlbumListPage(
            albums = sortedAlbums,
            state = powerListState,
            onBack = onBack,
            onAlbumClick = onAlbumClick,
            onShuffle = { onShuffle(sortedAlbums.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = { sortOrder = it },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedAlbumKey) { Uri.decode(selectedAlbumKey) }
        val album = remember(decodedKey, albums) {
            albums.firstOrNull { it.key == decodedKey } ?: AlbumGroupUi.empty(decodedKey)
        }

        CollectionHeroDetailPage(
            hero = album.toHeroData(),
            listScene = NavScene.ALBUMS,
            detailScene = NavScene.ALBUM_DETAIL,
            songListState = detailState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
            onOpenFolder = onOpenFolder,
            onShuffle = onShuffle,
            onSearch = onSearch,
            modifier = modifier
        )
    }
}

@Composable
private fun AlbumListPage(
    albums: List<AlbumGroupUi>,
    state: ComposePowerListState,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onShuffle: () -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current

    val items = remember(albums) {
        albums.map { album ->
            AlbumPowerListItem(
                key = album.key,
                name = album.name,
                artist = album.artist,
                cover = album.coverKey,
                songCount = album.songCount,
                totalDurationMs = album.totalDurationMs
            )
        }
    }

    val alphabetIndexData = rememberAdaptiveAlphabetIndexData(items) { it.title }

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_albums),
        sceneId = NavScene.ALBUMS.name,
        onBack = onBack,
        powerListState = state,
        onShuffle = onShuffle,
        currentSortOrder = sortOrder,
        onSortSelected = onSortOrderChange,
        sortOptions = listOf(
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_artist) to SortOrder.ARTIST_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_year) to SortOrder.YEAR_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_ADDED_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO
        ),
        modifier = modifier
    ) { topPadding, backdropSource ->
        ComposeGenericPowerList(
            items = items,
            state = state,
            contentTopPadding = topPadding,
            sharedCoverSceneId = NavScene.ALBUMS.name,
            modifier = Modifier.fillMaxSize().then(backdropSource),
            onItemClick = { item, _, _ ->
                val album = item as? AlbumPowerListItem ?: return@ComposeGenericPowerList

                coverRegistry.freeze(
                    sceneId = NavScene.ALBUMS.name,
                    elementId = album.sharedCoverElementId
                )

                onAlbumClick(album.key)
            }
        )

        RawAlphabetIndex(
            data = alphabetIndexData,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 92.dp, bottom = 118.dp, end = 0.dp)
                .then(backdropSource)
                .zIndex(30f),
            onTopSelect = {
                state.requestScrollToIndex(0)
            },
            onSelect = { _, index ->
                state.requestScrollToIndex(index)
            }
        )
    }
}

private const val ALBUM_KEY_SEPARATOR = "␟"

@Stable
private data class AlbumGroupUi(
    val key: String,
    val name: String,
    val artist: String,
    val songs: List<AudioFile>,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size
    val sharedElementId: String get() = "cover:album:${stablePowerListHash64(key)}"

    fun toHeroData(): CollectionHeroData {
        return CollectionHeroData(
            stableKey = key,
            sharedElementId = sharedElementId,
            coverKey = coverKey,
            title = name,
            subtitle = artist.ifBlank { "未知艺术家" },
            meta = "$songCount | ${formatPowerListDuration(totalDurationMs)}",
            songs = songs
        )
    }

    companion object {
        fun empty(key: String): AlbumGroupUi {
            val name = key.substringAfter(ALBUM_KEY_SEPARATOR, key).ifBlank { "未知专辑" }
            val artist = key.substringBefore(ALBUM_KEY_SEPARATOR, "未知艺术家")
            return AlbumGroupUi(
                key = key,
                name = name,
                artist = artist,
                songs = emptyList(),
                coverKey = "",
                totalDurationMs = 0L
            )
        }
    }
}

private fun List<AudioFile>.toAlbumGroups(): List<AlbumGroupUi> {
    return asSequence()
        .filter { it.album.isNotBlank() || it.albumArtPath.isNotBlank() || it.displayName.isNotBlank() }
        .groupBy { song ->
            val albumName = song.album.ifBlank { "未知专辑" }
            val artistName = song.albumArtist
                .ifBlank { song.artist }
                .ifBlank { "未知艺术家" }
            "$artistName$ALBUM_KEY_SEPARATOR$albumName"
        }
        .map { (key, albumSongs) ->
            val first = albumSongs.firstOrNull()
            val albumName = first?.album?.ifBlank { "未知专辑" } ?: "未知专辑"
            val artistName = if (first != null) {
                first.albumArtist.ifBlank { first.artist }.ifBlank { "未知艺术家" }
            } else {
                "未知艺术家"
            }
            val coverSong = albumSongs.firstOrNull { it.albumArtPath.isNotBlank() } ?: first
            AlbumGroupUi(
                key = key,
                name = albumName,
                artist = artistName,
                songs = albumSongs.sortedWith(compareBy<AudioFile> { it.discNumber }.thenBy { it.trackNumber }.thenBy { it.displayName }),
                coverKey = coverSong?.coverKey.orEmpty(),
                totalDurationMs = albumSongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
        .sortedWith(compareBy<AlbumGroupUi> { it.name.lowercase() }.thenBy { it.artist.lowercase() })
}

private fun List<AlbumGroupUi>.sortedFor(order: SortOrder): List<AlbumGroupUi> {
    val descending = order in setOf(
        SortOrder.TITLE_DESC, SortOrder.ARTIST_DESC, SortOrder.ALBUM_DESC,
        SortOrder.DATE_ADDED_DESC, SortOrder.DURATION_DESC, SortOrder.YEAR_DESC,
        SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC, SortOrder.PLAYBACK_INFO_DESC
    )
    val comparator = when (order) {
        SortOrder.ARTIST_ASC, SortOrder.ARTIST_DESC -> compareBy<AlbumGroupUi> { it.artist.lowercase() }.thenBy { it.name.lowercase() }
        SortOrder.YEAR_ASC, SortOrder.YEAR_DESC -> compareBy { group -> group.songs.map { it.year }.filter { it > 0 }.minOrNull() ?: 0 }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateModified } ?: 0L }
        SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> compareBy { it.songCount }
        else -> compareBy<AlbumGroupUi> { it.name.lowercase() }.thenBy { it.artist.lowercase() }
    }
    return sortedWith(if (descending) comparator.reversed() else comparator)
}
