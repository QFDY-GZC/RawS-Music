package com.rawsmusic.core.ui.widget

import android.graphics.RectF
import android.graphics.Path as AndroidPath
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.clipRect
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricTimingEditorTarget
import com.rawsmusic.core.common.utils.PlayerSwitchTrace
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.systemui.rawReducedNavigationBottomPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfEvent
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfTrace
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackQueueBindingIndex
import com.rawsmusic.core.ui.widget.player.AlbumDetailPanel
import com.rawsmusic.core.ui.widget.player.AudioVisualizerParticleColorMode
import com.rawsmusic.core.ui.widget.player.AudioVisualizerStyle
import com.rawsmusic.core.ui.widget.player.FullCoverPage
import com.rawsmusic.core.ui.widget.player.ImmersiveAlbumInfoPage
import com.rawsmusic.core.ui.widget.player.ImmersivePlayerHorizontalStack
import com.rawsmusic.core.ui.widget.player.ImmersiveLyricPage
import com.rawsmusic.core.ui.widget.player.ImmersivePlayerMainPage
import com.rawsmusic.core.ui.widget.player.LocalAudioVisualizerParticleColorMode
import com.rawsmusic.core.ui.widget.player.LocalAudioVisualizerStyle
import com.rawsmusic.core.ui.widget.player.LocalAudioVisualizerStyleChange
import com.rawsmusic.core.ui.widget.player.LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.LyricPage
import com.rawsmusic.core.ui.widget.player.OriginalArtworkViewerDialog
import com.rawsmusic.core.ui.widget.player.PlayerMainPage
import com.rawsmusic.core.ui.widget.player.SharedRealtimeVisualizerOverlay
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_ELEVATION_DP
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_PAUSED_HOLDER_SCALE
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.module.data.prefs.LyricLayoutPreferences
import com.rawsmusic.module.data.prefs.LyricTopLayoutStyle
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.PlayerProgressPreferences
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.launch

// The ordinary player should keep its own artwork visible while returning to the main page.
// The list-to-player shared handoff made the default artwork disappear during the gesture.
private const val ENABLE_PLAYER_LIST_COVER_TRANSITION = true

// Keep the two measured geometry values local to the transition implementation.
// resource magnitudes centralized so they can be replaced directly if resources.arsc/dimens.xml
// becomes available. The behavior/formulas around them are source-exact.

// The player transition uses the same 500ms path interpolator for the regular
// transition and the shared-element handoff. Keep the Compose implementation on that curve so
// the measured artwork bounds and the page content arrive together.

/**
 * Compose 播放器辅助容器。
 *
 * 仅管理歌词、队列、全屏封面等 Compose 页面。
 * 播放器主体由 MainActivity 的 Compose 树渲染，场景切换由 PlayerSceneController 驱动。
 */
