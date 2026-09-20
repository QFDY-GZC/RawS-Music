package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState

@Composable
fun QueuePage(
    songs: List<AudioFile>,
    currentIndex: Int,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit,
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("queue"),
) {
    // Queue entry is anchored before its first visible layout. Returning to the page keeps the
    // hoisted viewport instead, because seedInitialScrollToIndex becomes a no-op after first bind.
    virtualListState.seedInitialScrollToIndex(currentIndex)
    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_queue),
        sceneId = NavScene.QUEUE.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_songs, songs.size),
        onBack = onBack,
        virtualListState = virtualListState,
        onShuffle = { if (songs.isNotEmpty()) onShuffle(songs) }
    ) { topPadding, backdropSource ->
        SelectableSongList(
            songs = songs,
            currentPlayingIndex = currentIndex,
            state = virtualListState,
            contentTopPadding = topPadding,
            modifier = Modifier.then(backdropSource),
            menuKind = SelectionMenuKind.QUEUE,
            selectionActions = selectionActions,
            onSongClick = onSongClick,
        )
    }
}
