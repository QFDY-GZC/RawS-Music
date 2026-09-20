package com.rawsmusic

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.helper.LyricoIntegration
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.scanner.MediaStoreScanner
import com.rawsmusic.ui.settings.LyricoSearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the editor actually changed. Unknown is used for third-party Lyrico results without flags. */
internal enum class LyricoEditMutation(
    val lyricsChanged: Boolean,
    val artworkChanged: Boolean,
) {
    LyricsOnly(lyricsChanged = true, artworkChanged = false),
    ArtworkOnly(lyricsChanged = false, artworkChanged = true),
    LyricsAndArtwork(lyricsChanged = true, artworkChanged = true),
    Unknown(lyricsChanged = true, artworkChanged = true),
    ;

    companion object {
        fun fromFlags(lyricsChanged: Boolean, artworkChanged: Boolean): LyricoEditMutation = when {
            lyricsChanged && artworkChanged -> LyricsAndArtwork
            lyricsChanged -> LyricsOnly
            artworkChanged -> ArtworkOnly
            else -> Unknown
        }
    }
}

/** Owns Lyrico external-activity launches and the post-edit metadata refresh. */
internal class MainActivityLyricoCoordinator(
    private val activity: ComponentActivity,
    private val currentSong: () -> AudioFile?,
    private val editorLauncher: ActivityResultLauncher<Intent>,
    private val searchLauncher: ActivityResultLauncher<Intent>,
    private val setPendingEditSong: (AudioFile?) -> Unit,
    private val onEditApplied: (AudioFile, AudioFile, LyricoEditMutation) -> Unit,
) {
    fun launchEditor() {
        val song = currentSong()
        if (song == null) {
            AppNoticeBus.error(activity.getString(R.string.lyrico_no_current_song))
            return
        }
        if (!LyricoIntegration.isInstalled(activity)) {
            AppNoticeBus.error(activity.getString(R.string.lyrico_not_installed))
            return
        }
        val intent = LyricoIntegration.buildEditIntent(activity, song)
        if (intent == null) {
            AppNoticeBus.error(activity.getString(R.string.lyrico_audio_uri_unavailable))
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
            AppNoticeBus.error(activity.getString(R.string.lyrico_open_failed))
        }
    }

    fun launchOnlineSearch() {
        val song = currentSong()
        if (song == null) {
            AppNoticeBus.error(activity.getString(R.string.lyrico_no_current_song))
            return
        }
        runCatching {
            Log.i(LyricoIntegration.LOG_TAG, "Opening online lyric search for ${song.path}")
            searchLauncher.launch(Intent(activity, LyricoSearchActivity::class.java).apply {
                putExtra(LyricoSearchActivity.EXTRA_SONG, song)
            })
        }.onFailure { error ->
            Log.e(LyricoIntegration.LOG_TAG, "Unable to open online lyric search", error)
            AppNoticeBus.error(activity.getString(R.string.lyrico_open_failed))
        }
    }

    fun refreshAfterEdit(
        song: AudioFile,
        mutation: LyricoEditMutation = LyricoEditMutation.Unknown,
    ) {
        // Internal Lyrico lyric writes use a sidecar and do not touch the audio file. Do not scan,
        // republish currentSong, invalidate album art or rebuild player artwork identity just to
        // refresh lyrics. This is the cheapest possible handoff before a PLAYER <-> LYRIC gesture.
        if (mutation == LyricoEditMutation.LyricsOnly) {
            onEditApplied(song, song, mutation)
            AppNoticeBus.post(
                message = activity.getString(R.string.lyrico_refresh_complete),
                icon = AppNoticeIcon.LYRICS,
            )
            return
        }

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
            MusicRepository.updateSong(refreshed)
            withContext(Dispatchers.Main) {
                onEditApplied(song, refreshed, mutation)
                AppNoticeBus.post(
                    message = activity.getString(R.string.lyrico_refresh_complete),
                    icon = AppNoticeIcon.LYRICS,
                )
            }
        }
    }
}
