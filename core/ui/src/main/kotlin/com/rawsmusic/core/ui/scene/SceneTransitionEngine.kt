package com.rawsmusic.core.ui.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.roundToInt

private const val NORMAL_ANIM_MS = 320
private const val POWER_LIST_COMMIT_MS = 250
private const val POWER_LIST_CANCEL_MS = 500
private const val POWER_LIST_COMMIT_MIN_MS = 100
private const val POWER_LIST_CANCEL_MIN_MS = 166
private const val POWER_LIST_COMMIT_PROGRESS = 0.3f
private const val POWER_LIST_VELOCITY_DP_PER_S = 500f
private const val SHARED_PAIR_WAIT_FRAMES = 6
private const val SETTINGS_FRAGMENT_ANIM_MS = 300
private const val DRAG_ANIM_MS = 500
private const val COMMIT_PROGRESS_THRESHOLD = 0.8f
private const val COMMIT_VELOCITY_PX_PER_S = 1000f
private const val CANCEL_REVERSE_VELOCITY_PX_PER_S = -250f
private const val EDGE_DETECT_WIDTH_DP = 24
private const val OVER_DRAG_UNIT = 0.05f
private const val SCALE_MIN = 0.75f
private const val SCALE_MAX = 1.25f

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
    NavScene.AUDIO_EFFECTS,
    NavScene.AUDIO_SETTINGS,
    NavScene.ALBUM_ART_SETTINGS,
    NavScene.BASS_TREBLE_BOOST,
    NavScene.COMPRESSOR,
    NavScene.GLOBAL_FONT_SETTINGS,
    NavScene.LYRIC_FONT_SETTINGS,
    NavScene.LYRIC_MANAGEMENT,
    NavScene.PANORAMIC_360,
    NavScene.PEQ,
    NavScene.PLAYER_INTERFACE,
    NavScene.SPATIAL_SOUND,
    NavScene.STATUS_BAR_LYRIC,
    NavScene.SURROUND_360,
    NavScene.USB_DAC_SETTINGS,
    NavScene.WEBDAV_BACKUP,
)

private fun shouldAnimate(from: NavScene, to: NavScene): Boolean {
    return from != to
}

private fun usesSettingsFragmentMotion(from: NavScene, to: NavScene): Boolean {
    return from in settingsScenes && to in settingsScenes
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
    if (usesSharedCoverMotion(from, to)) return false
    return from != to
}

private fun dampProgress(progress: Float): Float {
    if (progress in 0f..1f) return progress
    val over = if (progress < 0f) -progress else 1f - progress
    val damped = ln(abs(over) / OVER_DRAG_UNIT + 1f) * OVER_DRAG_UNIT
    return if (progress < 0f) -damped else 1f + damped
}

private fun settleDurationMillis(start: Float, target: Float, velocityPxPerSecond: Float): Int {
    val remaining = max(abs(target - start), 0.25f)
    val velocityFactor = (abs(velocityPxPerSecond) / 1000f).coerceIn(0f, 10f)
    val seconds = abs(1f / ((1f / (DRAG_ANIM_MS / 1000f)) + velocityFactor)) * remaining
    return (seconds * 1000f).toInt().coerceIn(80, DRAG_ANIM_MS)
}

private suspend fun awaitSharedLayouts(
    coverRegistry: SharedCoverRegistry,
    itemRegistry: PowerListSceneTransitionRegistry,
    fromScene: NavScene,
    toScene: NavScene,
    transitionKey: String,
    allowRememberedTarget: Boolean = false
) {
    repeat(SHARED_PAIR_WAIT_FRAMES) {
        withFrameNanos { }
        val pairs = coverRegistry.findPairs(
            fromSceneId = fromScene.name,
            toSceneId = toScene.name,
            allowRememberedTarget = allowRememberedTarget
        )
        val bothScenesMeasured = itemRegistry.hasScene(fromScene.name) &&
            (itemRegistry.hasScene(toScene.name) ||
                (allowRememberedTarget && itemRegistry.hasRememberedScene(toScene.name)))
        if (pairs.isNotEmpty() && bothScenesMeasured) {
            pairs.forEach { (from, to) ->
                coverRegistry.freeze(from)
                coverRegistry.freeze(to)
                if (!allowRememberedTarget) coverRegistry.rememberReturnTarget(from)
            }
            itemRegistry.freezeTransition(
                transitionKey = transitionKey,
                fromSceneId = fromScene.name,
                toSceneId = toScene.name,
                anchorItemId = pairs.first().first.elementId,
                allowRememberedTarget = allowRememberedTarget,
                anchorFromBounds = pairs.first().first.boundsInWindow,
                anchorToBounds = pairs.first().second.boundsInWindow
            )
            return
        }
    }
    val pairs = coverRegistry.findPairs(
        fromSceneId = fromScene.name,
        toSceneId = toScene.name,
        allowRememberedTarget = allowRememberedTarget
    )
    pairs.forEach { (from, to) ->
        coverRegistry.freeze(from)
        coverRegistry.freeze(to)
        if (!allowRememberedTarget) coverRegistry.rememberReturnTarget(from)
    }
    itemRegistry.freezeTransition(
        transitionKey = transitionKey,
        fromSceneId = fromScene.name,
        toSceneId = toScene.name,
        anchorItemId = pairs.firstOrNull()
            ?.first
            ?.elementId
            .orEmpty(),
        allowRememberedTarget = allowRememberedTarget,
        anchorFromBounds = pairs.firstOrNull()?.first?.boundsInWindow,
        anchorToBounds = pairs.firstOrNull()?.second?.boundsInWindow
    )
}

