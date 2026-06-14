package com.rawsmusic.core.ui.widget.powerlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage

// ==================== 缓存池 (替代 ViewPool) ====================

/**
 * Compose 版本的缓存池
 * 使用 remember 缓存 Composable，避免重复创建
 */
class ComposeViewPool<T>(
    private val maxSize: Int = 50
) {
    private val cache = mutableListOf<T>()
    private val activeSet = mutableSetOf<T>()

    fun acquire(): T? {
        return cache.removeFirstOrNull()?.also { activeSet.add(it) }
    }

    fun release(item: T) {
        activeSet.remove(item)
        if (cache.size < maxSize) {
            cache.add(item)
        }
    }

    fun clear() {
        cache.clear()
        activeSet.clear()
    }

    val activeCount: Int get() = activeSet.size
    val cachedCount: Int get() = cache.size
}

// ==================== 淡入淡出动画 ====================

/**
 * Compose 版本的淡入淡出动画
 * 替代 View 的 animate().alpha()
 */
@Composable
fun FadeInOutContent(
    visible: Boolean,
    durationMs: Int = 300,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        alpha.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = durationMs,
                easing = LinearEasing
            )
        )
    }

    Box(modifier = Modifier.alpha(alpha.value)) {
        content()
    }
}

// ==================== 手势缩放 ====================

/**
 * Compose 版本的缩放手势检测
 * 替代 ScaleGestureDetector
 */
data class ZoomGestureState(
    var scale: Float = 1f,
    var isZooming: Boolean = false,
    var zoomPosition: Float = 1f  // 0=SMALL, 1=NORMAL, 2=ZOOMED
)

@Composable
fun rememberZoomGestureState(): ZoomGestureState {
    return remember { ZoomGestureState() }
}

/**
 * 缩放手势修饰符
 * 双指缩放切换列表大小
 */
fun Modifier.zoomGesture(
    state: ZoomGestureState,
    onZoomChanged: (ListZoomIndex) -> Unit
): Modifier {
    return this.pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ ->
            state.scale *= zoom

            when {
                state.scale < 0.8f -> {
                    // 缩小 -> 更紧凑
                    state.zoomPosition = (state.zoomPosition - 0.5f).coerceAtLeast(0f)
                    state.scale = 1f
                }
                state.scale > 1.2f -> {
                    // 放大 -> 更大
                    state.zoomPosition = (state.zoomPosition + 0.5f).coerceAtMost(2f)
                    state.scale = 1f
                }
            }

            val newIndex = when {
                state.zoomPosition < 0.5f -> ListZoomIndex.SMALL
                state.zoomPosition < 1.5f -> ListZoomIndex.NORMAL
                else -> ListZoomIndex.ZOOMED
            }
            onZoomChanged(newIndex)
        }
    }
}

// ==================== 专辑图转换动画 ====================

/**
 * Compose 版本的专辑图转换动画
 * 替代 CoverTransitionAnimator
 */
data class CoverTransitionState(
    var isTransitioning: Boolean = false,
    var progress: Float = 0f,
    var fromAlpha: Float = 1f,
    var toAlpha: Float = 0f,
    var fromScale: Float = 1f,
    var toScale: Float = 0.9f
)

@Composable
fun rememberCoverTransitionState(): CoverTransitionState {
    return remember { CoverTransitionState() }
}

/**
 * 专辑图转换动画修饰符
 * 场景切换时的封面动画
 */
fun Modifier.coverTransition(
    state: CoverTransitionState,
    isTarget: Boolean
): Modifier {
    return this.graphicsLayer {
        if (state.isTransitioning) {
            val progress = state.progress
            alpha = if (isTarget) {
                state.fromAlpha + (state.toAlpha - state.fromAlpha) * progress
            } else {
                state.fromAlpha - (state.fromAlpha - state.toAlpha) * progress
            }
            val scale = if (isTarget) {
                state.fromScale + (state.toScale - state.fromScale) * progress
            } else {
                state.fromScale - (state.fromScale - state.toScale) * progress
            }
            scaleX = scale
            scaleY = scale
        }
    }
}

/**
 * 启动专辑图转换动画
 */
suspend fun animateCoverTransition(
    state: CoverTransitionState,
    durationMs: Int = 300
) {
    state.isTransitioning = true
    val animatable = Animatable(0f)
    animatable.animateTo(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = durationMs,
            easing = LinearEasing
        )
    ) {
        state.progress = value
    }
    state.isTransitioning = false
    state.progress = 0f
}

