package com.rawsmusic.core.ui.widget.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels
import com.rawsmusic.core.ui.widget.powerlist.ListZoomParams
import com.rawsmusic.core.ui.widget.powerlist.lerpZoomParams

/**
 * 纯 Compose 版本的 AAItemView
 * 逐行转换自 item_track.xml + AAItemView.kt
 *
 * 对应 item_track.xml 的子 View：
 * - PlayingMark → playing indicator
 * - AAImageView → cover image
 * - MarqueeFastTextView (title) → title text
 * - MarqueeFastTextView (line2) → artist/album text
 * - FastTextView (meta) → duration/bitrate text
 * - FastCheckBoxOnly → selection checkbox (隐藏)
 * - FastTextView (num) → number (隐藏)
 * - CatImage → category image (隐藏)
 * - DragHandler → drag handle (隐藏)
 */
@Composable
fun ComposeAAItemView(
    song: AudioFile,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    currentScene: Int = AAItemView.SCENE_NORMAL,
    zoomParams: ListZoomParams? = null,
    transitionProgress: Float = 0f,
    sourceScene: Int = -1,
    targetScene: Int = -1,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLight = !ThemeManager.isDarkMode(context)
    val density = context.resources.displayMetrics.density

    // 当前缩放参数
    val effectiveZoom = zoomParams ?: ListZoomLevels.params[ListZoomIndex.NORMAL]!!

    // 场景过渡插值
    val isTransitioning = transitionProgress > 0f && sourceScene != targetScene
    val currentParams = if (isTransitioning) {
        val sourceParams = ListZoomLevels.params.entries.find {
            ListZoomLevels.sceneIdForParams(it.value) == sourceScene
        }?.value ?: effectiveZoom
        val targetParams = ListZoomLevels.params.entries.find {
            ListZoomLevels.sceneIdForParams(it.value) == targetScene
        }?.value ?: effectiveZoom
        lerpZoomParams(sourceParams, targetParams, transitionProgress)
    } else {
        effectiveZoom
    }

    // 是否网格模式
    val isGrid = currentScene == AAItemView.SCENE_GRID

    // 文字颜色
    val titleColor = if (isLight) Color(0xFF1C1B1B) else Color.White
    val line2Color = if (isLight) Color(0xCC1C1B1B) else Color(0xCCFFFFFF)
    val metaColor = if (isLight) Color(0x991C1B1B) else Color(0x99FFFFFF)
    val highlightColor = if (isLight) Color(0xFFC4956A) else Color(0xFFD4B896)

    // 文字缩放
    val textScale = currentParams.textScale
    val titleSize = (22 * textScale).sp
    val line2Size = (18.25f * textScale).sp
    val metaSize = (13.5f * textScale).sp

    // 封面大小
    val coverSizeDp = if (isGrid) {
        // 网格模式：正方形封面
        null // 使用 fillMaxWidth
    } else {
        currentParams.coverSizeDp.dp
    }
    val coverCornerRadius = if (isGrid) {
        currentParams.cornerRadiusAlbumsDp.dp
    } else {
        currentParams.cornerRadiusTracksDp.dp
    }

    // 行高
    val rowHeight = if (isGrid) null else currentParams.rowHeightValue.dp

    // 文字可见性（带过渡动画）
    val line2Alpha = if (isTransitioning) {
        val sourceVisible = ListZoomLevels.params.entries.find {
            ListZoomLevels.sceneIdForParams(it.value) == sourceScene
        }?.value?.line2Visible ?: currentParams.line2Visible
        val targetVisible = ListZoomLevels.params.entries.find {
            ListZoomLevels.sceneIdForParams(it.value) == targetScene
        }?.value?.line2Visible ?: currentParams.line2Visible
        val from = if (sourceVisible) 1f else 0f
        val to = if (targetVisible) 1f else 0f
        from + (to - from) * transitionProgress
    } else {
        if (currentParams.line2Visible) 1f else 0f
    }

    val metaAlpha = if (isTransitioning) {
        val sourceVisible = ListZoomLevels.params.entries.find {
            ListZoomLevels.sceneIdForParams(it.value) == sourceScene
        }?.value?.metaVisible ?: currentParams.metaVisible
        val targetVisible = ListZoomLevels.params.entries.find {
            ListZoomLevels.sceneIdForParams(it.value) == targetScene
        }?.value?.metaVisible ?: currentParams.metaVisible
        val from = if (sourceVisible) 1f else 0f
        val to = if (targetVisible) 1f else 0f
        from + (to - from) * transitionProgress
    } else {
        if (currentParams.metaVisible) 1f else 0f
    }

    // 元数据文本
    val metaText = buildString {
        if (song.duration > 0) {
            val min = song.duration / 60000
            val sec = (song.duration % 60000) / 1000
            append("$min:${sec.toString().padStart(2, '0')}")
        }
        if (song.sampleRate > 0) {
            if (isNotBlank()) append(" · ")
            append("${song.sampleRate / 1000}kHz")
        }
    }

    // line2 文本
    val line2Text = buildString {
        if (song.artist.isNotBlank()) append(song.artist)
        if (song.album.isNotBlank()) {
            if (isNotBlank()) append(" · ")
            append(song.album)
        }
    }

    if (isGrid) {
        // ==================== 网格模式 ====================
        // 对应 item_track.xml 的 SCENE_GRID
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clickableWithLongClick(onClick = onClick, onLongClick = onLongClick)
        ) {
            // PlayingMark (隐藏)

            // AAImageView - 封面
            val aaMargin = 8.dp
            if (song.albumArtPath.isNotBlank()) {
                BitmapImage(
                    key = song.albumArtPath,
                    contentDescription = song.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = aaMargin)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(coverCornerRadius)),
                    contentScale = ContentScale.Crop,
                    targetWidth = 256,
                    targetHeight = 256
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = aaMargin)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(coverCornerRadius))
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // MarqueeFastTextView (title)
            val labelMargin = 18.dp
            Text(
                text = song.displayName,
                fontSize = (22 * 0.65f).sp,  // GRID_TEXT_SCALE
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) highlightColor else titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = labelMargin)
            )

            Spacer(modifier = Modifier.height(3.dp))

            // MarqueeFastTextView (line2)
            if (line2Text.isNotBlank()) {
                Text(
                    text = line2Text,
                    fontSize = (18.25f * 0.65f).sp,
                    color = line2Color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = labelMargin)
                )
            }

            // FastTextView (meta) - 网格模式下隐藏
            // FastCheckBoxOnly (select_box) - 隐藏
            // FastTextView (num) - 隐藏
            // CatImage (cat_image) - 隐藏
            // DragHandler (drag_handler) - 隐藏
        }
    } else {
        // ==================== 列表模式 ====================
        // 对应 item_track.xml 的 SCENE_LIST (SMALL/NORMAL/ZOOMED)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(rowHeight!!)
                .clickableWithLongClick(onClick = onClick, onLongClick = onLongClick)
                .padding(
                    start = currentParams.coverMarginLeftDp.dp,
                    top = currentParams.coverMarginTopDp.dp,
                    end = currentParams.textMarginRightDp.dp,
                    bottom = currentParams.coverMarginBottomDp.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PlayingMark (隐藏)

            // AAImageView - 封面
            if (song.albumArtPath.isNotBlank()) {
                BitmapImage(
                    key = song.albumArtPath,
                    contentDescription = song.displayName,
                    modifier = Modifier
                        .size(coverSizeDp!!)
                        .clip(RoundedCornerShape(coverCornerRadius)),
                    contentScale = ContentScale.Crop,
                    targetWidth = 256,
                    targetHeight = 256
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(coverSizeDp!!)
                        .clip(RoundedCornerShape(coverCornerRadius))
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }

            Spacer(modifier = Modifier.width(currentParams.textMarginLeftDp.dp))

            // 文字区域
            Column(modifier = Modifier.weight(1f)) {
                // MarqueeFastTextView (title)
                Text(
                    text = song.displayName,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) highlightColor else titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // MarqueeFastTextView (line2) - 带 alpha 过渡
                if (line2Text.isNotBlank() && line2Alpha > 0f) {
                    Text(
                        text = line2Text,
                        fontSize = line2Size,
                        color = line2Color.copy(alpha = line2Alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = line2Alpha }
                    )
                }

                // FastTextView (meta) - 带 alpha 过渡
                if (metaText.isNotBlank() && metaAlpha > 0f) {
                    Text(
                        text = metaText,
                        fontSize = metaSize,
                        fontWeight = FontWeight.Bold,
                        color = metaColor.copy(alpha = metaAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = metaAlpha }
                    )
                }
            }

            // FastCheckBoxOnly (select_box) - 隐藏
            // FastTextView (num) - 隐藏
            // CatImage (cat_image) - 隐藏
            // DragHandler (drag_handler) - 隐藏
        }
    }
}

/**
 * 点击/长按修饰符
 */
@Composable
private fun Modifier.clickableWithLongClick(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier {
    return this
        .clickable(onClick = onClick)
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { onLongClick() })
        }
}
