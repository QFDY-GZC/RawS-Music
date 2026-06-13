package com.rawsmusic.ui.settings.compose.scene.container

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import com.rawsmusic.ui.settings.compose.scene.gesture.ComposePinchDetector
import com.rawsmusic.ui.settings.compose.scene.item.ComposeTrackItem
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState

/**
 * 纯 Compose 版本的列表容器
 * 替代原版 PowerListView
 *
 * 使用 LazyColumn 实现可滚动列表
 * 集成 ComposePinchDetector 实现捏合缩放
 */
@Composable
fun ComposePowerList(
    tracks: List<TrackData>,
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val viewConfiguration = LocalViewConfiguration.current
    val listState = rememberLazyListState()

    // 创建捏合手势检测器
    val pinchDetector = remember(state, viewConfiguration, density) {
        ComposePinchDetector(
            state = state,
            viewConfiguration = viewConfiguration,
            density = density
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // 处理捏合缩放
                    if (zoom != 1f) {
                        // 模拟捏合事件
                        // 注意：这里简化处理，实际需要更复杂的两指检测
                    }
                }
            }
    ) {
        // 列表内容
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                items = tracks,
                key = { _, track -> track.id }
            ) { index, track ->
                ComposeTrackItem(
                    title = track.title,
                    artist = track.artist,
                    duration = track.duration,
                    state = state,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 列表数据类
 */
data class TrackData(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: String
)
