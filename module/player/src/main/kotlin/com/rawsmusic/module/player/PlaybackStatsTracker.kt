package com.rawsmusic.module.player

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Tracks the maximum heard position and records one play per playback item. */
internal class PlaybackStatsTracker(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private var recordedItemKey = ""
    private var maxPositionMs = 0L

    fun reset() {
        recordedItemKey = ""
        maxPositionMs = 0L
    }

    fun onProgress(song: AudioFile?, positionMs: Long, durationMs: Long) {
        if (!AppPreferences.Player.playCountEnabled || song == null) return
        if (durationMs <= 0L || positionMs <= 0L) return

        val itemKey = "${song.id}|${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"
        if (recordedItemKey == itemKey) return

        maxPositionMs = maxOf(maxPositionMs, positionMs)
        val threshold = AppPreferences.Player.playCountThresholdPercent.coerceIn(1, 100)
        val percent = maxPositionMs.toDouble() / durationMs.toDouble() * 100.0
        if (percent < threshold) return

        recordedItemKey = itemKey
        scope.launch(Dispatchers.IO) {
            try {
                PlaybackStatsStore.getInstance(appContext).recordPlay(song)
                AppLogger.d(TAG, "play count recorded: ${song.title}, percent=${percent.toInt()} threshold=$threshold")
            } catch (error: Exception) {
                AppLogger.w(TAG, "record play count failed: ${error.message}")
            }
        }
    }

    private companion object {
        const val TAG = "PlayerController"
    }
}
