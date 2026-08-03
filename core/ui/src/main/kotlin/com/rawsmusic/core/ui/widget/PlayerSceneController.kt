package com.rawsmusic.core.ui.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.util.Log
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Pure Compose scene controller for the player stack.
 *
 * The remaining app UI is Compose, so this controller owns only scene state, ratio animation, and
 * callbacks. Gesture input is supplied by Compose in MainActivity.
 */
class PlayerSceneController {
    enum class Scene {
        MAIN,
        PLAYER,
        LYRIC,
        QUEUE,
        ALBUM_DETAIL
    }

    var currentScene: Scene = Scene.MAIN
        private set

    var composeCurrentScene by mutableStateOf(Scene.MAIN)
        private set

    var composeFromScene by mutableStateOf(Scene.MAIN)
        private set

    var composeToScene by mutableStateOf(Scene.MAIN)
        private set

    var composeIsTransitioning by mutableStateOf(false)
        private set

    var composeTransitionProgress by mutableFloatStateOf(0f)
        private set

    val playerLyricsTransitionCoordinator = PlayerLyricsTransitionCoordinator()

    var composeIsPlaying by mutableStateOf(false)

    var isImmersiveEnabled = true
        private set
    var isMiniCoverEnabled = true
        private set
    var isDefaultBackgroundEnabled = false
        private set

    var isTransitioning: Boolean = false
        private set

    var lyricEnabled: Boolean = false
    var isDeepHomePage: Boolean = false
    var disableDeepPageSwipe: Boolean = false
    var isCurrentlyPlaying: Boolean = false
    var disableGestureIntercept: Boolean = false

    var onSceneChanged: ((newScene: Scene, oldScene: Scene) -> Unit)? = null
    var onTransitionProgress: ((Scene, Float) -> Unit)? = null
    var onPreparePlayerToMain: ((onReady: () -> Unit) -> Unit)? = null
    var onPreparePlayerToLyric: (() -> Unit)? = null
    var onPreparePlayerToQueue: (() -> Unit)? = null
    var onPreparePlayerToAlbumDetail: (() -> Unit)? = null
    var onPrepareMainToPlayer: (() -> Unit)? = null
    var onSwipeBack: (() -> Unit)? = null
    var onImmersiveSwipeLeft: (() -> Unit)? = null
    var onLeftEdgeSwipe: (() -> Unit)? = null
    var onPlayerSwipeToMain: (() -> Unit)? = null
    var onHomeSwipeRightDrag: ((offset: Float) -> Unit)? = null
    var onHomeSwipeRightRelease: ((shouldOpen: Boolean) -> Unit)? = null

    private var transitionRatio = 0f
    private var fromScene = Scene.MAIN
    private var toScene = Scene.MAIN
    private var sceneAnimator: ValueAnimator? = null
    private var sceneAnimGeneration = 0

    fun updateDefaultBackgroundEnabled(enabled: Boolean) {
        isDefaultBackgroundEnabled = enabled
    }

    fun updateMiniCoverEnabled(enabled: Boolean) {
        isMiniCoverEnabled = enabled
    }

    fun updateImmersiveSettings(isImmersive: Boolean, isDark: Boolean) {
        isImmersiveEnabled = isImmersive
    }

    fun refreshImmersiveState(isImmersive: Boolean) {
        isImmersiveEnabled = isImmersive
    }

    fun transitionToScene(targetScene: Scene, duration: Long = SCENE_ANIM_DURATION) {
        if (isTransitioning || composeIsTransitioning) return
        if (currentScene == targetScene) return
        val oldScene = currentScene
        sceneAnimGeneration++
        sceneAnimator?.cancel()
        fromScene = oldScene
        toScene = targetScene
        if (PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(oldScene, targetScene)) {
            playerLyricsTransitionCoordinator.begin(oldScene, targetScene, interactive = false)
        }
        settleScene(oldScene, targetScene, duration, 0f, 1f)
    }