@Composable
fun SceneTransitionHost(
    state: NavigationState,
    modifier: Modifier = Modifier,
    prewarmScenes: List<NavScene> = emptyList(),
    content: @Composable (NavScene) -> Unit
) {
    val scope = rememberCoroutineScope()
    val animProgress = remember { Animatable(0f) }
    var displayedScene by remember { mutableStateOf(state.currentScene) }
    var fromScene by remember { mutableStateOf(state.currentScene) }
    var isAnimating by remember { mutableStateOf(false) }
    var isGestureActive by remember { mutableStateOf(false) }
    var pageMotion by remember { mutableStateOf(PageMotion.Generic) }
    var screenWidthPx by remember { mutableFloatStateOf(0f) }
    val prevSceneRef = remember { mutableStateOf(state.currentScene) }
    val sharedCoverRegistry = remember { SharedCoverRegistry() }
    val powerListSceneRegistry = remember { PowerListSceneTransitionRegistry() }

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
            return@LaunchedEffect
        }

        fromScene = oldScene
        sharedFromScene = oldScene
        sharedToScene = newScene
        pageMotion = when {
            usesSharedCoverMotion(oldScene, newScene) -> PageMotion.FolderSharedForward
            usesSettingsFragmentMotion(oldScene, newScene) -> PageMotion.SettingsForward
            else -> PageMotion.Generic
        }
        isAnimating = true
        animProgress.snapTo(1f)
        displayedScene = newScene
        if (pageMotion == PageMotion.FolderSharedForward) {
            awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                itemRegistry = powerListSceneRegistry,
                fromScene = oldScene,
                toScene = newScene,
                transitionKey = "${oldScene.name}->${newScene.name}"
            )
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
        isAnimating = false
    }

    LaunchedEffect(state.isAnimatingBack) {
        if (!state.isAnimatingBack) return@LaunchedEffect
        val targetScene = state.getPreviousScene() ?: run {
            state.completeAnimatingBack()
            return@LaunchedEffect
        }

        fromScene = targetScene
        sharedFromScene = state.currentScene
        sharedToScene = targetScene
        pageMotion = when {
            usesSharedCoverMotion(state.currentScene, targetScene) -> PageMotion.FolderSharedBack
            usesSettingsFragmentMotion(state.currentScene, targetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        isAnimating = true
        animProgress.snapTo(0f)
        if (pageMotion == PageMotion.FolderSharedBack) {
            awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                itemRegistry = powerListSceneRegistry,
                fromScene = state.currentScene,
                toScene = targetScene,
                transitionKey = "${state.currentScene.name}->${targetScene.name}",
                allowRememberedTarget = true
            )
        } else {
            withFrameNanos { }
        }
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
        isAnimating = false
        state.completeAnimatingBack()
        animProgress.snapTo(0f)
    }

    LaunchedEffect(state.isDraggingBack) {
        if (!state.isDraggingBack) return@LaunchedEffect
        val targetScene = state.getPreviousScene() ?: return@LaunchedEffect
        fromScene = targetScene
        sharedFromScene = state.currentScene
        sharedToScene = targetScene
        pageMotion = when {
            usesSharedCoverMotion(state.currentScene, targetScene) -> PageMotion.FolderSharedBack
            usesSettingsFragmentMotion(state.currentScene, targetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        isGestureActive = true
        animProgress.snapTo(0f)
        if (pageMotion == PageMotion.FolderSharedBack) {
            awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                itemRegistry = powerListSceneRegistry,
                fromScene = state.currentScene,
                toScene = targetScene,
                transitionKey = "${state.currentScene.name}->${targetScene.name}",
                allowRememberedTarget = true
            )
        } else {
            withFrameNanos { }
        }
    }

    LaunchedEffect(state.isDraggingBack, state.dragBackProgress, isGestureActive) {
        if (!state.isDraggingBack || !isGestureActive) return@LaunchedEffect
        animProgress.snapTo(dampProgress(state.dragBackProgress))
    }

    LaunchedEffect(state.dragBackReleaseToken) {
        if (state.dragBackReleaseToken == 0) return@LaunchedEffect
        val start = dampProgress(state.dragBackReleaseProgress).coerceIn(0f, 1f)
        val commit = state.dragBackReleaseCommit
        val target = if (commit) 1f else 0f
        val sharedPowerListSettle = pageMotion == PageMotion.FolderSharedBack
        if (sharedPowerListSettle) {
            val targetScene = state.getPreviousScene()
            val transitionKey = targetScene?.let { "${state.currentScene.name}->${it.name}" }.orEmpty()
            if (targetScene != null && !powerListSceneRegistry.isPrepared(transitionKey)) {
                awaitSharedLayouts(
                    coverRegistry = sharedCoverRegistry,
                    itemRegistry = powerListSceneRegistry,
                    fromScene = state.currentScene,
                    toScene = targetScene,
                    transitionKey = transitionKey,
                    allowRememberedTarget = true
                )
            }
        }
        val normalizedVelocity = state.dragBackReleaseVelocity / screenWidthPx.coerceAtLeast(1f)
        val carryVelocity = sharedPowerListSettle && if (commit) {
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
            sharedPowerListSettle && commit -> {
                ((1f - start) * POWER_LIST_COMMIT_MS).roundToInt()
                    .coerceAtLeast(POWER_LIST_COMMIT_MIN_MS)
            }
            sharedPowerListSettle -> {
                (start * POWER_LIST_CANCEL_MS).roundToInt()
                    .coerceAtLeast(POWER_LIST_CANCEL_MIN_MS)
            }
            else -> settleDurationMillis(start, target, state.dragBackReleaseVelocity)
        }
        val settleEasing = when {
            carryVelocity -> LinearEasing
            sharedPowerListSettle -> PowerListAccelerateDecelerate
            else -> Decelerate2
        }
        isGestureActive = false
        isAnimating = true
        animProgress.snapTo(start)
        animProgress.animateTo(target, tween(duration, easing = settleEasing))
        if (commit) {
            val targetScene = fromScene
            prevSceneRef.value = targetScene
            displayedScene = targetScene
            state.navigateBack()
        }
        isAnimating = false
        animProgress.snapTo(0f)
    }

    val gestureModifier = Modifier.pointerInput(state.canNavigateBack(), state.currentScene) {
        if (!state.canNavigateBack()) return@pointerInput
        val edgeWidthPx = EDGE_DETECT_WIDTH_DP.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (isAnimating || state.isDraggingBack) return@awaitEachGesture
            val localWidthPx = size.width.toFloat().coerceAtLeast(1f)
            val prev = state.getPreviousScene() ?: return@awaitEachGesture
            val sharedPowerListBack = usesSharedCoverMotion(state.currentScene, prev)
            val allowContentDrag = allowsContentBackDrag(state.currentScene, prev)
            var direction = when {
                down.position.x <= edgeWidthPx -> 1f
                down.position.x >= localWidthPx - edgeWidthPx -> -1f
                else -> 0f
            }
            if (!allowContentDrag && direction == 0f) return@awaitEachGesture

            var gestureDecided = false
            var dragging = false
            var startX = down.position.x
            var startY = down.position.y
            var lastX = startX
            var lastTime = down.uptimeMillis
            var velocityX = 0f
            var rawProgress = 0f
            fromScene = prev

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

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
                    if (!state.startBackDrag(direction)) return@awaitEachGesture
                    isGestureActive = true
                    scope.launch { animProgress.snapTo(0f) }
                    change.consume()
                }

                if (dragging) {
                    val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    velocityX = (change.position.x - lastX) / dt * 1000f
                    lastX = change.position.x
                    lastTime = change.uptimeMillis
                    rawProgress = if (localWidthPx > 0f) (dx * direction) / localWidthPx else 0f
                    state.updateBackDrag(rawProgress)
                    scope.launch { animProgress.snapTo(dampProgress(rawProgress)) }
                    change.consume()
                }
            }

                if (dragging) {
                    val signedVelocity = velocityX * direction
                    val releaseProgress = rawProgress.coerceIn(0f, 1f)
                    val commit = if (sharedPowerListBack) {
                        val velocityDpPerSecond = signedVelocity / density
                        if (abs(velocityDpPerSecond) >= POWER_LIST_VELOCITY_DP_PER_S) {
                            velocityDpPerSecond > 0f
                        } else {
                            releaseProgress > POWER_LIST_COMMIT_PROGRESS
                        }
                    } else {
                        val reversing = signedVelocity < CANCEL_REVERSE_VELOCITY_PX_PER_S || rawProgress <= 0f
                        !reversing &&
                            (releaseProgress > COMMIT_PROGRESS_THRESHOLD || signedVelocity > COMMIT_VELOCITY_PX_PER_S)
                    }
                    state.releaseBackDrag(commit = commit, velocity = signedVelocity)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier)
    ) {
        Layout(
            content = {},
            modifier = Modifier.fillMaxSize(),
            measurePolicy = { _, constraints ->
                screenWidthPx = constraints.maxWidth.toFloat()
                layout(0, 0) {}
            }
        )

        val progress = animProgress.value.coerceIn(0f, 1f)
        val inTransition = isAnimating || isGestureActive || state.isDraggingBack || progress > 0f

        // 按方向计算共享进度：forward progress 1→0 需反转，back/gesture progress 0→1 直接用
        val sharedProgress = when (pageMotion) {
            PageMotion.SettingsForward -> 1f - progress
            PageMotion.SettingsBack -> progress
            PageMotion.FolderSharedForward -> 1f - progress
            PageMotion.FolderSharedBack -> progress
            PageMotion.Generic -> {
                if (isGestureActive || state.isDraggingBack || state.isAnimatingBack) {
                    progress
                } else {
                    1f - progress
                }
            }
        }.coerceIn(0f, 1f)

        val sharedActive = inTransition && sharedFromScene != sharedToScene
        val transitionKey = "${sharedFromScene.name}->${sharedToScene.name}"

        if (!sharedActive) {
            sharedCoverRegistry.clearFrozen()
            powerListSceneRegistry.clearTransition()
        }

        val sharedSpec = SharedTransitionSpec(
            active = sharedActive,
            fromSceneId = sharedFromScene.name,
            toSceneId = sharedToScene.name,
            activeSceneId = displayedScene.name,
            progress = sharedProgress,
            transitionKey = transitionKey
        )

        CompositionLocalProvider(
            LocalSharedCoverRegistry provides sharedCoverRegistry,
            LocalPowerListSceneTransitionRegistry provides powerListSceneRegistry,
            LocalSharedTransitionSpec provides sharedSpec
        ) {
            if (inTransition && fromScene != displayedScene) {
                val transform = pageTransforms(pageMotion, progress, screenWidthPx)
                val isSettings = pageMotion == PageMotion.SettingsForward || pageMotion == PageMotion.SettingsBack

                // 来源页面（下层）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = transform.fromTranslationX
                            scaleX = transform.fromScale
                            scaleY = transform.fromScale
                            alpha = transform.fromAlpha
                            transformOrigin = TransformOrigin.Center
                            if (isSettings) {
                                clip = true
                                shape = RoundedCornerShape(28.dp)
                            }
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
                            scaleX = transform.currentScale
                            scaleY = transform.currentScale
                            alpha = transform.currentAlpha
                            transformOrigin = TransformOrigin.Center
                            if (isSettings) {
                                clip = true
                                shape = RoundedCornerShape(28.dp)
                            }
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
                prewarmScenes.forEach { scene ->
                    if (scene != displayedScene) {
                        Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = 0f
                                        translationX = screenWidthPx.coerceAtLeast(1f) * 2f
                                    }
                        ) {
                            SceneContent(scene)
                        }
                    }
                }
            }

            // 共享封面 overlay：文件夹/专辑/艺术家列表 <-> 对应详情页使用
            val sharedCoverOverlayActive = sharedActive &&
                (pageMotion == PageMotion.FolderSharedForward || pageMotion == PageMotion.FolderSharedBack)
            if (sharedCoverOverlayActive) {
                SharedCoverOverlay(
                    registry = sharedCoverRegistry,
                    spec = sharedSpec
                )
            }
        }
    }
}

