package com.rawsmusic.core.ui.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

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

    // The committed scene participates in mainPlayerSheetExpansionState. Keep it observable so
    // silent endpoint commits (used by the immersive dismiss host) invalidate that derived state
    // just like animated transitions do. A plain field leaves expansion cached at 1f after
    // PLAYER -> MAIN silent commit, making the library alpha stay at zero while hit targets remain.
    var currentScene by mutableStateOf(Scene.MAIN)
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

    /** Outer immersive dismiss progress used only to reveal retained MAIN underneath PLAYER. */
    private var externalMainDismissProgress by mutableFloatStateOf(0f)

    /**
     * Stable state object for the persistent MAIN/PLAYER sheet.
     *
     * Passing the float through MainActivity made the whole activity content recompose on every
     * drag/settle frame. Updating already-laid-out sheet and artwork nodes avoids this;
     * consumers should pass this State object down and read it only in the sheet subtree.
     */
    val mainPlayerSheetExpansionState: State<Float> = derivedStateOf {
        currentMainPlayerExpansion()
    }

    fun updateExternalMainDismissProgress(progress: Float) {
        externalMainDismissProgress = progress.coerceIn(0f, 1f)
    }

    /** True only while the finger drives the scene; release animations are not interactive. */
    var composeIsInteractiveGesture by mutableStateOf(false)
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
    private var playerReturnPreparationToken = 0
    private var playerReturnPreparationActive = false
    private var mainPlayerSheetDragActive = false
    private var mainPlayerSheetDragStartExpansion = 0f
    private var mainPlayerSheetCommittedScene = Scene.MAIN
    // ViewDragHelper's velocity branch normalizes the remaining *physical* drag distance by the
    // parent width, while its no-velocity branch uses the drag range. Compose keeps expansion as a
    // ratio, so retain travel/width once the sheet is measured and reconstruct that pixel ratio.
    private var mainPlayerSheetTravelToWidthRatio = 1f

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
        playerReturnPreparationToken++
        playerReturnPreparationActive = false
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
        composeIsInteractiveGesture = false
        externalMainDismissProgress = 0f
        mainPlayerSheetDragActive = false
        mainPlayerSheetDragStartExpansion = if (targetScene == Scene.PLAYER) 1f else 0f
        mainPlayerSheetCommittedScene = targetScene
        playerLyricsTransitionCoordinator.finish()
        if (oldScene != targetScene) {
            onSceneChanged?.invoke(targetScene, oldScene)
        }
    }

    fun startCoverDrag(targetScene: Scene = Scene.MAIN) {
        if (targetScene == Scene.MAIN && isMainPlayerSheetAvailable()) {
            beginMainPlayerSheetDrag()
            return
        }
        if (isTransitioning || composeIsTransitioning) return
        if (currentScene != Scene.PLAYER && currentScene != Scene.LYRIC && currentScene != Scene.ALBUM_DETAIL) return
        if (currentScene == targetScene) return
        startInteractiveDrag(currentScene, targetScene)
    }

    fun startCoverDrag(swipeRight: Boolean, targetScene: Scene = Scene.MAIN) {
        startCoverDrag(targetScene)
    }

    fun updateCoverDrag(ratio: Float) {
        if (mainPlayerSheetDragActive) {
            updateMainPlayerSheetExpansion(mainPlayerSheetDragStartExpansion - ratio)
            return
        }
        updateInteractiveDrag(ratio)
    }

    fun updateCoverDragProgress(ratio: Float) {
        updateCoverDrag(ratio)
    }

    fun endCoverDrag(shouldClose: Boolean, duration: Long = SCENE_ANIM_DURATION, velocity: Float = 0f) {
        if (mainPlayerSheetDragActive) {
            // The gesture owner has already applied the platform minimum-fling threshold and
            // nearest-anchor rule. Do not make a second decision in normalized coordinates: the
            // ViewDragHelper chooses the anchor once, then the settler only animates to it.
            settleMainPlayerSheet(expanded = !shouldClose, velocity = -velocity)
            return
        }
        val visualToScene = toScene
        val target = if (shouldClose) visualToScene else fromScene
        val endRatio = if (shouldClose) 1f else 0f
        val mainPlayerTransition =
            (fromScene == Scene.PLAYER && visualToScene == Scene.MAIN) ||
                (fromScene == Scene.MAIN && visualToScene == Scene.PLAYER)
        settleScene(
            oldScene = fromScene,
            targetScene = target,
            duration = if (mainPlayerTransition) PLAYER_SCENE_ANIM_DURATION else duration,
            startRatio = transitionRatio,
            endRatio = endRatio,
            velocity = velocity,
            visualToScene = visualToScene,
            velocityIsRatioPerSecond = mainPlayerTransition
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
        if (isMainPlayerVisualTransition()) {
            beginMainPlayerSheetDrag()
            return
        }
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
        if (mainPlayerSheetDragActive) {
            updateMainPlayerSheetExpansion(mainPlayerSheetDragStartExpansion + ratio)
            return
        }
        updateInteractiveDrag(ratio)
    }

    fun endCoverSwipeUpDrag(shouldOpen: Boolean, duration: Long = SCENE_ANIM_DURATION, velocity: Float = 0f) {
        if (mainPlayerSheetDragActive) {
            settleMainPlayerSheet(expanded = shouldOpen, velocity = velocity)
            return
        }
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
        sceneAnimGeneration++
        sceneAnimator?.cancel()
        sceneAnimator = null

        // Lifecycle/style changes may interrupt a settling animation while MainActivity is
        // backgrounded by a settings screen. The committed scene is authoritative; leaving the
        // Compose visual pair on PLAYER with progress reset to zero makes MAIN render underneath
        // while the bottom chrome still believes the player sheet is fully expanded. Canonicalize
        // every visual field to the committed scene before accepting new gestures.
        val committed = currentScene
        fromScene = committed
        toScene = committed
        composeFromScene = committed
        composeToScene = committed
        composeCurrentScene = committed
        transitionRatio = 0f
        isTransitioning = false
        composeIsTransitioning = false
        composeTransitionProgress = 0f
        composeIsInteractiveGesture = false
        externalMainDismissProgress = 0f
        mainPlayerSheetDragActive = false
        mainPlayerSheetDragStartExpansion = if (committed == Scene.PLAYER) 1f else 0f
        mainPlayerSheetCommittedScene = committed
        playerReturnPreparationActive = false
        playerLyricsTransitionCoordinator.cancel()
    }

    fun forceReapplyCurrentScene() {
        val scene = currentScene
        onSceneChanged?.invoke(scene, scene)
    }

    fun openPlayPage(animated: Boolean = true) {
        if (currentScene != Scene.MAIN) return
        onPrepareMainToPlayer?.invoke()
        if (animated) {
            transitionToScene(Scene.PLAYER, duration = PLAYER_SCENE_ANIM_DURATION)
        } else {
            switchToSceneSilent(Scene.PLAYER)
        }
    }

    /**
     * Starts the main-to-player bottom-sheet gesture.
     *
     * Unlike opening the player after the gesture, this mounts the player scene at ratio 0 and
     * lets the caller drive the same transition ratio for the whole drag. The release only
     * chooses the final anchor, so there is no second entrance animation after the finger lifts.
     */
    fun startMainToPlayerDrag() {
        if (!isMainPlayerSheetAvailable()) return
        if (currentMainPlayerExpansion() <= 0f) {
            onPrepareMainToPlayer?.invoke()
        }
        beginMainPlayerSheetDrag()
    }

    fun hasMainPlayerSheetBackTarget(): Boolean =
        isMainPlayerSheetAvailable() && currentMainPlayerExpansion() > 0f

    fun startMainPlayerPredictiveBack(): Boolean {
        if (!hasMainPlayerSheetBackTarget()) return false
        beginMainPlayerSheetDrag()
        return true
    }

    fun updateMainPlayerPredictiveBack(progress: Float) {
        if (!mainPlayerSheetDragActive) return
        updateMainPlayerSheetExpansion(
            mainPlayerSheetDragStartExpansion * (1f - progress.coerceIn(0f, 1f))
        )
    }

    fun finishMainPlayerPredictiveBack(commit: Boolean) {
        if (!mainPlayerSheetDragActive) return
        settleMainPlayerSheet(expanded = !commit, velocity = 0f)
    }

    fun updateMainToPlayerDrag(ratio: Float) {
        if (!mainPlayerSheetDragActive) return
        updateMainPlayerSheetExpansion(ratio)
    }

    fun updateMainPlayerSheetGeometry(travelPx: Float, parentWidthPx: Float) {
        if (travelPx <= 0f || parentWidthPx <= 0f) return
        mainPlayerSheetTravelToWidthRatio = travelPx / parentWidthPx
    }

    fun endMainToPlayerDrag(shouldOpen: Boolean, velocity: Float = 0f) {
        if (!mainPlayerSheetDragActive) return
        settleMainPlayerSheet(expanded = shouldOpen, velocity = -velocity)
    }

    /**
     * MAIN and PLAYER remain the two anchors of one draggable sheet. A new pointer may
     * capture that sheet while ViewDragHelper is still settling it, so preserve the absolute
     * expansion instead of restarting a route-relative transition at zero.
     */
    private fun beginMainPlayerSheetDrag() {
        val expansion = currentMainPlayerExpansion()
        mainPlayerSheetCommittedScene = currentScene
        sceneAnimGeneration++
        sceneAnimator?.cancel()
        sceneAnimator = null
        playerLyricsTransitionCoordinator.finish()
        fromScene = Scene.MAIN
        toScene = Scene.PLAYER
        composeFromScene = Scene.MAIN
        composeToScene = Scene.PLAYER
        transitionRatio = expansion
        composeTransitionProgress = expansion
        isTransitioning = true
        composeIsTransitioning = true
        composeIsInteractiveGesture = true
        mainPlayerSheetDragStartExpansion = expansion
        mainPlayerSheetDragActive = true
        onTransitionProgress?.invoke(Scene.PLAYER, expansion)
    }

    private fun updateMainPlayerSheetExpansion(expansion: Float) {
        if (!mainPlayerSheetDragActive) return
        val clamped = expansion.coerceIn(0f, 1f)
        transitionRatio = clamped
        composeTransitionProgress = clamped
        onTransitionProgress?.invoke(Scene.PLAYER, clamped)
    }

    private fun settleMainPlayerSheet(expanded: Boolean, velocity: Float) {
        val start = transitionRatio.coerceIn(0f, 1f)
        val end = if (expanded) 1f else 0f
        val targetScene = if (expanded) Scene.PLAYER else Scene.MAIN
        val callbackOldScene = mainPlayerSheetCommittedScene
        mainPlayerSheetDragActive = false
        sceneAnimator?.cancel()
        fromScene = Scene.MAIN
        toScene = Scene.PLAYER
        composeFromScene = Scene.MAIN
        composeToScene = Scene.PLAYER
        transitionRatio = start
        composeTransitionProgress = start
        isTransitioning = true
        composeIsTransitioning = true
        composeIsInteractiveGesture = false

        val gen = ++sceneAnimGeneration
        sceneAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = mainPlayerSheetVelocityAwareDuration(
                abs(end - start),
                velocity,
            )
            interpolator = PLAYER_SHEET_INTERPOLATOR
            addUpdateListener { animation ->
                transitionRatio = animation.animatedValue as Float
                composeTransitionProgress = transitionRatio
                onTransitionProgress?.invoke(Scene.PLAYER, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (sceneAnimator === animation) sceneAnimator = null
                    if (cancelled || sceneAnimGeneration != gen) return
                    currentScene = targetScene
                    composeCurrentScene = targetScene
                    transitionRatio = 0f
                    composeTransitionProgress = 0f
                    isTransitioning = false
                    composeIsTransitioning = false
                    composeIsInteractiveGesture = false
                    mainPlayerSheetDragActive = false
                    if (callbackOldScene != targetScene) {
                        onSceneChanged?.invoke(targetScene, callbackOldScene)
                    }
                }
            })
            start()
        }
    }

    private fun currentMainPlayerExpansion(): Float = when {
        externalMainDismissProgress > 0f && !composeIsTransitioning && currentScene != Scene.MAIN ->
            (1f - externalMainDismissProgress).coerceIn(0f, 1f)
        composeIsTransitioning && composeFromScene == Scene.MAIN && composeToScene == Scene.PLAYER ->
            composeTransitionProgress.coerceIn(0f, 1f)
        composeIsTransitioning && composeFromScene == Scene.PLAYER && composeToScene == Scene.MAIN ->
            (1f - composeTransitionProgress).coerceIn(0f, 1f)
        // currentScene is the committed anchor. composeCurrentScene is a drawing mirror and can
        // temporarily be stale if a lifecycle/style change interrupted a visual transition. Never
        // let that stale mirror keep MAIN's MiniPlayer/navigation hidden.
        currentScene == Scene.PLAYER -> 1f
        else -> 0f
    }

    private fun isMainPlayerSheetAvailable(): Boolean {
        val visualPair = isMainPlayerVisualTransition()
        return visualPair || currentScene == Scene.MAIN || currentScene == Scene.PLAYER
    }

    private fun isMainPlayerVisualTransition(): Boolean = composeIsTransitioning &&
        ((composeFromScene == Scene.MAIN && composeToScene == Scene.PLAYER) ||
            (composeFromScene == Scene.PLAYER && composeToScene == Scene.MAIN))

    fun closePlayPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        closePlayerStackToMain(animated)
    }

    fun closePlayPageWithCoverAlign(animated: Boolean = true) {
        closePlayPage(animated)
    }

    fun closeCurrentPlayerStackToMain(animated: Boolean = true) {
        if (currentScene == Scene.MAIN) return
        closePlayerStackToMain(animated)
    }

    /**
     * Prepare the source artwork before starting a player -> main transition.
     *
     * The old path started the animator immediately and asked MainActivity for the list target
     * afterwards. That left one frame with no valid shared target, so the player flashed and the
     * next open was committed silently. Wait until both endpoints are measured first.
     */
    private fun closePlayerStackToMain(animated: Boolean) {
        if (currentScene == Scene.MAIN) return
        if (!animated) {
            playerReturnPreparationToken++
            playerReturnPreparationActive = false
            switchToSceneSilent(Scene.MAIN)
            return
        }
        if (playerReturnPreparationActive) return

        val token = ++playerReturnPreparationToken
        playerReturnPreparationActive = true
        val proceed: () -> Unit = proceed@{
            if (token != playerReturnPreparationToken || !playerReturnPreparationActive) return@proceed
            playerReturnPreparationActive = false
            if (currentScene != Scene.MAIN) {
                transitionToScene(Scene.MAIN, duration = PLAYER_SCENE_ANIM_DURATION)
            }
        }
        val prepare = onPreparePlayerToMain
        if (prepare != null) {
            prepare(proceed)
        } else {
            proceed()
        }
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
        composeIsInteractiveGesture = true
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
        visualToScene: Scene = targetScene,
        velocityIsRatioPerSecond: Boolean = false,
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
        composeIsInteractiveGesture = false
        val playerLyricsSessionId = playerLyricsTransitionCoordinator.activeSession
            ?.takeIf {
                PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(oldScene, visualToScene)
            }
            ?.id
        if (playerLyricsSessionId != null) {
            playerLyricsTransitionCoordinator.updateProgress(transitionRatio)
        }
        val ratioDelta = abs(endRatio - startRatio)
        val mainPlayerTransition =
            (oldScene == Scene.MAIN && visualToScene == Scene.PLAYER) ||
                (oldScene == Scene.PLAYER && visualToScene == Scene.MAIN)
        val animDuration = if (playerLyricsSessionId != null) {
            playerLyricsVelocityAwareDuration(duration, ratioDelta, velocity)
        } else if (mainPlayerTransition) {
            playerSceneVelocityAwareDuration(duration, ratioDelta, velocity)
        } else {
            velocityAwareDuration(duration, ratioDelta, velocity)
        }
        val gen = ++sceneAnimGeneration
        sceneAnimator = ValueAnimator.ofFloat(startRatio, endRatio).apply {
            this.duration = animDuration
            interpolator = if (
                (oldScene == Scene.MAIN && visualToScene == Scene.PLAYER) ||
                (oldScene == Scene.PLAYER && visualToScene == Scene.MAIN)
            ) {
                PLAYER_SHEET_INTERPOLATOR
            } else {
                DecelerateInterpolator(PAGE_DECELERATE)
            }
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
                        composeIsInteractiveGesture = false
                        if (playerLyricsSessionId != null &&
                            playerLyricsTransitionCoordinator.activeSession?.id == playerLyricsSessionId
                        ) {
                            playerLyricsTransitionCoordinator.cancel()
                        }
                        return
                    }
                    currentScene = targetScene
                    if (playerLyricsSessionId != null &&
                        playerLyricsTransitionCoordinator.activeSession?.id == playerLyricsSessionId
                    ) {
                        // ValueAnimator normally delivers the terminal update before onAnimationEnd,
                        // but make the endpoint explicit. The session keeps this value after
                        // finish() so any retained old graphicsLayer still draws the committed
                        // target until Compose applies the settled scene tree.
                        playerLyricsTransitionCoordinator.updateProgress(endRatio)
                    }
                    transitionRatio = 0f
                    isTransitioning = false
                    composeCurrentScene = targetScene
                    composeIsTransitioning = false
                    composeTransitionProgress = 0f
                    composeIsInteractiveGesture = false
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

    /**
     * Persistent NORMAL sheet settle duration. [widthVelocityPerSecond] is physical Y velocity
     * divided by the sheet parent width after platform min/max fling clamping, which makes this
     * algebraically equivalent to ViewDragHelper.computeAxisDuration for the vertical axis.
     */
    private fun mainPlayerSheetVelocityAwareDuration(
        ratioDelta: Float,
        widthVelocityPerSecond: Float,
    ): Long {
        if (ratioDelta <= 0f) return 1L
        val distance = ratioDelta.coerceIn(0f, 1f)
        val velocity = abs(widthVelocityPerSecond)
        val durationMs = if (velocity == 0f) {
            // ViewDragHelper uses delta / verticalDragRange when velocity is zero. Because
            // distance is already expansion delta, this branch is exactly equivalent.
            (distance + 1f) * PLAYER_SCENE_NO_VELOCITY_STEP_MS
        } else {
            // Velocity branch is different: distanceRatio = abs(deltaPx) / parentWidth. Rebuild
            // that from expansion delta rather than incorrectly feeding expansion itself.
            val distanceToWidth = minOf(1f, distance * mainPlayerSheetTravelToWidthRatio)
            val sineDistance = sin(
                (distanceToWidth - 0.5f) * PLAYER_SCENE_SINE_RADIANS
            ) * 0.5f + 0.5f
            kotlin.math.round((sineDistance / velocity) * 1000f) * PLAYER_SCENE_VELOCITY_SCALE
        }
        return durationMs
            .roundToLong()
            .coerceIn(1L, PLAYER_SCENE_SETTLE_MAX_MS)
    }

    /** Legacy/non-persistent MAIN <-> PLAYER duration path. Keep its historical ratio velocity
     * semantics so FLOATING style is not changed by the NORMAL bottom-sheet alignment. */
    private fun playerSceneVelocityAwareDuration(
        baseDuration: Long,
        ratioDelta: Float,
        ratioVelocityPerSecond: Float,
    ): Long {
        if (ratioDelta <= 0f) return 1L
        val distance = ratioDelta.coerceIn(0f, 1f)
        val velocity = abs(ratioVelocityPerSecond)
        val durationMs = if (velocity < PLAYER_SCENE_MIN_FLING_RATIO_PER_SECOND) {
            (distance + 1f) * PLAYER_SCENE_NO_VELOCITY_STEP_MS
        } else {
            val sineDistance = sin(
                (minOf(1f, distance) - 0.5f) * PLAYER_SCENE_SINE_RADIANS
            ) * 0.5f + 0.5f
            kotlin.math.round((sineDistance / velocity) * 1000f) * PLAYER_SCENE_VELOCITY_SCALE
        }
        return durationMs
            .roundToLong()
            .coerceIn(1L, PLAYER_SCENE_SETTLE_MAX_MS)
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
        private const val PLAYER_SCENE_ANIM_DURATION = 600L
        private const val PLAYER_SCENE_SETTLE_MAX_MS = 600L
        private const val PLAYER_SCENE_NO_VELOCITY_STEP_MS = 256f
        private const val PLAYER_SCENE_SINE_RADIANS = 0.47123894f
        private const val PLAYER_SCENE_VELOCITY_SCALE = 4f
        private const val PLAYER_SCENE_MIN_FLING_RATIO_PER_SECOND = 0.06f
        private const val PLAYER_LYRIC_SETTLE_MIN_MS = 80L
        private const val VELOCITY_ADAPT_MIN_MS = 100L
        private const val VELOCITY_ADAPT_MAX_MS = 250L
        private const val VELOCITY_SENSITIVITY = 0.002f
        private val PLAYER_SHEET_INTERPOLATOR = Interpolator { value ->
            val shifted = value - 1f
            shifted * shifted * shifted * shifted * shifted + 1f
        }
    }
}
