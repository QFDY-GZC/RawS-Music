package com.rawsmusic.core.ui.widget.player

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.scene.pages.themeColors

/**
 * 播放队列面板（纯 Compose）。
 * 播放器队列场景面板。
 */
@Composable
fun QueuePanel(
    songs: List<AudioFile>,
    currentIndex: Int,
    onSongClick: (AudioFile, Int) -> Unit,
    onBack: () -> Unit,
    onClearPriorityQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()
    val listState = rememberLazyListState()

    // 自动滚动到当前播放项
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < songs.size) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = colors.primary, fontSize = 14.sp)
            }
            Text(
                "播放队列",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Text(
                "${songs.size} 首",
                fontSize = 13.sp,
                color = colors.secondaryText
            )
        }

        if (onClearPriorityQueue != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClearPriorityQueue) {
                    Text("清空优先队列", color = colors.primary, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 歌曲列表
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(songs) { index, song ->
                val isCurrent = index == currentIndex
                QueueItemRow(
                    song = song,
                    index = index,
                    isCurrent = isCurrent,
                    onClick = { onSongClick(song, index) }
                )
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    song: AudioFile,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val colors = themeColors()
    val bgColor = if (isCurrent) colors.primary.copy(alpha = 0.1f) else Color.Transparent
    val titleColor = if (isCurrent) colors.primary else colors.onSurface

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号
        Text(
            "${index + 1}",
            fontSize = 13.sp,
            color = if (isCurrent) colors.primary else colors.secondaryText,
            modifier = Modifier.width(32.dp)
        )

        // 歌曲信息
        Column(Modifier.weight(1f)) {
            Text(
                song.displayName,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist.ifBlank { "未知艺术家" },
                fontSize = 12.sp,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 时长
        Text(
            AudioUtils.formatDuration(song.duration),
            fontSize = 12.sp,
            color = colors.secondaryText
        )
    }
}
