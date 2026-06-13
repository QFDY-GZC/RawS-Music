package com.rawsmusic.ui.settings.compose.scene.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.compose.scene.container.ComposePowerList
import com.rawsmusic.ui.settings.compose.scene.container.TrackData
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels

/**
 * 纯 Compose 版本的统一主容器
 * 替代原版 UnifiedMainContainer
 *
 * 管理内容页面：HOME、SONGS、ALBUMS、ARTISTS 等
 * 集成双引擎列表、捏合缩放、场景切换等功能
 */
@Composable
fun ComposeUnifiedMainContainer(
    modifier: Modifier = Modifier
) {
    // 列表状态
    val listState = remember { ComposeListState() }

    // 示例数据
    val tracks = remember {
        listOf(
            TrackData(1, "夜曲", "周杰伦", "4:23"),
            TrackData(2, "晴天", "周杰伦", "4:29"),
            TrackData(3, "稻香", "周杰伦", "3:43"),
            TrackData(4, "青花瓷", "周杰伦", "3:59"),
            TrackData(5, "七里香", "周杰伦", "4:59"),
            TrackData(6, "以父之名", "周杰伦", "5:41"),
            TrackData(7, "简单爱", "周杰伦", "4:30"),
            TrackData(8, "双截棍", "周杰伦", "3:13"),
            TrackData(9, "告白气球", "周杰伦", "3:35"),
            TrackData(10, "等你下课", "周杰伦", "3:55")
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121010))
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "纯 Compose 场景系统",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 场景选择器
        SceneSelector(
            currentScene = listState.currentScene,
            onSceneSelected = { scene ->
                listState.startTransition(scene, scene.ordinal > listState.currentScene.ordinal)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 调试信息
        DebugPanel(state = listState)

        Spacer(modifier = Modifier.height(16.dp))

        // 列表
        ComposePowerList(
            tracks = tracks,
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp)
                )
        )
    }
}

/**
 * 场景选择器
 */
@Composable
private fun SceneSelector(
    currentScene: ListZoomIndex,
    onSceneSelected: (ListZoomIndex) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ListZoomIndex.entries.forEach { scene ->
            val isActive = scene == currentScene
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isActive) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0.05f)
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.06f),
                                    Color.White.copy(alpha = 0.02f)
                                )
                            )
                        }
                    )
                    .border(
                        width = if (isActive) 1.dp else 0.dp,
                        color = Color.White.copy(alpha = if (isActive) 0.3f else 0f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSceneSelected(scene) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (scene) {
                        ListZoomIndex.SMALL -> "小"
                        ListZoomIndex.NORMAL -> "标准"
                        ListZoomIndex.ZOOMED -> "大"
                    },
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * 调试面板
 */
@Composable
private fun DebugPanel(
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.1f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = "调试面板",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        DebugRow("当前场景", state.currentScene.name)
        DebugRow("源场景", state.sourceScene.name)
        DebugRow("目标场景", state.targetScene.name)
        DebugRow("过渡进度", "%.1f%%".format(state.transitionProgress * 100f))
        DebugRow("是否过渡中", if (state.isTransitioning) "是" else "否")
        DebugRow("缩放因子", "%.3f".format(state.transitionScaleFactor))

        Spacer(modifier = Modifier.height(8.dp))

        DebugRow("封面大小", "${state.currentParams.coverSizeDp.toInt()}dp")
        DebugRow("行高", "${state.currentParams.rowHeightValue.toInt()}dp")
        DebugRow("文本缩放", "%.2fx".format(state.currentParams.textScale))
        DebugRow("圆角", "${state.currentParams.cornerRadiusTracksDp.toInt()}dp")
    }
}

/**
 * 调试行
 */
@Composable
private fun DebugRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
