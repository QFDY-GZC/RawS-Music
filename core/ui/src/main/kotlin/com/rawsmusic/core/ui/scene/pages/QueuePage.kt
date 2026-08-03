package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState

@Composable
fun QueuePage(
    songs: List<AudioFile>,
    currentIndex: Int,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onShuffle: (List<AudioFile>) -> Unit
) {
    val state = rememberComposePowerListState("queue")
    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_queue),
        sceneId = NavScene.QUEUE.name,
        onBack = onBack,
        powerListState = state,
        onShuffle = { if (songs.isNotEmpty()) onShuffle(songs) }
    ) { topPadding, backdropSource ->
        ComposePowerListFull(
            songs = songs,
            currentPlayingIndex = currentIndex,
            state = state,
            contentTopPadding = topPadding,
            modifier = Modifier.then(backdropSource),
            onSongClick = onSongClick
        )
    }
}
