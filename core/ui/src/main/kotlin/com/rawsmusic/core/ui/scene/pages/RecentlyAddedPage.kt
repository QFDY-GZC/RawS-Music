package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState

private const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

@Composable
fun RecentlyAddedPage(
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onShuffle: (List<AudioFile>) -> Unit
) {
    val recentSongs = remember(songs) {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        songs.asSequence()
            .filter { it.dateAdded > cutoff || it.dateModified > cutoff }
            .sortedByDescending { maxOf(it.dateAdded, it.dateModified) }
            .toList()
    }
    val state = rememberComposePowerListState("recently_added")
    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_recently_added),
        sceneId = NavScene.RECENTLY_ADDED.name,
        onBack = onBack,
        powerListState = state,
        onShuffle = { if (recentSongs.isNotEmpty()) onShuffle(recentSongs) }
    ) { topPadding, backdropSource ->
        ComposePowerListFull(
            songs = recentSongs,
            state = state,
            contentTopPadding = topPadding,
            modifier = Modifier.then(backdropSource),
            onSongClick = onSongClick
        )
    }
}