// ==================== 判定/检测 ====================

/**
 * Compose 版本的滚动判定
 * 替代 PowerListView 的 fling/scroll 检测
 */
data class ScrollJudgement(
    val velocityThreshold: Float = 500f,  // dp/s
    val positionThreshold: Float = 0.3f
) {
    fun shouldFling(velocityDp: Float): Boolean {
        return kotlin.math.abs(velocityDp) >= velocityThreshold
    }

    fun shouldSnap(position: Float): Boolean {
        val fractional = position % 1f
        return fractional < positionThreshold || fractional > (1f - positionThreshold)
    }

    fun snapTarget(position: Float, velocityDp: Float): Int {
        return if (shouldFling(velocityDp)) {
            if (velocityDp > 0f) position.toInt() + 1 else position.toInt()
        } else {
            if (position % 1f > 0.5f) position.toInt() + 1 else position.toInt()
        }.coerceIn(0, 2)
    }
}

// ==================== 项位置追踪 ====================

/**
 * 计算可见项位置
 */
fun calculateItemPositions(
    itemCount: Int,
    rowHeightPx: Float,
    scrollOffsetPx: Float,
    viewportHeightPx: Float
): List<ComposeItemPosition> {
    val positions = mutableListOf<ComposeItemPosition>()
    val firstVisible = (scrollOffsetPx / rowHeightPx).toInt().coerceAtLeast(0)
    val lastVisible = ((scrollOffsetPx + viewportHeightPx) / rowHeightPx).toInt().coerceAtMost(itemCount - 1)

    for (i in firstVisible..lastVisible) {
        val top = i * rowHeightPx - scrollOffsetPx
        positions.add(
            ComposeItemPosition(
                left = 0,
                top = top.toInt(),
                right = 0,
                bottom = (top + rowHeightPx).toInt()
            )
        )
    }
    return positions
}

// ==================== 场景项 (替代 PowerListSceneItem) ====================

/**
 * Compose 版本的场景项
 * 替代 PowerListSceneItem
 */
data class ComposeSceneItem(
    val scene: Int,
    val alpha: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val visible: Boolean = true
) {
    companion object {
        const val SCENE_SMALL = 0
        const val SCENE_NORMAL = 1
        const val SCENE_ZOOMED = 2
    }
}

/**
 * 场景参数插值
 */
fun lerpSceneParams(
    from: ComposeSceneItem,
    to: ComposeSceneItem,
    fraction: Float
): ComposeSceneItem {
    val f = fraction.coerceIn(0f, 1f)
    return ComposeSceneItem(
        scene = if (f < 0.5f) from.scene else to.scene,
        alpha = from.alpha + (to.alpha - from.alpha) * f,
        scaleX = from.scaleX + (to.scaleX - from.scaleX) * f,
        scaleY = from.scaleY + (to.scaleY - from.scaleY) * f,
        translationX = from.translationX + (to.translationX - from.translationX) * f,
        translationY = from.translationY + (to.translationY - from.translationY) * f,
        visible = if (f < 0.5f) from.visible else to.visible
    )
}

// ==================== 布局计算 (替代 LayoutEngine) ====================

/**
 * Compose 版本的布局计算
 * 替代 LayoutEngine
 */
object ComposeLayoutEngine {
    /**
     * 计算列表项高度
     */
    fun calculateRowHeight(params: ListZoomParams, density: Float): Float {
        return if (params.rowHeightIsSp) {
            params.rowHeightValue * density * 1.5f  // sp to px approximation
        } else {
            params.rowHeightValue * density  // dp to px
        }
    }

    /**
     * 计算封面大小
     */
    fun calculateCoverSize(params: ListZoomParams, density: Float): Float {
        return if (params.coverSizeDp < 0f) {
            // MATCH_PARENT
            calculateRowHeight(params, density) - (params.coverMarginTopDp + params.coverMarginBottomDp) * density
        } else {
            params.coverSizeDp * density
        }
    }

    /**
     * 计算文字位置
     */
    fun calculateTextOffset(params: ListZoomParams, density: Float): Float {
        val coverSize = calculateCoverSize(params, density)
        return coverSize + params.textMarginLeftDp * density
    }

    /**
     * 计算网格列数
     */
    fun calculateGridColumns(params: ListZoomParams): Int {
        return when {
            params.coverSizeDp >= 100f -> 2
            params.coverSizeDp >= 60f -> 3
            else -> 4
        }
    }
}

