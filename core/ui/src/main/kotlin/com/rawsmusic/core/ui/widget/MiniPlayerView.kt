package com.rawsmusic.core.ui.widget

import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.NativePlayerArtworkSwitchEasing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MINI_PLAYER_SWITCH_THRESHOLD = 0.20f
private const val MINI_PLAYER_SWITCH_DURATION_MS = 260
private val MiniPlayerSwitchEasing = NativePlayerArtworkSwitchEasing

private data class MiniPlayerContentSnapshot(
    val identity: String,
    val title: String,
    val artist: String,
    val lyricText: String,
    val lyricTranslation: String,
    val coverPath: String?,
    val isPlaying: Boolean
)

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
    contentIdentity: String? = null,
    currentSong: AudioFile? = null,
    previousSong: AudioFile? = null,
    nextSong: AudioFile? = null,
    previousTitle: String? = null,
    previousArtist: String = "",
    previousCoverPath: String? = null,
    previousIdentity: String? = null,
    nextTitle: String? = null,
    nextArtist: String = "",
    nextCoverPath: String? = null,
    nextIdentity: String? = null,
    queueCurrentIndex: Int = -1,
    queueSize: Int = 0,
    backdrop: Backdrop? = null,
    animateArtwork: Boolean = false,
    onClick: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSwitchProgress: (progress: Float, active: Boolean) -> Unit = { _, _ -> },
    onCoverBoundsChanged: (RectF?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cs = MiuixTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    val shape = RoundedCornerShape(50)

    val textColor = cs.onBackground
    val secondaryColor = cs.onSurfaceVariantSummary

    val artworkModeState = rememberMiniPlayerArtworkMode()
    val artworkMode = artworkModeState.value
    val authoritativeSnapshot = remember(
        currentSong,
        contentIdentity,
        title,
        artist,
        lyricText,
        lyricTranslation,
        coverPath,
        isPlaying
    ) {
        MiniPlayerContentSnapshot(
            identity = contentIdentity?.takeIf { it.isNotBlank() }
                ?: currentSong.miniPlayerIdentity(title, artist),
            title = title,
            artist = artist,
            lyricText = lyricText,
            lyricTranslation = lyricTranslation,
            coverPath = coverPath,
            isPlaying = isPlaying
        )
    }
    val previousSnapshot = remember(
        previousSong, previousTitle, previousArtist, previousCoverPath, previousIdentity, isPlaying
    ) {
        previousSong?.toMiniPlayerPreviewSnapshot(isPlaying)
            ?: previousTitle?.takeIf(String::isNotBlank)?.let { previewTitle ->
                MiniPlayerContentSnapshot(
                    identity = previousIdentity?.takeIf(String::isNotBlank) ?: "$previewTitle|$previousArtist",
                    title = previewTitle,
                    artist = previousArtist,
                    lyricText = "",
                    lyricTranslation = "",
                    coverPath = previousCoverPath,
                    isPlaying = isPlaying,
                )
            }
    }
    val nextSnapshot = remember(
        nextSong, nextTitle, nextArtist, nextCoverPath, nextIdentity, isPlaying
    ) {
        nextSong?.toMiniPlayerPreviewSnapshot(isPlaying)
            ?: nextTitle?.takeIf(String::isNotBlank)?.let { previewTitle ->
                MiniPlayerContentSnapshot(
                    identity = nextIdentity?.takeIf(String::isNotBlank) ?: "$previewTitle|$nextArtist",
                    title = previewTitle,
                    artist = nextArtist,
                    lyricText = "",
                    lyricTranslation = "",
                    coverPath = nextCoverPath,
                    isPlaying = isPlaying,
                )
            }
    }
    val latestAuthoritativeSnapshot by rememberUpdatedState(authoritativeSnapshot)
    val latestQueueIndex by rememberUpdatedState(queueCurrentIndex)
    val latestPreviousSnapshot by rememberUpdatedState(previousSnapshot)
    val latestNextSnapshot by rememberUpdatedState(nextSnapshot)
    val latestSkipPrevious by rememberUpdatedState(onSkipPrevious)
    val latestSkipNext by rememberUpdatedState(onSkipNext)
    val latestSwitchProgress by rememberUpdatedState(onSwitchProgress)

    var visibleSnapshot by remember { mutableStateOf(authoritativeSnapshot) }
    var outgoingSnapshot by remember { mutableStateOf<MiniPlayerContentSnapshot?>(null) }
    var incomingSnapshot by remember { mutableStateOf<MiniPlayerContentSnapshot?>(null) }
    var transitionDirection by remember { mutableIntStateOf(0) }
    var contentWidthPx by remember { mutableFloatStateOf(1f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var transitionRunning by remember { mutableStateOf(false) }
    var pendingIdentity by remember { mutableStateOf<String?>(null) }
    var settledQueueIndex by remember { mutableIntStateOf(queueCurrentIndex) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun previewForDirection(direction: Int): MiniPlayerContentSnapshot? {
        return if (direction > 0) latestNextSnapshot else latestPreviousSnapshot
    }

    fun clearTransition(target: MiniPlayerContentSnapshot? = null) {
        val completedDirection = transitionDirection
        if (target != null) {
            val latest = latestAuthoritativeSnapshot
            visibleSnapshot = if (latest.identity == target.identity) latest else target
        }
        outgoingSnapshot = null
        incomingSnapshot = null
        transitionDirection = 0
        dragOffsetPx = 0f
        transitionRunning = false
        pendingIdentity = null
        settledQueueIndex = latestQueueIndex
        latestSwitchProgress(
            if (target != null) completedDirection.toFloat() else 0f,
            false
        )
    }

    fun animateSwitch(
        direction: Int,
        target: MiniPlayerContentSnapshot,
        dispatchTransport: Boolean,
        startOffset: Float = dragOffsetPx
    ) {
        transitionJob?.cancel()
        transitionJob = scope.launch {
            transitionRunning = true
            outgoingSnapshot = visibleSnapshot
            incomingSnapshot = target
            transitionDirection = direction
            pendingIdentity = target.identity

            if (dispatchTransport) {
                if (direction > 0) latestSkipNext() else latestSkipPrevious()
            }

            val animation = Animatable(startOffset)
            animation.animateTo(
                targetValue = -direction * contentWidthPx.coerceAtLeast(1f),
                animationSpec = tween(
                    durationMillis = MINI_PLAYER_SWITCH_DURATION_MS,
                    easing = MiniPlayerSwitchEasing
                )
            ) {
                dragOffsetPx = value
                latestSwitchProgress(
                    -value / contentWidthPx.coerceAtLeast(1f),
                    true
                )
            }
            clearTransition(target)
            transitionJob = null
        }
    }

    fun cancelDrag() {
        transitionJob?.cancel()
        transitionJob = scope.launch {
            transitionRunning = true
            val animation = Animatable(dragOffsetPx)
            animation.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 210,
                    easing = MiniPlayerSwitchEasing
                )
            ) {
                dragOffsetPx = value
                latestSwitchProgress(
                    -value / contentWidthPx.coerceAtLeast(1f),
                    true
                )
            }
            clearTransition()
            transitionJob = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(authoritativeSnapshot) {
        if (
            !transitionRunning &&
            visibleSnapshot.identity == authoritativeSnapshot.identity
        ) {
            visibleSnapshot = authoritativeSnapshot
            settledQueueIndex = queueCurrentIndex
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        authoritativeSnapshot.identity,
        transitionRunning
    ) {
        if (
            visibleSnapshot.identity == authoritativeSnapshot.identity ||
            pendingIdentity == authoritativeSnapshot.identity ||
            transitionRunning
        ) {
            return@LaunchedEffect
        }
        if (contentWidthPx <= 1f) {
            visibleSnapshot = authoritativeSnapshot
            settledQueueIndex = queueCurrentIndex
            return@LaunchedEffect
        }
        val direction = resolveMiniPlayerQueueDirection(
            oldIndex = settledQueueIndex,
            newIndex = queueCurrentIndex,
            queueSize = queueSize
        )
        animateSwitch(
            direction = direction,
            target = authoritativeSnapshot,
            dispatchTransport = false,
            startOffset = 0f
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(62.dp)
            .clip(shape)
            .miniPlayerOuterRemainingProgress(
                progress = progress,
                radiusDp = 31f,
                color = cs.primary
            )
            .onSizeChanged { contentWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        val interruptedTarget = incomingSnapshot
                        transitionJob?.cancel()
                        transitionJob = null
                        if (transitionRunning && interruptedTarget != null) {
                            val latest = latestAuthoritativeSnapshot
                            visibleSnapshot = if (latest.identity == interruptedTarget.identity) {
                                latest
                            } else {
                                interruptedTarget
                            }
                            settledQueueIndex = latestQueueIndex
                        }
                        transitionRunning = false
                        pendingIdentity = null
                        outgoingSnapshot = visibleSnapshot
                        incomingSnapshot = null
                        transitionDirection = 0
                        dragOffsetPx = 0f
                    },
                    onHorizontalDrag = { change, amount ->
                        val proposed = (dragOffsetPx + amount)
                            .coerceIn(-contentWidthPx, contentWidthPx)
                        val direction = when {
                            proposed < 0f -> 1
                            proposed > 0f -> -1
                            else -> 0
                        }
                        val target = if (direction == 0) null else previewForDirection(direction)
                        transitionDirection = direction
                        incomingSnapshot = target
                        dragOffsetPx = if (target == null) proposed * 0.16f else proposed
                        latestSwitchProgress(
                            -dragOffsetPx / contentWidthPx.coerceAtLeast(1f),
                            true
                        )
                        change.consume()
                    },
                    onDragEnd = {
                        val direction = when {
                            dragOffsetPx < 0f -> 1
                            dragOffsetPx > 0f -> -1
                            else -> 0
                        }
                        val target = if (direction == 0) null else previewForDirection(direction)
                        val shouldCommit = target != null &&
                            abs(dragOffsetPx) >= contentWidthPx * MINI_PLAYER_SWITCH_THRESHOLD
                        if (shouldCommit) {
                            animateSwitch(
                                direction = direction,
                                target = target,
                                dispatchTransport = true
                            )
                        } else {
                            cancelDrag()
                        }
                    },
                    onDragCancel = { cancelDrag() }
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
        val outgoing = outgoingSnapshot
        val incoming = incomingSnapshot
        if (transitionDirection != 0 && outgoing != null && incoming != null) {
            val progress = (abs(dragOffsetPx) / contentWidthPx).coerceIn(0f, 1f)
            MiniPlayerSlidingContent(
                snapshot = outgoing,
                artworkMode = artworkMode,
                textColor = textColor,
                secondaryColor = secondaryColor,
                animateArtwork = animateArtwork,
                onClick = onClick,
                onPlayPause = onPlayPause,
                onCoverBoundsChanged = {},
                onToggleArtworkMode = {
                    artworkModeState.value = artworkModeState.value.toggle()
                },
                controlsEnabled = false,
                modifier = Modifier.graphicsLayer {
                    translationX = dragOffsetPx
                    alpha = 1f - progress * 0.10f
                    scaleX = 1f - progress * 0.018f
                    scaleY = scaleX
                }
            )
            MiniPlayerSlidingContent(
                snapshot = incoming,
                artworkMode = artworkMode,
                textColor = textColor,
                secondaryColor = secondaryColor,
                animateArtwork = false,
                onClick = onClick,
                onPlayPause = onPlayPause,
                onCoverBoundsChanged = {},
                onToggleArtworkMode = {
                    artworkModeState.value = artworkModeState.value.toggle()
                },
                controlsEnabled = false,
                modifier = Modifier.graphicsLayer {
                    translationX = dragOffsetPx + transitionDirection * contentWidthPx
                    alpha = 0.90f + progress * 0.10f
                    scaleX = 0.982f + progress * 0.018f
                    scaleY = scaleX
                }
            )
        } else {
            MiniPlayerSlidingContent(
                snapshot = visibleSnapshot,
                artworkMode = artworkMode,
                textColor = textColor,
                secondaryColor = secondaryColor,
                animateArtwork = animateArtwork,
                onClick = onClick,
                onPlayPause = onPlayPause,
                onCoverBoundsChanged = onCoverBoundsChanged,
                onToggleArtworkMode = {
                    artworkModeState.value = artworkModeState.value.toggle()
                },
                controlsEnabled = true
            )
        }
    }
}

@Composable
private fun MiniPlayerSlidingContent(
    snapshot: MiniPlayerContentSnapshot,
    artworkMode: MiniPlayerArtworkMode,
    textColor: Color,
    secondaryColor: Color,
    animateArtwork: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onToggleArtworkMode: () -> Unit,
    controlsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val hasLyric = snapshot.lyricText.isNotBlank()
    val primaryText = if (hasLyric) {
        snapshot.lyricText.trim()
    } else {
        snapshot.title.ifBlank { "暂无音乐播放" }
    }
    val secondaryText = if (hasLyric) snapshot.lyricTranslation.trim() else snapshot.artist
    val centerLyrics = hasLyric && isLikelyChineseLyric(primaryText)

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniPlayerArtwork(
            mode = artworkMode,
            coverPath = snapshot.coverPath,
            isPlaying = snapshot.isPlaying,
            contentDescription = snapshot.title,
            onCoverBoundsChanged = onCoverBoundsChanged,
            onDoubleTapToggleMode = if (controlsEnabled) onToggleArtworkMode else ({}),
            onSingleTap = if (controlsEnabled) onClick else ({}),
            animateArtwork = animateArtwork
        )

        Spacer(modifier = Modifier.width(8.dp))

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
                            iterations = 1,
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
                                iterations = 1,
                                repeatDelayMillis = 900
                            )
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .then(
                    if (controlsEnabled) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPlayPause
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun AudioFile?.miniPlayerIdentity(title: String, artist: String): String {
    val song = this
    if (song != null) {
        return "${song.path}|${song.cueTrackIndex}|${song.cueOffsetMs}"
    }
    return "$title|$artist"
}

private fun AudioFile.toMiniPlayerPreviewSnapshot(isPlaying: Boolean): MiniPlayerContentSnapshot {
    return MiniPlayerContentSnapshot(
        identity = miniPlayerIdentity(displayName, artist),
        title = displayName,
        artist = artist,
        lyricText = "",
        lyricTranslation = "",
        coverPath = resolvePlaybackArtworkKey(albumArtPath),
        isPlaying = isPlaying
    )
}

private fun resolveMiniPlayerQueueDirection(
    oldIndex: Int,
    newIndex: Int,
    queueSize: Int
): Int {
    if (queueSize <= 1 || oldIndex < 0 || newIndex < 0 || oldIndex == newIndex) return 1
    if ((oldIndex + 1) % queueSize == newIndex) return 1
    if ((oldIndex - 1 + queueSize) % queueSize == newIndex) return -1
    return if (newIndex > oldIndex) 1 else -1
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
