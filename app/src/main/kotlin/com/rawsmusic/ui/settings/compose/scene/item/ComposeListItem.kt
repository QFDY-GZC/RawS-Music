package com.rawsmusic.ui.settings.compose.scene.item

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.compose.scene.state.ItemLayoutCalculator
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState

/**
 * 纯 Compose 版本的列表项
 * 替代原版 AAItemView 的列表模式
 *
 * 实现列表布局渲染
 */
@Composable
fun ComposeListItem(
    title: String,
    artist: String,
    duration: String,
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density

    // 计算列表布局
    val listLayout = remember(state.currentParams, density) {
        ItemLayoutCalculator.calculateListLayout(
            params = state.currentParams,
            containerWidth = 1080, // 默认宽度
            density = density
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height((listLayout.rowHeight / density).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        Box(
            modifier = Modifier
                .size((listLayout.coverSize / density).dp)
                .padding(
                    start = (listLayout.coverLeft / density).dp,
                    top = (listLayout.coverTop / density).dp
                )
                .clip(RoundedCornerShape((listLayout.cornerRadius / density).dp))
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
                    shape = RoundedCornerShape((listLayout.cornerRadius / density).dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (listLayout.coverSize > 28 * density) {
                Text(
                    text = "♪",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = (listLayout.coverSize * 0.3f / density).sp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 文本区域
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // 标题
            Text(
                text = title,
                style = listLayout.titleStyle,
                color = Color.White,
                maxLines = 1
            )

            // 第二行 (艺术家)
            if (listLayout.line2Visible) {
                Text(
                    text = artist,
                    style = listLayout.line2Style,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }

        // 元数据 (时长)
        if (listLayout.metaVisible && !listLayout.metaInline) {
            Text(
                text = duration,
                style = listLayout.metaStyle,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
    }
}

/**
 * 列表内容容器
 * 替代原版 ListContent
 */
@Composable
fun ListContent(
    tracks: List<TrackData>,
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        tracks.forEachIndexed { index, track ->
            ComposeListItem(
                title = track.title,
                artist = track.artist,
                duration = track.duration,
                state = state,
                modifier = Modifier.fillMaxWidth()
            )

            // 分隔线
            if (index < tracks.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = (state.currentParams.coverSizeDp + 12).dp),
                    color = Color.White.copy(alpha = 0.06f),
                    thickness = 0.5.dp
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
