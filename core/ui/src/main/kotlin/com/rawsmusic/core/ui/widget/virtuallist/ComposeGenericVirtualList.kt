package com.rawsmusic.core.ui.widget.virtuallist

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget

/**
 * 通用 VirtualList 入口。
 *
 * 非文件夹页面建议保持 sharedCoverSceneId = ""，这样只拥有 VirtualList 缩放/网格/slot/预热能力，
 * 不参与“列表封面 -> 顶部大封面”的 hero 转场。
 */
@Composable
fun ComposeGenericVirtualList(
    items: List<VirtualListVisualItem>,
    state: ComposeVirtualListState,
    modifier: Modifier = Modifier,
    sharedCoverSceneId: String = "",
    playingItemId: Long = -1L,
    selectedPositions: Set<Int> = emptySet(),
    revealIndexRequest: Int = -1,
    hidePlayingCover: Boolean = false,
    contentTopPadding: Dp = 0.dp,
    persistentHeaderHeight: Dp = 0.dp,
    persistentHeaderVisibilityHeight: Dp = persistentHeaderHeight,
    persistentHeaderSceneItemVisibilityHeight: Dp = persistentHeaderVisibilityHeight,
    persistentHeaderSceneItemId: String = "",
    virtualListEdgeEnabled: Boolean = false,
    persistentHeaderContent: @Composable (headerVisible: Boolean, sceneItemVisible: Boolean) -> Unit = { _, _ -> },
    contentBottomPadding: Dp? = null,
    sectionHeaders: List<VirtualListSectionHeader> = emptyList(),
    sectionHeaderHeight: Dp = 54.dp,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit = {},
    pinchEnabled: Boolean = true,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onItemCoverTargetChanged: (VirtualListVisualItem, Int, CoverTransitionTarget?) -> Unit = { _, _, _ -> },
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onItemClick: (VirtualListVisualItem, Int, CoverTransitionTarget?) -> Unit = { _, _, _ -> },
    onItemLongClick: (VirtualListVisualItem, Int) -> Unit = { _, _ -> }
) {
    // Keep the provider list lazy. Reference binds only attached holders; it does not materialize
    // an AudioFile-shaped row object for every collection item before a scene can animate.
    val audioFiles = remember(items) {
        retainedMappedList(items) { it.toVirtualListAudioFile() }
    }

    val currentPlayingIndex = remember(items, playingItemId) {
        if (playingItemId > 0L) {
            items.indexOfFirst { it.stableId == playingItemId }.coerceAtLeast(-1)
        } else {
            -1
        }
    }

    ComposeVirtualList(
        songs = audioFiles,
        state = state,
        modifier = modifier,
        playingSongId = playingItemId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        revealIndexRequest = revealIndexRequest,
        hidePlayingCover = hidePlayingCover,
        contentTopPadding = contentTopPadding,
        persistentHeaderHeight = persistentHeaderHeight,
        persistentHeaderVisibilityHeight = persistentHeaderVisibilityHeight,
        persistentHeaderSceneItemVisibilityHeight = persistentHeaderSceneItemVisibilityHeight,
        persistentHeaderSceneItemId = persistentHeaderSceneItemId,
        virtualListEdgeEnabled = virtualListEdgeEnabled,
        persistentHeaderContent = persistentHeaderContent,
        contentBottomPadding = contentBottomPadding,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeight = sectionHeaderHeight,
        sectionHeaderContent = sectionHeaderContent,
        pinchEnabled = pinchEnabled,
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = { _, index ->
            if (sharedCoverSceneId.isBlank()) "" else items.getOrNull(index)?.sharedCoverElementId.orEmpty()
        },
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onSharedCoverTargetChanged = { _, index, target ->
            items.getOrNull(index)?.let { item -> onItemCoverTargetChanged(item, index, target) }
        },
        onRevealCoverTargetResolved = onRevealCoverTargetResolved,
        onSongClick = { _, index ->
            val item = items.getOrNull(index) ?: return@ComposeVirtualList
            onItemClick(item, index, null)
        },
        onSongLongClick = { _, index ->
            val item = items.getOrNull(index) ?: return@ComposeVirtualList
            onItemLongClick(item, index)
        }
    )
}

/**
 * Converts a collection/search row into the lightweight AudioFile shape consumed by ComposeVirtualList.
 *
 * Collection rows are not audio files.  Their stableKey may be an album name, artist, folder group,
 * search key, or another virtual identity.  Putting that value in AudioFile.path makes AudioFile.coverKey
 * reinterpret it as a local audio source and replace the already-resolved artwork key with an invalid
 * `audio://<virtual-key>|0|0` identity.  Keep path empty so albumArtPath remains the authoritative,
 * already-versioned artwork identity supplied by the collection item.
 */
internal fun VirtualListVisualItem.toVirtualListAudioFile(): AudioFile {
    return AudioFile(
        id = stableId,
        path = "",
        title = title,
        artist = subtitle,
        album = "",
        albumArtPath = coverKey,
        duration = 0L,
        sampleRate = 0,
        bitsPerSample = 0,
        format = meta,
        fileSize = 0L,
        trackNumber = 0,
        year = 0,
        dateAdded = 0L,
        dateModified = 0L,
        genre = "",
        composer = "",
        discNumber = 0,
        channelCount = 0,
        bpm = 0,
        albumArtist = "",
        encodingFormat = when (this) {
            is SongVirtualListItem -> song.encodingFormat.ifBlank { "raws_track" }
            is ArtistVirtualListItem,
            is ComposerVirtualListItem -> VIRTUAL_LIST_COLLECTION_OTHER_ENCODING
            is FolderVirtualListItem -> if (showFolderGlyph) {
                VIRTUAL_LIST_FOLDER_ENCODING
            } else {
                VIRTUAL_LIST_COLLECTION_ENCODING
            }
            else -> VIRTUAL_LIST_COLLECTION_ENCODING
        },
        isFavorite = false,
        trackGain = 0f,
        trackPeak = 1.0f,
        albumGain = 0f,
        albumPeak = 1.0f,
        cueOffsetMs = 0L,
        cueEndMs = 0L,
        cueTrackIndex = 0
    )
}
