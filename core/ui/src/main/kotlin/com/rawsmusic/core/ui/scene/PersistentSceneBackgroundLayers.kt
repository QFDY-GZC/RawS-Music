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

private const val PERSISTENT_BACKGROUND_FRAME_INTERVAL_MS = 16L

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
    val touchesHome =
        navState.currentScene == NavScene.HOME ||
            navState.backPreviewScene == NavScene.HOME ||
            transitionFrame.active &&
            (transitionFrame.fromScene == NavScene.HOME ||
                transitionFrame.toScene == NavScene.HOME)
    val fromHome = transitionFrame.active && transitionFrame.fromScene == NavScene.HOME
    val toHome = transitionFrame.active && transitionFrame.toScene == NavScene.HOME
    val progress = transitionFrame.progress.coerceIn(0f, 1f)

    val carouselLayerAlpha = when {
        homeCarouselBackdropTransitionActive -> 1f
        fromHome && !toHome -> 1f - progress
        toHome && !fromHome -> progress
        else -> 0f
    }
    val rawLayerAlpha = when {
        homeCarouselBackdropTransitionActive -> 0f
        fromHome && !toHome -> progress
        toHome && !fromHome -> 1f - progress
        else -> 1f
    }
    val rawBackgroundAllowed = rawFlowLayerActive || touchesHome

    // Keep both renderers in the composition. Alpha is the only transition boundary, so the
    // rendered frame never falls through to the theme background during a scene commit.
    Box(modifier = modifier.fillMaxSize()) {
        // Keep alpha on a stable parent RenderNode. The artwork/flow composables remain mounted
        // and do not receive a changing modifier on every transition frame.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = carouselLayerAlpha },
        ) {
            HomeArtworkCarouselBackdrop(
                songs = homeCarouselSongs,
                currentSong = currentSong,
                state = homeCarouselState,
                active = carouselLayerAlpha > 0.001f || transitionFrame.active,
                motionEnabled = homeCarouselBackdropTransitionActive || transitionFrame.active,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (rawBackgroundAllowed) rawLayerAlpha else 0f },
        ) {
            RawFlowBackground(
                mode = rawFlowMode,
                sourceCoverKey = currentSong.resolvePlaybackArtworkKey(null),
                modifier = Modifier.fillMaxSize(),
                active = uiForeground && rawBackgroundAllowed &&
                    (rawLayerAlpha > 0.001f || transitionFrame.active),
                motionEnabled = rawFlowMotionActive || transitionFrame.active,
                frameIntervalMs = PERSISTENT_BACKGROUND_FRAME_INTERVAL_MS,
            )
        }
    }
}
