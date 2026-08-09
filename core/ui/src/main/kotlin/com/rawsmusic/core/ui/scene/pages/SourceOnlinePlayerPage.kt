package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransition
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.playbackArtworkSwipeGesture
import com.rawsmusic.core.ui.widget.bitmaps.rememberPlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.player.ComposePlayerControls
import com.rawsmusic.core.ui.widget.player.ComposePlayerTitleInfo
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.playback.MusicSourceArtworkRepository
import com.rawsmusic.module.data.source.playback.MusicSourceLyricSnapshot
import com.rawsmusic.module.data.source.playback.MusicSourceLyricStatus
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackSnapshot
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Ordinary-player visual shell backed by the independent online queue. */
@Composable
internal fun SourceOnlinePlayerPage(
    playback: MusicSourcePlaybackSnapshot,
    lyric: MusicSourceLyricSnapshot,
    artworkPaths: Map<String, String>,
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectQuality: (RawSourceQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val item = playback.currentItem
    val previous = playback.queue.getOrNull(playback.currentIndex - 1)
    val next = playback.queue.getOrNull(playback.currentIndex + 1)
    val currentPath = item?.let { artworkPaths[it.stableIdentity] }
    val previousPath = previous?.let { artworkPaths[it.stableIdentity] }
    val nextPath = next?.let { artworkPaths[it.stableIdentity] }
    val currentKey = currentPath ?: item?.stableIdentity
    val transitionState = rememberPlaybackArtworkTransitionState(
        currentKey = currentKey,
        queueCurrentIndex = playback.currentIndex,
        queueSize = playback.queue.size,
    )
    val animationStyle = remember {
        PlayerArtworkAnimationStyle.from(AppPreferences.UI.playerArtworkAnimationStyle)
    }
    var showQualityDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(item?.stableIdentity, previous?.stableIdentity, next?.stableIdentity) {
        MusicSourceArtworkRepository.prefetch(context, item)
        MusicSourceArtworkRepository.prefetch(context, previous)
        MusicSourceArtworkRepository.prefetch(context, next)
    }
    LaunchedEffect(previousPath, nextPath, previous?.stableIdentity, next?.stableIdentity) {
        transitionState.setGestureTargetKeys(
            previousPath ?: previous?.stableIdentity,
            nextPath ?: next?.stableIdentity,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        StandardPlayerBackdrop(
            coverPath = currentPath,
            accent = scheme.primary,
            artworkTransitionState = transitionState,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.source_player_collapse), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (playback.requestedQuality == playback.resolvedQuality) {
                        playback.requestedQuality.sourceQualityLabel()
                    } else {
                        "${playback.requestedQuality.sourceQualityLabel()} · 实际 ${playback.resolvedQuality.sourceQualityLabel()}"
                    },
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.22f))
                        .clickable(enabled = item != null) { showQualityDialog = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.player_lyrics_short),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = item != null, onClick = onOpenLyrics)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            if (!currentPath.isNullOrBlank()) {
                PlaybackArtworkTransition(
                    state = transitionState,
                    animationStyle = animationStyle,
                    contentScale = ContentScale.Crop,
                    cornerRadius = 30.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .playbackArtworkSwipeGesture(
                            state = transitionState,
                            previousKey = previousPath ?: previous?.stableIdentity,
                            nextKey = nextPath ?: next?.stableIdentity,
                            enabled = playback.queue.size > 1,
                            onPrevious = onPrevious,
                            onNext = onNext,
                        ),
                )
            } else {
                SourceArtwork(
                    item = item,
                    localPath = null,
                    targetSize = 1024,
                    cornerRadiusDp = 30,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            ComposePlayerTitleInfo(
                title = item?.title ?: stringResource(R.string.source_online_song_empty_title),
                artist = item?.artists?.joinToString(" / ").orEmpty(),
                album = item?.album.orEmpty(),
                titleColor = Color.White,
                artistColor = Color.White.copy(alpha = 0.78f),
                albumColor = Color.White.copy(alpha = 0.56f),
            )

            Spacer(Modifier.height(12.dp))
            OnlineLyricPreview(
                playback = playback,
                lyric = lyric,
                onClick = onOpenLyrics,
            )
            Spacer(Modifier.weight(1f))

            ComposePlayerControls(
                isPlaying = playback.isPlaying,
                currentPositionMs = playback.positionMs,
                totalDurationMs = playback.durationMs,
                previousIconRes = R.drawable.ic_skip_previous,
                playIconRes = R.drawable.ic_play,
                pauseIconRes = R.drawable.ic_pause,
                nextIconRes = R.drawable.ic_skip_next,
                onSeekStart = {},
                onSeekStop = { fraction ->
                    val duration = playback.durationMs
                    if (duration > 0L) onSeek((duration * fraction.coerceIn(0f, 1f)).toLong())
                },
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showQualityDialog && item != null) {
        SourceQualitySelectionDialog(
            title = stringResource(R.string.source_quality_title),
            qualities = item.availableQualities,
            selectedQuality = playback.requestedQuality,
            summary = stringResource(R.string.source_quality_summary),
            onDismiss = { showQualityDialog = false },
            onSelect = { quality ->
                showQualityDialog = false
                onSelectQuality(quality)
            },
        )
    }
}

@Composable
private fun OnlineLyricPreview(
    playback: MusicSourcePlaybackSnapshot,
    lyric: MusicSourceLyricSnapshot,
    onClick: () -> Unit,
) {
    val matchesCurrent = playback.currentItem?.stableIdentity == lyric.itemIdentity
    val currentIndex = remember(matchesCurrent, lyric.itemIdentity, lyric.lines, playback.positionMs) {
        if (matchesCurrent) lyric.currentLineIndex(playback.positionMs) else -1
    }
    val text = when {
        !matchesCurrent || lyric.status == MusicSourceLyricStatus.Loading -> stringResource(R.string.source_online_lyrics_loading)
        lyric.status == MusicSourceLyricStatus.Error -> stringResource(R.string.source_online_lyrics_error)
        lyric.status == MusicSourceLyricStatus.Empty -> stringResource(R.string.source_online_lyrics_empty)
        currentIndex in lyric.lines.indices -> lyric.lines[currentIndex].text
        lyric.lines.isNotEmpty() -> lyric.lines.first().text
        else -> stringResource(R.string.source_online_lyrics_open)
    }
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.76f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .clickable(enabled = playback.currentItem != null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}
