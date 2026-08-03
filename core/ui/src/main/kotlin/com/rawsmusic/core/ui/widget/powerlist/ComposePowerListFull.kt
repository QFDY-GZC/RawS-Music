package com.rawsmusic.core.ui.widget.powerlist

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget

@Composable
fun ComposePowerListFull(
    songs: List<AudioFile>,
    modifier: Modifier = Modifier,
    playingSongId: Long = -1L,
    currentPlayingIndex: Int = -1,
    selectedPositions: Set<Int> = emptySet(),
    revealIndexRequest: Int = -1,
    hidePlayingCover: Boolean = false,
    contentTopPadding: Dp = 0.dp,
    persistentHeaderHeight: Dp = 0.dp,
    persistentHeaderVisibilityHeight: Dp = persistentHeaderHeight,
    persistentHeaderSceneItemId: String = "",
    persistentHeaderContent: @Composable (visible: Boolean) -> Unit = {},
    sectionHeaders: List<PowerListSectionHeader> = emptyList(),
    sectionHeaderHeight: Dp = 54.dp,
    sectionHeaderContent: @Composable (PowerListSectionHeader) -> Unit = {},
    state: ComposePowerListState = rememberComposePowerListState(),
    sharedCoverSceneId: String = "",
    sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" },
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> }
) {
    ComposePowerList(
        songs = songs,
        state = state,
        modifier = modifier,
        playingSongId = playingSongId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        revealIndexRequest = revealIndexRequest,
        hidePlayingCover = hidePlayingCover,
        contentTopPadding = contentTopPadding,
        persistentHeaderHeight = persistentHeaderHeight,
        persistentHeaderVisibilityHeight = persistentHeaderVisibilityHeight,
        persistentHeaderSceneItemId = persistentHeaderSceneItemId,
        persistentHeaderContent = persistentHeaderContent,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeight = sectionHeaderHeight,
        sectionHeaderContent = sectionHeaderContent,
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onRevealCoverTargetResolved = onRevealCoverTargetResolved,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick
    )
}
