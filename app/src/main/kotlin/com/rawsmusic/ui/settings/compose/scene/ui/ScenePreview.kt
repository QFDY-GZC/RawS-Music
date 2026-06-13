package com.rawsmusic.ui.settings.compose.scene.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 场景预览
 * 对应原版 ComposePlayerDemo 的 ScenePreview
 *
 * 显示场景切换的预览效果
 */
@Composable
fun ScenePreview(
    currentScene: SceneType,
    onSceneChanged: (SceneType) -> Unit,
    modifier: Modifier = Modifier
) {
    var target by remember { mutableStateOf(currentScene) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragX = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        dragX += dragAmount
                    },
                    onDragEnd = {
                        val threshold = 100f
                        if (kotlin.math.abs(dragX) > threshold) {
                            val scenes = SceneType.entries
                            val currentIndex = scenes.indexOf(target)
                            val newIndex = if (dragX < 0) {
                                (currentIndex + 1).coerceAtMost(scenes.size - 1)
                            } else {
                                (currentIndex - 1).coerceAtLeast(0)
                            }
                            target = scenes[newIndex]
                            onSceneChanged(scenes[newIndex])
                        }
                        dragging = false
                        dragX = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragX = 0f
                    }
                )
            }
    ) {
        AnimatedContent(
            targetState = target,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith
                        slideOutHorizontally { -it } + fadeOut()
            }
        ) { scene ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (scene) {
                    SceneType.MAIN -> MainPreview()
                    SceneType.PLAYER -> PlayerPreview()
                    SceneType.LYRIC -> LyricPreview()
                    SceneType.QUEUE -> QueuePreview()
                }
            }
        }

        // 场景标签
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(
                    Color.Black.copy(alpha = 0.5f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = target.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 导航箭头
        if (!dragging) {
            val scenes = SceneType.entries
            val currentIndex = scenes.indexOf(target)
            if (currentIndex > 0) {
                Text(
                    text = "‹",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 28.sp
                )
            }
            if (currentIndex < scenes.size - 1) {
                Text(
                    text = "›",
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 28.sp
                )
            }
        }
    }
}

/**
 * 场景类型
 */
enum class SceneType(val label: String) {
    MAIN("首页"),
    PLAYER("播放器"),
    LYRIC("歌词"),
    QUEUE("队列")
}

/**
 * 首页预览
 */
@Composable
private fun MainPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "歌曲列表",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        for (r in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (c in 0 until 3) {
                    val i = r * 3 + c
                    val h = (i * 60f) % 360f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        hsl(h, 0.6f, 0.3f),
                                        hsl(h + 40f, 0.7f, 0.2f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "♪",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 播放器预览
 */
@Composable
private fun PlayerPreview() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(16.dp))
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
                text = "♫",
                fontSize = 48.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "夜曲",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "周杰伦",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp
        )
    }
}

/**
 * 歌词预览
 */
@Composable
private fun LyricPreview() {
    val lyrics = listOf(
        "一盏黄黄旧旧的灯",
        "时间在旁闷不吭声",
        "寂寞下手毫无分寸",
        "不懂得轻重之分"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        lyrics.forEachIndexed { index, line ->
            val alpha = if (index == 1) 1f else 0.4f
            val fontSize = if (index == 1) 18.sp else 14.sp
            val fontWeight = if (index == 1) FontWeight.Bold else FontWeight.Normal
            Text(
                text = line,
                color = Color.White.copy(alpha = alpha),
                fontSize = fontSize,
                fontWeight = fontWeight,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * 队列预览
 */
@Composable
private fun QueuePreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "播放队列",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        for (i in 0 until 5) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${i + 1}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.width(20.dp)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    hsl((i * 72f) % 360f, 0.5f, 0.3f),
                                    hsl((i * 72f + 40f) % 360f, 0.6f, 0.2f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "♪",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "歌曲 ${i + 1}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = "艺术家 ${i + 1}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                Text(
                    text = "3:${(i * 10 + 23) % 60}",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * HSL 颜色转换
 */
private fun hsl(h: Float, s: Float, l: Float): Color {
    val hn = ((h % 360f) + 360f) % 360f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((hn / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        hn < 60f -> Triple(c, x, 0f)
        hn < 120f -> Triple(x, c, 0f)
        hn < 180f -> Triple(0f, c, x)
        hn < 240f -> Triple(0f, x, c)
        hn < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        (r + m).coerceIn(0f, 1f),
        (g + m).coerceIn(0f, 1f),
        (b + m).coerceIn(0f, 1f)
    )
}
