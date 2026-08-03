package com.rawsmusic

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
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
            Toast.makeText(activity, activity.getString(R.string.ui_no_matching_songs), Toast.LENGTH_SHORT).show()
            return
        }
        if (LibraryMetadataMatchProgressBus.state.value.isRunning) {
            Toast.makeText(activity, activity.getString(R.string.ui_matching_in_progress), Toast.LENGTH_SHORT).show()
            return
        }

        val snapshot = songs.distinctBy { Triple(it.path, it.cueOffsetMs, it.cueTrackIndex) }
        activity.lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                LyricoPluginStore.get(activity).enabledInPreferredOrder().isNotEmpty()
            }
            if (!enabled) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.ui_import_source_first),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val intent = withContext(Dispatchers.IO) {
                LibraryMetadataMatchContract.createIntent(activity, snapshot, mode)
            }
            ContextCompat.startForegroundService(activity, intent)
            Toast.makeText(
                activity,
                "已在后台开始处理 ${snapshot.size} 首，八线程并发可能导致轻微发热",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
