package com.rawsmusic.ui.settings.compose.scene.item

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.compose.scene.state.GridLayoutCalculator
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState

/**
 * 纯 Compose 版本的网格列表项
 * 替代原版 AAItemView 的网格模式
 *
 * 实现网格布局渲染
 */
@Composable
fun ComposeGridItem(
    title: String,
    artist: String,
    index: Int,
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density

    // 计算网格布局
    val gridLayout = remember(state.columns, density) {
        GridLayoutCalculator.calculateGridLayout(
            columns = state.columns,
            itemIndex = index,
            containerWidth = 1080, // 默认宽度
            density = density
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape((gridLayout.cornerRadius / density).dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF8B5E3C),
                            Color(0xFF3C5E8B)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape((gridLayout.cornerRadius / density).dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "♪",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 标题
        Text(
            text = title,
            style = gridLayout.titleStyle,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // 第二行 (艺术家)
        if (state.currentParams.line2Visible) {
            Text(
                text = artist,
                style = gridLayout.line2Style,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 网格内容容器
 * 替代原版 GridContent
 */
@Composable
fun GridContent(
    columns: Int,
    tracks: List<TrackData>,
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    val rows = tracks.chunked(columns)

    Column(modifier = modifier) {
        rows.forEachIndexed { rowIndex, row ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEachIndexed { colIndex, track ->
                    val index = rowIndex * columns + colIndex
                    Box(modifier = Modifier.weight(1f)) {
                        ComposeGridItem(
                            title = track.title,
                            artist = track.artist,
                            index = index,
                            state = state
                        )
                    }
                }
                // 填充空位
                repeat(columns - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
