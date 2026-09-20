package com.rawsmusic.core.ui.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.pages.HomeArtworkCarouselBackdrop
import com.rawsmusic.core.ui.scene.pages.HomeArtworkCarouselState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.RawFlowMode
import com.rawsmusic.core.ui.widget.background.CustomMediaBackground

private const val PERSISTENT_BACKGROUND_FRAME_INTERVAL_MS = 16L

/**
 * Scene navigation must not turn an otherwise idle full-screen FLOW clock back on. RawFlow's
 * phase is derived from a global epoch and catches up when its frame loop resumes, so keeping the
 * callback alive during a short PivotTransition only spends GPU time without improving continuity.
 */
internal fun persistentRawFlowMotionEnabled(
    normalMotionActive: Boolean,
    @Suppress("UNUSED_PARAMETER") sceneTransitionActive: Boolean,
): Boolean = normalMotionActive

/**
 * Persistent background host for the app scene stack.
 *
 * The page host owns the transition, but this host stays mounted below it. This mirrors
 * A persistent layered renderer lets return transitions change layer alpha instead
 * of disposing the old background and recreating it from a fallback palette.
 */
@Composable
internal fun PersistentSceneBackgroundLayers(
    navState: NavigationState,
    transitionFrame: SceneTransitionFrameState,
    rawFlowMode: RawFlowMode,
    homeCarouselSongs: List<AudioFile>,
    currentSong: AudioFile?,
    homeCarouselState: HomeArtworkCarouselState,
    homeCarouselBackdropTransitionActive: Boolean,
    rawFlowLayerActive: Boolean,
    rawFlowMotionActive: Boolean,
    uiForeground: Boolean,
    modifier: Modifier = Modifier,
) {
    // NavigationState changes synchronously, while SceneTransitionHost publishes its first frame
    // from a LaunchedEffect. During that single handoff Compose would otherwise see a new library
    // route with an old idle frame and remove the source layer before motion starts. Treat the
    // pending route as the source-owned first frame, just as retained-view implementation enables transition mode on
    // the existing retained scene layout before changing its LayoutRes.
    val pendingLibraryTransition = !transitionFrame.active &&
        navState.currentScene != transitionFrame.toScene &&
        isReferenceLibraryScene(transitionFrame.fromScene) &&
        isReferenceLibraryScene(navState.currentScene)
    val frameActive = transitionFrame.active || pendingLibraryTransition
    val frameFromScene = transitionFrame.fromScene
    val frameToScene = if (pendingLibraryTransition) {
        navState.currentScene
    } else {
        transitionFrame.toScene
    }
    val touchesHome =
        navState.currentScene == NavScene.HOME ||
            navState.backPreviewScene == NavScene.HOME ||
            frameActive &&
            (frameFromScene == NavScene.HOME || frameToScene == NavScene.HOME)
    // Carousel/background blending belongs to library navigation only. Settings
    // returns must reveal the live flow directly, including the first return frame.
    val libraryBackgroundTransition = frameActive &&
        isReferenceLibraryScene(frameFromScene) && isReferenceLibraryScene(frameToScene)
    val fromHome = libraryBackgroundTransition && frameFromScene == NavScene.HOME
    val toHome = libraryBackgroundTransition && frameToScene == NavScene.HOME
    val committedBackgroundSingleOwnerTransition =
        frameActive &&
            isHomeCategorySceneTransition(frameFromScene, frameToScene) &&
            !homeCarouselBackdropTransitionActive
    val rawBackgroundAllowed = rawFlowLayerActive || touchesHome
    // HOME/category navigation keeps the committed playback background as one opaque owner. The
    // carousel is only a separate owner for an actual carousel-artwork transition; using it merely
    // because the scene transition touches HOME makes the animation end on carousel alpha=1 and
    // then snap back to RawFlow on the first settled frame (renderHomeBackdrop is disabled in the
    // page itself). That endpoint owner flip is visible as a post-transition flash.
    val carouselRendererActive = when {
        committedBackgroundSingleOwnerTransition -> false
        homeCarouselBackdropTransitionActive -> true
        libraryBackgroundTransition -> true
        else -> false
    }

    // Keep both renderers in the composition. Alpha is the only transition boundary, so the
    // rendered frame never falls through to the theme background during a scene commit.
    Box(modifier = modifier.fillMaxSize()) {
        // Keep alpha on a stable parent RenderNode. The artwork/flow composables remain mounted
        // and do not receive a changing modifier on every transition frame.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = if (pendingLibraryTransition) {
                        0f
                    } else {
                        transitionFrame.progress.coerceIn(0f, 1f)
                    }
                    alpha = when {
                        committedBackgroundSingleOwnerTransition -> 0f
                        homeCarouselBackdropTransitionActive -> 1f
                        fromHome && !toHome -> 1f - progress
                        toHome && !fromHome -> progress
                        else -> 0f
                    }
                },
        ) {
            HomeArtworkCarouselBackdrop(
                songs = homeCarouselSongs,
                currentSong = currentSong,
                state = homeCarouselState,
                active = carouselRendererActive,
                // A scene transition only changes layer alpha. Keep the carousel clock stopped
                // unless its artwork is actually changing; this mirrors the retained-view implementation's demand-driven
                // animation callbacks and avoids waking the renderer during category entry.
                motionEnabled = homeCarouselBackdropTransitionActive,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = if (pendingLibraryTransition) {
                        0f
                    } else {
                        transitionFrame.progress.coerceIn(0f, 1f)
                    }
                    val rawLayerAlpha = when {
                        committedBackgroundSingleOwnerTransition -> 1f
                        homeCarouselBackdropTransitionActive -> 0f
                        fromHome && !toHome -> progress
                        toHome && !fromHome -> 1f - progress
                        else -> 1f
                    }
                    alpha = if (rawBackgroundAllowed) rawLayerAlpha else 0f
                },
        ) {
            RawFlowBackground(
                mode = rawFlowMode,
                sourceCoverKey = currentSong.resolvePlaybackArtworkKey(null),
                modifier = Modifier.fillMaxSize(),
                active = uiForeground && rawBackgroundAllowed &&
                    (frameActive || !fromHome || toHome || homeCarouselBackdropTransitionActive),
                // FLOW uses a global phase epoch and catches up on re-enable. Keep the caller's
                // normal demand-driven motion policy during scene handoff instead of waking a
                // full-screen 16 ms animation loop underneath every VirtualList PivotTransition frame.
                motionEnabled = persistentRawFlowMotionEnabled(
                    normalMotionActive = rawFlowMotionActive,
                    sceneTransitionActive = frameActive,
                ),
                frameIntervalMs = PERSISTENT_BACKGROUND_FRAME_INTERVAL_MS,
            )
        }

        CustomMediaBackground(
            active = uiForeground,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
