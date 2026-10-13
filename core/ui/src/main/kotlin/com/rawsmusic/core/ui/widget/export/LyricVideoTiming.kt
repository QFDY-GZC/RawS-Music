package com.rawsmusic.core.ui.widget.export

/** Milliseconds to wait before posting a Canvas frame to a real-time-stamped encoder surface. */
internal fun exportFrameWaitMillis(
    startNs: Long,
    frameIndex: Int,
    fps: Int,
    nowNs: Long,
): Long {
    val targetNs = startNs + frameIndex * 1_000_000_000L / fps
    return ((targetNs - nowNs) / 1_000_000L).coerceAtLeast(0L)
}

/** Keep the encoder's capture-time gaps instead of shortening the clip when a frame is dropped. */
internal fun exportVideoPresentationTimeUs(
    rawPtsUs: Long,
    firstRawPtsUs: Long,
    previousPtsUs: Long,
): Long = (rawPtsUs - firstRawPtsUs).coerceAtLeast(previousPtsUs + 1L)