    fun switchToSceneSilent(targetScene: Scene) {
        sceneAnimator?.cancel()
        val oldScene = currentScene
        currentScene = targetScene
        fromScene = targetScene
        toScene = targetScene
        composeFromScene = targetScene
        composeToScene = targetScene
        transitionRatio = 0f
        isTransitioning = false
        composeCurrentScene = targetScene
        composeIsTransitioning = false
        composeTransitionProgress = 0f
        playerLyricsTransitionCoordinator.finish()
        if (oldScene != targetScene) {
            onSceneChanged?.invoke(targetScene, oldScene)
        }
    }

    fun startCoverDrag(targetScene: Scene = Scene.MAIN) {
        if (isTransitioning || composeIsTransitioning) return
        if (currentScene != Scene.PLAYER && currentScene != Scene.LYRIC && currentScene != Scene.ALBUM_DETAIL) return
        if (currentScene == targetScene) return
        startInteractiveDrag(currentScene, targetScene)
    }

    fun startCoverDrag(swipeRight: Boolean, targetScene: Scene = Scene.MAIN) {
        startCoverDrag(targetScene)
    }

    fun updateCoverDrag(ratio: Float) {
        updateInteractiveDrag(ratio)
    }

    fun updateCoverDragProgress(ratio: Float) {
        updateCoverDrag(ratio)
    }

    fun endCoverDrag(shouldClose: Boolean, duration: Long = SCENE_ANIM_DURATION, velocity: Float = 0f) {
        val visualToScene = toScene
        val target = if (shouldClose) visualToScene else fromScene
        val endRatio = if (shouldClose) 1f else 0f
        settleScene(
            oldScene = fromScene,
            targetScene = target,
            duration = duration,
            startRatio = transitionRatio,
            endRatio = endRatio,
            velocity = velocity,
            visualToScene = visualToScene
        )
    }

    fun releaseCoverDrag(shouldClose: Boolean, velocity: Float) {
        endCoverDrag(shouldClose, velocity = velocity)
    }

    fun updateDragBackProgress(ratio: Float) {
        updateInteractiveDrag(ratio)
    }

    fun endDragBack(shouldGoBack: Boolean, velocity: Float = 0f) {
        val visualToScene = toScene
        val target = if (shouldGoBack) visualToScene else fromScene
        val endRatio = if (shouldGoBack) 1f else 0f
        settleScene(
            oldScene = fromScene,
            targetScene = target,
            duration = SCENE_ANIM_DURATION,
            startRatio = transitionRatio,
            endRatio = endRatio,
            velocity = velocity,
            visualToScene = visualToScene
        )
    }

    fun startCoverSwipeUpDrag(from: Scene = Scene.PLAYER, to: Scene = Scene.LYRIC) {
        if (isTransitioning || composeIsTransitioning) return
        if (currentScene != from && currentScene != to) return
        val actualFrom = currentScene
        // actualTo 是"对方场景"：当前在 from 则去 to，当前在 to 则去 from
        val actualTo = if (actualFrom == from) to else from
        if (actualFrom == Scene.PLAYER && actualTo == Scene.LYRIC) {
            onPreparePlayerToLyric?.invoke()
        }
        startInteractiveDrag(actualFrom, actualTo)
    }

    /** Starts the lyric-header downward gesture with an explicit lyric -> player direction. */
    fun startLyricToPlayerDrag() {
        if (isTransitioning || composeIsTransitioning) return
        if (currentScene != Scene.LYRIC && composeCurrentScene != Scene.LYRIC) return
        startInteractiveDrag(Scene.LYRIC, Scene.PLAYER)
    }

    fun updateCoverSwipeUpDrag(ratio: Float) {
        updateInteractiveDrag(ratio)
    }

    fun endCoverSwipeUpDrag(shouldOpen: Boolean, duration: Long = SCENE_ANIM_DURATION, velocity: Float = 0f) {
        val visualToScene = toScene
        val target = if (shouldOpen) visualToScene else fromScene
        val endRatio = if (shouldOpen) 1f else 0f
        settleScene(
            oldScene = fromScene,
            targetScene = target,
            duration = duration,
            startRatio = transitionRatio,
            endRatio = endRatio,
            velocity = velocity,
            visualToScene = visualToScene
        )
    }

