package com.rawsmusic

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.helper.AudioInfoLink
import java.io.File

/** Handles audio-info links without coupling the Activity to file or scene details. */
internal class MainActivityAudioInfoLinkCoordinator(
    private val activity: ComponentActivity,
    private val navigationState: NavigationState,
    private val currentPlayerScene: () -> PlayerSceneController.Scene?,
    private val openPlayerDestination: (Int) -> Unit,
    private val sharedWindowActive: () -> Boolean,
    private val setSharedWindowActive: (Boolean) -> Unit,
    private val sharedWindowOrigin: () -> NavScene?,
    private val setSharedWindowOrigin: (NavScene?) -> Unit,
    private val updateDrawerLockMode: () -> Unit,
    private val updatePredictiveBackRegistration: () -> Unit,
) {
    fun open(link: AudioInfoLink) {
        when (link) {
            is AudioInfoLink.Settings -> openPlayerDestination(link.destinationId)
            is AudioInfoLink.Library -> {
                val playerScene = currentPlayerScene()
                if (playerScene == PlayerSceneController.Scene.PLAYER ||
                    playerScene == PlayerSceneController.Scene.LYRIC
                ) {
                    if (!sharedWindowActive()) {
                        setSharedWindowOrigin(navigationState.currentScene)
                    }
                    setSharedWindowActive(true)
                    AppLogger.i(
                        "AudioInfoLink",
                        "open_shared_window scene=${link.scene} origin=${sharedWindowOrigin()}",
                    )
                    execute(link)
                    updatePredictiveBackRegistration()
                } else {
                    execute(link)
                }
            }

            is AudioInfoLink.OpenFile -> execute(link)
        }
    }

    private fun execute(link: AudioInfoLink) {
        when (link) {
            is AudioInfoLink.Settings -> openPlayerDestination(link.destinationId)
            is AudioInfoLink.Library -> {
                val argument = link.key.takeIf { it.isNotBlank() }?.let(android.net.Uri::encode).orEmpty()
                if (link.scene == NavScene.SONGS) {
                    navigationState.navigateTo(link.scene)
                } else {
                    navigationState.navigateTo(link.scene, argument)
                }
                updateDrawerLockMode()
            }

            is AudioInfoLink.OpenFile -> openFile(link.path)
        }
    }

    private fun openFile(path: String) {
        if (path.startsWith("content://")) {
            runCatching {
                val uri = android.net.Uri.parse(path)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.ui_audio_open_file)))
            }.onFailure { error ->
                AppLogger.e("AudioInfoLink", "open_content_file_failed uri=$path", error)
                Toast.makeText(activity, activity.getString(R.string.ui_no_audio_app), Toast.LENGTH_SHORT).show()
            }
            return
        }

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(activity, activity.getString(R.string.ui_file_missing, file.name), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.ui_audio_open_file)))
        }.onFailure { error ->
            AppLogger.e("AudioInfoLink", "open_file_failed path=$path", error)
            Toast.makeText(activity, activity.getString(R.string.ui_no_audio_app), Toast.LENGTH_SHORT).show()
        }
    }
}
