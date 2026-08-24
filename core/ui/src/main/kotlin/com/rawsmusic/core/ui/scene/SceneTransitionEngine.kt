package com.rawsmusic.core.ui.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.rawsmusic.core.ui.widget.index.AlphabetIndexOverlayRegistry
import com.rawsmusic.core.ui.widget.index.LocalAlphabetIndexOverlayRegistry
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.bitmaps.PowerListCoilArtwork
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.roundToInt

private const val NORMAL_ANIM_MS = 320
private const val POWER_LIST_COMMIT_MS = 250
private const val POWER_LIST_CANCEL_MS = 500
private const val POWER_LIST_COMMIT_PROGRESS = 0.3f
private const val POWER_LIST_VELOCITY_DP_PER_S = 500f
private const val SHARED_PAIR_WAIT_FRAMES = 6
// One frame is enough to measure the retained target. Repeated full-page warm-up frames make
// cold entry worse in Compose.
private const val SCENE_PREPARE_FRAMES = 1
private const val SETTINGS_FRAGMENT_ANIM_MS = 300
private const val EDGE_DETECT_WIDTH_DP = 24
private const val OVER_DRAG_UNIT = 0.05f
private const val POWER_LIST_SCALE_MIN = 0.5f
private const val POWER_LIST_SCALE_MAX = 1.5f

private val Decelerate2 = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val FragmentFastOutExtraSlowIn = FragmentSceneEasing
private val transitionTween = tween<Float>(NORMAL_ANIM_MS, easing = Decelerate2)
private val powerListTransitionTween = tween<Float>(
    durationMillis = POWER_LIST_COMMIT_MS,
    easing = PowerListAccelerateDecelerate
)
private val settingsFragmentTween = tween<Float>(SETTINGS_FRAGMENT_ANIM_MS, easing = FragmentFastOutExtraSlowIn)

private enum class PageMotion {
    Generic,
    FolderSharedForward,
    FolderSharedBack,
    SettingsForward,
    SettingsBack,
}

/**
 * A frame of the page transition shared with persistent chrome/background layers.
 *
 * Keep the background renderer alive while pages move above it. Exposing the same
 * normalized progress prevents the background from being committed one frame after the page.
 */
data class SceneTransitionFrame(
    val active: Boolean,
    val progress: Float,
    val fromScene: NavScene,
    val toScene: NavScene,
    val isBack: Boolean,
) {
    companion object {
        fun idle(scene: NavScene): SceneTransitionFrame = SceneTransitionFrame(
            active = false,
            progress = 1f,
            fromScene = scene,
            toScene = scene,
            isBack = false,
        )
    }
}

/**
 * Stable holder for the frame shared with persistent chrome/background layers.
 *
 * The transition animation changes only [progress]. Keeping the holder identity stable lets
 * Compose invalidate the readers of that field instead of rebuilding AppMainLayout on every
 * display frame. This is the Compose representation of a long-lived renderer state.
 */
@Stable
class SceneTransitionFrameState(initialScene: NavScene) {
    var active by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(1f)
        private set
    var fromScene by mutableStateOf(initialScene)
        private set
    var toScene by mutableStateOf(initialScene)
        private set
    var isBack by mutableStateOf(false)
        private set

    fun update(frame: SceneTransitionFrame) {
        if (active != frame.active) active = frame.active
        if (progress != frame.progress) progress = frame.progress
        if (fromScene != frame.fromScene) fromScene = frame.fromScene
        if (toScene != frame.toScene) toScene = frame.toScene
        if (isBack != frame.isBack) isBack = frame.isBack
    }
}

private val settingsScenes = setOf(
    NavScene.SETTINGS,
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.ALBUMS,
    NavScene.ALBUM_DETAIL,
    NavScene.ARTISTS,
    NavScene.ARTIST_DETAIL,
    NavScene.PLAYLISTS,
    NavScene.PLAYLIST_DETAIL,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.WEBDAV,
    NavScene.ABOUT,
    NavScene.SONG_STATS,
    NavScene.LOG_VIEWER,
    NavScene.ANALYTICS,
    NavScene.APPEARANCE,
    NavScene.PERSONALIZATION_SETTINGS,
    NavScene.AUDIO_SETTINGS,
    NavScene.ALBUM_ART_SETTINGS,
    NavScene.BASS_TREBLE_BOOST,
    NavScene.COMPRESSOR,
    NavScene.GLOBAL_FONT_SETTINGS,
    NavScene.LYRIC_FONT_SETTINGS,
    NavScene.LYRIC_MANAGEMENT,
    NavScene.PANORAMIC_360,
    NavScene.PEQ,
    NavScene.AUDIO_EFFECTS,
    NavScene.PLAYER_INTERFACE,
    NavScene.SPATIAL_SOUND,
    NavScene.STATUS_BAR_LYRIC,
    NavScene.SURROUND_360,
    NavScene.USB_DAC_SETTINGS,
    NavScene.WEBDAV_BACKUP,
)

private val homeCategoryTransitionScenes = setOf(
    NavScene.FOLDERS,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
)

private fun shouldAnimate(from: NavScene, to: NavScene): Boolean {
    return from != to
}

private fun usesSettingsFragmentMotion(from: NavScene, to: NavScene): Boolean {
    if (from in settingsScenes && to in settingsScenes) return true
    return (from == NavScene.HOME && to in homeCategoryTransitionScenes) ||
        (to == NavScene.HOME && from in homeCategoryTransitionScenes)
}

