package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.pages.SongsSortLayoutSheet
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Sort
import kotlinx.coroutines.launch

internal data class InlinePlayerQueueColors(
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val icon: Color,
    val currentBackground: Color,
    val artworkPlaceholder: Color
)

/**
 * Embedded player queue. It deliberately has no page background or back button: the player artwork
 * and the five transport buttons remain the stable frame while only the information area changes.
 */
@Composable
internal fun InlinePlayerQueue(
    songs: List<AudioFile>,
    currentIndex: Int,
    currentSong: AudioFile?,
    currentCoverPath: String?,
    colors: InlinePlayerQueueColors,
    onSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val powerListState = rememberComposePowerListState("inline_queue_fullscreen")
    val locateScope = rememberCoroutineScope()
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.PLAYBACK_INFO) }
    var showSortLayout by rememberSaveable { mutableStateOf(false) }
    val resolvedIndex = currentIndex.takeIf { it in songs.indices }
        ?: songs.indexOfFirst { candidate ->
            candidate.path == currentSong?.path && candidate.cueTrackIndex == currentSong?.cueTrackIndex
        }
    val summarySong = songs.getOrNull(resolvedIndex) ?: currentSong
    val sortedEntries = remember(songs, sortOrder) {
        sortQueueEntries(songs, sortOrder)
    }
    val fullscreenSongs = remember(sortedEntries) { sortedEntries.map { it.song } }
    val fullscreenCurrentIndex = sortedEntries.indexOfFirst { it.originalIndex == resolvedIndex }

    LaunchedEffect(resolvedIndex, songs.size) {
        if (!fullscreen && resolvedIndex in songs.indices) {
            runCatching { listState.animateScrollToItem(resolvedIndex) }
        }
    }

    LaunchedEffect(fullscreen, fullscreenCurrentIndex, sortOrder, songs.size) {
        if (fullscreen && fullscreenCurrentIndex in fullscreenSongs.indices) {
            powerListState.requestScrollToIndex(fullscreenCurrentIndex)
        }
    }

    LaunchedEffect(fullscreen) {
        if (!fullscreen) showSortLayout = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.ListView,
                contentDescription = null,
                tint = colors.icon,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "播放队列",
                color = colors.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (fullscreen) {
                QueueMiuixButton(
                    imageVector = MiuixIcons.Regular.Sort,
                    contentDescription = stringResource(R.string.queue_sort_layout),
                    tint = colors.icon,
                    onClick = { showSortLayout = true }
                )
                Spacer(Modifier.width(2.dp))
            }
            QueueResourceButton(
                iconRes = R.drawable.ic_queue_fullscreen,
                contentDescription = stringResource(
                    if (fullscreen) R.string.queue_exit_fullscreen else R.string.queue_enter_fullscreen
                ),
                tint = colors.icon,
                onClick = { onFullscreenChange(!fullscreen) }
            )
            Spacer(Modifier.width(2.dp))
            QueueLocateButton(
                enabled = if (fullscreen) {
                    fullscreenCurrentIndex in fullscreenSongs.indices
                } else {
                    resolvedIndex in songs.indices
                },
                tint = colors.icon,
                onClick = {
                    if (fullscreen && fullscreenCurrentIndex in fullscreenSongs.indices) {
                        powerListState.requestScrollToIndex(fullscreenCurrentIndex)
                    } else if (resolvedIndex in songs.indices) {
                        locateScope.launch {
                            runCatching { listState.animateScrollToItem(resolvedIndex) }
                        }
                    }
                }
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (resolvedIndex in songs.indices) {
                    "${resolvedIndex + 1} / ${songs.size}"
                } else {
                    "${songs.size} 首"
                },
                color = colors.secondaryText,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        CurrentQueueSummary(
            song = summarySong,
            coverPath = currentCoverPath,
            colors = colors,
            onClearPriorityQueue = onClearPriorityQueue,
            modifier = Modifier.zIndex(2f)
        )
        Spacer(Modifier.height(8.dp))

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("队列为空", color = colors.secondaryText, fontSize = 14.sp)
            }
        } else {
            if (fullscreen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                ) {
                    ComposePowerListFull(
                        songs = fullscreenSongs,
                        currentPlayingIndex = fullscreenCurrentIndex,
                        state = powerListState,
                        modifier = Modifier.fillMaxSize(),
                        onSongClick = { _, sortedIndex ->
                            val entry = sortedEntries.getOrNull(sortedIndex) ?: return@ComposePowerListFull
                            onSongClick(entry.song, entry.originalIndex)
                        }
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = songs,
                        key = { index, song -> "${song.path}|${song.cueTrackIndex}|$index" }
                    ) { index, song ->
                        InlineQueueRow(
                            song = song,
                            index = index,
                            isCurrent = index == resolvedIndex,
                            colors = colors,
                            onClick = { onSongClick(song, index) }
                        )
                    }
                }
            }
        }
        }

        SongsSortLayoutSheet(
            visible = fullscreen && showSortLayout,
            currentSortOrder = sortOrder,
            powerListState = powerListState,
            onSortSelected = { sortOrder = it },
            sortOptions = listOf(
                stringResource(R.string.queue_sort_original) to SortOrder.PLAYBACK_INFO,
                stringResource(R.string.sort_by_name) to SortOrder.TITLE_ASC,
                stringResource(R.string.sort_by_artist) to SortOrder.ARTIST_ASC,
                stringResource(R.string.queue_sort_album) to SortOrder.ALBUM_ASC,
                stringResource(R.string.sort_by_duration) to SortOrder.DURATION_ASC
            ),
            onDismiss = { showSortLayout = false }
        )
    }
}

