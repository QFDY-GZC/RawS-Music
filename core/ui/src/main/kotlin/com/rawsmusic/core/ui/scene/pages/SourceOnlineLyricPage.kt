package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.source.playback.MusicSourceLyricSnapshot
import com.rawsmusic.module.data.source.playback.MusicSourceLyricStatus
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackSnapshot
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Standalone online lyric scene. It owns no local-player state. */
@Composable
internal fun SourceOnlineLyricPage(
    playback: MusicSourcePlaybackSnapshot,
    lyric: MusicSourceLyricSnapshot,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val item = playback.currentItem
    val lyricMatchesItem = item != null && lyric.itemIdentity == item.stableIdentity
    val effectiveStatus = if (lyricMatchesItem) lyric.status else MusicSourceLyricStatus.Loading
    val currentLineIndex = remember(lyricMatchesItem, lyric.lines, lyric.isTimed, playback.positionMs) {
        if (lyricMatchesItem) lyric.currentLineIndex(playback.positionMs) else -1
    }
    val listState = rememberLazyListState()

    LaunchedEffect(lyric.itemIdentity, currentLineIndex) {
        if (currentLineIndex >= 0 && lyric.lines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem((currentLineIndex - 2).coerceAtLeast(0)) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.source_back_to_player), tint = scheme.onBackground)
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.source_online_lyrics_title), color = scheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = item?.let { current ->
                        listOf(current.title, current.artists.joinToString(" / "))
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                    } ?: stringResource(R.string.source_online_song_unselected),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lyricMatchesItem && lyric.providerLabel.isNotBlank()) {
                    Text(
                        text = lyric.providerLabel,
                        color = scheme.primary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (item == null) {
            SourceEmptyPanel(
                title = stringResource(R.string.source_online_song_empty_title),
                summary = stringResource(R.string.source_online_song_empty_summary),
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }

        when (effectiveStatus) {
            MusicSourceLyricStatus.Idle,
            MusicSourceLyricStatus.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.source_lyrics_loading), color = scheme.onSurfaceVariantSummary, fontSize = 13.sp)
                }
            }

            MusicSourceLyricStatus.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFE15B64).copy(alpha = 0.10f))
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.source_lyrics_error_title), color = scheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        lyric.error.ifBlank { stringResource(R.string.source_lyrics_error_fallback) },
                        color = Color(0xFFE15B64),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.primary.copy(alpha = 0.10f))
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_retry),
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.source_retry), color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            MusicSourceLyricStatus.Empty -> SourceEmptyPanel(
                title = stringResource(R.string.source_lyrics_empty_title),
                summary = buildString {
                    append(stringResource(R.string.source_lyrics_empty_summary))
                    if (lyric.providerLabel.isNotBlank()) append(" 来源：${lyric.providerLabel}")
                },
                modifier = Modifier.padding(20.dp),
            )

            MusicSourceLyricStatus.Ready -> {
                if (!lyric.isTimed) {
                    Text(
                        text = stringResource(R.string.source_lyrics_static_hint),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = lyric.lines,
                        key = { index, line -> "${line.timestampMs}:$index:${line.text.hashCode()}" },
                    ) { index, line ->
                        val active = index == currentLineIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (active) scheme.primary.copy(alpha = 0.11f) else Color.Transparent)
                                .clickable(enabled = lyric.isTimed && line.timestampMs >= 0L) { onSeek(line.timestampMs) }
                                .padding(horizontal = 14.dp, vertical = if (active) 13.dp else 8.dp),
                        ) {
                            Text(
                                text = line.text.ifBlank { "…" },
                                color = if (active) scheme.primary else scheme.onBackground.copy(alpha = 0.68f),
                                fontSize = if (active) 21.sp else 16.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                lineHeight = if (active) 30.sp else 23.sp,
                            )
                            if (line.translation.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = line.translation,
                                    color = if (active) scheme.onBackground.copy(alpha = 0.78f)
                                    else scheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
                                    fontSize = if (active) 13.sp else 12.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            if (line.romanization.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = line.romanization,
                                    color = scheme.onSurfaceVariantSummary.copy(alpha = if (active) 0.72f else 0.55f),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
