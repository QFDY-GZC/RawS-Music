package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.SceneTransitionFrameState
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsLayout
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsMode
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.TopChromeStyle
import dev.chrisbanes.haze.HazeState

/**
 * Stable publisher/renderer split for the floating library toolbar.
 *
 * retained-view implementation keeps the bottom-toolbar slot in the persistent VirtualList owner instead of composing a
 * second page-sized overlay during PivotTransition. Raw mirrors that ownership boundary here: pages
 * publish only action callbacks, while AppMainLayout renders the toolbar after the scene backdrop.
 * The toolbar therefore never enters the retained list ViewGroup transition tree and can safely
 * sample the already-recorded app backdrop without recursively sampling itself.
 */
@Stable
internal class LibraryFloatingToolbarController {
    private val registrations = mutableStateMapOf<String, LibraryFloatingToolbarRegistration>()

    fun bind(next: LibraryFloatingToolbarRegistration) {
        if (registrations[next.sceneId] !== next) {
            registrations[next.sceneId] = next
        }
    }

    fun release(current: LibraryFloatingToolbarRegistration) {
        if (registrations[current.sceneId] === current) {
            registrations.remove(current.sceneId)
        }
    }

    fun registration(scene: NavScene): LibraryFloatingToolbarRegistration? = registrations[scene.name]
}

internal class LibraryFloatingToolbarRegistration(
    val sceneId: String,
    val visibilityAlpha: State<Float>,
    val onSelect: State<(() -> Unit)?>,
    val onPlay: State<(() -> Unit)?>,
    val onSearch: State<() -> Unit>,
    val onShuffle: State<(() -> Unit)?>,
    val onMore: State<() -> Unit>,
    val onCreatePlaylist: State<(() -> Unit)?>,
    val onImportPlaylist: State<(() -> Unit)?>,
)

internal val LocalLibraryFloatingToolbarController =
    staticCompositionLocalOf<LibraryFloatingToolbarController?> { null }

@Composable
internal fun PublishLibraryFloatingToolbar(
    sceneId: String,
    visibilityAlpha: Float = 1f,
    onSelect: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onSearch: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: () -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onImportPlaylist: (() -> Unit)?,
) {
    val controller = LocalLibraryFloatingToolbarController.current ?: return
    val alpha = rememberUpdatedState(visibilityAlpha)
    val select = rememberUpdatedState(onSelect)
    val play = rememberUpdatedState(onPlay)
    val search = rememberUpdatedState(onSearch)
    val shuffle = rememberUpdatedState(onShuffle)
    val more = rememberUpdatedState(onMore)
    val create = rememberUpdatedState(onCreatePlaylist)
    val import = rememberUpdatedState(onImportPlaylist)
    val registration = remember(sceneId) {
        LibraryFloatingToolbarRegistration(
            sceneId = sceneId,
            visibilityAlpha = alpha,
            onSelect = select,
            onPlay = play,
            onSearch = search,
            onShuffle = shuffle,
            onMore = more,
            onCreatePlaylist = create,
            onImportPlaylist = import,
        )
    }

    DisposableEffect(controller, registration) {
        controller.bind(registration)
        onDispose { controller.release(registration) }
    }
}

