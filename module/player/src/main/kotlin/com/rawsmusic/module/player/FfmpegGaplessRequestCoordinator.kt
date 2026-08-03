package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Owns gapless/crossfade request invalidation and stale-generation protection. */
internal class FfmpegGaplessRequestCoordinator(
    private val tag: String,
    private val clearNextRequest: (String) -> Unit,
    private val bumpPrepareEpoch: () -> Unit,
    private val manualRequested: () -> Boolean,
    private val setManualRequested: (Boolean) -> Unit,
    private val manualTargetPath: () -> String?,
    private val setManualTargetPath: (String?) -> Unit,
    private val manualGeneration: () -> Int,
    private val setManualGeneration: (Int) -> Unit,
    private val cancelUsbTrackSwitch: (String) -> Unit,
    private val resetCrossfade: (String) -> Unit,
    private val clearGaplessDecoder: (String) -> Unit,
    private val isCurrentPlayback: (String, Int) -> Boolean,
    private val currentGeneration: () -> Int,
    private val currentSourcePath: () -> String?,
) {
    fun clearNextSong() {
        clearNextRequest("clearNextSong")
        closeNextDecoder()
    }

    fun closeNextDecoder() {
        bumpPrepareEpoch()
        clearManualCrossfadeRequest("closeNextDecoder")
        cancelUsbTrackSwitch("closeNextDecoder")
        resetCrossfade("closeNextDecoder")
        clearGaplessDecoder("closeNextDecoder")
    }

    fun clearManualCrossfadeRequest(reason: String) {
        if (manualRequested() || manualTargetPath() != null || manualGeneration() >= 0) {
            AppLogger.d(
                tag,
                "Manual crossfade request cleared: reason=$reason " +
                    "target=${manualTargetPath()} gen=${manualGeneration()}",
            )
        }
        setManualRequested(false)
        setManualTargetPath(null)
        setManualGeneration(-1)
    }

    fun isManualCrossfadeTrigger(path: String, generation: Int): Boolean =
        manualRequested() && manualGeneration() == generation && manualTargetPath() == path

    fun shouldAbortStreamingForObsoleteRequest(
        sourcePath: String,
        generation: Int,
        reason: String,
    ): Boolean {
        if (isCurrentPlayback(sourcePath, generation)) return false
        AppLogger.w(
            tag,
            "Streaming playback obsolete at $reason; aborting old loop: " +
                "reqGen=$generation currentGen=${currentGeneration()} " +
                "reqSource=$sourcePath currentSource=${currentSourcePath()}",
        )
        clearManualCrossfadeRequest("obsolete_$reason")
        resetCrossfade("obsolete_$reason")
        return true
    }
}
