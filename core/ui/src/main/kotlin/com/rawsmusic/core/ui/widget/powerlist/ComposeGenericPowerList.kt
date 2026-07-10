package com.rawsmusic.core.ui.widget.powerlist

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget

/**
 * 通用 PowerList 入口。
 *
 * 非文件夹页面建议保持 sharedCoverSceneId = ""，这样只拥有 PowerList 缩放/网格/slot/预热能力，
 * 不参与“列表封面 -> 顶部大封面”的 hero 转场。
 */
@Composable
fun ComposeGenericPowerList(
    items: List<PowerListVisualItem>,
    state: ComposePowerListState,
    modifier: Modifier = Modifier,
    sharedCoverSceneId: String = "",
    playingItemId: Long = -1L,
    selectedPositions: Set<Int> = emptySet(),
    revealIndexRequest: Int = -1,
    hidePlayingCover: Boolean = false,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onItemClick: (PowerListVisualItem, Int, CoverTransitionTarget?) -> Unit = { _, _, _ -> },
    onItemLongClick: (PowerListVisualItem, Int) -> Unit = { _, _ -> }
) {
    val audioFiles = remember(items) {
        items.map { it.toAudioFile() }
    }

    val currentPlayingIndex = if (playingItemId > 0L) {
        items.indexOfFirst { it.stableId == playingItemId }.coerceAtLeast(-1)
    } else {
        -1
    }

    ComposePowerList(
        songs = audioFiles,
        state = state,
        modifier = modifier,
        playingSongId = playingItemId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        revealIndexRequest = revealIndexRequest,
        hidePlayingCover = hidePlayingCover,
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = { _, index ->
            if (sharedCoverSceneId.isBlank()) "" else items.getOrNull(index)?.sharedCoverElementId.orEmpty()
        },
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onRevealCoverTargetResolved = onRevealCoverTargetResolved,
        onSongClick = { _, index ->
            val item = items.getOrNull(index) ?: return@ComposePowerList
            onItemClick(item, index, null)
        },
        onSongLongClick = { _, index ->
            val item = items.getOrNull(index) ?: return@ComposePowerList
            onItemLongClick(item, index)
        }
    )
}

private fun PowerListVisualItem.toAudioFile(): AudioFile {
    return AudioFile(
        id = stableId,
        path = stableKey,
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
        encodingFormat = "",
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