private fun usesSharedCoverMotion(from: NavScene, to: NavScene): Boolean {
    return (from == NavScene.FOLDERS && to == NavScene.FOLDER_HIERARCHY) ||
        (from == NavScene.FOLDER_HIERARCHY && to == NavScene.FOLDERS) ||
        (from == NavScene.ALBUMS && to == NavScene.ALBUM_DETAIL) ||
        (from == NavScene.ALBUM_DETAIL && to == NavScene.ALBUMS) ||
        (from == NavScene.ARTISTS && to == NavScene.ARTIST_DETAIL) ||
        (from == NavScene.ARTIST_DETAIL && to == NavScene.ARTISTS) ||
        (from == NavScene.GENRE && to == NavScene.GENRE_DETAIL) ||
        (from == NavScene.GENRE_DETAIL && to == NavScene.GENRE) ||
        (from == NavScene.YEAR && to == NavScene.YEAR_DETAIL) ||
        (from == NavScene.YEAR_DETAIL && to == NavScene.YEAR) ||
        (from == NavScene.COMPOSER && to == NavScene.COMPOSER_DETAIL) ||
        (from == NavScene.COMPOSER_DETAIL && to == NavScene.COMPOSER)
}

private fun allowsContentBackDrag(from: NavScene, to: NavScene): Boolean {
    if (from in settingsScenes || to in settingsScenes) return false
    // The source portal owns a horizontally draggable liquid bottom bar. Content-wide scene back
    // would steal the pill drag before LiquidBottomTabs can claim it, so SOURCE_IMPORT keeps only
    // the normal system edge gesture for returning to HOME.
    if (from == NavScene.SOURCE_IMPORT) return false
    return from != to
}

private val horizontalCategoryScenes = listOf(
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
)

private fun adjacentCategory(scene: NavScene, direction: Float): NavScene? {
    val index = horizontalCategoryScenes.indexOf(scene)
    if (index < 0) return null
    val targetIndex = if (direction < 0f) index + 1 else index - 1
    return horizontalCategoryScenes.getOrNull(targetIndex)
}

private fun dampProgress(progress: Float): Float {
    if (progress in 0f..1f) return progress
    val over = if (progress < 0f) -progress else 1f - progress
    val damped = ln(abs(over) / OVER_DRAG_UNIT + 1f) * OVER_DRAG_UNIT
    return if (progress < 0f) -damped else 1f + damped
}

private suspend fun awaitSharedLayouts(
    coverRegistry: SharedCoverRegistry,
    fromScene: NavScene,
    toScene: NavScene,
    transitionKey: String,
    allowRememberedTarget: Boolean = false
): Boolean {
    repeat(SHARED_PAIR_WAIT_FRAMES) {
        withFrameNanos { }
        val pairs = coverRegistry.findPairs(
            fromSceneId = fromScene.name,
            toSceneId = toScene.name,
            allowRememberedTarget = allowRememberedTarget,
            requireLiveTarget = allowRememberedTarget,
        )
        if (pairs.isNotEmpty()) {
            val (from, to) = pairs.first()
            coverRegistry.prepareTransition(transitionKey, from, to)
            return true
        }
    }
    val pairs = coverRegistry.findPairs(
        fromSceneId = fromScene.name,
        toSceneId = toScene.name,
        allowRememberedTarget = allowRememberedTarget
    )
    val pair = pairs.firstOrNull() ?: return false
    val (from, to) = pair
    coverRegistry.prepareTransition(transitionKey, from, to)
    return true
}

