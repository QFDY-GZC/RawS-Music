package com.rawsmusic

import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.core.ui.widget.PlayerSceneController

/**
 * Owns the scene-navigation bridges that remain while the player is hosted by MainActivity.
 * The Activity keeps the entry points for Compose callbacks, while this class owns the
 * cross-scene bookkeeping and the legacy destination compatibility path.
 */
internal class MainActivitySceneNavigationCoordinator(
    private val navigationState: NavigationState,
    private val currentPlayerScene: () -> PlayerSceneController.Scene?,
    private val registerCoverCollapseParams: () -> Unit,
    private val closeLyricPage: (Boolean) -> Unit,
    private val closePlayPage: (Boolean) -> Unit,
    private val postDelayed: (Long, () -> Unit) -> Unit,
    private val setPendingSettingsScene: (NavScene) -> Unit,
    private val updateComposeRootVisibility: (Boolean) -> Unit,
    private val launchSettingsActivity: (Class<*>) -> Unit,
    private val updateDrawerLockMode: () -> Unit,
    private val prePlayerFragmentDestination: () -> Int?,
    private val prePlayerWasInFragmentMode: () -> Boolean,
    private val prePlayerContainerScene: () -> NavScene?,
    private val songsDestinationId: Int,
    private val setLegacyDestination: (Int) -> Unit,
    private val legacyDestination: () -> Int,
) {
    fun openDestinationFromPlayerPopup(destinationId: Int) {
        runCatching { registerCoverCollapseParams() }

        val targetScene = MainActivityNavigationPolicy.settingsSceneForDestination(destinationId)
        when (currentPlayerScene()) {
            PlayerSceneController.Scene.LYRIC -> {
                setPendingSettingsScene(targetScene)
                closeLyricPage(true)
                postDelayed(180L) {
                    if (currentPlayerScene() == PlayerSceneController.Scene.PLAYER) {
                        closePlayPage(true)
                    }
                }
            }

            PlayerSceneController.Scene.PLAYER -> {
                setPendingSettingsScene(targetScene)
                closePlayPage(true)
            }

            else -> navigateToSettingsScene(targetScene)
        }
    }

    fun navigateSettingsForward(destinationId: Int) {
        navigateToSettingsScene(MainActivityNavigationPolicy.settingsSceneForDestination(destinationId))
    }

    fun navigateSettingsBack() {
        if (!navigationState.navigateBackAnimated()) {
            navigationState.navigateHome()
        }
    }

    fun navigateToSettingsScene(scene: NavScene) {
        updateComposeRootVisibility(true)
        if (currentPlayerScene()?.let { it != PlayerSceneController.Scene.MAIN } == true) {
            setPendingSettingsScene(scene)
            closePlayPage(true)
            return
        }

        val activityClass = SETTINGS_ACTIVITY_MAP[scene]
        if (activityClass != null) {
            navigationState.navigateHome()
            launchSettingsActivity(activityClass)
        } else if (navigationState.currentScene != scene) {
            navigationState.navigateToSettings(scene)
        }
        updateDrawerLockMode()
    }

    fun legacyNavigateTo(destinationId: Int) {
        setLegacyDestination(destinationId)
    }

    fun legacyNavigateUp(): Boolean {
        if (legacyDestination() == songsDestinationId) return false
        setLegacyDestination(songsDestinationId)
        return true
    }

    fun legacyPopToSongs(): Boolean {
        val changed = legacyDestination() != songsDestinationId
        setLegacyDestination(songsDestinationId)
        return changed
    }

    fun switchToContainerMode(targetScene: NavScene? = null) {
        targetScene?.let(navigationState::switchToSilent)
        updateComposeRootVisibility(true)
    }

    fun prepareContainerForPlayerReturn() {
        val songsDestination = prePlayerWasInFragmentMode() &&
            (prePlayerFragmentDestination() == null || prePlayerFragmentDestination() == songsDestinationId)
        when {
            songsDestination -> switchToContainerMode(NavScene.SONGS)
            !prePlayerWasInFragmentMode() -> switchToContainerMode(prePlayerContainerScene())
            else -> updateComposeRootVisibility(true)
        }
    }
}
