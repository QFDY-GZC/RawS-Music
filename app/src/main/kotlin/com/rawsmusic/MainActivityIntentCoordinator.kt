package com.rawsmusic

import android.content.Intent
import android.os.Handler
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Routes launcher shortcuts and widget intents without coupling them to Activity rendering. */
internal class MainActivityIntentCoordinator(
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val isFinishing: () -> Boolean,
    private val isDestroyed: () -> Boolean,
    private val sceneController: () -> PlayerSceneController?,
    private val mainNavigation: () -> NavigationState,
    private val ensureController: (String) -> PlayerController,
    private val openPlayerPage: () -> Unit,
    private val updateRootVisibility: (Boolean) -> Unit,
    private val primePlayerUi: (AudioFile) -> Unit,
    private val openQueuePage: () -> Unit,
    private val openPlaylistPicker: () -> Unit,
    private val showNoSongs: () -> Unit,
) {
    fun handleLauncherShortcutIntent(intent: Intent?, delayMs: Long) {
        val shortcutAction = intent?.action?.takeIf {
            it == MainActivity.ACTION_SHORTCUT_PLAY ||
                it == MainActivity.ACTION_SHORTCUT_SEARCH ||
                it == MainActivity.ACTION_SHORTCUT_SHUFFLE
        } ?: return

        // A recreated Activity must not execute the same launcher action a second time.
        intent.action = Intent.ACTION_MAIN
        scope.launch(Dispatchers.Main) {
            delay(delayMs)
            val scenes = sceneController()
            if (isFinishing() || isDestroyed() || scenes == null) return@launch

            when (shortcutAction) {
                MainActivity.ACTION_SHORTCUT_SEARCH -> {
                    scenes.switchToSceneSilent(PlayerSceneController.Scene.MAIN)
                    mainNavigation().navigateHome()
                    mainNavigation().navigateTo(com.rawsmusic.core.ui.scene.NavScene.SEARCH)
                    updateRootVisibility(true)
                }

                MainActivity.ACTION_SHORTCUT_PLAY -> {
                    val controller = ensureController("launcher_shortcut_play")
                    if (controller.playState.value != com.rawsmusic.core.common.model.PlayState.PLAYING) {
                        controller.playPause()
                    }
                    delay(160L)
                    if (controller.currentSong.value != null &&
                        scenes.currentScene == PlayerSceneController.Scene.MAIN
                    ) {
                        openPlayerPage()
                    }
                }

                MainActivity.ACTION_SHORTCUT_SHUFFLE -> {
                    var songs = MusicRepository.songs.value
                    var attempts = 0
                    while (songs.isEmpty() && attempts < 40) {
                        delay(150L)
                        songs = MusicRepository.songs.value
                        attempts++
                    }
                    if (songs.isEmpty()) {
                        showNoSongs()
                        return@launch
                    }

                    val controller = ensureController("launcher_shortcut_shuffle")
                    controller.setPlayMode(PlayMode.SHUFFLE_ALL)
                    val startIndex = songs.indices.random()
                    primePlayerUi(songs[startIndex])
                    controller.setPlayQueue(songs, startIndex)
                    if (scenes.currentScene != PlayerSceneController.Scene.MAIN) {
                        scenes.switchToSceneSilent(PlayerSceneController.Scene.MAIN)
                    }
                    openPlayerPage()
                }
            }
        }
    }

    fun handlePlaybackWidgetIntent(intent: Intent?, delayMs: Long) {
        if (intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_PLAYER_FROM_WIDGET, false) != true) return
        val openQueue = intent.getBooleanExtra(MainActivity.EXTRA_OPEN_QUEUE_FROM_WIDGET, false)
        val openPlaylistPicker = intent.getBooleanExtra(
            MainActivity.EXTRA_OPEN_PLAYLIST_PICKER_FROM_WIDGET,
            false,
        )
        intent.removeExtra(MainActivity.EXTRA_OPEN_PLAYER_FROM_WIDGET)
        intent.removeExtra(MainActivity.EXTRA_OPEN_QUEUE_FROM_WIDGET)
        intent.removeExtra(MainActivity.EXTRA_OPEN_PLAYLIST_PICKER_FROM_WIDGET)

        mainHandler.postDelayed({
            val scenes = sceneController()
            if (isFinishing() || isDestroyed() || scenes == null) return@postDelayed
            if (scenes.currentScene == PlayerSceneController.Scene.MAIN) {
                openPlayerPage()
            }
            if (openQueue || openPlaylistPicker) {
                mainHandler.postDelayed({
                    if (isFinishing() || isDestroyed()) return@postDelayed
                    when {
                        openQueue -> openQueuePage()
                        openPlaylistPicker -> openPlaylistPicker()
                    }
                }, 180L)
            }
        }, delayMs)
    }
}
