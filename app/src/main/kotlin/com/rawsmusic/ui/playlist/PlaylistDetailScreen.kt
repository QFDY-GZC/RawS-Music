package com.rawsmusic.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import com.rawsmusic.module.data.prefs.PlaylistStore
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PlaylistDetailScreen(
    playlistStore: PlaylistStore,
    playlist: UserPlaylist?,
    librarySongs: List<AudioFile>,
    playingSongId: Long,
    onBack: () -> Unit,
    onPlaySong: (List<AudioFile>, Int) -> Unit,
    onRemoveSong: (AudioFile) -> Unit
) {
    val songs = playlistStore.resolveSongs(playlist, librarySongs)
    val state = rememberComposePowerListState("playlist-${playlist?.id.orEmpty()}")
    LibraryListScaffold(
        title = playlist?.name ?: stringResource(R.string.playlist_fallback_name),
        sceneId = NavScene.PLAYLIST_DETAIL_PAGE.name,
        onBack = onBack,
        powerListState = state,
        onShuffle = { if (songs.isNotEmpty()) onPlaySong(songs.shuffled(), 0) },
        contentOverlap = 0.dp,
        modifier = Modifier.fillMaxSize()
    ) { topPadding, backdropSource ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().then(backdropSource),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.playlist_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 15.sp
                )
            }
        } else {
            ComposePowerListFull(
                songs = songs,
                playingSongId = playingSongId,
                state = state,
                contentTopPadding = topPadding,
                modifier = Modifier.fillMaxSize().then(backdropSource),
                onSongClick = { _, index -> onPlaySong(songs, index) },
                onSongLongClick = { song, _ -> onRemoveSong(song) }
            )
        }
    }
}
