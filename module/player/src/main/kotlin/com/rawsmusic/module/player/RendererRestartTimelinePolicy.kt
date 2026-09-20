package com.rawsmusic.module.player

/** Pure timeline policy for renderer/route restarts of the same logical track. */
internal object RendererRestartTimelinePolicy {
    fun initialDisplayPositionMs(
        pendingPositionMs: Long,
        pendingPath: String?,
        songPath: String,
    ): Long = pendingPositionMs
        .takeIf { it > 0L && pendingPath == songPath }
        ?: 0L
}