    fun endLyricToPlayerDrag(shouldReturnToPlayer: Boolean, velocity: Float = 0f) {
        if (fromScene != Scene.LYRIC || toScene != Scene.PLAYER) {
            startInteractiveDrag(Scene.LYRIC, Scene.PLAYER)
        }
        val target = if (shouldReturnToPlayer) Scene.PLAYER else Scene.LYRIC
        val endRatio = if (shouldReturnToPlayer) 1f else 0f
        settleScene(
            oldScene = Scene.LYRIC,
            targetScene = target,
            duration = SCENE_ANIM_DURATION,
            startRatio = transitionRatio,
            endRatio = endRatio,
            velocity = velocity,
            visualToScene = Scene.PLAYER
        )
    }

    fun onDragStart(directionLeft: Boolean, forceBackToMain: Boolean = false) {
        if (forceBackToMain && currentScene != Scene.MAIN) {
            startInteractiveDrag(currentScene, Scene.MAIN)
            return
        }
        when (currentScene) {
            Scene.PLAYER -> {
                if (directionLeft) {
                    onPreparePlayerToLyric?.invoke()
                    startInteractiveDrag(Scene.PLAYER, Scene.LYRIC)
                } else {
                    if (isImmersiveEnabled) {
                        onPreparePlayerToAlbumDetail?.invoke()
                        startInteractiveDrag(Scene.PLAYER, Scene.ALBUM_DETAIL)
                    }
                }
            }
            Scene.LYRIC -> startInteractiveDrag(Scene.LYRIC, Scene.PLAYER)
            Scene.QUEUE -> startInteractiveDrag(Scene.QUEUE, Scene.MAIN)
            Scene.ALBUM_DETAIL -> {
                if (directionLeft) {
                    startInteractiveDrag(Scene.ALBUM_DETAIL, Scene.PLAYER)
                } else {
                    startInteractiveDrag(Scene.ALBUM_DETAIL, Scene.MAIN)
                }
            }
            Scene.MAIN -> Unit
        }
    }

    fun updateGestureDrag(deltaX: Float, width: Float) {
        if (width <= 0f || currentScene == Scene.MAIN) return
        val ratio = when (currentScene) {
            Scene.PLAYER -> abs(deltaX) / width
            Scene.LYRIC, Scene.QUEUE, Scene.ALBUM_DETAIL -> abs(deltaX) / width
            Scene.MAIN -> 0f
        }.coerceIn(0f, 1f)
        updateInteractiveDrag(ratio)
    }