@Composable
fun SceneTransitionHost(
    state: NavigationState,
    modifier: Modifier = Modifier,
    prewarmScenes: List<NavScene> = emptyList(),
    horizontalGestureExclusionBounds: Rect? = null,
    onTransitionActiveChanged: (Boolean) -> Unit = {},
    onTransitionFrameChanged: (SceneTransitionFrame) -> Unit = {},
    content: @Composable (NavScene) -> Unit
) {
    val scope = rememberCoroutineScope()
    val animProgress = remember { Animatable(0f) }
    var displayedScene by remember { mutableStateOf(state.currentScene) }
    var fromScene by remember { mutableStateOf(state.currentScene) }
    var retainedScene by remember { mutableStateOf<NavScene?>(null) }
    var preparingScene by remember { mutableStateOf<NavScene?>(null) }
    var isAnimating by remember { mutableStateOf(false) }
    var isGestureActive by remember { mutableStateOf(false) }
    var isBackTransition by remember { mutableStateOf(false) }
    var usesDirectionalBackPivot by remember { mutableStateOf(false) }
    var pageMotion by remember { mutableStateOf(PageMotion.Generic) }
    var hostPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var hostPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val alphabetIndexAlpha = remember { Animatable(1f) }
    val topMenuAlpha = remember { Animatable(1f) }
    var screenWidthPx by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableFloatStateOf(0f) }
    val prevSceneRef = remember { mutableStateOf(state.currentScene) }
    val sharedCoverRegistry = remember { SharedCoverRegistry() }
    val alphabetIndexOverlayRegistry = remember { AlphabetIndexOverlayRegistry() }

    // 共享元素专用 from/to（独立于渲染用的 fromScene/displayedScene）
    var sharedFromScene by remember { mutableStateOf(state.currentScene) }
    var sharedToScene by remember { mutableStateOf(state.currentScene) }
    val movableSceneContent = remember { mutableMapOf<NavScene, @Composable () -> Unit>() }

    @Composable
    fun SceneContent(scene: NavScene) {
        val movable = movableSceneContent.getOrPut(scene) {
            movableContentOf {
                content(scene)
            }
        }
        movable()
    }

    LaunchedEffect(state.currentScene) {
        val newScene = state.currentScene
        val oldScene = prevSceneRef.value
        if (newScene == oldScene) return@LaunchedEffect
        prevSceneRef.value = newScene

        if (isGestureActive || state.isDraggingBack) {
            isGestureActive = false
            animProgress.snapTo(0f)
        }

        if (!shouldAnimate(oldScene, newScene)) {
            displayedScene = newScene
            retainedScene = null
            preparingScene = null
            return@LaunchedEffect
        }

        fromScene = oldScene
        isBackTransition = false
        usesDirectionalBackPivot = false
        alphabetIndexAlpha.snapTo(1f)
        topMenuAlpha.snapTo(1f)
        sharedFromScene = oldScene
        sharedToScene = newScene
        pageMotion = when {
            state.navigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            usesSharedCoverMotion(oldScene, newScene) -> PageMotion.FolderSharedForward
            usesSettingsFragmentMotion(oldScene, newScene) -> PageMotion.SettingsForward
            else -> PageMotion.Generic
        }

        // Register the destination endpoints while the prepared page is still laid out at its
        // final, untransformed geometry. Capturing them after the destination root is scaled for
        // entry records layer-space coordinates and can send the shared visual outside the host.
        if (retainedScene != newScene) {
            preparingScene = newScene
            repeat(SCENE_PREPARE_FRAMES) {
                withFrameNanos { }
            }
        }
        isAnimating = true
        animProgress.snapTo(1f)
        displayedScene = newScene
        if (pageMotion == PageMotion.FolderSharedForward) {
            val prepared = awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                fromScene = oldScene,
                toScene = newScene,
                transitionKey = "${oldScene.name}->${newScene.name}"
            )
            if (!prepared) pageMotion = PageMotion.Generic
        } else {
            withFrameNanos { }
        }
        animProgress.animateTo(
            0f,
            when (pageMotion) {
                PageMotion.FolderSharedForward -> powerListTransitionTween
                PageMotion.SettingsForward -> settingsFragmentTween
                else -> transitionTween
            }
        )
        if (pageMotion == PageMotion.FolderSharedForward) {
            // Drop the page transform while the prepared shared owner is still covering the
            // target. The next frame can then hand the pixels back without exposing a stale
            // layout or running the cover through two coordinate systems.
            fromScene = displayedScene
            withFrameNanos { }
        }
        isAnimating = false
        retainedScene = oldScene
        preparingScene = null
        isBackTransition = false
        usesDirectionalBackPivot = false
    }

    LaunchedEffect(state.isAnimatingBack) {
        if (!state.isAnimatingBack) return@LaunchedEffect
        val targetScene = state.getPreviousScene() ?: run {
            state.completeAnimatingBack()
            return@LaunchedEffect
        }

        fromScene = targetScene
        isBackTransition = true
        usesDirectionalBackPivot = false
        sharedFromScene = state.currentScene
        sharedToScene = targetScene
        val sharedReturnSourceCaptured = usesSharedCoverMotion(state.currentScene, targetScene) &&
            sharedCoverRegistry.captureLiveCollectionReturnSource(
                fromSceneId = state.currentScene.name,
                toSceneId = targetScene.name,
            )
        pageMotion = when {
            state.backNavigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            sharedReturnSourceCaptured -> PageMotion.FolderSharedBack
            usesSettingsFragmentMotion(state.currentScene, targetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        // Resolve the retained source/target holders before exposing the transition layer. If the
        // detail list has moved, showing page transforms while the shared pair is still being
        // measured produces a full-screen preparation flash.
        if (pageMotion == PageMotion.FolderSharedBack) {
            val prepared = awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                fromScene = state.currentScene,
                toScene = targetScene,
                transitionKey = "${state.currentScene.name}->${targetScene.name}",
                allowRememberedTarget = true
            )
            if (!prepared) pageMotion = PageMotion.Generic
        } else {
            withFrameNanos { }
        }
        animProgress.snapTo(0f)
        isAnimating = true
        animProgress.animateTo(
            1f,
            when (pageMotion) {
                PageMotion.FolderSharedBack -> powerListTransitionTween
                PageMotion.SettingsBack -> settingsFragmentTween
                else -> transitionTween
            }
        )
        prevSceneRef.value = targetScene
        displayedScene = targetScene
        retainedScene = state.currentScene
        preparingScene = null
        // Restore the real target only after it has produced one frame at its final geometry.
        // This mirrors the retained-holder handoff used by the reference implementation and
        // prevents the shared layer from handing off to a still-transformed target.
        withFrameNanos { }
        isAnimating = false
        state.completeAnimatingBack()
        animProgress.snapTo(0f)
        alphabetIndexAlpha.snapTo(1f)
        topMenuAlpha.snapTo(1f)
        isBackTransition = false
    }

    LaunchedEffect(state.isDraggingBack) {
        if (!state.isDraggingBack) return@LaunchedEffect
        // Category/sibling gestures can target a scene that is not present in the
        // navigation back stack (notably a restored Music Library root).
        val targetScene = state.backPreviewScene ?: state.getPreviousScene()
            ?: return@LaunchedEffect
        fromScene = targetScene
        isBackTransition = true
        usesDirectionalBackPivot = true
        alphabetIndexAlpha.snapTo(1f)
        topMenuAlpha.snapTo(1f)
        sharedFromScene = state.currentScene
        sharedToScene = targetScene
        val sharedReturnSourceCaptured = usesSharedCoverMotion(state.currentScene, targetScene) &&
            sharedCoverRegistry.captureLiveCollectionReturnSource(
                fromSceneId = state.currentScene.name,
                toSceneId = targetScene.name,
            )
        pageMotion = when {
            state.backNavigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            sharedReturnSourceCaptured -> PageMotion.FolderSharedBack
            usesSettingsFragmentMotion(state.currentScene, targetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        isGestureActive = true
        animProgress.snapTo(0f)
        if (pageMotion == PageMotion.FolderSharedBack) {
            val prepared = awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                fromScene = state.currentScene,
                toScene = targetScene,
                transitionKey = "${state.currentScene.name}->${targetScene.name}",
                allowRememberedTarget = true
            )
            if (!prepared) pageMotion = PageMotion.Generic
        } else {
            withFrameNanos { }
        }
    }

    LaunchedEffect(state.isDraggingBack, isGestureActive) {
        if (!state.isDraggingBack || !isGestureActive) return@LaunchedEffect
        snapshotFlow { state.dragBackProgress }
            .collect { progress ->
                animProgress.snapTo(dampProgress(progress))
            }
    }

    LaunchedEffect(state.dragBackReleaseToken) {
        if (state.dragBackReleaseToken == 0) return@LaunchedEffect
        val start = dampProgress(state.dragBackReleaseProgress).coerceIn(0f, 1f)
        val commit = state.dragBackReleaseCommit
        val target = if (commit) 1f else 0f
        val sharedPowerListSettle = pageMotion == PageMotion.FolderSharedBack
        if (sharedPowerListSettle) {
            val targetScene = state.backPreviewScene ?: state.getPreviousScene()
            val transitionKey = targetScene?.let { "${state.currentScene.name}->${it.name}" }.orEmpty()
            if (targetScene != null && !sharedCoverRegistry.isPrepared(transitionKey)) {
                val prepared = awaitSharedLayouts(
                    coverRegistry = sharedCoverRegistry,
                    fromScene = state.currentScene,
                    toScene = targetScene,
                    transitionKey = transitionKey,
                    allowRememberedTarget = true
                )
                if (!prepared) pageMotion = PageMotion.Generic
            }
        }
        val normalizedVelocity = state.dragBackReleaseVelocity / screenWidthPx.coerceAtLeast(1f)
        val carryVelocity = if (commit) {
            start > 0.8f && normalizedVelocity > 0f
        } else {
            start < 0.2f && normalizedVelocity < 0f
        }
        val duration = when {
            carryVelocity && commit -> {
                val velocity = normalizedVelocity.coerceIn(4f, 8f)
                (((1f - start) / velocity) * 1000f).roundToInt().coerceAtLeast(1)
            }
            carryVelocity -> {
                val velocity = normalizedVelocity.coerceIn(-8f, -3.5f)
                ((start / -velocity) * 1000f).roundToInt().coerceAtLeast(1)
            }
            commit -> {
                ((1f - start) * POWER_LIST_COMMIT_MS).roundToInt().coerceAtLeast(100)
            }
            else -> {
                // PowerList keeps at least one third of its 500 ms cancellation duration.
                // This prevents a short rejected gesture from snapping back in one frame.
                (start * POWER_LIST_CANCEL_MS).roundToInt()
                    .coerceAtLeast(POWER_LIST_CANCEL_MS / 3)
            }
        }
        val settleEasing = when {
            carryVelocity -> LinearEasing
            else -> PowerListAccelerateDecelerate
        }
        isAnimating = true
        animProgress.snapTo(start)
        animProgress.animateTo(target, tween(duration, easing = settleEasing))
        if (commit) {
            val targetScene = fromScene
            val previousDisplayedScene = displayedScene
            prevSceneRef.value = targetScene
            displayedScene = targetScene
            retainedScene = previousDisplayedScene
            // Keep the shared owner at the endpoint while the target holder settles into its
            // untransformed layout. Releasing both in the same frame causes a visible jump.
            withFrameNanos { }
        }
        preparingScene = null
        state.completeBackDrag(commit)
        isGestureActive = false
        isAnimating = false
        animProgress.snapTo(0f)
        alphabetIndexAlpha.snapTo(1f)
        topMenuAlpha.snapTo(1f)
        isBackTransition = false
        usesDirectionalBackPivot = false
    }

    val gestureModifier = Modifier.pointerInput(
        state.canNavigateBack(),
        state.currentScene,
        horizontalGestureExclusionBounds,
        hostPositionInRoot
    ) {
        val canNavigateBack = state.canNavigateBack()
        val canNavigateCategory = state.currentScene in horizontalCategoryScenes
        if (!canNavigateBack && !canNavigateCategory) return@pointerInput
        val edgeWidthPx = EDGE_DETECT_WIDTH_DP.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            if (isAnimating || state.isDraggingBack) {
                return@awaitEachGesture
            }
            // The mini-player owns horizontal track switching. Do not let the parent scene
            // interceptor observe the same pointer sequence and start a page transition. The
            // mini-player bounds are reported in root coordinates, while this pointer node may
            // be translated during a scene transition, so normalize the down point first.
            val downInRoot = Offset(
                x = down.position.x + hostPositionInRoot.x,
                y = down.position.y + hostPositionInRoot.y,
            )
            val miniPlayerBounds = horizontalGestureExclusionBounds
            val expandedMiniPlayerBounds = miniPlayerBounds?.let { bounds ->
                Rect(
                    // The mini player owns the whole horizontal pointer sequence, not just
                    // the visible card. Cover the complete scene width over its vertical band
                    // so an edge-origin swipe cannot be re-captured by the parent host after
                    // the card's horizontal padding or rounded corners are crossed.
                    left = minOf(bounds.left - edgeWidthPx, hostPositionInRoot.x),
                    top = bounds.top - edgeWidthPx,
                    right = maxOf(
                        bounds.right + edgeWidthPx,
                        hostPositionInRoot.x + size.width.toFloat()
                    ),
                    bottom = bounds.bottom + edgeWidthPx,
                )
            }
            if (expandedMiniPlayerBounds?.contains(downInRoot) == true) {
                return@awaitEachGesture
            }
            val localWidthPx = size.width.toFloat().coerceAtLeast(1f)
            val prev = state.getPreviousScene()
            val categoryScene = state.currentScene in horizontalCategoryScenes
            val sharedPowerListBack = prev?.let { usesSharedCoverMotion(state.currentScene, it) } == true
            val allowContentDrag = prev?.let { allowsContentBackDrag(state.currentScene, it) }
                ?: canNavigateCategory
            var direction = when {
                down.position.x <= edgeWidthPx -> 1f
                down.position.x >= localWidthPx - edgeWidthPx -> -1f
                else -> 0f
            }
            if (!allowContentDrag && direction == 0f) return@awaitEachGesture

            var gestureDecided = false
            var dragging = false
            var startX = down.position.x
            var effectiveStartX = startX
            var startY = down.position.y
            var lastX = startX
            var lastTime = down.uptimeMillis
            var velocityX = 0f
            var rawProgress = 0f
            if (prev != null) fromScene = prev
            var categoryTarget: NavScene? = null

            while (true) {
                // Observe before PowerList/LazyColumn consumes the stream. We still leave vertical
                // gestures untouched and only consume after horizontal intent is established.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id }
                if (change == null) {
                    break
                }
                if (!change.pressed) {
                    break
                }

                val dx = change.position.x - startX
                val dy = change.position.y - startY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (!gestureDecided && (absDx > touchSlop || absDy > touchSlop)) {
                    gestureDecided = true
                    if (direction == 0f) {
                        direction = if (dx < 0f) -1f else 1f
                    }
                    dragging = absDx > absDy * 1.15f && (allowContentDrag || dx * direction > 0f)
                    if (!dragging) return@awaitEachGesture
                    // A full-width horizontal gesture inside a category switches to its sibling.
                    // Keep the system-style edge gesture reserved for returning to the parent.
                    val isEdgeBack = direction > 0f && startX <= edgeWidthPx
                    if (categoryScene && !isEdgeBack) {
                        categoryTarget = adjacentCategory(state.currentScene, direction)
                        if (categoryTarget != null) {
                            fromScene = categoryTarget
                            val started = state.startSiblingDrag(categoryTarget, direction)
                            if (!started) return@awaitEachGesture
                        } else if (state.currentScene == NavScene.SONGS && direction > 0f) {
                            // SONGS may be restored as the root entry, so a previous stack item is
                            // not guaranteed. Its rightward gesture still has an explicit HOME target.
                            categoryTarget = NavScene.HOME
                            fromScene = NavScene.HOME
                            val started = state.startSiblingDrag(NavScene.HOME, direction)
                            if (!started) {
                                return@awaitEachGesture
                            }
                        } else if (prev != null && direction > 0f) {
                            fromScene = prev
                            if (!state.startBackDrag(direction)) return@awaitEachGesture
                        } else {
                            return@awaitEachGesture
                        }
                        isGestureActive = true
                    } else if (prev != null) {
                        if (!state.startBackDrag(direction)) return@awaitEachGesture
                        isGestureActive = true
                    } else {
                        categoryTarget = adjacentCategory(state.currentScene, direction)
                        if (categoryTarget == null) return@awaitEachGesture
                        fromScene = categoryTarget
                        if (!state.startSiblingDrag(categoryTarget, direction)) return@awaitEachGesture
                        isGestureActive = true
                    }
                    // Remove touch slop from the captured origin. The first visual frame
                    // therefore starts at exactly zero instead of jumping by the recognition delta.
                    effectiveStartX = startX + direction * touchSlop
                    change.consume()
                }

                if (dragging) {
                    val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    velocityX = (change.position.x - lastX) / dt * 1000f
                    lastX = change.position.x
                    lastTime = change.uptimeMillis
                    val effectiveDx = change.position.x - effectiveStartX
                    rawProgress = if (localWidthPx > 0f) {
                        (effectiveDx * direction) / localWidthPx
                    } else {
                        0f
                    }
                    state.updateBackDrag(rawProgress)
                    change.consume()
                }
            }

                if (dragging) {
                    val signedVelocity = velocityX * direction
                    val releaseProgress = rawProgress.coerceIn(0f, 1f)
                    val velocityDpPerSecond = signedVelocity / density
                    val commit = if (abs(velocityDpPerSecond) >= POWER_LIST_VELOCITY_DP_PER_S) {
                        velocityDpPerSecond > 0f
                    } else {
                        releaseProgress > POWER_LIST_COMMIT_PROGRESS
                    }
                    if (categoryTarget != null) {
                        state.releaseBackDrag(commit = commit, velocity = signedVelocity)
                    } else if (prev != null) {
                        state.releaseBackDrag(commit = commit, velocity = signedVelocity)
                    }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                hostPositionInRoot = coordinates.positionInRoot()
                hostPositionInWindow = coordinates.positionInWindow()
            }
            .then(gestureModifier)
    ) {
        Layout(
            content = {},
            modifier = Modifier.fillMaxSize(),
            measurePolicy = { _, constraints ->
                screenWidthPx = constraints.maxWidth.toFloat()
                screenHeightPx = constraints.maxHeight.toFloat()
                layout(0, 0) {}
            }
        )

        val progress = animProgress.value.coerceIn(0f, 1f)
        val inTransition = isAnimating || isGestureActive || state.isDraggingBack || progress > 0f
        LaunchedEffect(inTransition) {
            onTransitionActiveChanged(inTransition)
        }

        // Publish the same frame used to render both page slots. The background host must not
        // infer transition state from NavigationState after the page has already committed.
        val backgroundFromScene = if (inTransition && isBackTransition) {
            displayedScene
        } else {
            fromScene
        }
        val backgroundToScene = if (inTransition && isBackTransition) {
            fromScene
        } else {
            displayedScene
        }
        SideEffect {
            onTransitionFrameChanged(
                SceneTransitionFrame(
                    active = inTransition,
                    progress = if (isBackTransition) progress else 1f - progress,
                    fromScene = backgroundFromScene,
                    toScene = backgroundToScene,
                    isBack = isBackTransition,
                )
            )
        }

        // 按方向计算共享进度：forward progress 1→0 需反转，back/gesture progress 0→1 直接用
        val sharedProgress = when (pageMotion) {
            PageMotion.SettingsForward -> 1f - progress
            PageMotion.SettingsBack -> progress
            PageMotion.FolderSharedForward -> 1f - progress
            PageMotion.FolderSharedBack -> progress
            PageMotion.Generic -> {
                if (isBackTransition) {
                    progress
                } else {
                    1f - progress
                }
            }
        }.coerceIn(0f, 1f)

        val sharedActive = (inTransition || preparingScene != null) &&
            sharedFromScene != sharedToScene
        val transitionKey = "${sharedFromScene.name}->${sharedToScene.name}"
        if (!sharedActive) {
            sharedCoverRegistry.clearFrozen()
        }

        val sharedSpec = SharedTransitionSpec(
            active = sharedActive,
            fromSceneId = sharedFromScene.name,
            toSceneId = sharedToScene.name,
            activeSceneId = displayedScene.name,
            progress = sharedProgress,
            transitionKey = transitionKey,
            allowRememberedTarget = pageMotion == PageMotion.FolderSharedBack,
            sharedCoverOverlayOwnsAnchor = sharedActive &&
                (pageMotion == PageMotion.FolderSharedForward ||
                    pageMotion == PageMotion.FolderSharedBack)
        )
        val preparedSharedPair = sharedCoverRegistry.getPreparedPair(transitionKey)
        val pageTransform = if (inTransition && fromScene != displayedScene) {
            if ((pageMotion == PageMotion.FolderSharedForward ||
                    pageMotion == PageMotion.FolderSharedBack) && preparedSharedPair == null
            ) {
                sharedPreparationTransform(pageMotion)
            } else {
                pageTransforms(
                    motion = pageMotion,
                    progress = progress,
                    directionalBack = usesDirectionalBackPivot,
                    backDirection = state.dragBackDirection,
                    viewportWidth = screenWidthPx,
                    viewportHeight = screenHeightPx,
                    hostPositionInWindow = hostPositionInWindow,
                    sharedPair = preparedSharedPair,
                )
            }
        } else {
            null
        }
        val detachAlphabetIndex =
            usesDirectionalBackPivot && fromScene == NavScene.HOME
        val topMenuGestureAlpha = if (detachAlphabetIndex) {
            (1f - progress / 0.2f).coerceIn(0f, 1f)
        } else {
            1f
        }
        val alphabetGestureAlpha = if (detachAlphabetIndex) {
            ((1f - progress) / 0.2f).coerceIn(0f, 1f)
        } else {
            1f
        }
        // Keep the action host mounted and move it with the same scene progress. This mirrors
        // the reference player's anchored toolbar: the icons do not get recreated or inserted
        // into the page while a predictive gesture is in flight.
        val topMenuTranslationProgress = if (inTransition && fromScene != displayedScene) {
            val direction = if (isBackTransition) 1f else -1f
            direction * progress
        } else {
            0f
        }

        CompositionLocalProvider(
            LocalSharedCoverRegistry provides sharedCoverRegistry,
            LocalAlphabetIndexOverlayRegistry provides alphabetIndexOverlayRegistry,
            LocalSharedTransitionSpec provides sharedSpec,
            LocalSceneChromeAlpha provides SceneChromeAlpha(
                alphabetIndex = alphabetIndexAlpha.value * alphabetGestureAlpha,
                topMenu = topMenuAlpha.value * topMenuGestureAlpha,
                topMenuTranslationProgress = topMenuTranslationProgress,
                detachAlphabetIndex = detachAlphabetIndex,
            ),
        ) {
            if (inTransition && fromScene != displayedScene) {
                val transform = checkNotNull(pageTransform)

                // 来源页面（下层）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = transform.fromTranslationX
                            translationY = transform.fromTranslationY
                            scaleX = transform.fromScale
                            scaleY = transform.fromScale
                            alpha = transform.fromAlpha
                            // Keep the page on its render node instead of allocating a full-screen
                            // offscreen buffer for alpha composition.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            transformOrigin = transform.fromTransformOrigin
                        }
                ) {
                    CompositionLocalProvider(
                        LocalSceneBackgroundFrozen provides false
                    ) {
                        SceneContent(fromScene)
                    }
                }

                // 当前页面（上层）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = transform.currentTranslationX
                            translationY = transform.currentTranslationY
                            scaleX = transform.currentScale
                            scaleY = transform.currentScale
                            alpha = transform.currentAlpha
                            // Keep the page transition on the render node. Offscreen here would
                            // rasterize both full pages and is the main cold-entry cost.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            transformOrigin = transform.currentTransformOrigin
                        }
                ) {
                    CompositionLocalProvider(
                        LocalSceneBackgroundFrozen provides false
                    ) {
                        SceneContent(displayedScene)
                    }
                }

            } else {
                SceneContent(displayedScene)

                // Retain exactly one fully measured spare scene alongside the two-slot
                // list host. It stays outside drawing/input while remaining ready for a smooth
                // reverse transition. A newly requested scene temporarily occupies this slot
                // during its preparation frame.
                val spareScene = preparingScene ?: retainedScene
                if (spareScene != null && spareScene != displayedScene) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val isPreparing = preparingScene != null
                                // Compose still composes and measures an alpha-zero layer, but
                                // the renderer skips its pixels. This warms layout without the
                                // full-screen Offscreen buffer that caused cold-entry jank.
                                alpha = 0f
                                translationX = if (isPreparing) {
                                    0f
                                } else {
                                    screenWidthPx.coerceAtLeast(1f) * 2f
                                }
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                    ) {
                        CompositionLocalProvider(
                            LocalSceneBackgroundFrozen provides true
                        ) {
                            SceneContent(spareScene)
                        }
                    }
                }
            }

            prewarmScenes.forEach { scene ->
                val participatesInTransition =
                    inTransition && (scene == displayedScene || scene == fromScene)
                val alreadyRetained = scene == (preparingScene ?: retainedScene)
                if (scene != displayedScene && !participatesInTransition && !alreadyRetained) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = 0.001f
                                translationX = screenWidthPx.coerceAtLeast(1f) * 2f
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                    ) {
                        SceneContent(scene)
                    }
                }
            }

            // 共享封面 overlay：文件夹/专辑/艺术家列表 <-> 对应详情页使用
            val sharedCoverOverlayActive = sharedActive &&
                (pageMotion == PageMotion.FolderSharedForward || pageMotion == PageMotion.FolderSharedBack)
            if (sharedCoverOverlayActive) {
                SharedCoverOverlay(
                    registry = sharedCoverRegistry,
                    spec = sharedSpec,
                    hostPositionInWindow = hostPositionInWindow,
                    viewportWidth = screenWidthPx,
                )
            }

            alphabetIndexOverlayRegistry.entry?.takeIf { detachAlphabetIndex }?.let { entry ->
                RawAlphabetIndex(
                    data = entry.data,
                    modifier = entry.modifier,
                    enabled = entry.enabled,
                    minCellHeightDp = entry.minCellHeightDp,
                    onTopSelect = entry.onTopSelect,
                    onSelect = entry.onSelect,
                    allowSceneOverlay = false,
                )
            }
        }
    }
}

