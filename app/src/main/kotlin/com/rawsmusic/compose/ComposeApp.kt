package com.rawsmusic.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.compose.main.ComposeMainContainer
import com.rawsmusic.compose.player.ComposePlayerContainer
import com.rawsmusic.compose.player.PlayerScene

/**
 * 纯 Compose 版本的应用根组件
 * 替代原版 MainActivity 的布局
 *
 * 组合 ComposeMainContainer + ComposePlayerContainer + MiniPlayerBar
 */
@Composable
fun ComposeApp(
    appState: AppState
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121010))
    ) {
        // 主内容容器
        ComposeMainContainer(
            onNavigateToPlayer = { appState.openPlayer() },
            onSongClick = { position ->
                // 处理歌曲点击
            },
            modifier = Modifier.fillMaxSize()
        )

        // 播放器容器（覆盖在主内容之上）
        if (appState.playerSceneState.currentScene != PlayerScene.MAIN) {
            ComposePlayerContainer(
                sceneState = appState.playerSceneState,
                onNavigateToMain = { appState.closePlayer() },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 底部迷你播放栏
        if (appState.playerSceneState.currentScene == PlayerScene.MAIN) {
            MiniPlayerBar(
                title = appState.currentTitle,
                artist = appState.currentArtist,
                isPlaying = appState.isPlaying,
                progress = appState.playbackProgress,
                onClick = { appState.openPlayer() },
                onPlayPause = { appState.togglePlay() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 底部迷你播放栏
 */
@Composable
private fun MiniPlayerBar(
    title: String,
    artist: String,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF121010).copy(alpha = 0.9f)
                    )
                )
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 进度条
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            color = Color.White.copy(alpha = 0.8f),
            trackColor = Color.White.copy(alpha = 0.1f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面占位
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8B5E3C),
                                Color(0xFF3C5E8B)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "♪",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 20.sp
                )
            }

            // 歌曲信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = title.ifEmpty { "未播放" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = artist.ifEmpty { "未知艺术家" },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            // 播放/暂停按钮
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }
    }
}