// ==================== 场景返回手势 (替代 SceneHelper) ====================

/**
 * Compose 版本的场景返回手势
 * 替代 SceneHelper
 */
data class SceneReturnState(
    var isActive: Boolean = false,
    var progress: Float = 0f,
    var targetScene: Int = -1,
    var startX: Float = 0f,
    var startY: Float = 0f
)

@Composable
fun rememberSceneReturnState(): SceneReturnState {
    return remember { SceneReturnState() }
}

/**
 * 场景返回手势修饰符
 */
fun Modifier.sceneReturnGesture(
    state: SceneReturnState,
    onReturnStart: () -> Unit,
    onReturnProgress: (Float) -> Unit,
    onReturnEnd: (Boolean) -> Unit
): Modifier {
    return this.pointerInput(Unit) {
        detectTransformGestures { _, _, _, _ ->
            // 场景返回手势逻辑
        }
    }
}

// ==================== 网格/列表切换 (替代 GridListEngine) ====================

/**
 * Compose 版本的网格/列表切换
 * 替代 GridListEngine
 */
data class GridListState(
    var isGrid: Boolean = false,
    var columns: Int = 1,
    var isAnimating: Boolean = false,
    var transitionProgress: Float = 0f
)

@Composable
fun rememberGridListState(): GridListState {
    return remember { GridListState() }
}

/**
 * 切换网格/列表模式
 */
suspend fun toggleGridList(
    state: GridListState,
    targetIsGrid: Boolean,
    durationMs: Int = 300
) {
    if (state.isGrid == targetIsGrid) return
    state.isAnimating = true
    val animatable = Animatable(0f)
    animatable.animateTo(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = durationMs,
            easing = LinearEasing
        )
    ) {
        state.transitionProgress = value
    }
    state.isGrid = targetIsGrid
    state.isAnimating = false
    state.transitionProgress = 0f
}

// ==================== 列表项 Composable ====================

/**
 * 标准列表项
 */
@Composable
fun PowerListItem(
    song: AudioFile,
    isPlaying: Boolean,
    params: ListZoomParams,
    textColor: Color,
    secondaryColor: Color,
    highlightColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coverSize = params.coverSizeDp.dp
    val cornerRadius = params.cornerRadiusTracksDp.dp
    val textScale = params.textScale

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(params.rowHeightValue.dp)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongClick() })
            }
            .padding(
                start = params.coverMarginLeftDp.dp,
                top = params.coverMarginTopDp.dp,
                end = params.textMarginRightDp.dp,
                bottom = params.coverMarginBottomDp.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        if (song.albumArtPath.isNotBlank()) {
            BitmapImage(
                key = song.albumArtPath,
                contentDescription = song.displayName,
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
                targetWidth = 256,
                targetHeight = 256
            )
        } else {
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.White.copy(alpha = 0.1f))
            )
        }

        Spacer(modifier = Modifier.width(params.textMarginLeftDp.dp))

        // 文字信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayName,
                fontSize = (14 * textScale).sp,
                fontWeight = FontWeight.Medium,
                color = if (isPlaying) highlightColor else textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (params.line2Visible) {
                val line2 = buildString {
                    if (song.artist.isNotBlank()) append(song.artist)
                    if (song.album.isNotBlank()) {
                        if (isNotBlank()) append(" · ")
                        append(song.album)
                    }
                }
                if (line2.isNotBlank()) {
                    Text(
                        text = line2,
                        fontSize = (12 * textScale).sp,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (params.metaVisible && song.duration > 0) {
                val min = song.duration / 60000
                val sec = (song.duration % 60000) / 1000
                Text(
                    text = "$min:${sec.toString().padStart(2, '0')}",
                    fontSize = (11 * textScale).sp,
                    color = secondaryColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 网格项
 */
@Composable
fun PowerGridItem(
    song: AudioFile,
    isPlaying: Boolean,
    params: ListZoomParams,
    textColor: Color,
    secondaryColor: Color,
    highlightColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cornerRadius = params.cornerRadiusAlbumsDp.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongClick() })
            }
            .padding(4.dp)
    ) {
        if (song.albumArtPath.isNotBlank()) {
            BitmapImage(
                key = song.albumArtPath,
                contentDescription = song.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
                targetWidth = 256,
                targetHeight = 256
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.White.copy(alpha = 0.1f))
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = song.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isPlaying) highlightColor else textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (song.artist.isNotBlank()) {
            Text(
                text = song.artist,
                fontSize = 11.sp,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
