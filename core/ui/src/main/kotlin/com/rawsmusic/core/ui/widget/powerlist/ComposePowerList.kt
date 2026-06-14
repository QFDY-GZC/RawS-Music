package com.rawsmusic.core.ui.widget.powerlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.theme.ThemeManager

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
 * - 淡入淡出动画
 * - 专辑图转换动画
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
    val isLight = !ThemeManager.isDarkMode(context)

    val textColor = if (isLight) Color(0xFF1C1B1F) else Color(0xFFE6E1DD)
    val secondaryColor = if (isLight) Color(0xFF49454F) else Color(0xFF9F8D80)
    val highlightColor = if (isLight) Color(0xFFC4956A) else Color(0xFFD4B896)

    // 缩放状态
    var currentZoomPosition by remember { mutableFloatStateOf(zoomIndexToInt(zoomIndex).toFloat()) }
    val currentParams = remember(currentZoomPosition) {
        getZoomParamsAtPosition(currentZoomPosition)
    }

    // 缩放手势
    val zoomState = rememberZoomGestureState()

    // 场景返回状态
    val sceneReturnState = rememberSceneReturnState()

    // 网格/列表状态
    val gridListState = rememberGridListState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .zoomGesture(zoomState) { newIndex ->
                currentZoomPosition = when (newIndex) {
                    ListZoomIndex.SMALL -> 0f
                    ListZoomIndex.NORMAL -> 1f
                    ListZoomIndex.ZOOMED -> 2f
                }
                onZoomChanged(newIndex)
            }
    ) {
        val isGrid = currentParams.coverSizeDp < 0f || currentParams.coverSizeDp > 100f

        if (isGrid) {
            // 网格模式
            val columns = ComposeLayoutEngine.calculateGridColumns(currentParams)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    PowerGridItem(
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 180.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    PowerListItem(
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

private fun zoomIndexToInt(index: ListZoomIndex): Int {
    return when (index) {
        ListZoomIndex.SMALL -> 0
        ListZoomIndex.NORMAL -> 1
        ListZoomIndex.ZOOMED -> 2
    }
}