@Composable
private fun QueueMiuixButton(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun QueueResourceButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun QueueLocateButton(
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_now_playing_locator),
            contentDescription = "定位到当前歌曲",
            colorFilter = ColorFilter.tint(
                tint.copy(alpha = if (enabled) 1f else 0.36f)
            ),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CurrentQueueSummary(
    song: AudioFile?,
    coverPath: String?,
    colors: InlinePlayerQueueColors,
    onClearPriorityQueue: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.currentBackground)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QueueArtwork(
            song = song,
            fallback = coverPath,
            size = 52,
            corner = 11,
            colors = colors
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song?.displayName ?: "暂无播放歌曲",
                color = colors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song?.artist?.ifBlank { "未知艺术家" }.orEmpty(),
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onClearPriorityQueue != null) {
            Text(
                text = "清空优先队列",
                color = colors.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onClearPriorityQueue)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            )
        } else {
            Text(
                text = "正在播放",
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun InlineQueueRow(
    song: AudioFile,
    index: Int,
    isCurrent: Boolean,
    colors: InlinePlayerQueueColors,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) colors.currentBackground else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                )
            } else {
                Text(
                    text = (index + 1).toString(),
                    color = colors.secondaryText,
                    fontSize = 11.sp
                )
            }
        }
        QueueArtwork(
            song = song,
            fallback = null,
            size = 40,
            corner = 9,
            colors = colors
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayName,
                color = if (isCurrent) colors.accent else colors.primaryText,
                fontSize = 13.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist.ifBlank { "未知艺术家" },
                color = colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = AudioUtils.formatDuration(song.duration),
            color = colors.secondaryText,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun QueueArtwork(
    song: AudioFile?,
    fallback: String?,
    size: Int,
    corner: Int,
    colors: InlinePlayerQueueColors
) {
    val key = song.resolvePlaybackArtworkKey(fallback).orEmpty()
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(colors.artworkPlaceholder),
        contentAlignment = Alignment.Center
    ) {
        if (key.isNotBlank()) {
            BitmapImage(
                key = key,
                contentDescription = song?.displayName,
                contentScale = ContentScale.Crop,
                targetWidth = if (size >= 50) 192 else 128,
                targetHeight = if (size >= 50) 192 else 128,
                surface = ArtworkSurface.List,
                fadeInMillis = 0,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private data class QueueSortEntry(
    val song: AudioFile,
    val originalIndex: Int
)

private fun sortQueueEntries(
    songs: List<AudioFile>,
    sortOrder: SortOrder
): List<QueueSortEntry> {
    val entries = songs.mapIndexed { index, song -> QueueSortEntry(song, index) }
    val comparator = when (sortOrder) {
        SortOrder.TITLE_ASC, SortOrder.TITLE_DESC -> compareBy<QueueSortEntry> { it.song.displayName.lowercase() }
        SortOrder.ARTIST_ASC, SortOrder.ARTIST_DESC -> compareBy<QueueSortEntry> { it.song.artist.lowercase() }
            .thenBy { it.song.displayName.lowercase() }
        SortOrder.ALBUM_ASC, SortOrder.ALBUM_DESC -> compareBy<QueueSortEntry> { it.song.album.lowercase() }
            .thenBy { it.song.displayName.lowercase() }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy<QueueSortEntry> { it.song.duration }
        else -> compareBy<QueueSortEntry> { it.originalIndex }
    }
    val descending = when (sortOrder) {
        SortOrder.TITLE_DESC,
        SortOrder.ARTIST_DESC,
        SortOrder.ALBUM_DESC,
        SortOrder.DURATION_DESC,
        SortOrder.PLAYBACK_INFO_DESC -> true
        else -> false
    }
    return entries.sortedWith(if (descending) comparator.reversed() else comparator)
}