@Composable
fun ComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    coverPath: String? = null,
    previousGestureArtworkKey: String? = null,
    nextGestureArtworkKey: String? = null,
    isPlaying: Boolean = false,
    currentPositionMs: Long = 0L,
    currentPositionState: State<Long>? = null,
    totalDurationMs: Long = 0L,
    audioVisualizerEnabled: Boolean = false,
    audioVisualizerStyle: AudioVisualizerStyle = AudioVisualizerStyle.Spectrum,
    audioVisualizerParticleColorMode: AudioVisualizerParticleColorMode =
        AudioVisualizerParticleColorMode.White,
    audioSpectrum: FloatArray = FloatArray(0),
    audioSpectrumState: State<FloatArray>? = null,
    onAudioVisualizerDismiss: () -> Unit = {},
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    onAudioVisualizerStyleChange: (AudioVisualizerStyle) -> Unit = {},
    onAudioVisualizerRuntimeActiveChange: (Boolean) -> Unit = {},
    previousIconRes: Int = 0,
    playIconRes: Int = 0,
    pauseIconRes: Int = 0,
    nextIconRes: Int = 0,
    playModeIconRes: Int = 0,
    moreIconRes: Int = 0,
    audioQualityIconRes: Int = 0,
    audioInfoText: String = "",
    audioInfoPlaybackChainText: String = "",
    smartTransitionVisible: Boolean = false,
    onSeekStart: () -> Unit = {},
    onSeekStop: (Float) -> Unit = {},
    onPrevious: () -> AudioFile? = { null },
    onPlayPause: () -> Unit = {},
    onNext: () -> AudioFile? = { null },
    onArtworkGesturePrevious: () -> AudioFile? = onPrevious,
    onArtworkGestureNext: () -> AudioFile? = onNext,
    onPlayMode: () -> Unit = {},
    onPlayModeLongPress: () -> Unit = {},
    onMore: () -> Unit = {},
    onOpenMetadata: () -> Unit = {},
    onOpenAudioEffects: () -> Unit = {},
    onOpenSpectrumAnalysis: () -> Unit = {},
    onPlaybackSpeedChange: (Float) -> Unit = {},
    realtimeSeparationEnabled: Boolean = false,
    realtimeSeparationPreparing: Boolean = false,
    realtimeSeparationStem: Int = 0,
    realtimeSeparationStrength: Float = 1f,
    realtimeSeparationStatus: String = "",
    aiPerformanceState: com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState =
        com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState(),
    onAiPerformanceModeChange: (com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode) -> Unit = {},
    onAiPerformanceInstrumentChange: (String) -> Unit = {},
    onAiPerformanceCancel: () -> Unit = {},
    onAiPerformanceImportMelodyModel: (String) -> Unit = {},
    onAiPerformanceImportInstrumentPack: (String) -> Unit = {},
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit = {},
    onRealtimeSeparationStemChange: (Int) -> Unit = {},
    onRealtimeSeparationStrengthChange: (Float) -> Unit = {},
    onPlayerStyleChange: (Boolean) -> Unit = {},
    onOpenLandscapePlayer: () -> Unit = {},
    onLyricModifyAlbumArt: () -> Unit = {},
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: (Int) -> Unit = {},
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenLyric: () -> Unit = {},
    onPlayerCoverSwipeUpStart: () -> Unit = {},
    onPlayerCoverSwipeUpProgress: (Float) -> Unit = {},
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit = { _, _ -> },
    onPlayerCoverSwipeDownStart: () -> Unit = {},
    onPlayerCoverSwipeDownProgress: (Float) -> Unit = {},
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    onMainPlayerSheetGeometryChanged: (Float, Float) -> Unit = { _, _ -> },
    onLyricCoverSwipeDownStart: () -> Unit = {},
    onLyricCoverSwipeDownProgress: (Float) -> Unit = {},
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    queueSongs: List<AudioFile> = emptyList(),
    queueCurrentIndex: Int = -1,
    onQueueSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onClearPriorityQueue: (() -> Unit)? = null,
    albumSongs: List<AudioFile> = emptyList(),
    albumCoverPath: String? = null,
    onAlbumSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    lyricSong: Song? = null,
    lyricPositionMs: Long = 0L,
    lyricPositionState: State<Long>? = null,
    displayTranslation: Boolean = false,
    displayRoma: Boolean = false,
    onLyricSeek: (Long) -> Unit = {},
    onLyricTranslationToggle: () -> Unit = {},
    onLyricRomaToggle: () -> Unit = {},
    onSearchLyrico: () -> Unit = {},
    onOpenInLyrico: () -> Unit = {},
    onAiTimingPreview: () -> Unit = {},
    onOpenExternalTimingEditor: (LyricTimingEditorTarget) -> Unit = {},
    isImmersiveEnabled: Boolean = false,
    persistentBottomSheet: Boolean = false,
    prewarmStandardPlayerInMain: Boolean = false,
    overlaySuspended: Boolean = false,
    onClosePlayer: () -> Unit = { sceneState.backToMain() },
    onBackToPlayer: () -> Unit = { sceneState.backToPlayer() },
    onModalVisibleChange: (Boolean) -> Unit = {},
    onModalDismissActionChange: ((() -> Unit)?) -> Unit = {},
    controllerScene: PlayerSceneController.Scene? = null,
    controllerFromScene: PlayerSceneController.Scene? = null,
    controllerToScene: PlayerSceneController.Scene? = null,
    controllerProgress: Float = 0f,
    controllerProgressState: State<Float>? = null,
    controllerIsTransitioning: Boolean = false,
    controllerIsInteractiveGesture: Boolean = false,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator? = null,
    sourceCoverTarget: CoverTransitionTarget? = null,
    /**
     * Actual MiniPlayer bounds in root coordinates. MAIN <-> PLAYER backdrop geometry starts from
     * this rectangle only; stacked navigation is deliberately excluded so the first drag frame
     * never appears as a full-width bottom-sheet slab.
     */
    miniPlayerBoundsInRoot: androidx.compose.ui.geometry.Rect? = null,
    /**
     * Live top edge of a floating MiniPlayer in root coordinates. Derive the collapsed sheet
     * geometry from the actual MiniPlayer/stacked chrome views instead of a synthetic screen
     * strip. Raw's floating style has no persistent BottomSheet child, so use its measured bar top
     * as the collapsed backdrop origin when available.
     */
    floatingMiniPlayerTopPx: Float? = null,
    modifier: Modifier = Modifier
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PLAYER_CONTAINER_COMPOSE_COMMIT)
        }
    }
    val miniPlayerStyle by PersonalizationPreferences.miniPlayerStyle.collectAsState()
    val latestOnModalVisibleChange by rememberUpdatedState(onModalVisibleChange)
    val latestOnModalDismissActionChange by rememberUpdatedState(onModalDismissActionChange)
    DisposableEffect(Unit) {
        onDispose {
            latestOnModalVisibleChange(false)
            latestOnModalDismissActionChange(null)
        }
    }

    val resolvedCoverPath = currentSong.resolvePlaybackArtworkKey(coverPath)
    val resolvedAlbumCoverPath = albumCoverPath?.takeIf { it.isNotBlank() } ?: resolvedCoverPath
    val committedQueueIndex = resolvePlaybackQueueBindingIndex(
        currentSong = currentSong,
        queueSongs = queueSongs,
        reportedIndex = queueCurrentIndex,
    )
    // Resolve queue/artwork identity once per queue publication. Button transport callbacks must
    // not scan the complete queue before the first artwork animation frame can be submitted.
    val queueArtworkKeys = remember(queueSongs) {
        queueSongs.map { it.resolvePlaybackArtworkKey(null) }
    }
    val firstQueueIndexByArtworkKey = remember(queueArtworkKeys) {
        HashMap<String, Int>(queueArtworkKeys.size * 2).apply {
            queueArtworkKeys.forEachIndexed { index, key ->
                if (!key.isNullOrBlank() && !containsKey(key)) put(key, index)
            }
        }
    }
    val queueIndexByPlaybackIdentity = remember(queueSongs) {
        HashMap<String, Int>(queueSongs.size * 2).apply {
            queueSongs.forEachIndexed { index, song ->
                put("${song.path}\u0000${song.cueOffsetMs}\u0000${song.cueTrackIndex}", index)
            }
        }
    }

    // Keep button/gesture projection outside Snapshot state.  The retained ArtworkPager owns the
    // first visual frame; publishing a Compose queue cursor from the click callback invalidates the
    // whole player tree before that holder property update can reach the screen.  Transition/current
    // song state will naturally trigger the next composition, which then observes this private
    // projected cursor without creating an independent frame clock.
    val requestedQueueIndexRef = remember(queueSongs) { intArrayOf(committedQueueIndex) }
    val requestedQueueIndex = requestedQueueIndexRef[0]
    LaunchedEffect(committedQueueIndex, queueSongs.size, resolvedCoverPath) {
        val playerBoundIsPreparedTarget = !resolvedCoverPath.isNullOrBlank() &&
            artworkTransitionState.foregroundTargetKey() == resolvedCoverPath
        if (!artworkTransitionState.hasPendingNavigation() ||
            committedQueueIndex == requestedQueueIndexRef[0] ||
            playerBoundIsPreparedTarget
        ) {
            requestedQueueIndexRef[0] = committedQueueIndex
        }
    }
    val requestedQueueKey = queueArtworkKeys.getOrNull(requestedQueueIndex)
    val gestureLogicalCenterKey = artworkTransitionState.gestureLogicalCenterKey()
    val gestureLogicalCenterIndex = gestureLogicalCenterKey
        ?.takeIf { it.isNotBlank() }
        ?.let(firstQueueIndexByArtworkKey::get)
        ?.takeIf { it >= 0 }
        ?: -1
    val requestedIndexIsGestureLogicalCenter =
        !requestedQueueKey.isNullOrBlank() &&
            requestedQueueKey == gestureLogicalCenterKey
    // Direct manipulation owns its own logical centre. Resolve that identity against the frozen
    // queue instead of publishing requestedQueueIndex from the pointer/settle callback. This keeps
    // an immediate second swipe centred on B after A -> B without a Compose queue-cursor write in
    // the release frame. Programmatic button/queue commands may still use requestedQueueIndex.
    val gestureBindingIndex = when {
        gestureLogicalCenterIndex >= 0 -> gestureLogicalCenterIndex
        requestedIndexIsGestureLogicalCenter -> requestedQueueIndex
        else -> committedQueueIndex
    }
    val previousArtworkIndex = when {
        queueSongs.size <= 1 -> -1
        gestureBindingIndex > 0 -> gestureBindingIndex - 1
        gestureBindingIndex == 0 -> queueSongs.lastIndex
        else -> -1
    }
    val nextArtworkIndex = when {
        queueSongs.size <= 1 -> -1
        gestureBindingIndex in 0 until queueSongs.lastIndex -> gestureBindingIndex + 1
        gestureBindingIndex == queueSongs.lastIndex -> 0
        else -> -1
    }
    val previousArtworkKey = queueArtworkKeys.getOrNull(previousArtworkIndex)
    val nextArtworkKey = queueArtworkKeys.getOrNull(nextArtworkIndex)
    val speculativeBinding = requestedIndexIsGestureLogicalCenter && gestureBindingIndex != committedQueueIndex
    val effectivePreviousGestureKey = if (speculativeBinding) {
        previousArtworkKey
    } else {
        previousGestureArtworkKey ?: previousArtworkKey
    }
    val effectiveNextGestureKey = if (speculativeBinding) {
        nextArtworkKey
    } else {
        nextGestureArtworkKey ?: nextArtworkKey
    }

    // Gesture recognition reads these values synchronously from pointer input. SideEffect publishes
    // the exact logical neighbours in the same composition commit; prefetch keeps the third retained
    // artwork holder hot before a rapid follow-up pointer crosses the next boundary.
    SideEffect {
        artworkTransitionState.setGestureTargetKeys(
            previousKey = effectivePreviousGestureKey,
            nextKey = effectiveNextGestureKey
        )
    }
    LaunchedEffect(effectivePreviousGestureKey, effectiveNextGestureKey) {
        artworkTransitionState.prefetchGestureTargets(
            previousKey = effectivePreviousGestureKey,
            nextKey = effectiveNextGestureKey
        )
    }

    fun indexForSong(song: AudioFile): Int {
        return queueIndexByPlaybackIdentity[
            "${song.path}\u0000${song.cueOffsetMs}\u0000${song.cueTrackIndex}"
        ] ?: -1
    }

    fun adjacentRequestedIndex(direction: PlayerArtworkDirection): Int {
        if (queueSongs.isEmpty()) return -1
        val base = requestedQueueIndexRef[0].takeIf { it in queueSongs.indices }
            ?: committedQueueIndex.takeIf { it in queueSongs.indices }
            ?: 0
        return when (direction) {
            PlayerArtworkDirection.Previous -> if (base > 0) base - 1 else queueSongs.lastIndex
            PlayerArtworkDirection.Next -> if (base < queueSongs.lastIndex) base + 1 else 0
        }
    }

    val inlineQueueVisible = sceneState.isQueueOverlayVisible
    var audioVisualizerForeground by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(audioVisualizerEnabled, isImmersiveEnabled) {
        if (!audioVisualizerEnabled) {
            audioVisualizerForeground = false
        } else if (isImmersiveEnabled) {
            // Immersive artwork already fills the viewport, so it starts directly above the cover.
            audioVisualizerForeground = true
        } else if (!audioVisualizerForeground) {
            kotlinx.coroutines.delay(2_000L)
            if (audioVisualizerEnabled) audioVisualizerForeground = true
        }
    }
    val visualizerPlayerPageVisible = if (controllerIsTransitioning) {
        controllerFromScene == PlayerSceneController.Scene.PLAYER ||
            controllerToScene == PlayerSceneController.Scene.PLAYER
    } else {
        (controllerScene == PlayerSceneController.Scene.PLAYER) ||
            (controllerScene == null && sceneState.currentScene == PlayerScene.PLAYER)
    }
    val backgroundVisualizerLyricPageVisible =
        audioVisualizerStyle != AudioVisualizerStyle.Spectrum &&
            if (controllerIsTransitioning) {
                controllerFromScene == PlayerSceneController.Scene.LYRIC ||
                    controllerToScene == PlayerSceneController.Scene.LYRIC
            } else {
                (controllerScene == PlayerSceneController.Scene.LYRIC) ||
                    (controllerScene == null && sceneState.currentScene == PlayerScene.LYRIC)
            }
    val progressStyleValue by PlayerProgressPreferences.progressStyle.collectAsState()
    val musicSpineSpectrumVisible =
        com.rawsmusic.core.ui.widget.player.ImmersiveProgressStyle.from(progressStyleValue) ==
            com.rawsmusic.core.ui.widget.player.ImmersiveProgressStyle.MusicSpine &&
            visualizerPlayerPageVisible
    val visualizerRuntimeActive =
        ((audioVisualizerEnabled &&
            (visualizerPlayerPageVisible || backgroundVisualizerLyricPageVisible)) ||
            musicSpineSpectrumVisible) &&
        !inlineQueueVisible &&
        !overlaySuspended
    val latestOnAudioVisualizerRuntimeActiveChange by
        rememberUpdatedState(onAudioVisualizerRuntimeActiveChange)
    LaunchedEffect(visualizerRuntimeActive) {
        latestOnAudioVisualizerRuntimeActiveChange(visualizerRuntimeActive)
    }
    DisposableEffect(Unit) {
        onDispose { latestOnAudioVisualizerRuntimeActiveChange(false) }
    }
    DisposableEffect(artworkTransitionState, visualizerPlayerPageVisible) {
        artworkTransitionState.setForegroundPresentationActive(visualizerPlayerPageVisible)
        onDispose {
            if (visualizerPlayerPageVisible) {
                artworkTransitionState.setForegroundPresentationActive(false)
            }
        }
    }
    var childInteractionVisible by remember { mutableStateOf(false) }
    var childModalDismissAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var immersiveMoreRequested by rememberSaveable { mutableStateOf(false) }
    var artworkViewerVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isImmersiveEnabled) {
        immersiveMoreRequested = false
        childInteractionVisible = false
        childModalDismissAction = null
    }
    val immersiveMoreVisible = immersiveMoreRequested && !overlaySuspended
    val dismissArtworkViewer = remember { { artworkViewerVisible = false } }
    val openArtworkViewer = {
        if (currentSong != null) artworkViewerVisible = true
    }

    fun dismissInlineQueue() {
        sceneState.closeQueueOverlay()
    }
    val dismissInlineQueueAction = remember(sceneState) {
        { sceneState.closeQueueOverlay() }
    }

    val toggleInlineQueue = {
        sceneState.toggleQueueOverlay()
    }
    val playQueueSong: (AudioFile, Int) -> Unit = { song, index ->
        val base = requestedQueueIndexRef[0].takeIf { it in queueSongs.indices } ?: committedQueueIndex
        val direction = if (base >= 0 && index < base) {
            PlayerArtworkDirection.Previous
        } else {
            PlayerArtworkDirection.Next
        }
        requestedQueueIndexRef[0] = index
        val targetKey = queueArtworkKeys.getOrNull(index) ?: song.resolvePlaybackArtworkKey(null)
        if (!PlayerSwitchTrace.isActive()) {
            PlayerSwitchTrace.begin(
                kind = "queue_tap",
                detail = "from=$committedQueueIndex to=$index key=${targetKey?.hashCode()?.toString(16) ?: "-"}",
            )
        }
        PlayerSwitchTrace.mark(
            "ui_queue_tap_arm",
            "from=$committedQueueIndex to=$index key=${targetKey?.hashCode()?.toString(16) ?: "-"}",
        )
        // reference player retained pager programmatic motion starts its ListWidget/retained pager holder programmatic motion from the
        // authoritative current-track callback, not from the click callback.  Only arm the exact
        // identity here.  bindSong() will consume this marker after renderer commitment and move
        // the already-attached three holders exactly once.
        artworkTransitionState.armManualNavigation(
            direction = direction,
            expectedKey = targetKey,
            expectedQueueIndex = index,
        )
        onQueueSongClick(song, index)
    }

    fun issueTrackCommand(
        direction: PlayerArtworkDirection,
        command: () -> AudioFile?,
        gestureCommand: () -> AudioFile?
    ) {
        if (!PlayerSwitchTrace.isActive()) {
            PlayerSwitchTrace.begin(
                kind = "ui_${direction.name.lowercase()}",
                detail = "queueIndex=$committedQueueIndex queueSize=${queueSongs.size}",
            )
        }
        PlayerSwitchTrace.mark(
            "ui_track_command_begin",
            "dir=$direction committed=$committedQueueIndex requested=${requestedQueueIndexRef[0]} gesture=${artworkTransitionState.isGestureActive}",
        )
        // An edge/empty-neighbour swipe has no target key by design. ArtworkPager already ran the
        // resisted current-holder motion and is settling back; this one-shot release only asks the
        // transport traversal for its authoritative boundary result so the app can show feedback.
        // Never arm a speculative target for this path.
        if (artworkTransitionState.consumeEdgeGestureRelease(direction)) {
            gestureCommand()
            return
        }
        // A transport button can remain reachable for one composition while the player is being
        // torn down. Do not create a speculative ArtworkPager/manual-navigation owner when there
        // is no media centre at all. PlayerQueueControlCoordinator also guards an empty queue, but
        // this UI-side gate is important because armManualNavigation() itself mutates the retained
        // three-holder presentation state before the transport callback is invoked.
        if (currentSong == null || queueSongs.isEmpty()) {
            artworkTransitionState.cancelManualNavigationExpectation()
            return
        }
        val gestureTargetKey = artworkTransitionState.pendingGestureTarget(direction)
        val requestedIndex = if (gestureTargetKey != null) {
            firstQueueIndexByArtworkKey[gestureTargetKey] ?: -1
        } else {
            adjacentRequestedIndex(direction)
        }
        val requestedKey = queueArtworkKeys.getOrNull(requestedIndex)
        // Button navigation must use the same traversal-aware neighbour that was already
        // prefetched for artwork gestures. In shuffle mode, requestedIndex is only the physical
        // queue-adjacent row, while PlayerController.previewNext/PreviousSong() may point at a
        // completely different queue index. Preferring requestedKey here made the first frame bind
        // the wrong holder, then cancel/re-arm the real shuffled target after transport returned.
        // reference player's retained three-holder pager is bound around the real traversal neighbours, so
        // use the already-warm gesture target first and keep the linear row only as a fallback.
        val previewKey = gestureTargetKey
            ?: artworkTransitionState.gestureTargetKey(direction)
            ?: requestedKey
        val previewIndex = when {
            requestedIndex in queueSongs.indices && previewKey == requestedKey -> requestedIndex
            previewKey.isNullOrBlank() -> requestedIndex
            else -> firstQueueIndexByArtworkKey[previewKey] ?: requestedIndex
        }

        artworkTransitionState.armManualNavigation(
            direction = direction,
            expectedKey = previewKey,
            expectedQueueIndex = previewIndex,
        )
        PlayerSwitchTrace.mark(
            "ui_manual_arm",
            "dir=$direction previewIndex=$previewIndex previewKey=${previewKey?.hashCode()?.toString(16) ?: "-"}",
        )
        val directGestureCommit = gestureTargetKey != null
        if (!directGestureCommit && previewIndex >= 0) requestedQueueIndexRef[0] = previewIndex

        val dispatchedSong = if (directGestureCommit) gestureCommand() else command()
        val dispatchedKey = dispatchedSong.resolvePlaybackArtworkKey(null)
        val dispatchedIndex = when {
            dispatchedSong == null -> -1
            requestedIndex in queueSongs.indices && dispatchedKey == requestedKey -> requestedIndex
            previewIndex in queueSongs.indices && dispatchedKey == previewKey -> previewIndex
            else -> indexForSong(dispatchedSong)
        }
        if (!directGestureCommit && dispatchedIndex >= 0) requestedQueueIndexRef[0] = dispatchedIndex

        if (dispatchedKey.isNullOrBlank()) {
            PlayerSwitchTrace.mark("ui_dispatch_no_key", "dir=$direction")
            artworkTransitionState.cancelManualNavigationExpectation()
            return
        }

        PlayerSwitchTrace.mark(
            "ui_transport_identity",
            "dir=$direction dispatchedIndex=$dispatchedIndex key=${dispatchedKey.hashCode().toString(16)} directGesture=$directGestureCommit",
        )

        if (dispatchedKey == artworkTransitionState.foregroundCurrentKey()) {
            // The common opposite-button case while A -> B is still physically settling is
            // transport B -> A while ArtworkPager's logical centre is still A. reference player keeps its
            // current fractional q and runs the release scroller back from that exact position;
            // it does not force B to the centre and start a second page. Preserve that continuity
            // here. The helper is a no-op for the ordinary "previous restarts current after 3s"
            // case where no programmatic artwork motion exists.
            if (!artworkTransitionState.reverseManualProgrammaticMotionToCurrent(dispatchedKey)) {
                artworkTransitionState.cancelManualNavigationExpectation()
            }
            return
        }

        if (previewKey != dispatchedKey) {
            artworkTransitionState.cancelManualNavigationExpectation()
            artworkTransitionState.armManualNavigation(
                direction = direction,
                expectedKey = dispatchedKey,
                expectedQueueIndex = dispatchedIndex,
            )
        }
        if (gestureTargetKey != null) {
            // Transport projection can already be one or more pages ahead of the immediate physical
            // neighbour. Feed the authoritative dispatched identity back into the same visual ArtworkPagerMotion
            // retarget chain instead of waiting for bindSong() to start a later programmatic page.
            artworkTransitionState.confirmManualNavigationBinding(
                dispatchedKey,
                dispatchedIndex,
                direction,
            )
        } else {
            // The three physical artwork holders are already attached and the exact dispatched
            // identity is known on this UI turn. Start their property motion immediately instead
            // of waiting several renderer/queue frames.  The manual ownership marker now survives
            // the visual centre crossing until bindSong() confirms the same identity, so this does
            // not reintroduce the old stale-target -> second-animation race.
            artworkTransitionState.startManualNavigation(
                direction = direction,
                expectedKey = dispatchedKey,
                expectedQueueIndex = dispatchedIndex,
            )
            PlayerSwitchTrace.mark(
                "ui_artwork_motion_started",
                "dir=$direction index=$dispatchedIndex key=${dispatchedKey.hashCode().toString(16)}",
            )
        }
    }

    val previousFromPlayer = {
        issueTrackCommand(
            PlayerArtworkDirection.Previous,
            command = onPrevious,
            gestureCommand = onArtworkGesturePrevious
        )
    }
    val nextFromPlayer = {
        issueTrackCommand(
            PlayerArtworkDirection.Next,
            command = onNext,
            gestureCommand = onArtworkGestureNext
        )
    }
    val modalVisibilityChanged: (Boolean) -> Unit = { childVisible ->
        childInteractionVisible = childVisible
    }
    val modalDismissActionChanged: ((() -> Unit)?) -> Unit = { dismissAction ->
        childModalDismissAction = dismissAction
    }
    val effectiveModalDismissAction = dismissArtworkViewer.takeIf { artworkViewerVisible }
        ?: childModalDismissAction
        ?: dismissInlineQueueAction.takeIf { inlineQueueVisible }
    val effectiveModalVisible = childInteractionVisible || effectiveModalDismissAction != null

    LaunchedEffect(effectiveModalVisible) {
        latestOnModalVisibleChange(effectiveModalVisible)
    }
    LaunchedEffect(effectiveModalDismissAction) {
        latestOnModalDismissActionChange(effectiveModalDismissAction)
    }

    LaunchedEffect(sceneState.currentScene) {
        when (sceneState.currentScene) {
            PlayerScene.QUEUE -> {
                sceneState.backToPlayer()
                sceneState.openQueueOverlay()
            }
            PlayerScene.PLAYER -> Unit
            else -> dismissInlineQueue()
        }
    }
    LaunchedEffect(controllerScene) {
        if (controllerScene != null &&
            controllerScene != PlayerSceneController.Scene.PLAYER &&
            controllerScene != PlayerSceneController.Scene.QUEUE
        ) {
            dismissInlineQueue()
        }
    }
    BackHandler(enabled = inlineQueueVisible) {
        dismissInlineQueue()
    }

    CompositionLocalProvider(
        LocalAudioVisualizerStyle provides audioVisualizerStyle,
        LocalAudioVisualizerStyleChange provides onAudioVisualizerStyleChange,
        LocalAudioVisualizerParticleColorMode provides audioVisualizerParticleColorMode,
    ) {
    if (isImmersiveEnabled) {
        ImmersiveComposePlayerContainer(
            sceneState = sceneState,
            currentSong = currentSong,
            coverPath = resolvedCoverPath,
            artworkTransitionState = artworkTransitionState,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            audioVisualizerEnabled = audioVisualizerEnabled,
            audioSpectrum = audioSpectrum,
            audioVisualizerForeground = audioVisualizerForeground,
            onAudioVisualizerDismiss = onAudioVisualizerDismiss,
            onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            playModeIconRes = playModeIconRes,
            lyricSong = lyricSong,
            lyricPositionMs = lyricPositionMs,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            albumSongs = albumSongs,
            albumCoverPath = resolvedAlbumCoverPath,
            onAlbumSongClick = onAlbumSongClick,
            queueSongs = queueSongs,
            queueCurrentIndex = committedQueueIndex,
            inlineQueueVisible = inlineQueueVisible,
            onToggleInlineQueue = toggleInlineQueue,
            onQueueSongClick = playQueueSong,
            onClearPriorityQueue = onClearPriorityQueue,
            onClosePlayer = onClosePlayer,
            morePanelVisible = immersiveMoreVisible,
            onMorePanelVisibleChangeState = { immersiveMoreRequested = it },
            onModalVisibleChange = modalVisibilityChanged,
            onModalDismissActionChange = modalDismissActionChanged,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = {
                onPrevious()
                Unit
            },
            onPlayPause = onPlayPause,
            onNext = {
                onNext()
                Unit
            },
            onPlayMode = onPlayMode,
            onLyricModifyAlbumArt = onLyricModifyAlbumArt,
            onSearchLyrico = onSearchLyrico,
            onOpenInLyrico = onOpenInLyrico,
            onAiTimingPreview = onAiTimingPreview,
            onOpenExternalTimingEditor = onOpenExternalTimingEditor,
            sleepTimerSelection = sleepTimerSelection,
            onSleepTimerSelectionChange = onSleepTimerSelectionChange,
            onOpenLyric = onOpenLyric,
            onArtworkLongPress = openArtworkViewer,
            audioInfoText = audioInfoText,
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
            smartTransitionVisible = smartTransitionVisible,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onOpenMetadata = onOpenMetadata,
            onOpenAudioEffects = onOpenAudioEffects,
            onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            realtimeSeparationEnabled = realtimeSeparationEnabled,
            realtimeSeparationPreparing = realtimeSeparationPreparing,
            realtimeSeparationStem = realtimeSeparationStem,
            realtimeSeparationStrength = realtimeSeparationStrength,
            realtimeSeparationStatus = realtimeSeparationStatus,
            aiPerformanceState = aiPerformanceState,
            onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
            onAiPerformanceCancel = onAiPerformanceCancel,
            onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
            onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
            onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
            onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
            onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
            onPlayerStyleChange = onPlayerStyleChange,
            onOpenLandscapePlayer = onOpenLandscapePlayer,
            onLyricSeek = onLyricSeek,
            onLyricTranslationToggle = onLyricTranslationToggle,
            onLyricRomaToggle = onLyricRomaToggle,
            controllerScene = controllerScene,
            controllerFromScene = controllerFromScene,
            controllerToScene = controllerToScene,
            controllerProgress = controllerProgress,
            controllerIsTransitioning = controllerIsTransitioning,
            modifier = modifier
        )
    } else {
        val standardPlayerArtworkCornerRadiusDp by
            PersonalizationPreferences.playerArtworkCornerRadiusDp.collectAsState()
        StandardComposePlayerContainer(
            sceneState = sceneState,
            currentSong = currentSong,
            coverPath = resolvedCoverPath,
            artworkTransitionState = artworkTransitionState,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            currentPositionState = currentPositionState,
            totalDurationMs = totalDurationMs,
            audioVisualizerEnabled = audioVisualizerEnabled,
            audioSpectrum = audioSpectrum,
            audioSpectrumState = audioSpectrumState,
            audioVisualizerForeground = audioVisualizerForeground,
            onAudioVisualizerDismiss = onAudioVisualizerDismiss,
            onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            playModeIconRes = playModeIconRes,
            moreIconRes = moreIconRes,
            audioQualityIconRes = audioQualityIconRes,
            audioInfoText = audioInfoText,
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
            smartTransitionVisible = smartTransitionVisible,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = previousFromPlayer,
            onPlayPause = onPlayPause,
            onNext = nextFromPlayer,
            onPlayMode = onPlayMode,
            onPlayModeLongPress = onPlayModeLongPress,
            onMore = onMore,
            onOpenMetadata = onOpenMetadata,
            onOpenAudioEffects = onOpenAudioEffects,
            onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            realtimeSeparationEnabled = realtimeSeparationEnabled,
            realtimeSeparationPreparing = realtimeSeparationPreparing,
            realtimeSeparationStem = realtimeSeparationStem,
            realtimeSeparationStrength = realtimeSeparationStrength,
            realtimeSeparationStatus = realtimeSeparationStatus,
            aiPerformanceState = aiPerformanceState,
            onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
            onAiPerformanceCancel = onAiPerformanceCancel,
            onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
            onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
            onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
            onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
            onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
            onPlayerStyleChange = onPlayerStyleChange,
            onOpenLandscapePlayer = onOpenLandscapePlayer,
            onLyricModifyAlbumArt = onLyricModifyAlbumArt,
            sleepTimerSelection = sleepTimerSelection,
            onSleepTimerSelectionChange = onSleepTimerSelectionChange,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onOpenLyric = onOpenLyric,
            onArtworkLongPress = openArtworkViewer,
            onPlayerCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
            onPlayerCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
            onPlayerCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
            onPlayerCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
            onPlayerCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
            onPlayerCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
            onMainPlayerSheetGeometryChanged = onMainPlayerSheetGeometryChanged,
            onLyricCoverSwipeDownStart = onLyricCoverSwipeDownStart,
            onLyricCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
            onLyricCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
            queueSongs = queueSongs,
            queueCurrentIndex = committedQueueIndex,
            inlineQueueVisible = inlineQueueVisible,
            onToggleInlineQueue = toggleInlineQueue,
            onQueueSongClick = playQueueSong,
            onClearPriorityQueue = onClearPriorityQueue,
            albumSongs = albumSongs,
            albumCoverPath = resolvedAlbumCoverPath,
            onAlbumSongClick = onAlbumSongClick,
            lyricSong = lyricSong,
            lyricPositionMs = lyricPositionMs,
            lyricPositionState = lyricPositionState,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            onLyricSeek = onLyricSeek,
            onLyricTranslationToggle = onLyricTranslationToggle,
            onLyricRomaToggle = onLyricRomaToggle,
            onOpenInLyrico = onOpenInLyrico,
            onAiTimingPreview = onAiTimingPreview,
            onOpenExternalTimingEditor = onOpenExternalTimingEditor,
            onSearchLyrico = onSearchLyrico,
            onClosePlayer = onClosePlayer,
            onBackToPlayer = onBackToPlayer,
            controllerScene = controllerScene,
            controllerFromScene = controllerFromScene,
            controllerToScene = controllerToScene,
            controllerProgress = controllerProgress,
            controllerProgressState = controllerProgressState,
            controllerIsTransitioning = controllerIsTransitioning,
            controllerIsInteractiveGesture = controllerIsInteractiveGesture,
            persistentBottomSheet = persistentBottomSheet,
            prewarmStandardPlayerInMain = prewarmStandardPlayerInMain,
            playerLyricsTransitionCoordinator = playerLyricsTransitionCoordinator,
            sourceCoverTarget = sourceCoverTarget,
            miniPlayerBoundsInRoot = miniPlayerBoundsInRoot,
            floatingMiniPlayerTopPx = floatingMiniPlayerTopPx,
            miniPlayerExpandedHeightDp = miniPlayerStyle.expandedHeightDp,
            miniPlayerCornerRadiusDp = miniPlayerStyle.cornerRadiusDp,
            standardPlayerArtworkCornerRadiusDp = standardPlayerArtworkCornerRadiusDp,
            overlaySuspended = overlaySuspended,
            // Modal visibility must not feed back into the child's own mount condition. It only
            // suspends the stationary PLAYER scene recognizers behind the modal surface.
            sceneGestureSuspended = overlaySuspended || effectiveModalVisible,
            onModalVisibleChange = modalVisibilityChanged,
            onModalDismissActionChange = modalDismissActionChanged,
            modifier = modifier
        )
    }
    }

    OriginalArtworkViewerDialog(
        show = artworkViewerVisible,
        song = currentSong,
        coverKey = resolvedCoverPath,
        onDismiss = dismissArtworkViewer
    )
}