private data class PageTransform(
    val fromScale: Float,
    val fromAlpha: Float,
    val fromTranslationX: Float,
    val currentScale: Float,
    val currentAlpha: Float,
    val currentTranslationX: Float,
)

private fun pageTransforms(
    motion: PageMotion,
    progress: Float,
    screenWidthPx: Float,
): PageTransform {
    val width = screenWidthPx.coerceAtLeast(1f)
    return when (motion) {
        PageMotion.SettingsForward -> {
            val elapsed = 1f - progress
            PageTransform(
                fromScale = lerp(1f, 0.95f, elapsed),
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                currentScale = 1f,
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = lerp(width, 0f, elapsed),
            )
        }

        PageMotion.FolderSharedForward -> {
            val elapsed = 1f - progress
            PageTransform(
                fromScale = 1f,
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                currentScale = 1f,
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = 0f,
            )
        }

        PageMotion.FolderSharedBack -> {
            val elapsed = progress
            PageTransform(
                fromScale = 1f,
                fromAlpha = lerp(0f, 1f, elapsed),
                fromTranslationX = 0f,
                currentScale = 1f,
                currentAlpha = lerp(1f, 0f, elapsed),
                currentTranslationX = 0f,
            )
        }

        PageMotion.SettingsBack -> {
            val elapsed = progress
            PageTransform(
                fromScale = 1f,
                fromAlpha = 1f,
                fromTranslationX = 0f,
                currentScale = 1f,
                currentAlpha = 1f,
                currentTranslationX = lerp(0f, width, elapsed),
            )
        }

        PageMotion.Generic -> PageTransform(
            fromScale = lerp(SCALE_MAX, 1f, progress),
            fromAlpha = progress,
            fromTranslationX = 0f,
            currentScale = lerp(1f, SCALE_MIN, progress),
            currentAlpha = 1f - progress,
            currentTranslationX = 0f,
        )
    }
}

