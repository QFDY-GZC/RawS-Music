package com.rawsmusic.module.player

internal object PlaybackStartupPolicy {
    fun rejectEmptyStart(bytesWritten: Int, terminal: Boolean, positionMs: Long, durationMs: Long): Boolean =
        bytesWritten == 0 && terminal && (durationMs <= 0 || positionMs < durationMs)
}
