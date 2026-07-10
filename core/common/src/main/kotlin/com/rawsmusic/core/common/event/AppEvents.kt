package com.rawsmusic.core.common.event

sealed class PlayerEvent {
    data class SongChanged(val song: com.rawsmusic.core.common.model.AudioFile) : PlayerEvent()
    data class StateChanged(val state: com.rawsmusic.core.common.model.PlayState) : PlayerEvent()
    data class ProgressChanged(val position: Long, val duration: Long) : PlayerEvent()
    data class QueueChanged(val queue: com.rawsmusic.core.common.model.PlayQueue) : PlayerEvent()
    data class RepeatModeChanged(val mode: com.rawsmusic.core.common.model.RepeatMode) : PlayerEvent()
    data class ShuffleChanged(val isShuffle: Boolean) : PlayerEvent()
    object PlaybackComplete : PlayerEvent()
    object Error : PlayerEvent()
}

sealed class ScannerEvent {
    data class ScanStarted(val totalEstimated: Int) : ScannerEvent()
    data class ScanProgress(val scanned: Int, val total: Int) : ScannerEvent()
    data class ScanCompleted(val found: Int, val timeMs: Long) : ScannerEvent()
    data class ScanError(val message: String) : ScannerEvent()
}

sealed class DataEvent {
    data class FavoriteChanged(val songId: Long, val isFavorite: Boolean) : DataEvent()
    data class PlaylistChanged(val playlistId: Long) : DataEvent()
    data class PlaylistSongChanged(val playlistId: Long) : DataEvent()
}
