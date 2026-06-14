package com.rawsmusic.core.ui.widget.powerlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage

/**
 * 纯 Compose 版本的 PowerList
 * 替代 PowerListView (6388 行 View 代码)
 *
 * 支持：
 * - 三级缩放 (SMALL/NORMAL/ZOOMED)
 * - 双指缩放手势
 * - 列表/网格切换
 * - 歌曲项点击/长按
 * - 当前播放高亮
 */
@Composable
fun ComposePowerList(
    songs: List<AudioFile>,
    currentPlayingIndex: Int = -1,
    zoomIndex: ListZoomIndex = ListZoomIndex.NORMAL,
    onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onZoomChanged: (ListZoomIndex) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isLight = !ThemeManager.isDarkMode(context)

    val textColor = if (isLight) Color(0xFF1C1B1F) else Color(0xFFE6E1DD)
    val secondaryColor = if (isLight) Color(0xFF49454F) else Color(0xFF9F8D80)
    val highlightColor = if (isLight) Color(0xFFC4956A) else Color(0xFFD4B896)

    // 当前缩放参数
    var currentZoomPosition by remember { mutableFloatStateOf(zoomIndexToPosition(zoomIndex).toFloat()) }
    val currentParams = remember(currentZoomPosition) {
        getZoomParamsAtPosition(currentZoomPosition)
    }

    // 双指缩放手势
    var scale by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale *= zoom
                    // 根据缩放比例调整 zoomPosition
                    val newPosition = when {
                        scale < 0.8f -> (currentZoomPosition - 0.1f).coerceAtLeast(0f)
                        scale > 1.2f -> (currentZoomPosition + 0.1f).coerceAtMost(2f)
                        else -> currentZoomPosition
                    }
                    if (newPosition != currentZoomPosition) {
                        currentZoomPosition = newPosition
                        scale = 1f
                        val newIndex = when {
                            newPosition < 0.5f -> ListZoomIndex.SMALL
                            newPosition < 1.5f -> ListZoomIndex.NORMAL
                            else -> ListZoomIndex.ZOOMED
                        }
                        onZoomChanged(newIndex)
                    }
                }
            }
    ) {
        // 根据缩放级别选择列表或网格
        val isGrid = currentParams.coverSizeDp < 0f || currentParams.coverSizeDp > 100f

        if (isGrid) {
            // 网格模式
            val columns = when {
                currentParams.coverSizeDp >= 100f -> 2
                currentParams.coverSizeDp >= 60f -> 3
                else -> 4
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    ComposeGridItem(
                        song = song,
                        isPlaying = index == currentPlayingIndex,
                        params = currentParams,
                        textColor = textColor,
                        secondaryColor = secondaryColor,
                        highlightColor = highlightColor,
                        onClick = { onSongClick(song, index) },
                        onLongClick = { onSongLongClick(song, index) }
                    )
                }
            }
        } else {
            // 列表模式
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = 180.dp
                )
            ) {
                itemsIndexed(songs) { index, song ->
                    ComposeListItem(
                        song = song,
                        isPlaying = index == currentPlayingIndex,
                        params = currentParams,
                        textColor = textColor,
                        secondaryColor = secondaryColor,
                        highlightColor = highlightColor,
                        onClick = { onSongClick(song, index) },
                        onLongClick = { onSongLongClick(song, index) }
                    )
                }
            }
        }
    }
}

/**
 * 列表项
 */
@Composable
private fun ComposeListItem(
    song: AudioFile,
    isPlaying: Boolean,
    params: ListZoomParams,
    textColor: Color,
    secondaryColor: Color,
    highlightColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val coverSize = params.coverSizeDp.dp
    val cornerRadius = params.cornerRadiusTracksDp.dp
    val textScale = params.textScale

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(params.rowHeightValue.dp)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongClick() }
                )
            }
            .padding(
                start = params.coverMarginLeftDp.dp,
                top = params.coverMarginTopDp.dp,
                end = params.textMarginRightDp.dp,
                bottom = params.coverMarginBottomDp.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        if (song.albumArtPath.isNotBlank()) {
            BitmapImage(
                key = song.albumArtPath,
                contentDescription = song.displayName,
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
                targetWidth = 256,
                targetHeight = 256
            )
        } else {
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.White.copy(alpha = 0.1f))
            )
        }

        Spacer(modifier = Modifier.width(params.textMarginLeftDp.dp))

        // 文字信息
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // 标题
            Text(
                text = song.displayName,
                fontSize = (14 * textScale).sp,
                fontWeight = FontWeight.Medium,
                color = if (isPlaying) highlightColor else textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 第二行（艺术家/专辑）
            if (params.line2Visible) {
                val line2 = if (song.artist.isNotBlank() && song.album.isNotBlank()) {
                    "${song.artist} · ${song.album}"
                } else {
                    song.artist.ifBlank { song.album }
                }
                if (line2.isNotBlank()) {
                    Text(
                        text = line2,
                        fontSize = (12 * textScale).sp,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 元数据（时长/格式）
            if (params.metaVisible) {
                val meta = buildString {
                    if (song.duration > 0) {
                        val min = song.duration / 60000
                        val sec = (song.duration % 60000) / 1000
                        append("$min:${sec.toString().padStart(2, '0')}")
                    }
                    if (song.sampleRate > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${song.sampleRate / 1000}kHz")
                    }
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        fontSize = (11 * textScale).sp,
                        color = secondaryColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 网格项
 */
@Composable
private fun ComposeGridItem(
    song: AudioFile,
    isPlaying: Boolean,
    params: ListZoomParams,
    textColor: Color,
    secondaryColor: Color,
    highlightColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cornerRadius = params.cornerRadiusAlbumsDp.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongClick() }
                )
            }
            .padding(4.dp)
    ) {
        // 封面
        if (song.albumArtPath.isNotBlank()) {
            BitmapImage(
                key = song.albumArtPath,
                contentDescription = song.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
                targetWidth = 256,
                targetHeight = 256
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.White.copy(alpha = 0.1f))
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 标题
        Text(
            text = song.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isPlaying) highlightColor else textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 艺术家
        if (song.artist.isNotBlank()) {
            Text(
                text = song.artist,
                fontSize = 11.sp,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