/**
 * 共享封面 overlay。
 * 在 SceneTransitionHost 根层绘制，不受来源页/目标页的 alpha 影响。
 * 从 SharedCoverRegistry 读取两端 bounds/radius，插值后用 CrossfadeAlbumArt 渲染。
 */
@Composable
private fun SharedCoverOverlay(
    registry: SharedCoverRegistry,
    spec: SharedTransitionSpec
) {
    if (!spec.active) return

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
        val pairs = registry.findPairs(
            fromSceneId = spec.fromSceneId,
            toSceneId = spec.toSceneId
        )
        if (pairs.isEmpty()) return@Box

        for ((rawFrom, rawTo) in pairs) {
            if (registry.getFrozen(rawFrom.sceneId, rawFrom.elementId) == null) {
                registry.freeze(rawFrom.sceneId, rawFrom.elementId)
            }
            if (registry.getFrozen(rawTo.sceneId, rawTo.elementId) == null) {
                registry.freeze(rawTo.sceneId, rawTo.elementId)
            }

            val from = registry.getFrozen(rawFrom.sceneId, rawFrom.elementId) ?: rawFrom
            val to = registry.getFrozen(rawTo.sceneId, rawTo.elementId) ?: rawTo
            val progress = spec.progress.coerceIn(0f, 1f)

            val leftPx = lerp(from.boundsInWindow.left, to.boundsInWindow.left, progress) - overlayOrigin.x
            val topPx = lerp(from.boundsInWindow.top, to.boundsInWindow.top, progress) - overlayOrigin.y
            val widthPx = lerp(from.boundsInWindow.width, to.boundsInWindow.width, progress).coerceAtLeast(1f)
            val heightPx = lerp(from.boundsInWindow.height, to.boundsInWindow.height, progress).coerceAtLeast(1f)
            val baseWidthPx = max(from.boundsInWindow.width, to.boundsInWindow.width).coerceAtLeast(1f)
            val baseHeightPx = max(from.boundsInWindow.height, to.boundsInWindow.height).coerceAtLeast(1f)
            val radiusDp = lerp(from.radiusDp, to.radiusDp, progress)
            val coverKey = if (to.coverKey.isNotBlank()) to.coverKey else from.coverKey
            if (coverKey.isBlank()) continue
            val scaleX = widthPx / baseWidthPx
            val scaleY = heightPx / baseHeightPx
            val visualScale = minOf(scaleX, scaleY).coerceAtLeast(0.001f)

            com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt(
                key = coverKey,
                modifier = Modifier
                    .requiredSize(
                        width = with(density) { baseWidthPx.toDp() },
                        height = with(density) { baseHeightPx.toDp() }
                    )
                    .graphicsLayer {
                        translationX = leftPx
                        translationY = topPx
                        this.scaleX = scaleX
                        this.scaleY = scaleY
                        transformOrigin = TransformOrigin(0f, 0f)
                        alpha = 1f
                        clip = true
                        // The layer is clipped before it is scaled. Compensate so the visible
                        // corner radius, rather than the pre-scale radius, follows the interpolation.
                        shape = RoundedCornerShape((radiusDp / visualScale).dp)
                    },
                contentScale = ContentScale.Crop,
                showPlaceholder = false,
                fadeMillis = 0,
                freezeBitmapUpdates = true
            )
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