@Composable
private fun ImmersiveComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit,
    previousIconRes: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    nextIconRes: Int,
    playModeIconRes: Int,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    albumSongs: List<AudioFile>,
    albumCoverPath: String?,
    onAlbumSongClick: (AudioFile, Int) -> Unit,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    inlineQueueVisible: Boolean,
    onToggleInlineQueue: () -> Unit,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onClosePlayer: () -> Unit,
    morePanelVisible: Boolean,
    onMorePanelVisibleChangeState: (Boolean) -> Unit,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onLyricModifyAlbumArt: () -> Unit,
    onSearchLyrico: () -> Unit,
    onOpenInLyrico: () -> Unit,
    onAiTimingPreview: () -> Unit,
    onOpenExternalTimingEditor: (LyricTimingEditorTarget) -> Unit,
    sleepTimerSelection: Int,
    onSleepTimerSelectionChange: (Int) -> Unit,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit,
    audioInfoText: String = "",
    audioInfoPlaybackChainText: String = "",
    smartTransitionVisible: Boolean = false,
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenMetadata: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onOpenSpectrumAnalysis: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    realtimeSeparationEnabled: Boolean,
    realtimeSeparationPreparing: Boolean,
    realtimeSeparationStem: Int,
    realtimeSeparationStrength: Float,
    realtimeSeparationStatus: String,
    aiPerformanceState: com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState,
    onAiPerformanceModeChange: (com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode) -> Unit,
    onAiPerformanceInstrumentChange: (String) -> Unit,
    onAiPerformanceCancel: () -> Unit,
    onAiPerformanceImportMelodyModel: (String) -> Unit,
    onAiPerformanceImportInstrumentPack: (String) -> Unit,
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit,
    onRealtimeSeparationStemChange: (Int) -> Unit,
    onRealtimeSeparationStrengthChange: (Float) -> Unit,
    onPlayerStyleChange: (Boolean) -> Unit,
    onOpenLandscapePlayer: () -> Unit,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onLyricRomaToggle: () -> Unit,
    controllerScene: PlayerSceneController.Scene?,
    controllerFromScene: PlayerSceneController.Scene?,
    controllerToScene: PlayerSceneController.Scene?,
    controllerProgress: Float,
    controllerIsTransitioning: Boolean,
    modifier: Modifier
) {
    val controllerSceneForVisibility = if (controllerIsTransitioning) {
        val from = controllerFromScene ?: PlayerSceneController.Scene.MAIN
        val to = controllerToScene ?: from
        if (to != PlayerSceneController.Scene.MAIN) to else from
    } else {
        controllerScene
    }
    val auxiliaryScene = sceneState.currentScene.takeIf {
        it == PlayerScene.QUEUE || it == PlayerScene.ALBUM_DETAIL || it == PlayerScene.FULL_COVER
    }
    val resolvedScene = auxiliaryScene
        ?: controllerSceneForVisibility?.toPlayerScene()
        ?: sceneState.currentScene
    val scene = if (resolvedScene == PlayerScene.QUEUE) PlayerScene.PLAYER else resolvedScene
    if (scene == PlayerScene.MAIN) return

    Box(modifier = modifier.fillMaxSize()) {
        if (scene in setOf(PlayerScene.PLAYER, PlayerScene.LYRIC, PlayerScene.ALBUM_DETAIL)) {
            val from = (controllerFromScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val to = (controllerToScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val progress = controllerProgress.coerceIn(0f, 1f)
            val drawerProgress = when {
                controllerIsTransitioning && from == PlayerSceneController.Scene.MAIN && to != PlayerSceneController.Scene.MAIN -> progress
                controllerIsTransitioning && from != PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.MAIN -> 1f - progress
                else -> 1f
            }.coerceIn(0f, 1f)
            AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.ExitTransition.None,
                modifier = Modifier.fillMaxSize()
            ) {
                ImmersivePlayerHorizontalStack(
                    currentScene = (controllerScene ?: scene.toControllerScene()).inlineQueueHostScene(),
                    fromScene = from,
                    toScene = to,
                    progress = controllerProgress,
                    isTransitioning = controllerIsTransitioning,
                    currentSong = currentSong,
                    coverPath = coverPath,
                    artworkTransitionState = artworkTransitionState,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    audioVisualizerEnabled = audioVisualizerEnabled,
                    audioSpectrum = audioSpectrum,
                    audioVisualizerForeground = audioVisualizerForeground,
                    onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                    onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                    previousIconRes = previousIconRes,
                    playIconRes = playIconRes,
                    pauseIconRes = pauseIconRes,
                    nextIconRes = nextIconRes,
                    playModeIconRes = playModeIconRes,
                    lyricSong = lyricSong,
                    lyricPositionMs = lyricPositionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    audioInfoText = audioInfoText,
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
                    smartTransitionVisible = smartTransitionVisible,
                    albumSongs = albumSongs,
                    albumCoverPath = albumCoverPath ?: coverPath,
                    queueVisible = inlineQueueVisible,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onQueueSongClick = onQueueSongClick,
                    onClearPriorityQueue = onClearPriorityQueue,
                    onToggleQueue = onToggleInlineQueue,
                    onBack = onClosePlayer,
                    onSeekStart = onSeekStart,
                    onSeekStop = onSeekStop,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPlayMode = onPlayMode,
                    onAudioQuality = onAudioQuality,
                    onAudioQualityLongPress = onAudioQualityLongPress,
                    onOpenMetadata = onOpenMetadata,
                    onOpenAudioEffects = onOpenAudioEffects,
                    onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                    onPlaybackSpeedChange = onPlaybackSpeedChange,
                    realtimeSeparationEnabled = realtimeSeparationEnabled,
                    realtimeSeparationPreparing = realtimeSeparationPreparing,
                    realtimeSeparationStem = realtimeSeparationStem,
                    realtimeSeparationStrength = realtimeSeparationStrength,
                    realtimeSeparationStatus = realtimeSeparationStatus,
                    aiPerformanceState = aiPerformanceState,
                    onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
                    onAiPerformanceCancel = onAiPerformanceCancel,
                    onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
                    onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
                    onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                    onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                    onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                    isImmersiveEnabled = true,
                    onPlayerStyleChange = onPlayerStyleChange,
                    onOpenLandscapePlayer = onOpenLandscapePlayer,
                    showPlayerMore = morePanelVisible,
                    onShowPlayerMoreChange = onMorePanelVisibleChangeState,
                    onMorePanelVisibleChange = onModalVisibleChange,
                    onModalDismissActionChange = onModalDismissActionChange,
                    sleepTimerSelection = sleepTimerSelection,
                    onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                    onLyricModifyAlbumArt = onLyricModifyAlbumArt,
                    onSearchLyrico = onSearchLyrico,
                    onOpenInLyrico = onOpenInLyrico,
                    onAiTimingPreview = onAiTimingPreview,
                    onOpenExternalTimingEditor = onOpenExternalTimingEditor,
                    onOpenLyric = onOpenLyric,
                    onArtworkLongPress = onArtworkLongPress,
                    onLyricSeek = onLyricSeek,
                    onLyricTranslationToggle = onLyricTranslationToggle,
                    onLyricRomaToggle = onLyricRomaToggle,
                    onAlbumSongClick = onAlbumSongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = size.height * (1f - drawerProgress)
                            alpha = 1f
                            clip = true
                        }
                        .clipToBounds()
                )
            }
            return@Box
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.FULL_COVER,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            FullCoverPage(
                currentSong = currentSong,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                coverPath = coverPath,
                title = currentSong?.title ?: "",
                onQueueSongClick = onQueueSongClick,
                onBack = { sceneState.backToPlayer() },
            )
        }
    }
}

