package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState

private const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

@Composable
fun RecentlyAddedPage(
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit,
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("recently_added"),
) {
    val recentSongs = remember(songs) {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        songs.asSequence()
            .filter { it.dateAdded > cutoff || it.dateModified > cutoff }
            .sortedByDescending { maxOf(it.dateAdded, it.dateModified) }
            .toList()
    }
    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_recently_added),
        sceneId = NavScene.RECENTLY_ADDED.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_recent, recentSongs.size),
        onBack = onBack,
        virtualListState = virtualListState,
        onShuffle = { if (recentSongs.isNotEmpty()) onShuffle(recentSongs) }
    ) { topPadding, backdropSource ->
        SelectableSongList(
            songs = recentSongs,
            state = virtualListState,
            contentTopPadding = topPadding,
            modifier = Modifier.then(backdropSource),
            selectionActions = selectionActions,
            onSongClick = onSongClick,
        )
    }
}
