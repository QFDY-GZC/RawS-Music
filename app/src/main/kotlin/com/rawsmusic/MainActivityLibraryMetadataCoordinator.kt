package com.rawsmusic

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.lyrico.LyricoPluginStore
import com.rawsmusic.metadata.LibraryMetadataMatchContract
import com.rawsmusic.metadata.LibraryMetadataMatchMode
import com.rawsmusic.metadata.LibraryMetadataMatchProgressBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coordinates long-running library metadata matching without coupling it to the main UI. */
internal class MainActivityLibraryMetadataCoordinator(
    private val activity: ComponentActivity,
) {
    fun start(songs: List<AudioFile>, mode: LibraryMetadataMatchMode) {
        if (songs.isEmpty()) {
            AppNoticeBus.error(activity.getString(R.string.ui_no_matching_songs))
            return
        }
        if (LibraryMetadataMatchProgressBus.state.value.isRunning) {
            AppNoticeBus.post(
                message = activity.getString(R.string.ui_matching_in_progress),
                icon = AppNoticeIcon.METADATA,
            )
            return
        }

        val snapshot = songs.distinctBy { Triple(it.path, it.cueOffsetMs, it.cueTrackIndex) }
        activity.lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                LyricoPluginStore.get(activity).enabledInPreferredOrder().isNotEmpty()
            }
            if (!enabled) {
                AppNoticeBus.error(activity.getString(R.string.ui_import_source_first))
                return@launch
            }
            val intent = withContext(Dispatchers.IO) {
                LibraryMetadataMatchContract.createIntent(activity, snapshot, mode)
            }
            ContextCompat.startForegroundService(activity, intent)
            AppNoticeBus.post(
                message = activity.getString(R.string.metadata_match_started, snapshot.size),
                icon = AppNoticeIcon.METADATA,
            )
        }
    }
}
