package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource

/** MAIN-level queue popup opened directly from the MiniPlayer without expanding PLAYER. */
@Composable
fun MiniPlayerQueueDialog(
    show: Boolean,
    songs: List<AudioFile>,
    currentIndex: Int,
    currentSong: AudioFile?,
    currentCoverPath: String?,
    onSongClick: (AudioFile, Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!show) return
    val scheme = MiuixTheme.colorScheme
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.queue_title),
        summary = if (songs.isEmpty()) stringResource(R.string.queue_empty) else null,
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
                .padding(top = 4.dp),
        ) {
            InlinePlayerQueue(
                songs = songs,
                currentIndex = currentIndex,
                currentSong = currentSong,
                currentCoverPath = currentSong.resolvePlaybackArtworkKey(currentCoverPath),
                colors = InlinePlayerQueueColors(
                    primaryText = scheme.onSurface,
                    secondaryText = scheme.onSurfaceVariantSummary,
                    accent = scheme.primary,
                    icon = scheme.onSurface,
                    currentBackground = scheme.surfaceContainer.copy(alpha = 0.72f),
                    artworkPlaceholder = Color.Black.copy(alpha = 0.08f),
                ),
                onSongClick = { song, index ->
                    onSongClick(song, index)
                    onDismissRequest()
                },
                onClearPriorityQueue = null,
                fullscreen = false,
                onFullscreenChange = {},
                showFullscreenControl = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
