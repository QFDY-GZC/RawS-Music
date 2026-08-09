package com.rawsmusic

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.helper.LyricoIntegration
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.scanner.MediaStoreScanner
import com.rawsmusic.ui.settings.LyricoSearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Owns Lyrico external-activity launches and the post-edit metadata refresh. */
internal class MainActivityLyricoCoordinator(
    private val activity: ComponentActivity,
    private val currentSong: () -> AudioFile?,
    private val editorLauncher: ActivityResultLauncher<Intent>,
    private val searchLauncher: ActivityResultLauncher<Intent>,
    private val setPendingEditSong: (AudioFile?) -> Unit,
    private val onSongRefreshed: (AudioFile, AudioFile) -> Unit,
) {
    fun launchEditor() {
        val song = currentSong()
        if (song == null) {
            Toast.makeText(activity, R.string.lyrico_no_current_song, Toast.LENGTH_SHORT).show()
            return
        }
        if (!LyricoIntegration.isInstalled(activity)) {
            Toast.makeText(activity, R.string.lyrico_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        val intent = LyricoIntegration.buildEditIntent(activity, song)
        if (intent == null) {
            Toast.makeText(activity, R.string.lyrico_audio_uri_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            LyricoIntegration.traceIntent(activity, intent, "edit_launch_attempt")
            setPendingEditSong(song)
            editorLauncher.launch(intent)
            Log.i(LyricoIntegration.LOG_TAG, "edit_launch_dispatched path=${song.path}")
        }.onFailure { error ->
            setPendingEditSong(null)
            LyricoIntegration.traceLaunchFailure("edit_launch", error)
            Toast.makeText(activity, R.string.lyrico_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun launchOnlineSearch() {
        val song = currentSong()
        if (song == null) {
            Toast.makeText(activity, R.string.lyrico_no_current_song, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            Log.i(LyricoIntegration.LOG_TAG, "Opening online lyric search for ${song.path}")
            searchLauncher.launch(Intent(activity, LyricoSearchActivity::class.java).apply {
                putExtra(LyricoSearchActivity.EXTRA_SONG, song)
            })
        }.onFailure { error ->
            Log.e(LyricoIntegration.LOG_TAG, "Unable to open online lyric search", error)
            Toast.makeText(activity, R.string.lyrico_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun refreshAfterEdit(song: AudioFile) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val sourceFile = File(song.path)
            val sourceSnapshot = if (sourceFile.isFile) {
                song.copy(
                    fileSize = sourceFile.length(),
                    dateModified = sourceFile.lastModified(),
                )
            } else {
                song
            }
            val refreshed = MediaStoreScanner.enrichSong(sourceSnapshot)
            withContext(Dispatchers.Main) {
                MusicRepository.updateSong(refreshed)
                onSongRefreshed(song, refreshed)
                Toast.makeText(
                    activity,
                    R.string.lyrico_refresh_complete,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
