package com.rawsmusic.core.ui.widget

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.liquidGlass
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import kotlin.math.abs

/**
 * 纯 Compose 版本的迷你播放栏
 *
 * 支持：
 * - 液态玻璃背景（Backdrop）
 * - 封面旋转 + 环形进度条
 * - 歌曲信息/歌词滚动
 * - 水平滑动手势切歌
 * - 点击打开播放器
 */
@Composable
fun ComposeMiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    progress: Float = 0f,
    coverPath: String? = null,
    coverBitmap: Bitmap? = null,
    backdrop: Backdrop? = null,
    onClick: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLight = !ThemeManager.isDarkMode(context)
    val shape = RoundedCornerShape(24.dp)

    val containerColor = if (isLight) {
        Color.White.copy(alpha = 0.44f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }

    val textColor = if (isLight) Color(0xFF1C1B1F) else Color(0xFFE6E1DD)
    val secondaryColor = if (isLight) Color(0xFF49454F) else Color(0xFF9F8D80)

    // 封面旋转动画
    val infiniteTransition = rememberInfiniteTransition(label = "cover_rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    // 液态玻璃背景修饰符
    val glassModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(20f)
                lens(
                    refractionHeight = 8f,
                    refractionAmount = 16f,
                    depthEffect = true,
                    chromaticAberration = true
                )
                liquidGlass(
                    cornerRadius = 24f,
                    refraction = 0.85f,
                    curve = 0.7f,
                    dispersion = 0.4f,
                    saturation = 1.2f,
                    contrast = 1.1f,
                    edge = 0.3f,
                    tintR = 1f,
                    tintG = 1f,
                    tintB = 1f,
                    tintA = 0.05f
                )
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = if (isLight) 0.30f else 0.20f
                )
            },
            shadow = {
                Shadow.Default.copy(
                    color = Color.Black.copy(
                        alpha = if (isLight) 0.15f else 0.35f
                    )
                )
            },
            onDrawSurface = {
                drawRect(containerColor)
            }
        )
    } else {
        Modifier.background(containerColor, shape)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .then(glassModifier)
            .pointerInput(Unit) {
                var dragAmount = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragAmount = 0f },
                    onHorizontalDrag = { change, amount ->
                        dragAmount += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (abs(dragAmount) > 96f) {
                            if (dragAmount < 0f) onSkipNext()
                            else onSkipPrevious()
                        }
                        dragAmount = 0f
                    },
                    onDragCancel = { dragAmount = 0f }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面 + 进度环
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center
        ) {
            // 环形进度条
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 2.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                // 背景环
                drawCircle(
                    color = Color.White.copy(alpha = 0.15f),
                    radius = radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth)
                )
                // 进度弧
                drawArc(
                    color = if (isLight) Color(0xFFC4956A) else Color(0xFFD4B896),
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth)
                )
            }

            // 封面图片
            if (coverPath != null && coverPath.isNotBlank()) {
                BitmapImage(
                    key = coverPath,
                    contentDescription = title,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .then(
                            if (isPlaying) Modifier.rotate(rotation)
                            else Modifier
                        ),
                    contentScale = ContentScale.Crop,
                    targetWidth = 256,
                    targetHeight = 256
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 歌曲信息
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = title.ifBlank { "暂无音乐播放" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 播放/暂停按钮
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPlaying) "⏸" else "▶",
                fontSize = 20.sp,
                color = textColor
            )
        }
    }
}