private data class PageTransform(
    val fromScale: Float,
    val fromAlpha: Float,
    val fromTranslationX: Float,
    val fromTranslationY: Float = 0f,
    val fromTransformOrigin: TransformOrigin = TransformOrigin.Center,
    val currentScale: Float,
    val currentAlpha: Float,
    val currentTranslationX: Float,
    val currentTranslationY: Float = 0f,
    val currentTransformOrigin: TransformOrigin = TransformOrigin.Center,
)

private fun pageTransforms(
    motion: PageMotion,
    progress: Float,
    directionalBack: Boolean,
    backDirection: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    hostPositionInWindow: Offset,
    sharedPair: Pair<SharedCoverSnapshot, SharedCoverSnapshot>?,
): PageTransform {
    val directionalPivot = if (backDirection >= 0f) {
        TransformOrigin(1.5f, 0.5f)
    } else {
        TransformOrigin(-0.5f, 0.5f)
    }
    return when (motion) {
        PageMotion.SettingsForward -> {
            val elapsed = 1f - progress
            PageTransform(
                fromScale = lerp(1f, POWER_LIST_SCALE_MAX, elapsed),
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                currentScale = lerp(POWER_LIST_SCALE_MIN, 1f, elapsed),
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = 0f,
            )
        }

        PageMotion.FolderSharedForward -> {
            val elapsed = 1f - progress
            val pair = sharedPair
            val sourcePivot = pair?.first?.boundsInWindow?.toPowerListItemPivot(
                hostPositionInWindow = hostPositionInWindow,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                horizontalOffsetFraction = 0f,
            ) ?: TransformOrigin.Center
            PageTransform(
                // Item -> header: the source list contracts around the selected item's bottom
                // edge while the header list expands from its viewport centre.
                fromScale = lerp(1f, POWER_LIST_SCALE_MIN, elapsed),
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = sourcePivot,
                currentScale = lerp(POWER_LIST_SCALE_MAX, 1f, elapsed),
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = TransformOrigin.Center,
            )
        }

        PageMotion.FolderSharedBack -> {
            val elapsed = progress
            val pair = sharedPair
            val horizontalPivotOffset = if (directionalBack) {
                if (backDirection >= 0f) 1f else -1f
            } else {
                0f
            }
            val targetPivot = pair?.second?.boundsInWindow?.toPowerListItemPivot(
                hostPositionInWindow = hostPositionInWindow,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                horizontalOffsetFraction = horizontalPivotOffset,
            ) ?: TransformOrigin(
                pivotFractionX = 0.5f + horizontalPivotOffset,
                pivotFractionY = 0.5f,
            )
            val headerPivot = TransformOrigin(
                pivotFractionX = 0.5f + horizontalPivotOffset,
                pivotFractionY = 0.5f,
            )
            PageTransform(
                // Header -> item uses the same two layout engines in reverse. The destination
                // list is centred on the exact target item, not on a copied screen coordinate.
                fromScale = lerp(POWER_LIST_SCALE_MAX, 1f, elapsed),
                fromAlpha = lerp(0f, 1f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = targetPivot,
                currentScale = lerp(1f, POWER_LIST_SCALE_MIN, elapsed),
                currentAlpha = lerp(1f, 0f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = headerPivot,
            )
        }

        PageMotion.SettingsBack -> {
            val elapsed = progress
            PageTransform(
                fromScale = lerp(POWER_LIST_SCALE_MAX, 1f, elapsed),
                fromAlpha = lerp(0f, 1f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
                currentScale = lerp(1f, POWER_LIST_SCALE_MIN, elapsed),
                currentAlpha = lerp(1f, 0f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
            )
        }

        PageMotion.Generic -> PageTransform(
            fromScale = lerp(POWER_LIST_SCALE_MAX, 1f, progress),
            fromAlpha = progress,
            fromTranslationX = 0f,
            fromTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
            currentScale = lerp(1f, POWER_LIST_SCALE_MIN, progress),
            currentAlpha = 1f - progress,
            currentTranslationX = 0f,
            currentTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
        )
    }
}

private fun sharedPreparationTransform(motion: PageMotion): PageTransform {
    return when (motion) {
        PageMotion.FolderSharedForward -> PageTransform(
            fromScale = 1f,
            fromAlpha = 1f,
            fromTranslationX = 0f,
            currentScale = 1f,
            currentAlpha = 0f,
            currentTranslationX = 0f,
        )
        PageMotion.FolderSharedBack -> PageTransform(
            fromScale = 1f,
            fromAlpha = 0f,
            fromTranslationX = 0f,
            currentScale = 1f,
            currentAlpha = 1f,
            currentTranslationX = 0f,
        )
        else -> error("Only shared collection transitions have a preparation frame")
    }
}

private fun Rect.toPowerListItemPivot(
    hostPositionInWindow: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    horizontalOffsetFraction: Float,
): TransformOrigin {
    // PowerList's native layout engine uses viewportCenter +
    // (-viewportHeight / 2 + itemTop + itemHeight), which resolves to item.bottom.
    // Its swipe variant shifts the horizontal pivot by exactly one viewport width.
    val localBottom = bottom - hostPositionInWindow.y
    return TransformOrigin(
        pivotFractionX = 0.5f + horizontalOffsetFraction,
        pivotFractionY = localBottom / viewportHeight.coerceAtLeast(1f),
    )
}

/**
 * 共享封面 overlay。
 * 在 SceneTransitionHost 根层绘制，不受来源页/目标页的 alpha 影响。
 * 从 SharedCoverRegistry 读取两端 bounds/radius，插值后用 CrossfadeAlbumArt 渲染。
 */
@Composable
private fun SharedCoverOverlay(
    registry: SharedCoverRegistry,
    spec: SharedTransitionSpec,
    hostPositionInWindow: Offset,
    viewportWidth: Float,
) {
    if (!spec.active) return

    val context = LocalContext.current
    val density = LocalDensity.current
    var overlayOrigin by remember {
        mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayOrigin = coordinates.positionInWindow()
            }
    ) {
        val preparedPair = registry.getPreparedPair(spec.transitionKey) ?: return@Box
        with(preparedPair) {
            val from = first
            val to = second
            val progress = spec.progress.coerceIn(0f, 1f)

            val rawFrom = from.boundsInWindow
            val sourceWidthPx = rawFrom.width.coerceAtLeast(1f)
            val sourceHeightPx = rawFrom.height.coerceAtLeast(1f)
            val viewportCenterX = hostPositionInWindow.x + viewportWidth / 2f
            // A detail header is a viewport-level hero. Keep its measured vertical position,
            // but correct its horizontal centre to the host just like the promoted PowerList
            // holder. This also neutralises stale window-inset/retained-layer X offsets.
            val fromRect = if (viewportWidth > 0f && rawFrom.width >= viewportWidth * 0.5f) {
                Rect(
                    left = viewportCenterX - rawFrom.width / 2f,
                    top = rawFrom.top,
                    right = viewportCenterX + rawFrom.width / 2f,
                    bottom = rawFrom.bottom,
                )
            } else {
                rawFrom
            }
            val toRect = to.boundsInWindow
            // PowerList interpolates the promoted holder's complete LayoutRes rectangle. Edge
            // interpolation preserves its centre and independent width/height exactly; using a
            // single width-derived scale is what previously sent the large cover off course.
            val currentLeft = lerp(fromRect.left, toRect.left, progress)
            val currentTop = lerp(fromRect.top, toRect.top, progress)
            val currentRight = lerp(fromRect.right, toRect.right, progress)
            val currentBottom = lerp(fromRect.bottom, toRect.bottom, progress)
            val scaleX = ((currentRight - currentLeft) / sourceWidthPx).coerceAtLeast(0.001f)
            val scaleY = ((currentBottom - currentTop) / sourceHeightPx).coerceAtLeast(0.001f)
            val leftPx = currentLeft - overlayOrigin.x
            val topPx = currentTop - overlayOrigin.y
            val radiusDp = lerp(from.radiusDp, to.radiusDp, progress)
            val coverKey = from.coverKey.ifBlank { to.coverKey }
            if (coverKey.isBlank()) return@with
            val promotedBitmap = remember(spec.transitionKey, coverKey) {
                PowerListCoilArtwork.peekBitmap(
                    context = context,
                    key = coverKey,
                    width = sourceWidthPx.roundToInt().coerceAtLeast(1),
                    height = sourceHeightPx.roundToInt().coerceAtLeast(1)
                )
            }
            val promotedImage = remember(promotedBitmap) {
                promotedBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
            }
            val imageModifier = Modifier
                .requiredSize(
                    width = with(density) { sourceWidthPx.toDp() },
                    height = with(density) { sourceHeightPx.toDp() }
                )
                .graphicsLayer {
                    translationX = leftPx
                    translationY = topPx
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                    transformOrigin = TransformOrigin(0f, 0f)
                    alpha = 1f
                    clip = true
                    // Shape is evaluated before the layer transform. Compensate each axis so
                    // the visible radius follows the same LayoutRes interpolation.
                    shape = RoundedCornerShape((radiusDp / minOf(scaleX, scaleY)).dp)
                }

            if (promotedImage != null) {
                Image(
                    bitmap = promotedImage,
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High
                )
            } else {
                com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt(
                    key = coverKey,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop,
                    showPlaceholder = false,
                    fadeMillis = 0,
                    freezeBitmapUpdates = true
                )
            }
        }
    }
}

private object FragmentSceneEasing : Easing {
    override fun transform(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        val segment = if (x <= 0.166666f) firstSegment else secondSegment
        var low = 0f
        var high = 1f
        repeat(14) {
            val mid = (low + high) * 0.5f
            if (cubic(segment.x0, segment.x1, segment.x2, segment.x3, mid) < x) {
                low = mid
            } else {
                high = mid
            }
        }
        val t = (low + high) * 0.5f
        return cubic(segment.y0, segment.y1, segment.y2, segment.y3, t)
    }

    private val firstSegment = CubicPathSegment(
        x0 = 0f,
        y0 = 0f,
        x1 = 0.05f,
        y1 = 0f,
        x2 = 0.133333f,
        y2 = 0.06f,
        x3 = 0.166666f,
        y3 = 0.4f,
    )

    private val secondSegment = CubicPathSegment(
        x0 = 0.166666f,
        y0 = 0.4f,
        x1 = 0.208333f,
        y1 = 0.82f,
        x2 = 0.25f,
        y2 = 1f,
        x3 = 1f,
        y3 = 1f,
    )
}

private data class CubicPathSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
)

private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val oneMinusT = 1f - t
    return oneMinusT * oneMinusT * oneMinusT * p0 +
        3f * oneMinusT * oneMinusT * t * p1 +
        3f * oneMinusT * t * t * p2 +
        t * t * t * p3
}

private object PowerListAccelerateDecelerate : Easing {
    override fun transform(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        return (cos((x + 1f) * Math.PI).toFloat() * 0.5f) + 0.5f
    }
}
