package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.widget.MiniPlayerArtwork
import com.rawsmusic.core.ui.widget.MiniPlayerArtworkMode
import com.rawsmusic.core.ui.widget.rememberMiniPlayerArtworkMode
import com.rawsmusic.core.ui.systemui.rawNavigationBarsPadding

/**
 * 底部迷你播放栏。
 * Mini player bar rendered entirely in Compose.
 */
@Composable
fun MiniPlayerBar(
    title: String,
    artist: String,
    coverPath: String?,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val artworkModeState = rememberMiniPlayerArtworkMode()
    val artworkMode = artworkModeState.value
    val colors = themeColors()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .rawNavigationBarsPadding(reduceBy = 12.dp)
    ) {
        // 进度条
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(colors.outline.copy(alpha = 0.2f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(colors.primary)
            )
        }

        // 内容
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面（黑胶 + 记忆封面）
            MiniPlayerArtwork(
                mode = artworkMode,
                coverPath = coverPath,
                isPlaying = isPlaying,
                contentDescription = title,
                onCoverBoundsChanged = {},
                onDoubleTapToggleMode = {
                    artworkModeState.value = artworkModeState.value.toggle()
                },
                onSingleTap = onClick,
                animateArtwork = false
            )

            Spacer(Modifier.width(8.dp))

            // 标题/艺术家
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "未在播放" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    artist,
                    fontSize = 12.sp,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 控制按钮
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                MiniTransportIcon(
                    type = MiniTransportIconType.Previous,
                    color = colors.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
            ) {
                MiniTransportIcon(
                    type = if (isPlaying) MiniTransportIconType.Pause else MiniTransportIconType.Play,
                    color = colors.background,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                MiniTransportIcon(
                    type = MiniTransportIconType.Next,
                    color = colors.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniTransportIcon(
    type: MiniTransportIconType,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun triangle(left: Float, top: Float, right: Float, bottom: Float, pointsRight: Boolean) {
            val path = Path()
            if (pointsRight) {
                path.moveTo(left, top)
                path.lineTo(right, h / 2f)
                path.lineTo(left, bottom)
            } else {
                path.moveTo(right, top)
                path.lineTo(left, h / 2f)
                path.lineTo(right, bottom)
            }
            path.close()
            drawPath(path, color)
        }

        when (type) {
            MiniTransportIconType.Previous -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.24f, h * 0.25f),
                    size = androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.50f)
                )
                triangle(w * 0.36f, h * 0.25f, w * 0.72f, h * 0.75f, pointsRight = false)
            }

            MiniTransportIconType.Next -> {
                triangle(w * 0.28f, h * 0.25f, w * 0.64f, h * 0.75f, pointsRight = true)
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.68f, h * 0.25f),
                    size = androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.50f)
                )
            }

            MiniTransportIconType.Play -> {
                triangle(w * 0.34f, h * 0.24f, w * 0.72f, h * 0.76f, pointsRight = true)
            }

            MiniTransportIconType.Pause -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.34f, h * 0.25f),
                    size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.50f)
                )
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.56f, h * 0.25f),
                    size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.50f)
                )
            }
        }
    }
}

private enum class MiniTransportIconType {
    Previous,
    Next,
    Play,
    Pause
}
