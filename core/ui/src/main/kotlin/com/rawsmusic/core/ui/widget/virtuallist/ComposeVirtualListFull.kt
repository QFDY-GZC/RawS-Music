package com.rawsmusic.core.ui.widget.virtuallist

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget

@Composable
fun ComposeVirtualListFull(
    songs: List<AudioFile>,
    modifier: Modifier = Modifier,
    playingSongId: Long = -1L,
    currentPlayingIndex: Int = -1,
    selectedPositions: Set<Int> = emptySet(),
    revealIndexRequest: Int = -1,
    hidePlayingCover: Boolean = false,
    contentTopPadding: Dp = 0.dp,
    contentBottomPadding: Dp? = null,
    presentationTopClipInset: Dp? = null,
    persistentHeaderHeight: Dp = 0.dp,
    persistentHeaderVisibilityHeight: Dp = persistentHeaderHeight,
    persistentHeaderSceneItemVisibilityHeight: Dp = persistentHeaderVisibilityHeight,
    persistentHeaderSceneItemId: String = "",
    virtualListEdgeEnabled: Boolean = false,
    persistentHeaderContent: @Composable (headerVisible: Boolean, sceneItemVisible: Boolean) -> Unit = { _, _ -> },
    sectionHeaders: List<VirtualListSectionHeader> = emptyList(),
    sectionHeaderHeight: Dp = 54.dp,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit = {},
    state: ComposeVirtualListState = rememberComposeVirtualListState(),
    sharedCoverSceneId: String = "",
    sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" },
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onScrollActiveChanged: (Boolean) -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> }
) {
    ComposeVirtualList(
        songs = songs,
        state = state,
        modifier = modifier,
        playingSongId = playingSongId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        revealIndexRequest = revealIndexRequest,
        hidePlayingCover = hidePlayingCover,
        contentTopPadding = contentTopPadding,
        contentBottomPadding = contentBottomPadding,
        presentationTopClipInset = presentationTopClipInset,
        persistentHeaderHeight = persistentHeaderHeight,
        persistentHeaderVisibilityHeight = persistentHeaderVisibilityHeight,
        persistentHeaderSceneItemVisibilityHeight = persistentHeaderSceneItemVisibilityHeight,
        persistentHeaderSceneItemId = persistentHeaderSceneItemId,
        virtualListEdgeEnabled = virtualListEdgeEnabled,
        persistentHeaderContent = persistentHeaderContent,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeight = sectionHeaderHeight,
        sectionHeaderContent = sectionHeaderContent,
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onRevealCoverTargetResolved = onRevealCoverTargetResolved,
        onScrollActiveChanged = onScrollActiveChanged,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick
    )
}
