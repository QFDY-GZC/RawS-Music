package com.rawsmusic.core.ui.widget

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.rawsmusic.core.ui.R
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.theme.ThemeManager
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
 * - 双击切换普通/黑胶模式
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeMiniPlayer(
    title: String,
    artist: String,
    lyricText: String = "",
    lyricTranslation: String = "",
    isPlaying: Boolean,
    progress: Float = 0f,
    coverPath: String? = null,
    coverBitmap: Bitmap? = null,
    backdrop: Backdrop? = null,
    animateArtwork: Boolean = false,
    onClick: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onCoverBoundsChanged: (RectF?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cs = MiuixTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    val shape = RoundedCornerShape(50)

    val textColor = cs.onBackground
    val secondaryColor = cs.onSurfaceVariantSummary
    val hasLyric = lyricText.isNotBlank()
    val primaryText = if (hasLyric) lyricText.trim() else title.ifBlank { "暂无音乐播放" }
    val secondaryText = if (hasLyric) lyricTranslation.trim() else artist
    val centerLyrics = hasLyric && isLikelyChineseLyric(primaryText)

    val artworkModeState = rememberMiniPlayerArtworkMode()
    val artworkMode = artworkModeState.value

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(62.dp)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (isLight) 0.18f else 0.36f),
                spotColor = Color.Black.copy(alpha = if (isLight) 0.20f else 0.50f)
            )
            .clip(shape)
            .miniPlayerOuterRemainingProgress(
                progress = progress,
                radiusDp = 31f,
                color = cs.primary
            )
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
    ) {
        LiquidGlassMiniPlayerBg(
            backdrop = backdrop,
            isLight = isLight
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniPlayerArtwork(
                mode = artworkMode,
                coverPath = coverPath,
                coverBitmap = coverBitmap,
                isPlaying = isPlaying,
                contentDescription = title,
                onCoverBoundsChanged = onCoverBoundsChanged,
                onDoubleTapToggleMode = {
                    artworkModeState.value = artworkModeState.value.toggle()
                },
                onSingleTap = onClick,
                animateArtwork = animateArtwork
            )

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
                        text = primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = if (centerLyrics) TextAlign.Center else TextAlign.Start,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                repeatDelayMillis = 900
                            )
                    )
                    if (secondaryText.isNotBlank()) {
                        Text(
                            text = secondaryText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryColor,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = if (centerLyrics) TextAlign.Center else TextAlign.Start,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    repeatDelayMillis = 900
                                )
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
                Icon(
                    painter = painterResource(
                        id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun isLikelyChineseLyric(text: String): Boolean {
    val hasHan = text.any { it in '\u3400'..'\u9FFF' }
    val hasKana = text.any { it in '\u3040'..'\u30FF' }
    return hasHan && !hasKana
}

/**
 * 黑胶模式下播放栏外围剩余进度线。
 * progress = 0 时蓝线完整，progress = 1 时蓝线消失。
 */
private fun Modifier.miniPlayerOuterRemainingProgress(
    progress: Float,
    radiusDp: Float,
    color: Color
): Modifier {
    return drawWithContent {
        drawContent()

        val strokeWidth = 2.dp.toPx()
        val inset = strokeWidth / 2f
        val radius = radiusDp.dp.toPx()

        val rect = android.graphics.RectF(
            inset,
            inset,
            size.width - inset,
            size.height - inset
        )

        val path = android.graphics.Path().apply {
            addRoundRect(
                rect,
                radius,
                radius,
                android.graphics.Path.Direction.CW
            )
        }

        val remaining = 1f - progress.coerceIn(0f, 1f)
        if (remaining <= 0.001f) return@drawWithContent

        val measure = PathMeasure(path, false)
        val length = measure.length
        val segment = android.graphics.Path()

        val start = 0f
        val end = length * remaining
        measure.getSegment(start, end, segment, true)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = 2.dp.toPx()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color.toArgb()
        }

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(segment, paint)
        }
    }
}