@Composable
private fun StandardComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    currentPositionState: State<Long>?,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>?,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit,
    previousIconRes: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    nextIconRes: Int,
    playModeIconRes: Int,
    moreIconRes: Int,
    audioQualityIconRes: Int,
    audioInfoText: String,
    audioInfoPlaybackChainText: String,
    smartTransitionVisible: Boolean,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlayModeLongPress: () -> Unit,
    onMore: () -> Unit,
    onOpenMetadata: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onOpenSpectrumAnalysis: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    realtimeSeparationEnabled: Boolean,
    realtimeSeparationPreparing: Boolean,
    realtimeSeparationStem: Int,
    realtimeSeparationStrength: Float,
    realtimeSeparationStatus: String,
    aiPerformanceState: com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState,
    onAiPerformanceModeChange: (com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode) -> Unit,
    onAiPerformanceInstrumentChange: (String) -> Unit,
    onAiPerformanceCancel: () -> Unit,
    onAiPerformanceImportMelodyModel: (String) -> Unit,
    onAiPerformanceImportInstrumentPack: (String) -> Unit,
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit,
    onRealtimeSeparationStemChange: (Int) -> Unit,
    onRealtimeSeparationStrengthChange: (Float) -> Unit,
    onPlayerStyleChange: (Boolean) -> Unit,
    onOpenLandscapePlayer: () -> Unit,
    onLyricModifyAlbumArt: () -> Unit,
    sleepTimerSelection: Int,
    onSleepTimerSelectionChange: (Int) -> Unit,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit,
    onPlayerCoverSwipeUpStart: () -> Unit,
    onPlayerCoverSwipeUpProgress: (Float) -> Unit,
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit,
    onPlayerCoverSwipeDownStart: () -> Unit,
    onPlayerCoverSwipeDownProgress: (Float) -> Unit,
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    onMainPlayerSheetGeometryChanged: (Float, Float) -> Unit,
    onLyricCoverSwipeDownStart: () -> Unit,
    onLyricCoverSwipeDownProgress: (Float) -> Unit,
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    inlineQueueVisible: Boolean,
    onToggleInlineQueue: () -> Unit,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    albumSongs: List<AudioFile>,
    albumCoverPath: String?,
    onAlbumSongClick: (AudioFile, Int) -> Unit,
    lyricSong: Song?,
    lyricPositionMs: Long,
    lyricPositionState: State<Long>?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onLyricRomaToggle: () -> Unit,
    onSearchLyrico: () -> Unit,
    onOpenInLyrico: () -> Unit,
    onAiTimingPreview: () -> Unit,
    onOpenExternalTimingEditor: (LyricTimingEditorTarget) -> Unit,
    onClosePlayer: () -> Unit,
    onBackToPlayer: () -> Unit,
    controllerScene: PlayerSceneController.Scene?,
    controllerFromScene: PlayerSceneController.Scene?,
    controllerToScene: PlayerSceneController.Scene?,
    controllerProgress: Float,
    controllerProgressState: State<Float>?,
    controllerIsTransitioning: Boolean,
    controllerIsInteractiveGesture: Boolean = false,
    persistentBottomSheet: Boolean,
    prewarmStandardPlayerInMain: Boolean,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator?,
    sourceCoverTarget: CoverTransitionTarget?,
    miniPlayerBoundsInRoot: androidx.compose.ui.geometry.Rect?,
    floatingMiniPlayerTopPx: Float?,
    miniPlayerExpandedHeightDp: Float,
    miniPlayerCornerRadiusDp: Float,
    standardPlayerArtworkCornerRadiusDp: Float,
    overlaySuspended: Boolean,
    sceneGestureSuspended: Boolean,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    modifier: Modifier
) {
    val controllerSceneForVisibility = if (controllerIsTransitioning) {
        val from = controllerFromScene ?: PlayerSceneController.Scene.MAIN
        val to = controllerToScene ?: from
        if (to != PlayerSceneController.Scene.MAIN) to else from
    } else {
        controllerScene
    }
    val auxiliaryScene = sceneState.currentScene.takeIf {
        it == PlayerScene.QUEUE || it == PlayerScene.ALBUM_DETAIL || it == PlayerScene.FULL_COVER
    }
    val resolvedScene = auxiliaryScene
        ?: controllerSceneForVisibility?.toPlayerScene()
        ?: sceneState.currentScene
    val scene = if (resolvedScene == PlayerScene.QUEUE) PlayerScene.PLAYER else resolvedScene
    val prewarmMainOnly = prewarmStandardPlayerInMain &&
        scene == PlayerScene.MAIN
    // Persistent NORMAL owns a stable *host*, not a permanently live PLAYER/LYRIC content tree.
    // Once the one-shot prewarm has been consumed, settled MAIN returns before mounting the full
    // player hierarchy. MiniPlayer/bottom chrome remains the collapsed endpoint outside this tree;
    // transition-to-PLAYER resolves [scene] to PLAYER before animation work begins and remounts the
    // already-hot hierarchy in the same stable host.
    if (scene == PlayerScene.MAIN && !prewarmMainOnly && !controllerIsTransitioning) return

    // A seek gesture is a child-owned interaction. Publish that ownership from DOWN until
    // seek completion so the stationary Initial-pass BottomSheet recognizer can decline the same
    // pointer stream before vertical finger drift crosses touchSlop.
    val timelineSeekGestureActive = remember { mutableStateOf(false) }
    val playerSeekStart = {
        timelineSeekGestureActive.value = true
        onSeekStart()
    }
    val playerSeekStop: (Float) -> Unit = { fraction ->
        timelineSeekGestureActive.value = false
        onSeekStop(fraction)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (
            scene == PlayerScene.PLAYER ||
            scene == PlayerScene.LYRIC ||
            prewarmMainOnly
        ) {
            val from = (controllerFromScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val to = (controllerToScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val mainPlayerPair =
                (from == PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.PLAYER) ||
                    (from == PlayerSceneController.Scene.PLAYER && to == PlayerSceneController.Scene.MAIN)
            // Do not read the per-frame sheet state in composition. StandardPlayerLyricStack
            // consumes the stable State object from graphicsLayer callbacks, which limits each
            // frame to RenderNode property updates instead of recomposing the player hierarchy.
            val progress = if (mainPlayerPair && controllerProgressState != null) {
                0f
            } else {
                controllerProgress
            }.coerceIn(0f, 1f)
            val drawerProgress = when {
                controllerIsTransitioning && from == PlayerSceneController.Scene.MAIN && to != PlayerSceneController.Scene.MAIN -> progress
                controllerIsTransitioning && from != PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.MAIN -> 1f - progress
                else -> 1f
            }.coerceIn(0f, 1f)
            val mainPlayerTransition = mainPlayerPair
            CompositionLocalProvider(
                LocalUiFrameAnimationActive provides !prewarmMainOnly,
            ) {
            StandardPlayerLyricStack(
                currentScene = (controllerScene ?: scene.toControllerScene()).inlineQueueHostScene(),
                fromScene = from,
                toScene = to,
                progress = progress,
                mainPlayerExpansionState = controllerProgressState,
                isTransitioning = controllerIsTransitioning,
                isInteractiveGesture = controllerIsInteractiveGesture,
                persistentBottomSheet = persistentBottomSheet,
                prewarmOnly = prewarmMainOnly,
                playerLyricsTransitionCoordinator = playerLyricsTransitionCoordinator,
                currentSong = currentSong,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                currentPositionState = currentPositionState,
                totalDurationMs = totalDurationMs,
                audioVisualizerEnabled = audioVisualizerEnabled,
                audioSpectrum = audioSpectrum,
                audioSpectrumState = audioSpectrumState,
                audioVisualizerForeground = audioVisualizerForeground,
                onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                lyricPositionState = lyricPositionState,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                moreIconRes = moreIconRes,
                audioQualityIconRes = audioQualityIconRes,
                audioInfoText = audioInfoText,
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
                smartTransitionVisible = smartTransitionVisible,
                seekGestureActiveState = timelineSeekGestureActive,
                onSeekStart = playerSeekStart,
                onSeekStop = playerSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                onPlaybackSpeedChange = onPlaybackSpeedChange,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                aiPerformanceState = aiPerformanceState,
                onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
                onAiPerformanceCancel = onAiPerformanceCancel,
                onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
                onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
                onLyricModifyAlbumArt = onLyricModifyAlbumArt,
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                onAudioQuality = onAudioQuality,
                onAudioQualityLongPress = onAudioQualityLongPress,
                onOpenLyric = onOpenLyric,
                onArtworkLongPress = onArtworkLongPress,
                onPlayerCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
                onPlayerCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
                onPlayerCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
                onPlayerCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
                onPlayerCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
                onPlayerCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
                onMainPlayerSheetGeometryChanged = onMainPlayerSheetGeometryChanged,
                onLyricCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onLyricCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onLyricCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                queueVisible = inlineQueueVisible,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onToggleQueue = onToggleInlineQueue,
                onLyricSeek = onLyricSeek,
                onLyricTranslationToggle = onLyricTranslationToggle,
                onLyricRomaToggle = onLyricRomaToggle,
                onSearchLyrico = onSearchLyrico,
                onOpenInLyrico = onOpenInLyrico,
                onAiTimingPreview = onAiTimingPreview,
                onOpenExternalTimingEditor = onOpenExternalTimingEditor,
                onClosePlayer = onClosePlayer,
                onBackToPlayer = onBackToPlayer,
                sourceCoverTarget = sourceCoverTarget,
                miniPlayerBoundsInRoot = miniPlayerBoundsInRoot,
                floatingMiniPlayerTopPx = floatingMiniPlayerTopPx,
                miniPlayerExpandedHeightDp = miniPlayerExpandedHeightDp,
                miniPlayerCornerRadiusDp = miniPlayerCornerRadiusDp,
                standardPlayerArtworkCornerRadiusDp = standardPlayerArtworkCornerRadiusDp,
                overlaySuspended = overlaySuspended,
                sceneGestureSuspended = sceneGestureSuspended,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                drawerProgress = drawerProgress,
                modifier = Modifier
                    .fillMaxSize()
                    .prewarmPlacedOffstage(prewarmMainOnly)
            )
            }
            return@Box
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.PLAYER,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerMainPage(
                currentSong = currentSong,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                currentPositionState = currentPositionState,
                totalDurationMs = totalDurationMs,
                audioVisualizerEnabled = audioVisualizerEnabled,
                audioSpectrum = audioSpectrum,
                audioVisualizerForeground = audioVisualizerForeground,
                onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                lyricPositionState = lyricPositionState,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                moreIconRes = moreIconRes,
                audioQualityIconRes = audioQualityIconRes,
                audioInfoText = audioInfoText,
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
                smartTransitionVisible = smartTransitionVisible,
                onSeekStart = playerSeekStart,
                onSeekStop = playerSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                onPlaybackSpeedChange = onPlaybackSpeedChange,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                aiPerformanceState = aiPerformanceState,
                onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
                onAiPerformanceCancel = onAiPerformanceCancel,
                onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
                onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                isImmersiveEnabled = false,
                artworkCornerRadiusDp = standardPlayerArtworkCornerRadiusDp,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onAudioQuality = onAudioQuality,
                onAudioQualityLongPress = onAudioQualityLongPress,
                onOpenLyric = onOpenLyric,
                onArtworkLongPress = onArtworkLongPress,
                queueVisible = inlineQueueVisible,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onToggleQueue = onToggleInlineQueue,
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                overlaySuspended = overlaySuspended,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.LYRIC,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            LyricPage(
                currentSong = currentSong,
                coverPath = coverPath,
                song = lyricSong,
                positionMs = lyricPositionMs,
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                moreIconRes = moreIconRes,
                onSeek = onLyricSeek,
                onPlayPause = onPlayPause,
                onTranslationToggle = onLyricTranslationToggle,
                onRomaToggle = onLyricRomaToggle,
                onOpenInLyrico = onOpenInLyrico,
                onAiTimingPreview = onAiTimingPreview,
                onOpenExternalTimingEditor = onOpenExternalTimingEditor,
                onSearchLyrico = onSearchLyrico,
                onModifyAlbumArt = onLyricModifyAlbumArt,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onBack = onBackToPlayer
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.ALBUM_DETAIL,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            AlbumDetailPanel(
                currentSong = currentSong,
                songs = albumSongs,
                coverPath = albumCoverPath,
                onSongClick = onAlbumSongClick,
                onBack = { sceneState.backToPlayer() }
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.FULL_COVER,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            FullCoverPage(
                currentSong = currentSong,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                coverPath = coverPath,
                title = currentSong?.title ?: "",
                onQueueSongClick = onQueueSongClick,
                onBack = { sceneState.backToPlayer() },
            )
        }
    }
}

private fun PlayerSceneController.Scene.inlineQueueHostScene(): PlayerSceneController.Scene =
    if (this == PlayerSceneController.Scene.QUEUE) PlayerSceneController.Scene.PLAYER else this

private fun PlayerScene.toControllerScene(): PlayerSceneController.Scene = when (this) {
    PlayerScene.MAIN -> PlayerSceneController.Scene.MAIN
    PlayerScene.PLAYER -> PlayerSceneController.Scene.PLAYER
    PlayerScene.LYRIC -> PlayerSceneController.Scene.LYRIC
    PlayerScene.QUEUE -> PlayerSceneController.Scene.QUEUE
    PlayerScene.ALBUM_DETAIL -> PlayerSceneController.Scene.ALBUM_DETAIL
    PlayerScene.FULL_COVER -> PlayerSceneController.Scene.PLAYER
}

@Composable
private fun StandardPlayerLyricStack(
    currentScene: PlayerSceneController.Scene,
    fromScene: PlayerSceneController.Scene,
    toScene: PlayerSceneController.Scene,
    progress: Float,
    mainPlayerExpansionState: State<Float>?,
    isTransitioning: Boolean,
    isInteractiveGesture: Boolean = false,
    persistentBottomSheet: Boolean,
    prewarmOnly: Boolean = false,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator?,
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    currentPositionState: State<Long>?,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>?,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit,
    previousIconRes: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    nextIconRes: Int,
    playModeIconRes: Int,
    moreIconRes: Int,
    audioQualityIconRes: Int,
    audioInfoText: String,
    audioInfoPlaybackChainText: String,
    smartTransitionVisible: Boolean,
    seekGestureActiveState: State<Boolean>,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlayModeLongPress: () -> Unit,
    onMore: () -> Unit,
    onOpenMetadata: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onOpenSpectrumAnalysis: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    realtimeSeparationEnabled: Boolean,
    realtimeSeparationPreparing: Boolean,
    realtimeSeparationStem: Int,
    realtimeSeparationStrength: Float,
    realtimeSeparationStatus: String,
    aiPerformanceState: com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState,
    onAiPerformanceModeChange: (com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode) -> Unit,
    onAiPerformanceInstrumentChange: (String) -> Unit,
    onAiPerformanceCancel: () -> Unit,
    onAiPerformanceImportMelodyModel: (String) -> Unit,
    onAiPerformanceImportInstrumentPack: (String) -> Unit,
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit,
    onRealtimeSeparationStemChange: (Int) -> Unit,
    onRealtimeSeparationStrengthChange: (Float) -> Unit,
    onPlayerStyleChange: (Boolean) -> Unit,
    onOpenLandscapePlayer: () -> Unit,
    onLyricModifyAlbumArt: () -> Unit,
    sleepTimerSelection: Int,
    onSleepTimerSelectionChange: (Int) -> Unit,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit,
    onPlayerCoverSwipeUpStart: () -> Unit,
    onPlayerCoverSwipeUpProgress: (Float) -> Unit,
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit,
    onPlayerCoverSwipeDownStart: () -> Unit,
    onPlayerCoverSwipeDownProgress: (Float) -> Unit,
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    onMainPlayerSheetGeometryChanged: (Float, Float) -> Unit,
    onLyricCoverSwipeDownStart: () -> Unit,
    onLyricCoverSwipeDownProgress: (Float) -> Unit,
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    queueVisible: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onToggleQueue: () -> Unit,
    lyricSong: Song?,
    lyricPositionMs: Long,
    lyricPositionState: State<Long>?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onLyricRomaToggle: () -> Unit,
    onSearchLyrico: () -> Unit,
    onOpenInLyrico: () -> Unit,
    onAiTimingPreview: () -> Unit,
    onOpenExternalTimingEditor: (LyricTimingEditorTarget) -> Unit,
    onClosePlayer: () -> Unit,
    onBackToPlayer: () -> Unit,
    sourceCoverTarget: CoverTransitionTarget?,
    miniPlayerBoundsInRoot: androidx.compose.ui.geometry.Rect?,
    floatingMiniPlayerTopPx: Float?,
    miniPlayerExpandedHeightDp: Float,
    miniPlayerCornerRadiusDp: Float,
    standardPlayerArtworkCornerRadiusDp: Float,
    overlaySuspended: Boolean,
    sceneGestureSuspended: Boolean,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    drawerProgress: Float,
    modifier: Modifier = Modifier
) {
    var containerRootBounds by remember { mutableStateOf<RectF?>(null) }
    var playerArtworkAnchor by remember(coverPath) {
        mutableStateOf<PlayerLyricsArtworkAnchor?>(null)
    }
    var playerLyricTitleBoundsInRoot by remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                containerRootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val particleVisualizerHeight = (maxHeight * 0.30f).coerceAtMost(220.dp)

        val session = playerLyricsTransitionCoordinator?.activeSession
        val playerLyricsProgressState = session?.progressState
        val playerLyricTransition = isTransitioning &&
            session != null &&
            PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(
                session.from,
                session.to
            )
        fun currentLyricsFractionForLayer(): Float {
            if (!playerLyricTransition) {
                return if (currentScene == PlayerSceneController.Scene.LYRIC) 1f else 0f
            }
            val activeSession = session ?: return 0f
            val liveProgress = playerLyricsProgressState?.value ?: progress.coerceIn(0f, 1f)
            return PlayerLyricsTransitionCoordinator.absoluteLyricsFraction(
                session = activeSession,
                transitionProgress = liveProgress,
            ).coerceIn(0f, 1f)
        }

        val mainPlayerTransition = isTransitioning &&
            (
                (fromScene == PlayerSceneController.Scene.MAIN &&
                    toScene == PlayerSceneController.Scene.PLAYER) ||
                    (fromScene == PlayerSceneController.Scene.PLAYER &&
                        toScene == PlayerSceneController.Scene.MAIN)
                )

        val statusTopPx = with(density) { rawStableStatusBarTopPadding().toPx() }
        val fallbackArtworkBitmap = artworkTransitionState.foregroundArtworkBitmap()
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        val fallbackArtworkWidth = fallbackArtworkBitmap?.width ?: 0
        val fallbackArtworkHeight = fallbackArtworkBitmap?.height ?: 0
        val fallbackPlayerCoverRect = remember(
            widthPx,
            statusTopPx,
            density.density,
            fallbackArtworkWidth,
            fallbackArtworkHeight,
            isPlaying,
        ) {
            // Portrait PlayerMainPage now places the outer artwork item directly below statusBarsPadding
            // at full viewport width. The visible bitmap is then inset/scaled locally. The old
            // 14dp/20dp target described a pre-9A player layout, so the shared actor reached that
            // stale rectangle and the real artwork corrected its size/position on ownership handoff.
            val envelope = resolvePlayerArtworkContentRect(
                outerLeft = 0f,
                outerTop = statusTopPx,
                outerWidth = widthPx,
                outerHeight = widthPx,
                contentInsetPx = with(density) { STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP.dp.toPx() },
                contentScale = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE,
            )
            val visible = resolvePlayerArtworkAspectRect(
                envelope = envelope,
                sourceWidth = fallbackArtworkWidth,
                sourceHeight = fallbackArtworkHeight,
            )
            val holderScaled = scalePlayerArtworkHandoffRectAroundCenter(
                rect = visible,
                scale = if (isPlaying) 1f else STANDARD_PLAYER_ARTWORK_PAUSED_HOLDER_SCALE,
            )
            RectF(
                holderScaled.left,
                holderScaled.top,
                holderScaled.left + holderScaled.width,
                holderScaled.top + holderScaled.height,
            )
        }
        DisposableEffect(artworkTransitionState, playerLyricTransition) {
            artworkTransitionState.setExternalSceneArtworkMotionActive(playerLyricTransition)
            onDispose {
                if (playerLyricTransition) {
                    artworkTransitionState.setExternalSceneArtworkMotionActive(false)
                }
            }
        }
        // PlayerMainPage reports the real AlbumArtCard bounds. Prefer them over the old
        // synthetic full-width rectangle; this is the Compose equivalent of a
        // ChangeImageTransform capturing the source and destination views.
        val measuredPlayerCoverRect = playerArtworkAnchor
            ?.takeIf { it.artworkKey == coverPath && it.rect?.isUsable == true }
            ?.rect
            ?.toLocalPlayerLyricsRect(containerRootBounds)
            ?.takeIf { it.isUsable }
            ?.let { RectF(it.left, it.top, it.right, it.bottom) }
        val measuredPlayerTitleRect = playerLyricTitleBoundsInRoot
            ?.let { RectF(it.left, it.top, it.right, it.bottom) }
            ?.toLocalRect(containerRootBounds)
            ?.takeIf { it.isUsableSource(widthPx, heightPx) }
        // The measured cover sits inside the translated player sheet, so boundsInRoot changes on
        // every frame while MAIN and PLAYER move. Interpolating toward that moving target squeezed
        // and shook the artwork. Freeze the endpoint, but derive it from PlayerMainPage's current
        // outer-item + local ArtworkImageNode inset/scale formula rather than obsolete layout margins.
        // The measured anchor remains authoritative for stable PLAYER/LYRIC transitions.
        val playerCoverRect = if (mainPlayerTransition) {
            fallbackPlayerCoverRect
        } else {
            measuredPlayerCoverRect ?: fallbackPlayerCoverRect
        }
        val source = sourceCoverTarget?.bounds
            ?.toLocalRect(containerRootBounds)
            ?.takeIf { it.isUsableSource(widthPx, heightPx) }
        val returningPlayerToMain = mainPlayerTransition &&
            fromScene == PlayerSceneController.Scene.PLAYER &&
            toScene == PlayerSceneController.Scene.MAIN
        val mainSharedTransition = ENABLE_PLAYER_LIST_COVER_TRANSITION &&
            mainPlayerTransition &&
            !coverPath.isNullOrBlank() &&
            source != null &&
            // Never let an entry-time ListCover survive into PLAYER -> MAIN. If a stale
            // composition still has that source, keep the real player artwork visible until the
            // MiniPlayer snapshot is supplied instead of animating toward the list.
            (!returningPlayerToMain ||
                sourceCoverTarget?.source == CoverTransitionTarget.Source.MiniPlayer)
        // reference player retained artwork view promotes the already-owned retained bitmap wrapper/Bitmap through the scene/LayoutRes
        // transition and keeps the old wrapper referenced until the handoff finishes. Do the same
        // here: freeze the raster already owned by the persistent PlaybackArtworkTransition holder
        // instead of launching a second provider request for the MAIN <-> PLAYER shared actor.
        val frozenMainSharedArtworkBitmap = remember(mainSharedTransition, coverPath) {
            if (
                mainSharedTransition &&
                !coverPath.isNullOrBlank() &&
                (artworkTransitionState.foregroundCurrentKey() == coverPath ||
                    artworkTransitionState.foregroundTargetKey() == coverPath)
            ) {
                artworkTransitionState.foregroundArtworkBitmapForKey(coverPath)
                    ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            } else {
                null
            }
        }
        val frozenMainSharedArtworkImage = remember(frozenMainSharedArtworkBitmap) {
            frozenMainSharedArtworkBitmap?.asImageBitmap()
        }
        var mainArtworkReadyKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(mainSharedTransition, coverPath) {
            if (!mainSharedTransition) mainArtworkReadyKey = null
        }
        val mainArtworkOwnerActive = mainSharedTransition &&
            (frozenMainSharedArtworkImage != null || mainArtworkReadyKey == coverPath)
        val transitionRoute = session?.route ?: PlayerLyricsTransitionRoute.OffscreenSceneZoom
        val sessionPlayerRect = session?.playerArtworkRect
            ?.toLocalPlayerLyricsRect(containerRootBounds)
        val sessionLyricRect = session?.lyricArtworkRect
            ?.toLocalPlayerLyricsRect(containerRootBounds)
        // There is deliberately no synthetic artwork route. The overlay may only exist when
        // both real endpoints were visible and frozen at session creation.
        val artworkOverlayActive = playerLyricTransition &&
            transitionRoute == PlayerLyricsTransitionRoute.VisibleArtworkAligned &&
            !session?.artworkKey.isNullOrBlank() &&
            sessionPlayerRect?.isUsable == true &&
            sessionLyricRect?.isUsable == true

        // Shared artwork is a newly composed node. Hiding the real player/lyric artwork in the
        // same composition that mounts this node can expose one empty raster frame before
        // BitmapImage has produced its first draw, which looks like a flash exactly when the
        // zoom gesture captures. Keep the endpoint views underneath for one rendered frame; the
        // overlay starts at the exact same frozen rect, so the overlap is invisible. On the next
        // frame ownership moves exclusively to the frozen overlay.
        var artworkOverlayReadySessionId by remember { mutableStateOf<Long?>(null) }
        var artworkOverlayRasterReadySessionId by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(artworkOverlayActive, session?.id) {
            val id = session?.id
            if (!artworkOverlayActive || id == null) {
                artworkOverlayReadySessionId = null
                artworkOverlayRasterReadySessionId = null
                return@LaunchedEffect
            }
            artworkOverlayReadySessionId = null
            artworkOverlayRasterReadySessionId = null
            withFrameNanos { }
            if (playerLyricsTransitionCoordinator?.activeSession?.id == id) {
                artworkOverlayReadySessionId = id
            }
        }
        // Freeze the exact already-drawn artwork holder bitmap for this PLAYER <-> LYRIC session. The
        // previous implementation mounted a new BitmapImage keyed by the post-edit file version,
        // which could launch a provider/decode/texture path on the first gesture frame after a
        // Lyrico lyric/cover write. Reference transitions the existing ArtworkItemNode visual instead.
        val frozenPlayerLyricArtworkBitmap = remember(session?.id, artworkOverlayActive) {
            if (artworkOverlayActive) artworkTransitionState.foregroundArtworkBitmap() else null
        }
        val frozenPlayerLyricArtworkImage = remember(frozenPlayerLyricArtworkBitmap) {
            frozenPlayerLyricArtworkBitmap
                ?.takeUnless { it.isRecycled }
                ?.asImageBitmap()
        }
        val artworkOverlayHasRaster = frozenPlayerLyricArtworkImage != null ||
            artworkOverlayRasterReadySessionId == session?.id
        val artworkOverlayOwnsEndpoints = artworkOverlayActive &&
            artworkOverlayReadySessionId == session?.id &&
            artworkOverlayHasRaster
        val settledPlayerVisible =
            currentScene == PlayerSceneController.Scene.PLAYER || mainPlayerTransition
        val settledLyricVisible = currentScene == PlayerSceneController.Scene.LYRIC

        // MAIN -> PLAYER uses three visual owners: backdrop geometry, curved shared artwork,
        // and ordinary player information. The ordinary content keeps its final internal layout,
        // but the whole body rides the current sheet top so it enters from inside the player
        // surface rather than from beyond the physical screen.
        fun currentPlayerRevealFraction(): Float = when {
            mainPlayerTransition && mainPlayerExpansionState != null ->
                mainPlayerExpansionState.value.coerceIn(0f, 1f)
            mainPlayerTransition && fromScene == PlayerSceneController.Scene.MAIN ->
                progress.coerceIn(0f, 1f)
            mainPlayerTransition -> 1f - progress.coerceIn(0f, 1f)
            persistentBottomSheet && currentScene == PlayerSceneController.Scene.MAIN -> 0f
            else -> 1f
        }

        fun currentPlayerContentAlpha(): Float {
            if (!mainPlayerTransition &&
                !(persistentBottomSheet && currentScene == PlayerSceneController.Scene.MAIN)
            ) return 1f
            val reveal = currentPlayerRevealFraction()
            return if (reveal <= 0f) 0f
            else kotlin.math.max(0f, 1f + 0.5f * kotlin.math.ln(reveal))
        }

        fun currentPlayerBackdropAlpha(): Float {
            if (!mainPlayerTransition && currentScene == PlayerSceneController.Scene.MAIN) return 0f
            // reference player's player root (k1) only cross-fades when the background drawable itself is
            // replaced; sheet motion changes the owning View/clip/translation. Raw already reveals
            // this backdrop through playerSurfaceClipModifier, so applying another reveal-ratio
            // alpha here creates a non-reference player full-screen background fade on PLAYER enter/return.
            return 1f
        }
        val reducedNavigationBottomPadding = rawReducedNavigationBottomPadding(reduceBy = 12.dp)
        val miniPlayerLocalBounds = miniPlayerBoundsInRoot?.let { bounds ->
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom).toLocalRect(containerRootBounds)
        }
        // The Bottom Chrome host is the geometry owner. Use its measured MiniPlayer top as the
        // sheet's collapsed anchor; only use a static estimate before the first bounds callback.
        val measuredMiniCollapsedTop = miniPlayerLocalBounds
            ?.top
            ?.takeIf { it.isFinite() && it > 0f && it < heightPx }
        val measuredFloatingCollapsedTop = floatingMiniPlayerTopPx
            ?.takeIf { it.isFinite() && it > 0f && it < heightPx }
        val fallbackPeekHeightPx = with(density) {
            val miniHeight = miniPlayerExpandedHeightDp.dp
            val navigationAndGap = 72.dp
            ((if (persistentBottomSheet) miniHeight + navigationAndGap else miniHeight) + reducedNavigationBottomPadding).toPx()
        }
        val playerSheetTravelPx = measuredMiniCollapsedTop
            ?: measuredFloatingCollapsedTop
            ?: (heightPx - fallbackPeekHeightPx).coerceAtLeast(0f)
        val fallbackMiniPlayerHeightPx = with(density) { miniPlayerExpandedHeightDp.dp.toPx() }
        val collapsedBackdropBounds = (miniPlayerLocalBounds ?: RectF(
            0f,
            playerSheetTravelPx,
            widthPx,
            (playerSheetTravelPx + fallbackMiniPlayerHeightPx).coerceAtMost(heightPx),
        )).let { raw ->
            RectF(
                raw.left.coerceIn(0f, widthPx),
                raw.top.coerceIn(0f, heightPx),
                raw.right.coerceIn(0f, widthPx),
                raw.bottom.coerceIn(0f, heightPx),
            )
        }
        val collapsedBackdropCornerRadiusPx = if (persistentBottomSheet) {
            with(density) { miniPlayerCornerRadiusDp.dp.toPx() }
                .coerceAtMost(collapsedBackdropBounds.height() * 0.5f)
        } else {
            (collapsedBackdropBounds.height() * 0.5f).coerceAtLeast(0f)
        }
        // Both NORMAL and floating player variants must measure drag deltas in the stationary
        // parent coordinate space. In floating mode the old PLAYER -> MAIN recognizer lived on
        // AlbumArtCard, which is a descendant of the translated sheet. Every translation changed
        // that pointer node's local Y coordinates, feeding the sheet movement back into the next
        // drag sample and producing the remaining oscillation/jitter. The sheet behavior /
        // ViewDragHelper always reads MotionEvents in CoordinatorLayout coordinates and moves the
        // sheet child separately. Treat a valid floating collapsed top as the same root-owned
        // draggable sheet geometry instead of falling back to the moving artwork recognizer.
        val rootOwnsMainPlayerSheetDownGesture =
            persistentBottomSheet || playerSheetTravelPx > 0f
        LaunchedEffect(playerSheetTravelPx, widthPx) {
            onMainPlayerSheetGeometryChanged(playerSheetTravelPx, widthPx)
        }

        // Keep the whole Now Playing root attached to one sheet behavior. Once PLAYER is
        // expanded, a downward drag can start from any ordinary player area; while the sheet is
        // settling, a new pointer may recapture it and reverse direction. Keep that ownership on
        // this persistent sheet instead of limiting PLAYER -> MAIN to AlbumArtCard. Scrollable
        // queue content remains authoritative while it is visible; matching nested-scroll
        // handoff for that child can be layered on separately without stealing its scrolling.
        val context = LocalContext.current
        val sheetFlingVelocityBoundsPx = remember(context) {
            ViewConfiguration.get(context).let { config ->
                config.scaledMinimumFlingVelocity.toFloat() to
                    config.scaledMaximumFlingVelocity.toFloat()
            }
        }
        val minimumSheetFlingVelocityPx = sheetFlingVelocityBoundsPx.first
        val maximumSheetFlingVelocityPx = sheetFlingVelocityBoundsPx.second
        val latestSheetCurrentScene by rememberUpdatedState(currentScene)
        val latestMainPlayerTransition by rememberUpdatedState(mainPlayerTransition)
        val latestPlayerLyricTransition by rememberUpdatedState(playerLyricTransition)
        val latestQueueVisible by rememberUpdatedState(queueVisible)
        val latestSceneGestureSuspended by rememberUpdatedState(sceneGestureSuspended)
        val latestSheetDragStart by rememberUpdatedState(onPlayerCoverSwipeDownStart)
        val latestSheetDragProgress by rememberUpdatedState(onPlayerCoverSwipeDownProgress)
        val latestSheetDragEnd by rememberUpdatedState(onPlayerCoverSwipeDownEnd)
        // The drag helper only receives a DOWN that actually lands inside the sheet child.
        // Our stationary parent pointer node intentionally avoids the translated-child coordinate
        // feedback loop, but keeping that node active across a non-interactive PLAYER -> MAIN
        // settle would make the *whole screen* a sheet hit target. Then an early list scroll on the
        // already-exposed MAIN area can recapture the sheet and reopen PLAYER. During a committed
        // collapse settle, drop the parent recognizer entirely so the revealed MAIN immediately
        // owns touches, matching CoordinatorLayout child hit testing.
        val settlingFromPlayer =
            rootOwnsMainPlayerSheetDownGesture &&
                mainPlayerTransition &&
                !isInteractiveGesture &&
                currentScene == PlayerSceneController.Scene.PLAYER
        val playerSheetDragModifier = if (
            !rootOwnsMainPlayerSheetDownGesture ||
            playerSheetTravelPx <= 0f ||
            settlingFromPlayer
        ) {
            Modifier
        } else {
            Modifier.pointerInput(
                playerSheetTravelPx,
                minimumSheetFlingVelocityPx,
                maximumSheetFlingVelocityPx
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val expansionAtDown = mainPlayerExpansionState?.value?.coerceIn(0f, 1f)
                        ?: if (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f
                    val sheetTopAtDown = playerSheetTravelPx * (1f - expansionAtDown)
                    // A real BottomSheetBehavior cannot capture a DOWN above the child's current
                    // top. Preserve the stationary coordinate space for drag math, but restore
                    // that hit-region rule so MAIN content revealed during a transition remains
                    // immediately scrollable instead of becoming a full-screen recapture zone.
                    val downHitsCurrentSheet = down.position.y >= sheetTopAtDown
                    val enabledAtDown =
                        !latestPlayerLyricTransition &&
                            !latestQueueVisible &&
                            !latestSceneGestureSuspended &&
                            downHitsCurrentSheet &&
                            (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER ||
                                latestMainPlayerTransition)
                    if (!enabledAtDown) return@awaitEachGesture

                    val pointerId = down.id
                    val startPosition = down.position
                    val tracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    var dragStartExpansion = mainPlayerExpansionState?.value?.coerceIn(0f, 1f)
                        ?: if (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f
                    var axis = 0 // 0 undecided, 1 vertical sheet, 2 rejected
                    val stableExpandedPlayerAtDown =
                        latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER &&
                            !latestMainPlayerTransition
                    var lastPointerY = startPosition.y
                    var draggedDy = 0f
                    var finishedNormally = false

                    try {
                        while (true) {
                            // CoordinatorLayout/BottomSheetBehavior gets first chance to
                            // intercept a vertical move before ordinary children. Initial pass
                            // gives this persistent sheet the same ownership timing; horizontal
                            // gestures remain unconsumed and continue to artwork/controls.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)

                            val dx = change.position.x - startPosition.x
                            val dy = change.position.y - startPosition.y
                            val absX = kotlin.math.abs(dx)
                            val absY = kotlin.math.abs(dy)

                            // SeekBar owns its pointer stream from DOWN to stopTrackingTouch.
                            // Our parent is intentionally observing Initial pass, so explicitly
                            // abandon capture before evaluating touchSlop once the child has started
                            // seeking. This prevents small vertical drift while scrubbing from
                            // collapsing the PLAYER sheet.
                            if (axis == 0 && seekGestureActiveState.value) {
                                axis = 2
                                continue
                            }

                            // The sheet and artwork carousel observe the same stream at Initial.
                            // Waiting only for vertical touchSlop lets ordinary finger jitter start
                            // PLAYER -> MAIN while the user is clearly switching artwork. Resolve
                            // the axis once and never recapture a stream rejected as horizontal.
                            // Ambiguous diagonals remain undecided until one direction dominates.
                            if (axis == 0 &&
                                (absX > viewConfiguration.touchSlop ||
                                    absY > viewConfiguration.touchSlop)
                            ) {
                                if (absX > absY * 1.10f) {
                                    axis = 2
                                    continue
                                }
                                if (absY <= absX * 1.20f) {
                                    continue
                                }
                                // Stable PLAYER reserves upward motion for PLAYER -> LYRICS. The
                                // parent sheet only owns downward collapse there. During an actual
                                // MAIN <-> PLAYER drag/recapture, both directions still belong to
                                // the sheet, just like ViewDragHelper. This prevents the Initial-
                                // pass parent from stealing the same upward pointer that the
                                // artwork uses to enter lyrics.
                                if (stableExpandedPlayerAtDown && dy < 0f) {
                                    axis = 2
                                    continue
                                }
                                axis = 1
                                // Preserve the distance *beyond* touchSlop on the capture frame.
                                // The old code rebased to the current pointer and discarded the
                                // whole pre-capture delta, so the finger permanently led the sheet
                                // by one event (often 15-30 px on high-density devices). A real
                                // drag should have only the touchSlop dead-zone: any overshoot must
                                // move the sheet immediately on the same MotionEvent.
                                val slop = viewConfiguration.touchSlop
                                val captureOverslop = when {
                                    dy > slop -> dy - slop
                                    dy < -slop -> dy + slop
                                    else -> 0f
                                }
                                lastPointerY = change.position.y
                                draggedDy = captureOverslop
                                dragStartExpansion = mainPlayerExpansionState?.value?.coerceIn(0f, 1f)
                                    ?: if (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f
                                latestSheetDragStart()
                                if (captureOverslop != 0f) {
                                    latestSheetDragProgress(captureOverslop / playerSheetTravelPx)
                                }
                                // CoordinatorLayout intercepts at this boundary and cancels the
                                // child stream. Consume the capture event after applying overslop
                                // so there is no one-frame stationary pause at capture.
                                change.consume()
                            } else if (axis == 1) {
                                val frameDy = change.position.y - lastPointerY
                                lastPointerY = change.position.y
                                draggedDy += frameDy
                                latestSheetDragProgress(draggedDy / playerSheetTravelPx)
                                change.consume()
                            }

                            if (!change.pressed) {
                                finishedNormally = true
                                if (axis == 1) {
                                    val rawVelocity = tracker.calculateVelocity()
                                    fun clampFlingVelocity(value: Float): Float = when {
                                        kotlin.math.abs(value) < minimumSheetFlingVelocityPx -> 0f
                                        value > maximumSheetFlingVelocityPx -> maximumSheetFlingVelocityPx
                                        value < -maximumSheetFlingVelocityPx -> -maximumSheetFlingVelocityPx
                                        else -> value
                                    }
                                    val velocityX = clampFlingVelocity(rawVelocity.x)
                                    val velocityY = clampFlingVelocity(rawVelocity.y)
                                    val predictedExpansion =
                                        (dragStartExpansion - draggedDy / playerSheetTravelPx)
                                            .coerceIn(0f, 1f)
                                    // BottomSheetBehavior.onViewReleased(): an upward Y fling always
                                    // expands. A downward fling collapses only when Y is at least as
                                    // dominant as X. Zero-Y or horizontal-dominant release uses the
                                    // nearest anchor instead of treating a tiny diagonal as a fling.
                                    val shouldClose = when {
                                        velocityY < 0f -> false
                                        velocityY == 0f || kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY) ->
                                            predictedExpansion < 0.5f
                                        else -> true
                                    }
                                    // ViewDragHelper.computeAxisDuration uses parentWidth / 2 as
                                    // its distance influence even for vertical settling. Normalize
                                    // physical Y velocity by the parent width, not the sheet travel,
                                    // so PlayerSceneController's dimensionless formula is identical.
                                    latestSheetDragEnd(shouldClose, velocityY / widthPx)
                                }
                                break
                            }
                        }
                    } finally {
                        if (!finishedNormally && axis == 1) {
                            val predictedExpansion =
                                (dragStartExpansion - draggedDy / playerSheetTravelPx)
                                    .coerceIn(0f, 1f)
                            latestSheetDragEnd(predictedExpansion < 0.5f, 0f)
                        }
                    }
                }
            }
        }

        // PLAYER -> LYRICS upward navigation must use the same stationary parent coordinate
        // space. AlbumArtCard and ArtworkTitleInfoPager are both transformed by sceneMotion while
        // the transition is interactive; deriving dy from either moving child feeds the transform
        // back into the next pointer sample and can flash/duplicate the whole PLAYER/LYRIC stack.
        // Owning the gesture here also covers the title and the otherwise-empty band between the
        // artwork and mini lyrics with one physical drag owner.
        val latestPlayerLyricUpScene by rememberUpdatedState(currentScene)
        val latestPlayerLyricUpTransition by rememberUpdatedState(isTransitioning)
        val latestPlayerLyricUpQueueVisible by rememberUpdatedState(queueVisible)
        val latestPlayerLyricUpSceneGestureSuspended by rememberUpdatedState(sceneGestureSuspended)
        val latestPlayerLyricUpStart by rememberUpdatedState(onPlayerCoverSwipeUpStart)
        val latestPlayerLyricUpProgress by rememberUpdatedState(onPlayerCoverSwipeUpProgress)
        val latestPlayerLyricUpEnd by rememberUpdatedState(onPlayerCoverSwipeUpEnd)
        val latestPlayerLyricArtworkHitRect by rememberUpdatedState(measuredPlayerCoverRect)
        val latestPlayerLyricTitleHitRect by rememberUpdatedState(measuredPlayerTitleRect)
        val playerLyricSwipeUpModifier = Modifier.pointerInput(widthPx) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val start = down.position
                val enabledAtDown =
                    latestPlayerLyricUpScene == PlayerSceneController.Scene.PLAYER &&
                        !latestPlayerLyricUpTransition &&
                        !latestPlayerLyricUpQueueVisible &&
                        !latestPlayerLyricUpSceneGestureSuspended &&
                        !seekGestureActiveState.value &&
                        (
                            latestPlayerLyricArtworkHitRect?.contains(start.x, start.y) == true ||
                                latestPlayerLyricTitleHitRect?.contains(start.x, start.y) == true
                            )
                if (!enabledAtDown) return@awaitEachGesture

                val pointerId = down.id
                val tracker = VelocityTracker().apply {
                    addPosition(down.uptimeMillis, down.position)
                }
                var axis = 0 // 0 undecided, 1 PLAYER -> LYRIC up, 2 rejected
                var effectiveDy = 0f
                var finishedNormally = false

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)

                        val dx = change.position.x - start.x
                        val dy = change.position.y - start.y
                        if (axis == 0 && (
                                seekGestureActiveState.value ||
                                    latestPlayerLyricUpQueueVisible ||
                                    latestPlayerLyricUpSceneGestureSuspended ||
                                    latestPlayerLyricUpTransition ||
                                    latestPlayerLyricUpScene != PlayerSceneController.Scene.PLAYER
                            )
                        ) {
                            axis = 2
                        }
                        if (axis == 0 &&
                            (kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                kotlin.math.abs(dy) > viewConfiguration.touchSlop)
                        ) {
                            axis = if (
                                dy < 0f &&
                                kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
                            ) {
                                1
                            } else {
                                2
                            }
                            if (axis == 1) latestPlayerLyricUpStart()
                        }

                        if (axis == 1) {
                            effectiveDy = (dy + viewConfiguration.touchSlop)
                                .coerceIn(-widthPx, 0f)
                            latestPlayerLyricUpProgress((-effectiveDy / widthPx).coerceIn(0f, 1f))
                            change.consume()
                        }

                        if (!change.pressed) {
                            finishedNormally = true
                            if (axis == 1) {
                                val ratio = (-effectiveDy / widthPx).coerceIn(0f, 1f)
                                val velocityY = tracker.calculateVelocity().y
                                val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                                    progress = ratio,
                                    velocityPxPerSecond = velocityY,
                                    density = density.density,
                                    expectedVelocitySign = -1
                                )
                                val settleVelocity =
                                    PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                        progress = ratio,
                                        commit = commit,
                                        velocityPxPerSecond = velocityY,
                                        travelDistancePx = widthPx,
                                        expectedVelocitySign = -1
                                    )
                                latestPlayerLyricUpEnd(commit, settleVelocity)
                            }
                            break
                        }
                    }
                } finally {
                    if (!finishedNormally && axis == 1) {
                        latestPlayerLyricUpEnd(false, 0f)
                    }
                }
            }
        }

        // PLAYER <-> LYRICS horizontal navigation must also live in this stationary parent.
        // LyricPage itself is translated/scaled during the shared scene motion; reading
        // change.position from that moving subtree feeds the scene transform back into the next
        // pointer sample. The error grows with drag distance, which is why the right half of the
        // display could produce a full-frame flash/duplicate surface while swiping back.
        val latestLyricScene by rememberUpdatedState(currentScene)
        val latestAnyTransition by rememberUpdatedState(isTransitioning)
        val latestLyricSwipeStart by rememberUpdatedState(onLyricCoverSwipeDownStart)
        val latestLyricSwipeProgress by rememberUpdatedState(onLyricCoverSwipeDownProgress)
        val latestLyricSwipeEnd by rememberUpdatedState(onLyricCoverSwipeDownEnd)
        val lyricSwipeBackModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val enabledAtDown =
                    latestLyricScene == PlayerSceneController.Scene.LYRIC &&
                        !latestAnyTransition &&
                        !latestSceneGestureSuspended
                if (!enabledAtDown) return@awaitEachGesture

                val pointerId = down.id
                val start = down.position
                val tracker = VelocityTracker().apply {
                    addPosition(down.uptimeMillis, down.position)
                }
                var axis = 0 // 0 undecided, 1 right-swipe back, 2 rejected
                var effectiveDx = 0f
                var finishedNormally = false

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)

                        val dx = change.position.x - start.x
                        val dy = change.position.y - start.y
                        if (axis == 0 &&
                            (kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                kotlin.math.abs(dy) > viewConfiguration.touchSlop)
                        ) {
                            axis = if (
                                dx > 0f &&
                                kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
                            ) {
                                1
                            } else {
                                2
                            }
                            if (axis == 1) {
                                latestLyricSwipeStart()
                            }
                        }

                        if (axis == 1) {
                            // Keep only the ordinary touchSlop dead-zone; all movement beyond it
                            // is mapped in the stationary parent coordinate system.
                            effectiveDx = (dx - viewConfiguration.touchSlop)
                                .coerceIn(0f, widthPx)
                            latestLyricSwipeProgress(effectiveDx / widthPx)
                            change.consume()
                        }

                        if (!change.pressed) {
                            finishedNormally = true
                            if (axis == 1) {
                                val progress = (effectiveDx / widthPx).coerceIn(0f, 1f)
                                val velocityX = tracker.calculateVelocity().x
                                val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                                    progress = progress,
                                    velocityPxPerSecond = velocityX,
                                    density = density.density,
                                    expectedVelocitySign = 1
                                )
                                val settleVelocity =
                                    PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                        progress = progress,
                                        commit = commit,
                                        velocityPxPerSecond = velocityX,
                                        travelDistancePx = widthPx,
                                        expectedVelocitySign = 1
                                    )
                                latestLyricSwipeEnd(commit, settleVelocity)
                            }
                            break
                        }
                    }
                } finally {
                    if (!finishedNormally && axis == 1) {
                        latestLyricSwipeEnd(false, 0f)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(lyricSwipeBackModifier)
                .then(playerLyricSwipeUpModifier)
                // Keep gesture tracking in the stationary parent coordinate space. The
                // ViewDragHelper reads MotionEvent coordinates from CoordinatorLayout and moves
                // the captured sheet child separately. Attaching pointerInput to the translated
                // sheet creates a feedback loop: moving the sheet changes the pointer's local Y,
                // which then changes the next expansion sample and makes the surface shiver.
                .then(playerSheetDragModifier)
        ) {
            if (persistentBottomSheet) {
                // Draw the behavior scrim behind the sheet. The
                // stacked holder writes the raw slide offset here, so p=0 is clear and p=1 is
                // fully black. The sheet itself covers the screen at p=1; the scrim is primarily
                // visible above it during the drag and through the two rounded top corners.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = currentPlayerRevealFraction()
                        }
                        .background(Color.Black)
                )
            }
            // Backdrop and ordinary PLAYER information are one visual surface. The surface itself
            // morphs from the real MiniPlayer bounds to fullscreen; playback information stays in
            // final PLAYER-local coordinates and is revealed only where this same rounded surface
            // exists. This removes the independent screen-space content flight entirely.
            val playerSurfaceClipModifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    // One retained path mirrors a View/RenderNode clip. Reusing it avoids allocating
                    // Path/RoundRect objects on every animation frame.
                    val retainedClip = AndroidPath()
                    onDrawWithContent drawPlayerSurface@{
                        val reveal = currentPlayerRevealFraction().coerceIn(0f, 1f)
                        val shouldUseMiniPlayerClip =
                            mainPlayerTransition || currentScene == PlayerSceneController.Scene.MAIN
                        if (!shouldUseMiniPlayerClip || reveal >= 0.999f) {
                            drawContent()
                        } else {
                            val surface = resolvePlayerSurfaceHandoffBounds(
                                sourceLeft = collapsedBackdropBounds.left,
                                sourceTop = collapsedBackdropBounds.top,
                                sourceRight = collapsedBackdropBounds.right,
                                sourceBottom = collapsedBackdropBounds.bottom,
                                sourceCornerRadius = collapsedBackdropCornerRadiusPx,
                                viewportWidth = size.width,
                                viewportHeight = size.height,
                                fraction = reveal,
                            )
                            if (surface.cornerRadius > 0.5f) {
                                retainedClip.rewind()
                                retainedClip.addRoundRect(
                                    surface.left,
                                    surface.top,
                                    surface.right,
                                    surface.bottom,
                                    surface.cornerRadius,
                                    surface.cornerRadius,
                                    AndroidPath.Direction.CW,
                                )
                                val nativeCanvas = drawContext.canvas.nativeCanvas
                                val saveCount = nativeCanvas.save()
                                try {
                                    nativeCanvas.clipPath(retainedClip)
                                    this@drawPlayerSurface.drawContent()
                                } finally {
                                    nativeCanvas.restoreToCount(saveCount)
                                }
                            } else {
                                clipRect(
                                    surface.left,
                                    surface.top,
                                    surface.right,
                                    surface.bottom,
                                ) {
                                    this@drawPlayerSurface.drawContent()
                                }
                            }
                        }
                    }
                }

            Box(modifier = playerSurfaceClipModifier) {
            // Backdrop geometry is independent from the translated content sheet. At the first
            // MAIN -> PLAYER frame only the MiniPlayer rectangle owns the player background; the
            // stacked navigation area is never part of that source surface. As expansion grows,
            // reveal the exact same full-screen backdrop through a smoothly expanding clip.
            // No elevation/shadow layer is added: separation comes from the MiniPlayer geometry
            // and the existing MAIN scrim only.
            CompositionLocalProvider(
                // Both MAIN and PLAYER backgrounds are visible during the sheet handoff, so both
                // may own their draw clocks while motion is in flight. At the PLAYER endpoint the
                // library owner is frozen by AppMainLayout; at MAIN the PLAYER tree is unmounted.
                // Hidden prewarm remains the only state which must never own a frame callback.
                LocalUiFrameAnimationActive provides !prewarmOnly,
            ) {
                StandardPlayerBackdrop(
                    coverPath = coverPath,
                    artworkTransitionState = artworkTransitionState,
                    motionEnabled =
                        isPlaying ||
                            mainPlayerTransition ||
                            artworkTransitionState.isGestureActive ||
                            artworkTransitionState.isSettling,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = currentPlayerBackdropAlpha()
                        }
                )
            }

            SharedRealtimeVisualizerOverlay(
                style = LocalAudioVisualizerStyle.current,
                spectrum = audioSpectrum,
                spectrumState = audioSpectrumState,
                visible =
                    audioVisualizerEnabled &&
                        LocalAudioVisualizerStyle.current != AudioVisualizerStyle.Spectrum &&
                        !queueVisible &&
                        !overlaySuspended &&
                        !prewarmOnly &&
                        (settledPlayerVisible || settledLyricVisible || playerLyricTransition),
                isPlaying = isPlaying,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(particleVisualizerHeight)
                    .clipToBounds(),
            )

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
            // Ordinary information has a substantial sheet-like rise again, but the translation is
            // applied only inside this same PLAYER surface clip. The surface itself is therefore the
            // hard visual boundary: title/progress/controls emerge from its bottom edge instead of
            // becoming an independent layer that can fly in from the physical screen edge.
            // Keep the state read inside PlayerMainPage's graphicsLayer. Passing a sampled Float
            // here used to recompose this complete player tree for every transition frame.
            val playerSurfaceContentOffsetProvider: (() -> Float)? = if (mainPlayerTransition) {
                remember(mainPlayerExpansionState, collapsedBackdropBounds.top) {
                    {
                        resolvePlayerSurfaceContentOffsetY(
                            collapsedSurfaceTop = collapsedBackdropBounds.top,
                            fraction = mainPlayerExpansionState?.value ?: 0f,
                        )
                    }
                }
            } else {
                null
            }
            PlayerMainPage(
                currentSong = currentSong,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                isPlaying = isPlaying && (
                    currentScene == PlayerSceneController.Scene.PLAYER || playerLyricTransition
                    ),
                artworkPlaybackActive = isPlaying,
                currentPositionMs = currentPositionMs,
                currentPositionState = currentPositionState,
                totalDurationMs = totalDurationMs,
                audioVisualizerEnabled = audioVisualizerEnabled &&
                    (settledPlayerVisible || playerLyricTransition),
                audioSpectrum = audioSpectrum,
                audioSpectrumState = audioSpectrumState,
                audioVisualizerForeground = audioVisualizerForeground,
                onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                lyricPositionState = lyricPositionState,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                moreIconRes = moreIconRes,
                audioQualityIconRes = audioQualityIconRes,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                onPlaybackSpeedChange = onPlaybackSpeedChange,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                aiPerformanceState = aiPerformanceState,
                onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
                onAiPerformanceCancel = onAiPerformanceCancel,
                onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
                onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                isImmersiveEnabled = false,
                artworkCornerRadiusDp = standardPlayerArtworkCornerRadiusDp,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onAudioQuality = onAudioQuality,
                onOpenLyric = onOpenLyric,
                onArtworkLongPress = onArtworkLongPress,
                onClosePlayer = onClosePlayer,
                onCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
                onCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
                onCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
                onCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
                onCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
                mainPlayerSheetTransitionActive = mainPlayerTransition,
                mainPlayerContentOffsetProvider = playerSurfaceContentOffsetProvider,
                mainPlayerSheetTravelPx = playerSheetTravelPx,
                rootOwnsPlayerSheetDownGesture = rootOwnsMainPlayerSheetDownGesture,
                rootOwnsPlayerLyricUpGesture = true,
                onArtworkAnchorChanged = {
                    if (!prewarmOnly) {
                        playerArtworkAnchor = it
                        playerLyricsTransitionCoordinator?.updatePlayerCandidate(it)
                    }
                },
                onLyricSwipeTitleBoundsChanged = { bounds ->
                    if (!prewarmOnly && playerLyricTitleBoundsInRoot != bounds) {
                        playerLyricTitleBoundsInRoot = bounds
                    }
                },
                showAlbumArt = !mainArtworkOwnerActive && !artworkOverlayOwnsEndpoints,
                renderBackdrop = false,
                audioInfoText = audioInfoText,
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
                smartTransitionVisible = smartTransitionVisible,
                onAudioQualityLongPress = onAudioQualityLongPress,
                queueVisible = queueVisible,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onToggleQueue = onToggleQueue,
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                overlaySuspended = overlaySuspended,
                contentAlpha = if (mainPlayerTransition) 1f else currentPlayerContentAlpha(),
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .graphicsLayer {
                        val lyricMotion = if (playerLyricTransition) {
                            resolvePlayerLyricsSceneMotion(
                                session = session,
                                lyricsFraction = currentLyricsFractionForLayer(),
                            )
                        } else {
                            null
                        }
                        // reference player keeps PLAYER and Library as two LayoutRes populations in one
                        // VirtualList. PLAYER therefore owns its inverse GenericPivot on the
                        // content population itself (1.25 -> 1.0 while alpha 0 -> 1), not on the
                        // expanding surface/clip shell.  Reading the sheet fraction here keeps the
                        // vsync lane in RenderNode property updates instead of recomposing this
                        // player tree for every frame.
                        val playerReveal = if (mainPlayerTransition) {
                            currentPlayerRevealFraction().coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        val playerPopulationScale = if (mainPlayerTransition) {
                            1.25f - 0.25f * playerReveal
                        } else {
                            lyricMotion?.playerScale ?: 1f
                        }
                        alpha = if (mainPlayerTransition) {
                            playerReveal
                        } else {
                            lyricMotion?.playerAlpha ?: if (settledPlayerVisible) 1f else 0f
                        }
                        scaleX = playerPopulationScale
                        scaleY = playerPopulationScale
                        translationX = lyricMotion?.playerTranslationX ?: 0f
                        translationY = lyricMotion?.playerTranslationY ?: 0f
                        transformOrigin = if (lyricMotion?.useTopLeftTransformOrigin == true) {
                            TransformOrigin(0f, 0f)
                        } else {
                            TransformOrigin.Center
                        }
                    }
            )
            }
            }

                // Keep the full LYRIC scene mounted for PLAYER<->LYRIC geometry continuity, but
                // freeze its playback/follow owner for the entire scene transition. reference player moves
                // an already-laid-out lyrics population; it does not make the list seek/follow its
                // live clock while that population is being transformed. Waking the LazyColumn,
                // anchor correction and karaoke row motion on the first transition frame used to
                // contend with the scene animator and shared artwork handoff.
                val fullLyricSceneAwake =
                    currentScene == PlayerSceneController.Scene.LYRIC && !playerLyricTransition
                val fullLyricInteractionEnabled =
                    currentScene == PlayerSceneController.Scene.LYRIC && !playerLyricTransition
                // A new immutable transition session captures the exact current clock once. During
                // motion, positionMs stays fixed even if playback continues underneath. Once LYRIC
                // commits, the live clock is restored and the follow engine performs at most one
                // post-transition catch-up instead of competing with every animation frame.
                val frozenFullLyricPositionMs = remember(currentSong?.path, session?.id) {
                    lyricPositionMs
                }
                val fullLyricPositionMs = if (fullLyricSceneAwake) lyricPositionMs
                else frozenFullLyricPositionMs

                LyricPage(
                    currentSong = currentSong,
                    coverPath = coverPath,
                    song = lyricSong,
                    positionMs = fullLyricPositionMs,
                    isPlaying = isPlaying && fullLyricSceneAwake,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                moreIconRes = moreIconRes,
                onSeek = onLyricSeek,
                onPlayPause = onPlayPause,
                onTranslationToggle = onLyricTranslationToggle,
                onRomaToggle = onLyricRomaToggle,
                onModifyAlbumArt = onLyricModifyAlbumArt,
                onSearchLyrico = onSearchLyrico,
                onOpenInLyrico = onOpenInLyrico,
                onAiTimingPreview = onAiTimingPreview,
                onOpenExternalTimingEditor = onOpenExternalTimingEditor,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onBack = onBackToPlayer,
                interactiveHorizontalSwipe = false,
                parentOwnsHorizontalSwipe = true,
                onCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                showHeaderCover = !artworkOverlayOwnsEndpoints,
                onHeaderCoverVisibilityChange = { },
                onArtworkAnchorChanged = {
                    if (!prewarmOnly) {
                        playerLyricsTransitionCoordinator?.updateLyricCandidate(it)
                    }
                },
                artworkTransitionState = artworkTransitionState,
                renderBackdrop = false,
                contentAlpha = 1f,
                interactionEnabled = fullLyricInteractionEnabled,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(
                        when {
                            currentScene == PlayerSceneController.Scene.LYRIC -> 1f
                            playerLyricTransition -> 1f
                            else -> -1f
                        }
                    )
                    .graphicsLayer {
                        val lyricMotion = if (playerLyricTransition) {
                            resolvePlayerLyricsSceneMotion(
                                session = session,
                                lyricsFraction = currentLyricsFractionForLayer(),
                            )
                        } else {
                            null
                        }
                        alpha = lyricMotion?.lyricAlpha ?: if (settledLyricVisible) 1f else 0f
                        scaleX = lyricMotion?.lyricScale ?: 1f
                        scaleY = lyricMotion?.lyricScale ?: 1f
                        translationX = lyricMotion?.lyricTranslationX ?: 0f
                        translationY = lyricMotion?.lyricTranslationY ?: 0f
                        transformOrigin = if (lyricMotion?.useTopLeftTransformOrigin == true) {
                            TransformOrigin(0f, 0f)
                        } else {
                            TransformOrigin.Center
                        }
                    }
                )
        }

        if (mainSharedTransition) {
            val mainSourceRect = requireNotNull(source)
            val mainSourceTarget = requireNotNull(sourceCoverTarget)
            val sourceRadius = mainSourceTarget.radiusDp
            val playerRadius = playerArtworkAnchor
                ?.takeIf { it.artworkKey == coverPath }
                ?.cornerRadiusDp
                ?: standardPlayerArtworkCornerRadiusDp
            // Keep the outer shared-element envelope stable while the bitmap itself preserves its source
            // aspect ratio. This avoids a final-frame crop/pop when the player holder takes ownership.
            Box(
                modifier = Modifier
                    .zIndex(3f)
                    .offset {
                        IntOffset(
                            playerCoverRect.left.toInt(),
                            playerCoverRect.top.toInt()
                        )
                    }
                    .size(
                        width = with(density) { playerCoverRect.width().toDp() },
                        height = with(density) { playerCoverRect.height().toDp() }
                    )
                    .graphicsLayer {
                        val f = currentPlayerRevealFraction()
                        val geometryFraction = resolvePlayerArtworkHandoffFraction(f)
                        val rect = resolvePlayerArtworkHandoffRect(
                            sourceLeft = mainSourceRect.left,
                            sourceTop = mainSourceRect.top,
                            sourceWidth = mainSourceRect.width(),
                            sourceHeight = mainSourceRect.height(),
                            targetLeft = playerCoverRect.left,
                            targetTop = playerCoverRect.top,
                            targetWidth = playerCoverRect.width(),
                            targetHeight = playerCoverRect.height(),
                            fraction = f,
                        )
                        val scale = (rect.width / playerCoverRect.width()).coerceAtLeast(0.001f)
                        val localRadiusDp = lerpFloat(
                            sourceRadius,
                            playerRadius,
                            geometryFraction
                        ) / scale
                        translationX = rect.left - playerCoverRect.left
                        translationY = rect.top - playerCoverRect.top
                        this.scaleX = scale
                        this.scaleY = scale
                        // The real mini-player stays mounted at the collapsed anchor. Cross-fade
                        // ownership instead of replacing it on the first committed frame. This is
                        // especially important for the vinyl source, whose sleeve + record cannot
                        // be represented by the single shared bitmap node. On return the same curve
                        // hands the final frame back to the real mini-player with no shape pop.
                        alpha = (1f - kotlin.math.exp(-300f * f)).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0f, 0f)
                        // Keep the artwork image view elevation continuous while the shared actor owns
                        // the outer holder. Divide by the current scale so the screen-space shadow
                        // follows 0dp at MiniPlayer -> 4dp at PLAYER without a return-frame drop.
                        shadowElevation = with(density) {
                            (STANDARD_PLAYER_ARTWORK_ELEVATION_DP * geometryFraction).dp.toPx()
                        } / scale
                        clip = true
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            localRadiusDp.dp
                        )
                    }
            ) {
                val retainedImage = frozenMainSharedArtworkImage
                if (retainedImage != null) {
                    Image(
                        bitmap = retainedImage,
                        contentDescription = currentSong?.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Cold-start fallback only. Normal MAIN <-> PLAYER handoffs reuse the raster
                    // already owned by PlaybackArtworkTransition, so this path does not compete
                    // with the persistent player artwork holder for bitmap/provider ownership.
                    BitmapImage(
                        key = coverPath,
                        contentDescription = currentSong?.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        targetWidth = 1080,
                        targetHeight = 1080,
                        priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                        surface = ArtworkSurface.Playback,
                        fadeInMillis = 0,
                        holdPreviousOnKeyChange = true,
                        fadeOnBitmapChange = false,
                        showDefaultArtwork = false,
                        aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                        onSuccess = {
                            if (mainSharedTransition && mainArtworkReadyKey != coverPath) {
                                mainArtworkReadyKey = coverPath
                            }
                        },
                    )
                }
            }
        }

        if (artworkOverlayActive) {
            val playerRect = requireNotNull(sessionPlayerRect)
            val lyricRect = requireNotNull(sessionLyricRect)
            val targetScaleX = (lyricRect.width / playerRect.width).coerceAtLeast(0.001f)
            val targetScaleY = (lyricRect.height / playerRect.height).coerceAtLeast(0.001f)
            val targetTranslationX = lyricRect.left - playerRect.left
            val targetTranslationY = lyricRect.top - playerRect.top
            val playerRadiusDp = session?.playerRadiusDp ?: standardPlayerArtworkCornerRadiusDp
            val lyricRadiusDp = session?.lyricRadiusDp ?: LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP
            Box(
                modifier = Modifier
                    .zIndex(4f)
                    // Keep one measured holder for the complete transition. reference player retains the
                    // shared item and changes its matrix; changing offset+size from progress here
                    // previously forced Compose measure/layout on every animation frame.
                    .offset { IntOffset(playerRect.left.toInt(), playerRect.top.toInt()) }
                    .size(
                        width = with(density) { playerRect.width.toDp() },
                        height = with(density) { playerRect.height.toDp() }
                    )
                    .graphicsLayer {
                        val f = currentLyricsFractionForLayer()
                        val layerScaleX = lerpFloat(1f, targetScaleX, f)
                        val layerScaleY = lerpFloat(1f, targetScaleY, f)
                        scaleX = layerScaleX
                        scaleY = layerScaleY
                        translationX = lerpFloat(0f, targetTranslationX, f)
                        translationY = lerpFloat(0f, targetTranslationY, f)
                        transformOrigin = TransformOrigin(0f, 0f)
                        alpha = 1f
                        clip = true
                        // Shape is expressed in the unscaled holder's coordinates. Divide by the
                        // current scale so the visible corner radius follows the frozen endpoints.
                        val visualRadiusDp = lerpFloat(playerRadiusDp, lyricRadiusDp, f)
                        val radiusScale = maxOf(layerScaleX, layerScaleY).coerceAtLeast(0.001f)
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            (visualRadiusDp / radiusScale).dp
                        )
                    }
            ) {
                val frozenImage = frozenPlayerLyricArtworkImage
                if (frozenImage != null) {
                    Image(
                        bitmap = frozenImage,
                        contentDescription = currentSong?.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Defensive cold-start fallback only. Normal PLAYER/LYRIC sessions always have
                    // the current persistent artwork holder and therefore never request artwork here.
                    BitmapImage(
                        key = session?.artworkKey.orEmpty(),
                        contentDescription = currentSong?.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        targetWidth = 1080,
                        targetHeight = 1080,
                        priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                        surface = ArtworkSurface.Playback,
                        fadeInMillis = 0,
                        holdPreviousOnKeyChange = true,
                        fadeOnBitmapChange = false,
                        showDefaultArtwork = false,
                        aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                        onSuccess = {
                            val id = session?.id
                            if (id != null && playerLyricsTransitionCoordinator?.activeSession?.id == id) {
                                artworkOverlayRasterReadySessionId = id
                            }
                        },
                    )
                }
            }
        }
    }
}


