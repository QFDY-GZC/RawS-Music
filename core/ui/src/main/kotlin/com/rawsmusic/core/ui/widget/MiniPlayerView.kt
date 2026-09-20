package com.rawsmusic.core.ui.widget

import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.waveform.RawWaveformCache
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.NativePlayerArtworkSwitchEasing
import com.rawsmusic.core.ui.widget.player.LyricDirectRenderClock
import com.rawsmusic.core.ui.widget.player.LyricColorSurface
import com.rawsmusic.core.ui.widget.player.ResolvedLyricColor
import com.rawsmusic.core.ui.widget.player.effectiveLineEnd
import com.rawsmusic.core.ui.widget.player.effectiveLineStart
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import com.rawsmusic.core.ui.widget.player.rememberLyricDirectRenderClock
import com.rawsmusic.core.ui.widget.player.rememberResolvedLyricColor
import com.rawsmusic.core.ui.widget.text.SharedMarqueeText
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.MiniPlayerBubbleMotion
import com.rawsmusic.module.data.prefs.MiniPlayerBubbleOrigin
import com.rawsmusic.module.data.prefs.MiniPlayerProgressDirection
import com.rawsmusic.module.data.prefs.MiniPlayerPlayPausePosition
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MINI_PLAYER_SWITCH_THRESHOLD = 0.20f
private const val MINI_PLAYER_SWITCH_DURATION_MS = 260
private const val MINI_PLAYER_POST_DRAG_CLICK_BLOCK_MS = 320L
private val MiniPlayerSwitchEasing = NativePlayerArtworkSwitchEasing
private val MiniPlayerEnergyGlowEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private const val MINI_PLAYER_ENERGY_GLOW_MAX_ALPHA = 0.5019608f
private val MINI_PLAYER_ENERGY_GLOW_RADIUS = 5.dp

object MiniPlayerKaraokeEffectState {
    var enabled by mutableStateOf(AppPreferences.UI.miniPlayerKaraokeEffectEnabled)
        private set

    fun updateEnabled(value: Boolean) {
        enabled = value
        AppPreferences.UI.miniPlayerKaraokeEffectEnabled = value
    }
}

private data class MiniPlayerContentSnapshot(
    val identity: String,
    val title: String,
    val artist: String,
    val lyricText: String,
    val lyricTranslation: String,
    val coverPath: String?,
    val isPlaying: Boolean
)

private data class MiniPlayerKaraokeWordSpan(
    val beginMs: Long,
    val endMs: Long,
    val startCharacter: Int,
    val endCharacterExclusive: Int,
)

private data class MiniPlayerKaraokeLineSpan(
    val beginMs: Long,
    val endMs: Long,
    val words: List<MiniPlayerKaraokeWordSpan>,
)

private data class MiniPlayerKaraokePresentation(
    val textLength: Int,
    val lines: List<MiniPlayerKaraokeLineSpan>,
    val rtl: Boolean,
) {
    fun characterPositionAt(positionMs: Float): Float {
        if (textLength <= 0 || lines.isEmpty()) return 0f
        val position = positionMs.coerceAtLeast(0f)
        val activeLine = lines.firstOrNull { line ->
            position >= line.beginMs.toFloat() && position < line.endMs.toFloat()
        }
        // During an instrumental gap LyricsCoordinator intentionally keeps the last readable
        // line visible. Keep that line fully highlighted instead of snapping to a future duplicate
        // with the same text. If the same sentence appears again later, the active interval above
        // takes ownership as soon as its real timeline starts.
        val line = activeLine
            ?: lines.lastOrNull { it.beginMs.toFloat() <= position }
            ?: lines.first()
        if (position < line.beginMs.toFloat()) return 0f
        if (position >= line.endMs.toFloat()) return textLength.toFloat()
        if (line.words.isEmpty()) return 0f

        var completedCharacter = 0f
        line.words.forEach { word ->
            if (position < word.beginMs.toFloat()) return completedCharacter
            if (position >= word.endMs.toFloat()) {
                completedCharacter = word.endCharacterExclusive.toFloat()
                return@forEach
            }
            val duration = (word.endMs - word.beginMs).coerceAtLeast(1L).toFloat()
            val wordProgress = ((position - word.beginMs.toFloat()) / duration).coerceIn(0f, 1f)
            return word.startCharacter.toFloat() +
                (word.endCharacterExclusive - word.startCharacter).toFloat() * wordProgress
        }
        return completedCharacter.coerceIn(0f, textLength.toFloat())
    }
}

/** Gesture-only state. Intentionally not Snapshot state: DOWN/first-drag preparation must not
 * schedule a Compose tree recomposition. The three physical holders are already mounted. */
private class MiniPlayerGestureTransaction {
    var frozenCenter: MiniPlayerContentSnapshot? = null
    var preparedDirection: Int = 0
    var preparedTargetIdentity: String? = null
    private var pointerCaptured: Boolean = false
    private var controlsBlockedUntilUptimeMs: Long = 0L

    fun reset() {
        frozenCenter = null
        preparedDirection = 0
        preparedTargetIdentity = null
    }

    fun beginPointerCapture(nowUptimeMs: Long) {
        pointerCaptured = true
        controlsBlockedUntilUptimeMs = maxOf(
            controlsBlockedUntilUptimeMs,
            nowUptimeMs + MINI_PLAYER_POST_DRAG_CLICK_BLOCK_MS,
        )
    }

    fun endPointerCapture(nowUptimeMs: Long) {
        pointerCaptured = false
        controlsBlockedUntilUptimeMs = maxOf(
            controlsBlockedUntilUptimeMs,
            nowUptimeMs + MINI_PLAYER_POST_DRAG_CLICK_BLOCK_MS,
        )
    }

