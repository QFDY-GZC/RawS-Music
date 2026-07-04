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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
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
    powerListState: ComposePowerListState = rememberComposePowerListState("albums"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("album_detail_songs")
    val albums by remember(songs) {
        derivedStateOf { songs.toAlbumGroups() }
    }

    if (selectedAlbumKey.isNullOrBlank()) {
        AlbumListPage(
            albums = albums,
            state = powerListState,
            onAlbumClick = onAlbumClick,
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
            modifier = modifier
        )
    }
}

@Composable
private fun AlbumListPage(
    albums: List<AlbumGroupUi>,
    state: ComposePowerListState,
    onAlbumClick: (String) -> Unit,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        ComposeGenericPowerList(
            items = items,
            state = state,
            sharedCoverSceneId = NavScene.ALBUMS.name,
            modifier = Modifier.fillMaxSize(),
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
                .zIndex(30f),
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
            meta = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}",
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
                coverKey = coverSong?.albumArtPath?.ifBlank { coverSong.path }.orEmpty(),
                totalDurationMs = albumSongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
        .sortedWith(compareBy<AlbumGroupUi> { it.name.lowercase() }.thenBy { it.artist.lowercase() })
}