    fun releaseGestureDrag(
        deltaX: Float,
        velocityX: Float,
        width: Float,
        density: Float = 1f
    ) {
        if (width <= 0f) return
        if (PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(fromScene, toScene) &&
            playerLyricsTransitionCoordinator.activeSession != null
        ) {
            val expectedSign = if (
                fromScene == Scene.PLAYER && toScene == Scene.LYRIC
            ) {
                -1
            } else {
                1
            }
            val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                progress = transitionRatio,
                velocityPxPerSecond = velocityX,
                density = density,
                expectedVelocitySign = expectedSign
            )
            val settleVelocity = PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                progress = transitionRatio,
                commit = commit,
                velocityPxPerSecond = velocityX,
                travelDistancePx = width,
                expectedVelocitySign = expectedSign
            )
            endCoverDrag(commit, velocity = settleVelocity)
            return
        }
        val isFlingRight = velocityX > 900f
        val isFlingLeft = velocityX < -900f
        when (currentScene) {
            Scene.PLAYER -> {
                val targetLyric = deltaX < 0f
                if (targetLyric && fromScene != Scene.PLAYER) startInteractiveDrag(Scene.PLAYER, Scene.LYRIC)
                val shouldGo = transitionRatio > SWIPE_THRESHOLD_RATIO || (targetLyric && isFlingLeft) || (!targetLyric && isFlingRight)
                endCoverDrag(shouldGo, velocity = velocityX)
            }
            Scene.LYRIC -> endCoverDrag(transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight, velocity = velocityX)
            Scene.QUEUE, Scene.ALBUM_DETAIL -> endDragBack(transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight, velocityX)
            Scene.MAIN -> {
                if (isDeepHomePage) onSwipeBack?.invoke() else onHomeSwipeRightRelease?.invoke(isFlingRight)
                resetInteractionState()
            }
        }
    }

    fun setLyricAtTopBoundary(atTop: Boolean) = Unit

    fun resetInteractionState() {
        sceneAnimator?.cancel()
        transitionRatio = 0f
        isTransitioning = false
        composeIsTransitioning = false
        composeTransitionProgress = 0f
        playerLyricsTransitionCoordinator.cancel()
    }

    fun forceReapplyCurrentScene() {
        val scene = currentScene
        onSceneChanged?.invoke(scene, scene)
    }

    fun openPlayPage(animated: Boolean = true) {
        if (currentScene != Scene.MAIN) return
        onPrepareMainToPlayer?.invoke()
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun closePlayPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        // Temporarily bypass the player -> main shared-cover handoff.  Keep the
        // scene state path simple while the player/mini-player artwork paths are
        // being stabilized; player -> lyric animation remains enabled below.
        if (animated) transitionToScene(Scene.MAIN) else switchToSceneSilent(Scene.MAIN)
    }

    fun closePlayPageWithCoverAlign(animated: Boolean = true) {
        closePlayPage(animated)
    }

    fun closeCurrentPlayerStackToMain(animated: Boolean = true) {
        if (currentScene == Scene.MAIN) return
        // Same as closePlayPage(): no temporary shared-cover handoff to main.
        if (animated) transitionToScene(Scene.MAIN) else switchToSceneSilent(Scene.MAIN)
    }

    fun openLyricPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToLyric?.invoke()
        if (animated) transitionToScene(Scene.LYRIC) else switchToSceneSilent(Scene.LYRIC)
    }

    fun closeLyricPage(animated: Boolean = true) {
        if (currentScene != Scene.LYRIC) return
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun openQueuePage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToQueue?.invoke()
        if (animated) transitionToScene(Scene.QUEUE) else switchToSceneSilent(Scene.QUEUE)
    }

    fun closeQueuePage(animated: Boolean = true) {
        if (currentScene != Scene.QUEUE) return
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun openAlbumDetailPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToAlbumDetail?.invoke()
        if (animated) transitionToScene(Scene.ALBUM_DETAIL) else switchToSceneSilent(Scene.ALBUM_DETAIL)
    }

    fun closeAlbumDetailPage(animated: Boolean = true) {
        if (currentScene != Scene.ALBUM_DETAIL) return
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun syncRotationState(isPlaying: Boolean) {
        isCurrentlyPlaying = isPlaying
        composeIsPlaying = isPlaying
    }

    fun cancelAllStateAnims() = Unit

    private fun startInteractiveDrag(from: Scene, to: Scene) {
        if (isTransitioning || composeIsTransitioning) return
        sceneAnimGeneration++
        sceneAnimator?.cancel()
        if (PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(from, to)) {
            playerLyricsTransitionCoordinator.begin(from, to, interactive = true)
        } else {
            playerLyricsTransitionCoordinator.finish()
        }
        fromScene = from
        toScene = to
        composeFromScene = from
        composeToScene = to
        transitionRatio = 0f
        isTransitioning = true
        composeIsTransitioning = true
        composeTransitionProgress = 0f
    }

    private fun updateInteractiveDrag(ratio: Float) {
        val clamped = ratio.coerceIn(0f, 1f)
        transitionRatio = clamped
        composeTransitionProgress = clamped
        if (PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(fromScene, toScene)) {
            playerLyricsTransitionCoordinator.updateProgress(clamped)
        }
        onTransitionProgress?.invoke(toScene, clamped)
    }

    private fun settleScene(
        oldScene: Scene,
        targetScene: Scene,
        duration: Long,
        startRatio: Float,
        endRatio: Float,
        velocity: Float = 0f,
        visualToScene: Scene = targetScene
    ) {
        sceneAnimator?.cancel()
        fromScene = oldScene
        // Keep the original visual pair while an interactive gesture settles back to its source.
        // Collapsing LYRIC -> PLAYER into LYRIC -> LYRIC on cancel removes the shared overlay
        // immediately and causes a release-time geometry/radius jump instead of a reverse lerp.
        toScene = visualToScene
        composeFromScene = oldScene
        composeToScene = visualToScene
        isTransitioning = true
        composeIsTransitioning = true
        transitionRatio = startRatio.coerceIn(0f, 1f)
        composeTransitionProgress = transitionRatio
        val playerLyricsSessionId = playerLyricsTransitionCoordinator.activeSession
            ?.takeIf {
                PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(oldScene, visualToScene)
            }
            ?.id
        if (playerLyricsSessionId != null) {
            playerLyricsTransitionCoordinator.updateProgress(transitionRatio)
        }
        val ratioDelta = abs(endRatio - startRatio)
        val animDuration = if (playerLyricsSessionId != null) {
            playerLyricsVelocityAwareDuration(duration, ratioDelta, velocity)
        } else {
            velocityAwareDuration(duration, ratioDelta, velocity)
        }
        val gen = ++sceneAnimGeneration
        sceneAnimator = ValueAnimator.ofFloat(startRatio, endRatio).apply {
            this.duration = animDuration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedValue as Float
                composeTransitionProgress = transitionRatio
                if (playerLyricsSessionId != null &&
                    playerLyricsTransitionCoordinator.activeSession?.id == playerLyricsSessionId
                ) {
                    playerLyricsTransitionCoordinator.updateProgress(transitionRatio)
                }
                onTransitionProgress?.invoke(visualToScene, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        isTransitioning = false
                        composeIsTransitioning = false
                        if (playerLyricsSessionId != null &&
                            playerLyricsTransitionCoordinator.activeSession?.id == playerLyricsSessionId
                        ) {
                            playerLyricsTransitionCoordinator.cancel()
                        }
                        return
                    }
                    currentScene = targetScene
                    transitionRatio = 0f
                    isTransitioning = false
                    composeCurrentScene = targetScene
                    composeIsTransitioning = false
                    composeTransitionProgress = 0f
                    if (playerLyricsSessionId != null &&
                        playerLyricsTransitionCoordinator.activeSession?.id == playerLyricsSessionId
                    ) {
                        playerLyricsTransitionCoordinator.finish()
                    }
                    if (sceneAnimGeneration == gen) {
                        onSceneChanged?.invoke(targetScene, oldScene)
                    }
                }
            })
            start()
        }
    }

    private fun playerLyricsVelocityAwareDuration(
        baseDuration: Long,
        ratioDelta: Float,
        ratioVelocityPerSecond: Float
    ): Long {
        if (ratioDelta <= 0f) return 1L
        val baseMs = (baseDuration * ratioDelta)
            .coerceIn(PLAYER_LYRIC_SETTLE_MIN_MS.toFloat(), baseDuration.toFloat())
        if (ratioVelocityPerSecond == 0f) return baseMs.toLong()
        val projectedMs = ratioDelta / abs(ratioVelocityPerSecond) * 1000f
        return minOf(baseMs, projectedMs)
            .coerceIn(PLAYER_LYRIC_SETTLE_MIN_MS.toFloat(), baseDuration.toFloat())
            .toLong()
    }

    private fun velocityAwareDuration(baseDuration: Long, ratioDelta: Float, velocity: Float): Long {
        if (ratioDelta <= 0f) return 1L
        val baseMs = (baseDuration * ratioDelta).coerceAtLeast(VELOCITY_ADAPT_MIN_MS.toFloat())
        if (velocity == 0f) return baseMs.toLong()
        val adapted = abs(1f / (1f / baseMs + abs(velocity) * VELOCITY_SENSITIVITY))
        return adapted.coerceIn(VELOCITY_ADAPT_MIN_MS.toFloat(), VELOCITY_ADAPT_MAX_MS.toFloat()).toLong()
    }

    companion object {
        const val SWIPE_THRESHOLD_RATIO = 0.30f
        private const val PAGE_DECELERATE = 2.0f
        private const val SCENE_ANIM_DURATION = 250L
        private const val PLAYER_LYRIC_SETTLE_MIN_MS = 80L
        private const val VELOCITY_ADAPT_MIN_MS = 100L
        private const val VELOCITY_ADAPT_MAX_MS = 250L
        private const val VELOCITY_SENSITIVITY = 0.002f
    }
}