    fun controlsAllowed(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean =
        miniPlayerChildControlAllowed(
            pointerCaptured = pointerCaptured,
            controlsBlockedUntilUptimeMs = controlsBlockedUntilUptimeMs,
            nowUptimeMs = nowUptimeMs,
        )
}

/**
 * 纯 Compose 版本的迷你播放栏
 *
 * 支持：
 * - 液态玻璃背景（Backdrop）
 * - 封面旋转 + 环形进度条
 * - 歌曲信息/歌词滚动
 * - 水平滑动手势切歌
 * - 点击打开播放器
 * - 长按在圆形 / 黑胶 / 原图三种封面模式间切换
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeMiniPlayer(
    title: String,
    artist: String,
    lyricText: String = "",
    lyricTranslation: String = "",
    lyricSong: Song? = null,
    isPlaying: Boolean,
    progress: Float = 0f,
    playbackPositionMs: Long = 0L,
    playbackDurationMs: Long = 0L,
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
    artworkVisible: Boolean = true,
    backdrop: Backdrop? = null,
    animateArtwork: Boolean = false,
    drawBackground: Boolean = true,
    drawOuterProgress: Boolean = true,
    drawInlineProgress: Boolean = false,
    inlineProgressColor: Color? = null,
    inlineProgressBubbles: Boolean = true,
    inlineProgressDirection: MiniPlayerProgressDirection = MiniPlayerProgressDirection.LEFT_TO_RIGHT,
    inlineProgressBubbleCount: Int = 14,
    inlineProgressBubbleMotion: MiniPlayerBubbleMotion = MiniPlayerBubbleMotion.FLOAT,
    inlineProgressBubbleSpeed: Float = 1f,
    inlineProgressHighEnergyHighlight: Boolean = false,
    inlineProgressBubbleOrigin: MiniPlayerBubbleOrigin = MiniPlayerBubbleOrigin.START_EDGE,
    containerHeight: androidx.compose.ui.unit.Dp = 62.dp,
    containerShape: Shape = RoundedCornerShape(50),
    clipContent: Boolean = true,
    contentPaddingHorizontal: androidx.compose.ui.unit.Dp = 8.dp,
    contentPaddingStart: androidx.compose.ui.unit.Dp = contentPaddingHorizontal,
    contentPaddingEnd: androidx.compose.ui.unit.Dp = contentPaddingHorizontal,
    contentPaddingVertical: androidx.compose.ui.unit.Dp = 6.dp,
    playPauseVisualSize: androidx.compose.ui.unit.Dp = 24.dp,
    playPauseTouchSize: androidx.compose.ui.unit.Dp = 48.dp,
    showPreviousControl: Boolean = false,
    previousIconRes: Int = R.drawable.ic_skip_previous,
    playPausePosition: MiniPlayerPlayPausePosition = MiniPlayerPlayPausePosition.TRAILING,
    showSkipNextControl: Boolean = false,
    skipNextIconRes: Int = R.drawable.ic_skip_next,
    skipNextImageVector: ImageVector? = null,
    secondaryControlVisualSize: androidx.compose.ui.unit.Dp = 28.dp,
    secondaryControlTouchSize: androidx.compose.ui.unit.Dp = 48.dp,
    secondaryControlVisibility: Float = 1f,
    artworkSize: androidx.compose.ui.unit.Dp = 44.dp,
    artworkConstraintHeight: androidx.compose.ui.unit.Dp? = null,
    originalArtworkCornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    artworkTextGap: androidx.compose.ui.unit.Dp = 8.dp,
    controlGap: androidx.compose.ui.unit.Dp = 0.dp,
    primaryContentColor: Color? = null,
    secondaryContentColor: Color? = null,
    onClick: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSecondaryAction: () -> Unit = onSkipNext,
    onSwitchProgress: (progress: Float, active: Boolean) -> Unit = { _, _ -> },
    onCoverBoundsChanged: (RectF?) -> Unit = {},
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cs = MiuixTheme.colorScheme
    val staticForeground = usesReferenceStaticForeground()
    val isLight = !staticForeground && cs.background.luminance() > 0.5f
    val shape = containerShape

    val textColor = primaryContentColor ?: if (staticForeground) Color.White else cs.onBackground
    val secondaryColor = secondaryContentColor ?: if (staticForeground) Color.White.copy(alpha = 0.72f) else cs.onSurfaceVariantSummary

    val artworkModeState = rememberMiniPlayerArtworkMode()
    val artworkMode = artworkModeState.value
    val artworkSizingHeightDp = minOf(
        containerHeight.value,
        artworkConstraintHeight?.value ?: containerHeight.value,
    )
    val effectiveArtworkSizeDp = miniPlayerArtworkSizeBounds(
        expandedHeightDp = artworkSizingHeightDp,
        compactHeightDp = artworkSizingHeightDp,
        mode = artworkMode,
    ).constrain(artworkSize.value)
    val effectiveArtworkSize = effectiveArtworkSizeDp.dp
    val artworkEnvelopeHeight = when (artworkMode) {
        MiniPlayerArtworkMode.Normal,
        MiniPlayerArtworkMode.Original -> effectiveArtworkSize
        MiniPlayerArtworkMode.Vinyl -> effectiveArtworkSize * (52f / 44f)
    }
    val contentEnvelopeHeight = maxOf(44.dp, artworkEnvelopeHeight)
    val effectiveContentPaddingVertical = minOf(
        contentPaddingVertical,
        ((containerHeight - contentEnvelopeHeight) / 2f).coerceAtLeast(0.dp),
    )
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
    val miniPlayerKaraokeEnabled = MiniPlayerKaraokeEffectState.enabled
    val lyricLines = remember(lyricSong) { lyricSong?.lyrics.orEmpty() }
    val miniLyricAlbumAccent = rememberCoverAccentColor(
        currentSong.resolvePlaybackArtworkKey(coverPath),
    )
    val miniLyricColors = rememberResolvedLyricColor(
        surface = LyricColorSurface.MINI_PLAYER,
        fallbackPrimary = textColor,
        albumAccent = miniLyricAlbumAccent,
    )
    val karaokePresentation = remember(
        miniPlayerKaraokeEnabled,
        lyricLines,
        authoritativeSnapshot.lyricText,
    ) {
        if (miniPlayerKaraokeEnabled) {
            resolveMiniPlayerKaraokePresentation(
                lines = lyricLines,
                displayedText = authoritativeSnapshot.lyricText,
            )
        } else {
            null
        }
    }
    val karaokeClock = rememberLyricDirectRenderClock(
        positionMs = playbackPositionMs,
        isPlaying = isPlaying && miniPlayerKaraokeEnabled && karaokePresentation != null,
        durationMs = playbackDurationMs,
    )
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

    // Reference keeps three complete ArtworkItemNode holders attached and moves the whole holder. Mirror
    // that ownership here: these are physical Compose holder lanes, not temporary transition trees.
    // The lane carrying the target becomes the new center lane at commit, so its title/artwork node is
    // never destroyed on the exact frame where the queue/current-song state changes.
    var centerHolderSlot by remember { mutableIntStateOf(1) }
    var holder0Snapshot by remember { mutableStateOf(previousSnapshot ?: authoritativeSnapshot) }
    var holder1Snapshot by remember { mutableStateOf(authoritativeSnapshot) }
    var holder2Snapshot by remember { mutableStateOf(nextSnapshot ?: authoritativeSnapshot) }

    var transitionDirection by remember { mutableIntStateOf(0) }
    var contentWidthPx by remember { mutableFloatStateOf(1f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var transitionRunning by remember { mutableStateOf(false) }
    var pendingIdentity by remember { mutableStateOf<String?>(null) }
    var settledQueueIndex by remember { mutableIntStateOf(queueCurrentIndex) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    // A horizontal gesture is a transaction. Freeze the physical center holder at capture and do
    // not allow queue-preview or delayed state publication to rewrite it until the gesture either
    // commits or rebounds. This mirrors Reference's attached ArtworkItemNode ownership and prevents the
    // first drag frame from briefly showing the previous track.
    val gestureTransaction = remember { MiniPlayerGestureTransaction() }
    // The mini-player owns both horizontal track switching and tap-to-open. Keep a short
    // post-drag suppression window so the UP that commits a track switch can never leak through
    // the sibling clickable/tap detector and force-open the full player. Treat dragging the
    // mini player and tapping it as mutually exclusive gesture outcomes.
    var suppressOpenUntilUptimeMs by remember { mutableStateOf(0L) }
    var horizontalGestureActive by remember { mutableStateOf(false) }
    fun blockOpenFromCurrentHorizontalGesture() {
        suppressOpenUntilUptimeMs = SystemClock.uptimeMillis() + MINI_PLAYER_POST_DRAG_CLICK_BLOCK_MS
    }
    fun childControlsAllowed(): Boolean = gestureTransaction.controlsAllowed()
    val guardedOpenPlayer = {
        if (SystemClock.uptimeMillis() >= suppressOpenUntilUptimeMs) {
            onClick()
        }
    }
    val scope = rememberCoroutineScope()
    // Same-track lyric/translation/play-state updates are live content, not a track transition.
    // Render them directly from the authoritative snapshot instead of copying the whole snapshot
    // through a LaunchedEffect one frame later. That extra state commit rebuilt the mini-player
    // text subtree on every sentence boundary and was visible as a short flash on device.
    val renderedSnapshot = if (
        !transitionRunning &&
        transitionDirection == 0 &&
        visibleSnapshot.identity == authoritativeSnapshot.identity
    ) {
        visibleSnapshot.withAuthoritativePresentation(authoritativeSnapshot)
    } else {
        visibleSnapshot
    }
    // pointerInput(Unit) intentionally stays mounted across song changes. Never let that long-lived
    // gesture coroutine retain an old renderedSnapshot value; callbacks must see the latest center.
    val latestRenderedSnapshot by rememberUpdatedState(renderedSnapshot)

    fun previewForDirection(direction: Int): MiniPlayerContentSnapshot? {
        return if (direction > 0) latestNextSnapshot else latestPreviousSnapshot
    }

    fun previousHolderSlot(center: Int = centerHolderSlot): Int = (center + 2) % 3
    fun nextHolderSlot(center: Int = centerHolderSlot): Int = (center + 1) % 3
    fun holderSnapshot(slot: Int): MiniPlayerContentSnapshot = when (slot) {
        0 -> holder0Snapshot
        1 -> holder1Snapshot
        else -> holder2Snapshot
    }
    fun setHolderSnapshot(slot: Int, snapshot: MiniPlayerContentSnapshot) {
        when (slot) {
            0 -> holder0Snapshot = snapshot
            1 -> holder1Snapshot = snapshot
            else -> holder2Snapshot = snapshot
        }
    }
    fun preparePersistentHolderTarget(
        direction: Int,
        target: MiniPlayerContentSnapshot,
        frozenCenter: MiniPlayerContentSnapshot = gestureTransaction.frozenCenter ?: latestRenderedSnapshot,
    ) {
        // The center is written once per gesture/transition, never once per pointer delta. Rewriting
        // Compose snapshot state on every drag frame caused unnecessary composition work and made
        // the switch feel sticky on high-refresh devices.
        setHolderSnapshot(centerHolderSlot, frozenCenter)
        val targetSlot = if (direction > 0) nextHolderSlot() else previousHolderSlot()
        setHolderSnapshot(targetSlot, target)
        gestureTransaction.preparedDirection = direction
        gestureTransaction.preparedTargetIdentity = target.identity
    }

    fun clearTransition(target: MiniPlayerContentSnapshot? = null) {
        val completedDirection = transitionDirection
        if (target != null) {
            val latest = latestAuthoritativeSnapshot
            val committed = if (latest.identity == target.identity) {
                target.withAuthoritativePresentation(latest)
            } else {
                target
            }
            val targetSlot = if (completedDirection > 0) {
                nextHolderSlot()
            } else {
                previousHolderSlot()
            }
            // Promote the physical target holder itself. Immediately before this commit it is
            // already at x=0; after the center index rotates it remains at x=0 with the same node.
            setHolderSnapshot(targetSlot, committed)
            centerHolderSlot = targetSlot
            visibleSnapshot = committed
        }
        transitionDirection = 0
        dragOffsetPx = 0f
        transitionRunning = false
        pendingIdentity = null
        gestureTransaction.reset()
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
            val frozenCenter = gestureTransaction.frozenCenter ?: latestRenderedSnapshot
            transitionDirection = direction
            pendingIdentity = target.identity
            if (
                gestureTransaction.preparedDirection != direction ||
                gestureTransaction.preparedTargetIdentity != target.identity
            ) {
                preparePersistentHolderTarget(direction, target, frozenCenter)
            }

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

    SideEffect {
        if (
            !transitionRunning &&
            transitionDirection == 0 &&
            visibleSnapshot.identity == authoritativeSnapshot.identity
        ) {
            // Rebind in place like Reference ArtworkItemNode.L0(): keep all three holder trees attached
            // and only replace the content owned by their stable physical lane.
            val centerPresentation = visibleSnapshot.withAuthoritativePresentation(authoritativeSnapshot)
            setHolderSnapshot(centerHolderSlot, centerPresentation)
            setHolderSnapshot(previousHolderSlot(), previousSnapshot ?: centerPresentation)
            setHolderSnapshot(nextHolderSlot(), nextSnapshot ?: centerPresentation)
            if (settledQueueIndex != queueCurrentIndex) {
                settledQueueIndex = queueCurrentIndex
            }
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
        val stableTarget = previewForDirection(direction)
            ?.takeIf { it.identity == authoritativeSnapshot.identity }
            ?.withAuthoritativePresentation(authoritativeSnapshot)
            ?: authoritativeSnapshot
        animateSwitch(
            direction = direction,
            target = stableTarget,
            dispatchTransport = false,
            startOffset = 0f
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeight)
            .then(if (clipContent) Modifier.clip(shape) else Modifier)
            .then(
                if (drawOuterProgress) {
                    Modifier.miniPlayerOuterRemainingProgress(
                        progress = progress,
                        radiusDp = containerHeight.value / 2f,
                        color = if (staticForeground) Color.White else cs.primary
                    )
                } else {
                    Modifier
                }
            )
            .onSizeChanged { contentWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        val captureUptimeMs = SystemClock.uptimeMillis()
                        gestureTransaction.beginPointerCapture(captureUptimeMs)
                        horizontalGestureActive = true
                        blockOpenFromCurrentHorizontalGesture()
                        transitionJob?.cancel()
                        transitionJob = null

                        // Start a fresh gesture from the *authoritative* current holder. If the
                        // finger recaptures while the previous switch is still settling, the old
                        // center lane may still physically be slot N while playback has already
                        // committed slot N+1. Find the holder carrying the authoritative identity
                        // and promote that physical lane before resetting geometry. Never promote a
                        // merely-previewed neighbour lane.
                        val latest = latestAuthoritativeSnapshot
                        val hadActiveTransition = transitionRunning || transitionDirection != 0 || abs(dragOffsetPx) > 0.5f
                        val authoritativeSlot = (0..2).firstOrNull { slot ->
                            holderSnapshot(slot).identity == latest.identity
                        }
                        if (authoritativeSlot != null && authoritativeSlot != centerHolderSlot) {
                            centerHolderSlot = authoritativeSlot
                        }
                        val centerRaw = holderSnapshot(centerHolderSlot)
                        val frozenCenter = if (centerRaw.identity == latest.identity) {
                            centerRaw.withAuthoritativePresentation(latest)
                        } else {
                            latest
                        }
                        gestureTransaction.reset()
                        gestureTransaction.frozenCenter = frozenCenter

                        // Prime both already-mounted neighbours at capture. Usually these assignments are
                        // equality no-ops because SideEffect has kept them warm; importantly, the first
                        // pointer delta no longer changes a holder subtree or starts bitmap/text work.
                        setHolderSnapshot(centerHolderSlot, frozenCenter)
                        setHolderSnapshot(previousHolderSlot(), latestPreviousSnapshot ?: frozenCenter)
                        setHolderSnapshot(nextHolderSlot(), latestNextSnapshot ?: frozenCenter)
                        if (visibleSnapshot.identity != frozenCenter.identity || hadActiveTransition) {
                            visibleSnapshot = frozenCenter
                        }
                        if (settledQueueIndex != latestQueueIndex) settledQueueIndex = latestQueueIndex
                        if (hadActiveTransition) {
                            transitionRunning = false
                            pendingIdentity = null
                            transitionDirection = 0
                            dragOffsetPx = 0f
                        }
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
                        if (target != null) {
                            val targetSlot = if (direction > 0) nextHolderSlot() else previousHolderSlot()
                            if (holderSnapshot(targetSlot).identity == target.identity) {
                                // Already prewarmed: mark the transaction prepared without touching
                                // Snapshot state. This also removes the release-frame holder rewrite.
                                gestureTransaction.preparedDirection = direction
                                gestureTransaction.preparedTargetIdentity = target.identity
                            } else if (
                                gestureTransaction.preparedDirection != direction ||
                                gestureTransaction.preparedTargetIdentity != target.identity
                            ) {
                                preparePersistentHolderTarget(
                                    direction = direction,
                                    target = target,
                                    frozenCenter = gestureTransaction.frozenCenter ?: latestRenderedSnapshot,
                                )
                            }
                        }
                        dragOffsetPx = if (target == null) proposed * 0.16f else proposed
                        latestSwitchProgress(
                            -dragOffsetPx / contentWidthPx.coerceAtLeast(1f),
                            true
                        )
                        change.consume()
                    },
                    onDragEnd = {
                        gestureTransaction.endPointerCapture(SystemClock.uptimeMillis())
                        horizontalGestureActive = false
                        blockOpenFromCurrentHorizontalGesture()
                        val direction = when {
                            dragOffsetPx < 0f -> 1
                            dragOffsetPx > 0f -> -1
                            else -> 0
                        }
                        val target = if (direction == 0) null else previewForDirection(direction)
                        if (
                            target != null &&
                            abs(dragOffsetPx) >= contentWidthPx * MINI_PLAYER_SWITCH_THRESHOLD
                        ) {
                            animateSwitch(
                                direction = direction,
                                target = target,
                                dispatchTransport = true
                            )
                        } else {
                            cancelDrag()
                        }
                    },
                    onDragCancel = {
                        gestureTransaction.endPointerCapture(SystemClock.uptimeMillis())
                        horizontalGestureActive = false
                        blockOpenFromCurrentHorizontalGesture()
                        cancelDrag()
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = guardedOpenPlayer
            )
    ) {
        if (drawBackground) {
            LiquidGlassMiniPlayerBg(
                backdrop = backdrop,
                isLight = isLight
            )
        }
        if (drawInlineProgress) {
            MiniPlayerInlineProgress(
                reportedProgress = progress,
                playbackPositionMs = playbackPositionMs,
                playbackDurationMs = playbackDurationMs,
                isPlaying = isPlaying,
                contentIdentity = authoritativeSnapshot.identity,
                color = inlineProgressColor ?: if (staticForeground) Color.White else cs.primary,
                bubbles = inlineProgressBubbles,
                currentSong = currentSong,
                direction = inlineProgressDirection,
                bubbleCount = inlineProgressBubbleCount,
                bubbleMotion = inlineProgressBubbleMotion,
                bubbleSpeed = inlineProgressBubbleSpeed,
                highEnergyHighlight = inlineProgressHighEnergyHighlight,
                bubbleOrigin = inlineProgressBubbleOrigin,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Keep the per-frame drag offset out of composition. Reference mutates attached holder
        // properties directly; here the equivalent is reading dragOffsetPx from graphicsLayer so
        // pointer movement invalidates only the layer instead of recomposing three text/art trees.
        val stableCenter = !transitionRunning && transitionDirection == 0
        val previousSlot = previousHolderSlot()
        val nextSlot = nextHolderSlot()

        // Three call sites stay mounted for the life of the MiniPlayer. Logical previous/current/next
        // rotates across these physical slots at commit rather than rebuilding a target title tree.
        for (slot in 0..2) {
            val rawSnapshot = holderSnapshot(slot)
            val snapshot = rawSnapshot.withAuthoritativePresentation(authoritativeSnapshot)
            val logicalOffset = when (slot) {
                centerHolderSlot -> 0
                nextSlot -> 1
                else -> -1
            }
            val activeCenter = logicalOffset == 0
            // Horizontal track switching owns the complete pointer transaction. Disable child
            // click targets as soon as drag capture begins, and keep the callback-side guard below
            // for the release event itself in case Compose has not presented this state yet.
            val controlsEnabled = stableCenter && activeCenter && !horizontalGestureActive
            val coverBoundsCallback: (RectF?) -> Unit = if (controlsEnabled) {
                { rect ->
                    onCoverBoundsChanged(rect)
                    val sourceRadiusDp = miniPlayerArtworkSourceRadiusDp(
                        mode = artworkMode,
                        effectiveArtworkSizeDp = effectiveArtworkSizeDp,
                        originalArtworkCornerRadiusDp = originalArtworkCornerRadius.value,
                    )
                    onCoverTargetChanged(
                        rect?.let { bounds ->
                            CoverTransitionTarget(
                                bounds = RectF(bounds),
                                radiusDp = sourceRadiusDp,
                                source = CoverTransitionTarget.Source.MiniPlayer,
                                songId = currentSong?.id ?: -1L,
                                coverKey = coverPath.orEmpty(),
                            )
                        }
                    )
                }
            } else {
                {}
            }

            MiniPlayerSlidingContent(
                snapshot = snapshot,
                lyricColors = miniLyricColors,
                karaokePresentation = karaokePresentation.takeIf {
                    activeCenter && snapshot.identity == authoritativeSnapshot.identity
                },
                karaokeClock = karaokeClock.takeIf {
                    activeCenter && snapshot.identity == authoritativeSnapshot.identity
                },
                artworkMode = artworkMode,
                textColor = textColor,
                secondaryColor = secondaryColor,
                animateArtwork = animateArtwork && controlsEnabled,
                onClick = guardedOpenPlayer,
                canDispatchControl = ::childControlsAllowed,
                onPlayPause = onPlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onSecondaryAction = onSecondaryAction,
                onCoverBoundsChanged = coverBoundsCallback,
                artworkVisible = artworkVisible,
                onToggleArtworkMode = {
                    artworkModeState.value = artworkModeState.value.toggle()
                },
                controlsEnabled = controlsEnabled,
                marqueeActive = controlsEnabled,
                contentPaddingStart = contentPaddingStart,
                contentPaddingEnd = contentPaddingEnd,
                contentPaddingVertical = effectiveContentPaddingVertical,
                playPauseVisualSize = playPauseVisualSize,
                playPauseTouchSize = playPauseTouchSize,
                showPreviousControl = showPreviousControl,
                previousIconRes = previousIconRes,
                playPausePosition = playPausePosition,
                showSkipNextControl = showSkipNextControl,
                skipNextIconRes = skipNextIconRes,
                skipNextImageVector = skipNextImageVector,
                secondaryControlVisualSize = secondaryControlVisualSize,
                secondaryControlTouchSize = secondaryControlTouchSize,
                secondaryControlVisibility = secondaryControlVisibility,
                artworkSize = effectiveArtworkSize,
                originalArtworkCornerRadius = originalArtworkCornerRadius,
                artworkTextGap = artworkTextGap,
                controlGap = controlGap,
                modifier = Modifier.graphicsLayer {
                    val width = contentWidthPx.coerceAtLeast(1f)
                    val offset = dragOffsetPx
                    val progress = (abs(offset) / width).coerceIn(0f, 1f)
                    val visualDirection = when {
                        offset < -0.5f -> 1
                        offset > 0.5f -> -1
                        else -> transitionDirection
                    }
                    val activeTarget = visualDirection != 0 && logicalOffset == visualDirection
                    val visuallyStable = progress <= 0.0005f && transitionDirection == 0
                    translationX = offset + logicalOffset * width
                    alpha = when {
                        visuallyStable && activeCenter -> 1f
                        visuallyStable -> 0f
                        activeCenter -> 1f - progress * 0.10f
                        activeTarget -> 0.90f + progress * 0.10f
                        else -> 0f
                    }
                    val holderScale = when {
                        visuallyStable && activeCenter -> 1f
                        activeCenter -> 1f - progress * 0.018f
                        activeTarget -> 0.982f + progress * 0.018f
                        else -> 0.982f
                    }
                    scaleX = holderScale
                    scaleY = holderScale
                },
            )
        }
    }
}

@Composable
private fun MiniPlayerSlidingContent(
    snapshot: MiniPlayerContentSnapshot,
    lyricColors: ResolvedLyricColor,
    karaokePresentation: MiniPlayerKaraokePresentation?,
    karaokeClock: LyricDirectRenderClock?,
    artworkMode: MiniPlayerArtworkMode,
    textColor: Color,
    secondaryColor: Color,
    animateArtwork: Boolean,
    onClick: () -> Unit,
    canDispatchControl: () -> Boolean,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSecondaryAction: () -> Unit,
    onCoverBoundsChanged: (RectF?) -> Unit,
    artworkVisible: Boolean,
    onToggleArtworkMode: () -> Unit,
    controlsEnabled: Boolean,
    marqueeActive: Boolean = controlsEnabled,
    contentPaddingStart: androidx.compose.ui.unit.Dp = 8.dp,
    contentPaddingEnd: androidx.compose.ui.unit.Dp = 8.dp,
    contentPaddingVertical: androidx.compose.ui.unit.Dp = 6.dp,
    playPauseVisualSize: androidx.compose.ui.unit.Dp = 24.dp,
    playPauseTouchSize: androidx.compose.ui.unit.Dp = 48.dp,
    showPreviousControl: Boolean = false,
    previousIconRes: Int = R.drawable.ic_skip_previous,
    playPausePosition: MiniPlayerPlayPausePosition = MiniPlayerPlayPausePosition.TRAILING,
    showSkipNextControl: Boolean = false,
    skipNextIconRes: Int = R.drawable.ic_skip_next,
    skipNextImageVector: ImageVector? = null,
    secondaryControlVisualSize: androidx.compose.ui.unit.Dp = 28.dp,
    secondaryControlTouchSize: androidx.compose.ui.unit.Dp = 48.dp,
    secondaryControlVisibility: Float = 1f,
    artworkSize: androidx.compose.ui.unit.Dp = 44.dp,
    originalArtworkCornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    artworkTextGap: androidx.compose.ui.unit.Dp = 8.dp,
    controlGap: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val hasLyric = snapshot.lyricText.isNotBlank()
    val primaryText = if (hasLyric) {
        snapshot.lyricText.trim()
    } else {
        snapshot.title.ifBlank { stringResource(R.string.player_no_music) }
    }
    val secondaryText = if (hasLyric) snapshot.lyricTranslation.trim() else snapshot.artist
    val centerLyrics = hasLyric && isLikelyChineseLyric(primaryText)
    val karaokeActive = hasLyric && karaokePresentation != null
    val primaryBaseColor = if (hasLyric) {
        if (karaokeActive) lyricColors.dim else lyricColors.primary
    } else {
        textColor
    }
    val effectiveSecondaryColor = if (hasLyric) lyricColors.secondary else secondaryColor

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPaddingStart,
                end = contentPaddingEnd,
                top = contentPaddingVertical,
                bottom = contentPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        @Composable
        fun Controls() {
            MiniPlayerTransportCluster(
                isPlaying = snapshot.isPlaying,
                textColor = textColor,
                controlsEnabled = controlsEnabled,
                showPrevious = showPreviousControl,
                previousIconRes = previousIconRes,
                onPrevious = { if (canDispatchControl()) onSkipPrevious() },
                onPlayPause = { if (canDispatchControl()) onPlayPause() },
                playPauseVisualSize = playPauseVisualSize,
                playPauseTouchSize = playPauseTouchSize,
                secondaryVisualSize = secondaryControlVisualSize,
                secondaryTouchSize = secondaryControlTouchSize,
                controlGap = controlGap,
            )
        }

        @Composable
        fun Secondary() {
            MiniPlayerSecondaryControl(
                show = showSkipNextControl,
                iconRes = skipNextIconRes,
                imageVector = skipNextImageVector,
                onClick = { if (canDispatchControl()) onSecondaryAction() },
                controlsEnabled = controlsEnabled,
                textColor = textColor,
                visualSize = secondaryControlVisualSize,
                touchSize = secondaryControlTouchSize,
                visibility = secondaryControlVisibility,
            )
        }

        if (playPausePosition == MiniPlayerPlayPausePosition.ARTWORK_LEFT) {
            Controls()
            if (controlGap > 0.dp) Spacer(Modifier.width(controlGap))
        }

        MiniPlayerArtwork(
            mode = artworkMode,
            coverPath = snapshot.coverPath,
            isPlaying = snapshot.isPlaying,
            contentDescription = snapshot.title,
            onCoverBoundsChanged = onCoverBoundsChanged,
            onDoubleTapToggleMode = if (controlsEnabled) {
                { if (canDispatchControl()) onToggleArtworkMode() }
            } else ({}),
            onSingleTap = if (controlsEnabled) {
                { if (canDispatchControl()) onClick() }
            } else ({}),
            animateArtwork = animateArtwork,
            artworkSize = artworkSize,
            originalArtworkCornerRadius = originalArtworkCornerRadius,
            modifier = Modifier.graphicsLayer {
                // Keep the MiniPlayer's physical artwork node and BitmapImage owner mounted while
                // PLAYER owns the screen. PLAYER -> MAIN promotes a separate shared actor; hiding
                // only these pixels prevents a duplicate source image during the downward handoff
                // without disposing/re-requesting the MiniPlayer bitmap. Visibility returns only
                // after PlayerSceneController commits the MAIN endpoint.
                alpha = if (artworkVisible) 1f else 0f
            },
        )

        if (playPausePosition == MiniPlayerPlayPausePosition.ARTWORK_RIGHT) {
            if (controlGap > 0.dp) Spacer(Modifier.width(controlGap))
            Controls()
        }

        Spacer(modifier = Modifier.width(artworkTextGap))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (secondaryText.isBlank()) {
                // A single-line mini-player must own the full text envelope. Previously we still
                // composed the empty 18dp secondary lane, leaving the title/lyric in the upper
                // 20dp lane and making it look vertically off-center. SharedMarqueeText already
                // centers its native baseline inside the supplied canvas, so give the only line
                // the complete 44dp envelope. Keep the existing two-line geometry unchanged.
                SharedMarqueeText(
                    text = primaryText,
                    fontSizeSp = 14f,
                    fontWeight = FontWeight.Medium,
                    color = primaryBaseColor,
                    textAlign = if (centerLyrics) TextAlign.Center else TextAlign.Start,
                    visible = marqueeActive && !hasLyric,
                    highlightCharacterPositionProvider = karaokePresentation?.let { presentation ->
                        { presentation.characterPositionAt(karaokeClock?.currentPosition() ?: 0f) }
                    },
                    highlightColor = lyricColors.primary,
                    highlightFromEnd = karaokePresentation?.rtl == true,
                    rainbowGradient = hasLyric && lyricColors.rainbow,
                    drawClock = karaokeClock,
                    edgeFeather = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
            } else {
                androidx.compose.foundation.layout.Column {
                    SharedMarqueeText(
                        text = primaryText,
                        fontSizeSp = 14f,
                        fontWeight = FontWeight.Medium,
                        color = primaryBaseColor,
                        textAlign = if (centerLyrics) TextAlign.Center else TextAlign.Start,
                        visible = marqueeActive && !hasLyric,
                        highlightCharacterPositionProvider = karaokePresentation?.let { presentation ->
                            { presentation.characterPositionAt(karaokeClock?.currentPosition() ?: 0f) }
                        },
                        highlightColor = lyricColors.primary,
                        highlightFromEnd = karaokePresentation?.rtl == true,
                        rainbowGradient = hasLyric && lyricColors.rainbow,
                        drawClock = karaokeClock,
                        edgeFeather = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                    )
                    SharedMarqueeText(
                        text = secondaryText,
                        fontSizeSp = 11f,
                        fontWeight = FontWeight.Medium,
                        color = effectiveSecondaryColor,
                        textAlign = if (centerLyrics) TextAlign.Center else TextAlign.Start,
                        visible = marqueeActive && !hasLyric,
                        rainbowGradient = hasLyric && lyricColors.rainbow,
                        edgeFeather = 7.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                    )
                }
            }
        }

        if (playPausePosition == MiniPlayerPlayPausePosition.TRAILING) {
            Controls()
        }
        if (showSkipNextControl && secondaryControlVisibility > 0f) {
            if (controlGap > 0.dp) Spacer(Modifier.width(controlGap * secondaryControlVisibility.coerceIn(0f, 1f)))
            Secondary()
        }
    }
}

private fun resolveMiniPlayerKaraokePresentation(
    lines: List<IRichLyricLine>,
    displayedText: String,
): MiniPlayerKaraokePresentation? {
    val visibleText = displayedText.trim()
    if (visibleText.isBlank() || lines.isEmpty()) return null
    val normalizedVisibleText = normalizeMiniPlayerLyricText(visibleText)
    val matchingLines = lines.mapIndexedNotNull { lineIndex, line ->
        val lineText = line.text.orEmpty().trim()
        val words = line.words.orEmpty()
        if (
            lineText.isBlank() ||
            words.isEmpty() ||
            normalizeMiniPlayerLyricText(lineText) != normalizedVisibleText
        ) {
            return@mapIndexedNotNull null
        }

        var cursor = 0
        val wordSpans = ArrayList<MiniPlayerKaraokeWordSpan>(words.size)
        words.forEachIndexed { wordIndex, word ->
            val token = word.text.orEmpty().trim()
            if (token.isEmpty()) return@forEachIndexed
            val startCharacter = visibleText.indexOf(token, startIndex = cursor)
            if (startCharacter < 0) return@mapIndexedNotNull null
            val endCharacterExclusive = (startCharacter + token.length).coerceAtMost(visibleText.length)
            val beginMs = word.begin.coerceAtLeast(0L)
            val nextBeginMs = words.getOrNull(wordIndex + 1)?.begin?.takeIf { it > beginMs }
            val fallbackLineEnd = effectiveLineEnd(lines, lineIndex)
            val endMs = word.end.takeIf { it > beginMs }
                ?: nextBeginMs
                ?: fallbackLineEnd
            wordSpans += MiniPlayerKaraokeWordSpan(
                beginMs = beginMs,
                endMs = endMs.coerceAtLeast(beginMs + 1L),
                startCharacter = startCharacter,
                endCharacterExclusive = endCharacterExclusive,
            )
            cursor = endCharacterExclusive
        }
        if (wordSpans.isEmpty()) return@mapIndexedNotNull null

        val lineBeginMs = effectiveLineStart(line)
        val lineEndMs = effectiveLineEnd(lines, lineIndex).coerceAtLeast(lineBeginMs + 1L)
        MiniPlayerKaraokeLineSpan(
            beginMs = lineBeginMs,
            endMs = lineEndMs,
            words = wordSpans,
        )
    }
    if (matchingLines.isEmpty()) return null

    return MiniPlayerKaraokePresentation(
        textLength = visibleText.length,
        lines = matchingLines,
        rtl = isLikelyRtlMiniPlayerText(visibleText),
    )
}

private fun normalizeMiniPlayerLyricText(text: String): String =
    text.trim().replace(Regex("\\s+"), " ")

private fun isLikelyRtlMiniPlayerText(text: String): Boolean {
    for (char in text) {
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true
        }
    }
    return false
}

@Composable
private fun MiniPlayerTransportCluster(
    isPlaying: Boolean,
    textColor: Color,
    controlsEnabled: Boolean,
    showPrevious: Boolean,
    previousIconRes: Int,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    playPauseVisualSize: androidx.compose.ui.unit.Dp,
    playPauseTouchSize: androidx.compose.ui.unit.Dp,
    secondaryVisualSize: androidx.compose.ui.unit.Dp,
    secondaryTouchSize: androidx.compose.ui.unit.Dp,
    controlGap: androidx.compose.ui.unit.Dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        fun Modifier.controlClick(enabled: Boolean, action: () -> Unit): Modifier = then(
            if (enabled) Modifier.clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
                onClick = action,
            ) else Modifier
        )

        if (showPrevious) {
            Box(
                modifier = Modifier
                    .size(secondaryTouchSize)
                    .controlClick(controlsEnabled, onPrevious),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(previousIconRes),
                    contentDescription = stringResource(R.string.mini_player_previous),
                    tint = textColor,
                    modifier = Modifier.size(secondaryVisualSize),
                )
            }
            if (controlGap > 0.dp) Spacer(Modifier.width(controlGap))
        }

        Box(
            modifier = Modifier
                .size(playPauseTouchSize)
                .controlClick(controlsEnabled, onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.common_pause else R.string.common_play),
                tint = textColor,
                modifier = Modifier.size(playPauseVisualSize),
            )
        }


    }
}

@Composable
private fun MiniPlayerSecondaryControl(
    show: Boolean,
    iconRes: Int,
    imageVector: ImageVector? = null,
    onClick: () -> Unit,
    controlsEnabled: Boolean,
    textColor: Color,
    visualSize: androidx.compose.ui.unit.Dp,
    touchSize: androidx.compose.ui.unit.Dp,
    visibility: Float,
) {
    val visible = visibility.coerceIn(0f, 1f)
    if (!show || visible <= 0f) return
    Box(
        modifier = Modifier
            .width(touchSize * visible)
            .height(touchSize)
            .graphicsLayer {
                alpha = visible
                scaleX = 0.82f + 0.18f * visible
                scaleY = scaleX
            }
            .then(
                if (controlsEnabled && visible > 0.6f) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(visualSize),
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(visualSize),
            )
        }
    }
}

private fun MiniPlayerContentSnapshot.withAuthoritativePresentation(
    authoritative: MiniPlayerContentSnapshot,
): MiniPlayerContentSnapshot {
    if (identity != authoritative.identity) return this
    return authoritative.copy(
        // Queue/current-item callbacks can publish the new identity before title/artist/artwork
        // metadata has finished rebinding. Keep the already-rendered or queue-preview presentation
        // until the authoritative non-empty value arrives so track switches never flash blank text.
        title = authoritative.title.ifBlank { title },
        artist = authoritative.artist.ifBlank { artist },
        coverPath = authoritative.coverPath ?: coverPath,
    )
}

@Composable
private fun MiniPlayerInlineProgress(
    reportedProgress: Float,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    isPlaying: Boolean,
    contentIdentity: String,
    color: Color,
    bubbles: Boolean,
    currentSong: AudioFile?,
    direction: MiniPlayerProgressDirection,
    bubbleCount: Int,
    bubbleMotion: MiniPlayerBubbleMotion,
    bubbleSpeed: Float,
    highEnergyHighlight: Boolean,
    bubbleOrigin: MiniPlayerBubbleOrigin,
    modifier: Modifier = Modifier,
) {
    // Playback publishes position more slowly than display refresh. Keep service cadence out of the
    // visual layer: extrapolate from the last authoritative position and softly converge at vsync.
    var visualProgress by remember(contentIdentity) {
        mutableFloatStateOf(
            if (playbackDurationMs > 0L) {
                playbackPositionMs.toFloat().div(playbackDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                reportedProgress.coerceIn(0f, 1f)
            }
        )
    }
    var anchorPositionMs by remember(contentIdentity) { mutableLongStateOf(playbackPositionMs) }
    var anchorDurationMs by remember(contentIdentity) { mutableLongStateOf(playbackDurationMs) }
    var anchorRealtimeNanos by remember(contentIdentity) {
        mutableLongStateOf(SystemClock.elapsedRealtimeNanos())
    }
    var bubbleMotionSeconds by remember(contentIdentity) { mutableFloatStateOf(0f) }

    val context = LocalContext.current.applicationContext
    var energyProfile by remember(contentIdentity) { mutableStateOf<MiniPlayerEnergyProfile?>(null) }
    LaunchedEffect(
        contentIdentity,
        currentSong?.path,
        currentSong?.fileSize,
        currentSong?.dateModified,
        playbackDurationMs,
        highEnergyHighlight,
    ) {
        if (!highEnergyHighlight || currentSong == null || playbackDurationMs <= 0L) {
            energyProfile = null
            return@LaunchedEffect
        }
        val duration = playbackDurationMs.coerceAtLeast(1L)
        // ~10 Hz is enough for broad energy + beat autocorrelation while staying far cheaper than
        // audio-rate analysis. The waveform cache performs the scan off the UI thread and reuses it.
        val samples = (duration / 100L).toInt().coerceIn(320, 3_600)
        energyProfile = withContext(Dispatchers.IO) {
            val result = RawWaveformCache.loadOrScanResult(context, currentSong, samples)
            if (result.isReal) analyzeMiniPlayerEnergy(result.values, duration) else null
        }
    }

    LaunchedEffect(
        contentIdentity,
        playbackPositionMs,
        playbackDurationMs,
        isPlaying,
        reportedProgress,
    ) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (playbackDurationMs <= 0L) {
            anchorPositionMs = 0L
            anchorDurationMs = 0L
            anchorRealtimeNanos = now
            visualProgress = reportedProgress.coerceIn(0f, 1f)
            return@LaunchedEffect
        }

        val duration = playbackDurationMs.coerceAtLeast(1L)
        val authoritativeProgress = playbackPositionMs.toFloat()
            .div(duration.toFloat())
            .coerceIn(0f, 1f)
        val visualPositionMs = (visualProgress * duration).toLong()
        val seekSnapThresholdMs = maxOf(900L, minOf(2_200L, duration / 100L))
        if (!isPlaying || abs(playbackPositionMs - visualPositionMs) >= seekSnapThresholdMs) {
            visualProgress = authoritativeProgress
        }
        anchorPositionMs = playbackPositionMs.coerceIn(0L, duration)
        anchorDurationMs = duration
        anchorRealtimeNanos = now
    }

    LaunchedEffect(contentIdentity, isPlaying, playbackDurationMs, bubbles, bubbleSpeed) {
        if (!isPlaying || playbackDurationMs <= 0L) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val duration = anchorDurationMs.coerceAtLeast(1L)
                val now = SystemClock.elapsedRealtimeNanos()
                val elapsedMs = ((now - anchorRealtimeNanos).coerceAtLeast(0L) / 1_000_000L)
                val predictedPositionMs = (anchorPositionMs + elapsedMs).coerceIn(0L, duration)
                val target = predictedPositionMs.toFloat() / duration.toFloat()
                if (lastFrameNanos == 0L) lastFrameNanos = frameNanos
                val dtSeconds = ((frameNanos - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0)
                    .coerceAtMost(0.05)
                lastFrameNanos = frameNanos
                if (bubbles && dtSeconds > 0.0) {
                    val nextPhase = bubbleMotionSeconds + dtSeconds.toFloat() * bubbleSpeed.coerceIn(0.25f, 2.5f)
                    bubbleMotionSeconds = if (nextPhase >= 4096f) nextPhase % 4096f else nextPhase
                }
                val error = target - visualProgress
                visualProgress = if (abs(error) > 0.045f) {
                    target
                } else {
                    val follow = (1.0 - exp(-dtSeconds * 26.0)).toFloat()
                    (visualProgress + error * follow).coerceIn(0f, 1f)
                }
            }
        }
    }

    val bubbleTone = if (color.luminance() < 0.48f) Color.White else Color.Black
    Canvas(modifier = modifier) {
        val smoothProgress = visualProgress.coerceIn(0f, 1f)
        val fillWidth = smoothProgress * size.width
        if (fillWidth <= 0.5f) return@Canvas
        val leftToRight = direction == MiniPlayerProgressDirection.LEFT_TO_RIGHT
        val fillLeft = if (leftToRight) 0f else size.width - fillWidth
        val fillRight = if (leftToRight) fillWidth else size.width
        val progressEdgeX = if (leftToRight) fillRight else fillLeft
        val gradientStart = if (leftToRight) fillLeft else fillRight
        val gradientEnd = if (leftToRight) fillRight else fillLeft

        val profile = energyProfile
        val energy = if (highEnergyHighlight && profile != null) {
            profile.energyAt(smoothProgress)
        } else {
            0f
        }
        val energyEnvelope = MiniPlayerEnergyGlowEasing.transform(
            ((energy - 0.42f) / 0.58f).coerceIn(0f, 1f)
        )
        val estimatedPositionMs = (smoothProgress * playbackDurationMs.coerceAtLeast(0L)).toLong()
        val beatPulse = profile?.beatPulse(estimatedPositionMs) ?: 0f
        val glowStrength = (energyEnvelope * (0.86f + beatPulse * 0.14f)).coerceIn(0f, 1f)

        clipRect(left = fillLeft, top = 0f, right = fillRight, bottom = size.height) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.15f),
                        color.copy(alpha = 0.23f),
                        color.copy(alpha = 0.31f),
                    ),
                    startX = gradientStart,
                    endX = gradientEnd,
                )
            )

            if (glowStrength > 0.001f) {
                // Borrow the glass bottom navigation long-note emphasis light character: white glow, ~5dp
                // radius and ~0.5 peak alpha, but apply it to the progress liquid rather than text.
                drawRect(
                    color = Color.White.copy(alpha = 0.055f * glowStrength),
                    topLeft = androidx.compose.ui.geometry.Offset(fillLeft, 0f),
                    size = androidx.compose.ui.geometry.Size(fillWidth, size.height),
                )
            }

            if (bubbles && bubbleCount > 0) {
                // Only instantiate the population unlocked by the *filled* fraction. Unfilled space
                // owns zero bubble particles; there is no full-width pre-layout hidden by clipping.
                val maxPopulation = bubbleCount.coerceIn(0, 32)
                val activeCount = (maxPopulation * smoothProgress).roundToInt().coerceIn(0, maxPopulation)
                val minRadius = 1.25.dp.toPx()
                val maxRadius = 3.25.dp.toPx()
                val margin = maxRadius + 1.dp.toPx()
                val available = (fillWidth - margin * 2f).coerceAtLeast(1f)
                fun localToScreenX(localX: Float): Float = if (leftToRight) {
                    fillLeft + localX
                } else {
                    fillRight - localX
                }
                for (i in 0 until activeCount) {
                    val phaseOffset = (i * 0.6180339f) % 1f
                    val cycleRate = 0.26f + (i % 4) * 0.035f
                    val life = (phaseOffset + bubbleMotionSeconds * cycleRate) % 1f
                    val pulse = sin(PI * life.toDouble()).toFloat().coerceAtLeast(0f)
                    val radius = minRadius + (maxRadius - minRadius) * (0.28f + 0.72f * pulse)
                    val seed = ((i * 0.7548777f) % 1f).coerceIn(0f, 1f)

                    var localX: Float
                    var y: Float
                    when (bubbleOrigin) {
                        MiniPlayerBubbleOrigin.START_EDGE -> {
                            localX = margin + available * (0.08f + 0.84f * life)
                            y = size.height * (0.28f + 0.44f * seed)
                        }
                        MiniPlayerBubbleOrigin.TOP -> {
                            localX = margin + available * seed
                            y = margin + (size.height - margin * 2f).coerceAtLeast(1f) * life
                        }
                        MiniPlayerBubbleOrigin.BOTTOM -> {
                            localX = margin + available * seed
                            y = size.height - margin - (size.height - margin * 2f).coerceAtLeast(1f) * life
                        }
                    }

                    val motionAmplitude = min(5.dp.toPx(), size.height * 0.18f)
                    when (bubbleMotion) {
                        MiniPlayerBubbleMotion.FLOAT -> {
                            y += sin(life.toDouble() * PI * 2.0 + i * 0.77).toFloat() * motionAmplitude * 0.38f
                            localX += sin(life.toDouble() * PI + i * 1.17).toFloat() * motionAmplitude * 0.20f
                        }
                        MiniPlayerBubbleMotion.WAVE -> {
                            y += sin(life.toDouble() * PI * 4.0 + i * 0.83).toFloat() * motionAmplitude
                            localX += life * motionAmplitude * 0.18f
                        }
                        MiniPlayerBubbleMotion.ORBIT -> {
                            val angle = life.toDouble() * PI * 2.0 + i * 0.91
                            localX += cos(angle).toFloat() * motionAmplitude * 0.55f
                            y += sin(angle).toFloat() * motionAmplitude * 0.55f
                        }
                    }
                    val maxLocalX = (fillWidth - radius).coerceAtLeast(radius)
                    val maxY = (size.height - radius).coerceAtLeast(radius)
                    localX = localX.coerceIn(radius, maxLocalX)
                    y = y.coerceIn(radius, maxY)
                    val alpha = (0.045f + 0.17f * pulse) * (0.78f + 0.22f * energyEnvelope)
                    drawCircle(
                        color = bubbleTone.copy(alpha = alpha.coerceIn(0f, 0.24f)),
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(localToScreenX(localX), y),
                    )
                }
            }
        }

        if (glowStrength > 0.001f) {
            // Approximate the 5dp white shadow used for long-note emphasis without installing a
            // blur RenderEffect on the whole bottom bar. Four narrow bands preserve the same soft
            // falloff while keeping this draw path cheap on 120 Hz devices.
            val radius = MINI_PLAYER_ENERGY_GLOW_RADIUS.toPx()
            val bands = floatArrayOf(1f, 0.58f, 0.30f, 0.14f)
            bands.forEachIndexed { index, weight ->
                val halfWidth = radius * (index + 1) / bands.size.toFloat()
                drawRect(
                    color = Color.White.copy(
                        alpha = MINI_PLAYER_ENERGY_GLOW_MAX_ALPHA * glowStrength * weight
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(progressEdgeX - halfWidth, 0f),
                    size = androidx.compose.ui.geometry.Size(halfWidth * 2f, size.height),
                )
            }
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
