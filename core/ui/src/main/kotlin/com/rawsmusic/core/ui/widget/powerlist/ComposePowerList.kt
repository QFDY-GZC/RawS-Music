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
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.scene.AAItemView
import com.rawsmusic.core.ui.widget.scene.ComposeAAItemView

/**
 * 纯 Compose 版本的 PowerList
 * 使用 ComposeAAItemView 渲染每一项
 *
 * 支持：
 * - 三级缩放 (SMALL/NORMAL/ZOOMED)
 * - 双指缩放手势
 * - 列表/网格切换
 * - 场景过渡动画
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
    // 缩放状态
    var currentZoomPosition by remember { mutableFloatStateOf(zoomIndexToFloat(zoomIndex)) }
    val currentParams = remember(currentZoomPosition) {
        getZoomParamsAtPosition(currentZoomPosition)
    }

    // 当前场景 ID
    val currentScene = remember(currentParams) {
        ListZoomLevels.sceneIdForParams(currentParams)
    }

    // 是否网格模式
    val isGrid = currentScene == AAItemView.SCENE_GRID

    // 缩放手势
    val zoomState = rememberZoomGestureState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .zoomGesture(zoomState) { newIndex ->
                currentZoomPosition = zoomIndexToFloat(newIndex)
                onZoomChanged(newIndex)
            }
    ) {
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
                    ComposeAAItemView(
                        song = song,
                        isPlaying = index == currentPlayingIndex,
                        currentScene = currentScene,
                        zoomParams = currentParams,
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
                    ComposeAAItemView(
                        song = song,
                        isPlaying = index == currentPlayingIndex,
                        currentScene = currentScene,
                        zoomParams = currentParams,
                        onClick = { onSongClick(song, index) },
                        onLongClick = { onSongLongClick(song, index) }
                    )
                }
            }
        }
    }
}

private fun zoomIndexToFloat(index: ListZoomIndex): Float {
    return when (index) {
        ListZoomIndex.SMALL -> 0f
        ListZoomIndex.NORMAL -> 1f
        ListZoomIndex.ZOOMED -> 2f
    }
}