@Composable
internal fun BoxScope.LibraryFloatingToolbarHost(
    controller: LibraryFloatingToolbarController,
    currentScene: NavScene,
    transitionFrame: SceneTransitionFrameState,
    hazeState: HazeState,
    backdrop: Backdrop?,
) {
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val bottomButtonsMode by PersonalizationPreferences.libraryBottomButtonsMode.collectAsState()
    val bottomButtonsLayout by PersonalizationPreferences.libraryBottomButtonsLayout.collectAsState()
    val bottomButtonsSurface by PersonalizationPreferences.libraryBottomButtonsSurface.collectAsState()
    val floatingChromeSelected = topChromeStyle == TopChromeStyle.FLOATING ||
        topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    if (!floatingChromeSelected) return
    if (bottomButtonsMode == LibraryBottomButtonsMode.DISABLED) return

    val vertical = bottomButtonsLayout == LibraryBottomButtonsLayout.VERTICAL
    val active = transitionFrame.active && transitionFrame.fromScene != transitionFrame.toScene
    val registrations = if (active) {
        listOfNotNull(
            controller.registration(transitionFrame.fromScene),
            controller.registration(transitionFrame.toScene),
        ).distinctBy { it.sceneId }
    } else {
        listOfNotNull(controller.registration(currentScene))
    }
    if (registrations.isEmpty()) return

    val windowInfo = LocalWindowInfo.current
    val viewportCenter = Offset(
        x = windowInfo.containerSize.width * 0.5f,
        y = windowInfo.containerSize.height * 0.5f,
    )

    registrations.forEach { registration ->
        val role = when {
            !active -> LibraryTopChromeSceneRole.IDLE
            registration.sceneId == transitionFrame.fromScene.name -> LibraryTopChromeSceneRole.EXIT
            registration.sceneId == transitionFrame.toScene.name -> LibraryTopChromeSceneRole.ENTER
            else -> LibraryTopChromeSceneRole.IDLE
        }
        key(registration.sceneId) {
            val entranceProgress = remember {
                Animatable(if (active) 1f else 0f)
            }
            LaunchedEffect(registration.sceneId) {
                if (entranceProgress.value < 1f) {
                    entranceProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 180),
                    )
                }
            }
            var centerInRoot by remember(registration) { mutableStateOf(Offset.Unspecified) }
            val sceneScaleProvider = remember(transitionFrame, role) {
                {
                    val p = transitionFrame.progress.coerceIn(0f, 1f)
                    when (role) {
                        LibraryTopChromeSceneRole.IDLE -> 1f
                        LibraryTopChromeSceneRole.EXIT -> if (transitionFrame.isBack) {
                            // Back: current/source list contracts 1 -> 0.5.
                            1f - 0.5f * p
                        } else {
                            // Forward: retained/source list expands 1 -> 1.5.
                            1f + 0.5f * p
                        }
                        LibraryTopChromeSceneRole.ENTER -> if (transitionFrame.isBack) {
                            // Back: retained destination resolves 1.5 -> 1.
                            1.5f - 0.5f * p
                        } else {
                            // Forward: current destination resolves 0.5 -> 1.
                            0.5f + 0.5f * p
                        }
                    }
                }
            }
            val sceneExitProgressProvider = if (active) {
                { transitionFrame.progress.coerceIn(0f, 1f) }
            } else {
                null
            }
            FloatingLibraryActionBar(
                onSelect = registration.onSelect.value,
                onPlay = registration.onPlay.value,
                onSearch = registration.onSearch.value,
                onShuffle = registration.onShuffle.value,
                onMore = registration.onMore.value,
                onCreatePlaylist = registration.onCreatePlaylist.value,
                onImportPlaylist = registration.onImportPlaylist.value,
                vertical = vertical,
                // Settled-page ownership appears outside PivotTransition, so give the toolbar its
                // own one-shot 0.85 -> 1.0 entrance. A destination first composed inside an active
                // scene transition starts at 1.0 here; its scale/fade remain owned exclusively by
                // PivotTransition and won't replay at the endpoint.
                visibilityProgress = entranceProgress.value,
                maxAlpha = bottomButtonsMode.maxAlpha * registration.visibilityAlpha.value.coerceIn(0f, 1f),
                surface = bottomButtonsSurface,
                hazeState = hazeState,
                backdrop = backdrop,
                transitionAlpha = 1f,
                transitionAlphaProvider = null,
                sceneExitProgressProvider = sceneExitProgressProvider,
                sceneRole = role,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (vertical) 40.dp else 56.dp)
                    .onGloballyPositioned { coordinates ->
                        centerInRoot = coordinates.boundsInRoot().center
                    }
                    .graphicsLayer {
                        val sceneScale = sceneScaleProvider().coerceIn(0.5f, 1.5f)
                        val center = centerInRoot
                        if (center.x.isFinite() && center.y.isFinite()) {
                            // Apply holder scale around the same viewport pivot as the retained list.
                            // Local scaling alone keeps the toolbar center fixed and is the reason the
                            // old sibling implementation looked detached from PivotTransition.
                            translationX = (center.x - viewportCenter.x) * (sceneScale - 1f)
                            translationY = (center.y - viewportCenter.y) * (sceneScale - 1f)
                        }
                        scaleX = sceneScale
                        scaleY = sceneScale
                        clip = false
                    }
                    .zIndex(if (role == LibraryTopChromeSceneRole.ENTER) 56f else 55f),
            )
        }
    }
}