private fun PlayerLyricsRect.toLocalPlayerLyricsRect(
    containerRootBounds: RectF?
): PlayerLyricsRect {
    val root = containerRootBounds ?: return this
    return PlayerLyricsRect(
        left = left - root.left,
        top = top - root.top,
        right = right - root.left,
        bottom = bottom - root.top
    )
}

private fun lerpFloat(start: Int, stop: Int, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun lerpRect(start: RectF, stop: RectF, fraction: Float): RectF {
    val f = fraction.coerceIn(0f, 1f)
    return RectF(
        start.left + (stop.left - start.left) * f,
        start.top + (stop.top - start.top) * f,
        start.right + (stop.right - start.right) * f,
        start.bottom + (stop.bottom - start.bottom) * f
    )
}

private fun RectF.toLocalRect(containerRootBounds: RectF?): RectF {
    val root = containerRootBounds ?: return RectF(this)
    return RectF(
        left - root.left,
        top - root.top,
        right - root.left,
        bottom - root.top
    )
}

private fun RectF.isUsableSource(widthPx: Float, heightPx: Float): Boolean {
    if (width() < 8f || height() < 8f) return false
    if (right < -widthPx || left > widthPx * 2f) return false
    if (bottom < -heightPx || top > heightPx * 2f) return false
    return true
}

private fun Modifier.prewarmPlacedOffstage(offstage: Boolean): Modifier =
    // Keep this layer node present at both endpoints. Inserting/removing a Modifier node at the
    // first PLAYER frame would recreate layer ownership even though the child composition is
    // retained. Only the RenderNode properties change.
    this.graphicsLayer {
        translationX = if (offstage) -size.width * 2f else 0f
        alpha = if (offstage) 0f else 1f
    }

private fun PlayerSceneController.Scene.toPlayerScene(): PlayerScene = when (this) {
    PlayerSceneController.Scene.MAIN -> PlayerScene.MAIN
    PlayerSceneController.Scene.PLAYER -> PlayerScene.PLAYER
    PlayerSceneController.Scene.LYRIC -> PlayerScene.LYRIC
    PlayerSceneController.Scene.QUEUE -> PlayerScene.QUEUE
    PlayerSceneController.Scene.ALBUM_DETAIL -> PlayerScene.ALBUM_DETAIL
}
