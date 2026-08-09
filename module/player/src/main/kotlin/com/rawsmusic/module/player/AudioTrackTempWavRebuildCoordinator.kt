package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Owns the asynchronous AudioTrack rebuild path for a temporary WAV source. */
internal class AudioTrackTempWavRebuildCoordinator(
    private val tag: String,
    private val playbackWorker: PlaybackWorkerController,
    private val audioTrackLifecycle: AudioTrackLifecycleController,
    private val isReleased: () -> Boolean,
    private val startPlaybackFromOffset: (
        startByteOffset: Long,
        playPath: String,
        generation: Int,
        isSeek: Boolean,
        sourcePath: String,
    ) -> Unit,
) {
    fun submit(
        positionMs: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Long,
        seekPath: String,
        originalSourcePath: String,
        generation: Int,
        lap: (String) -> Unit,
    ) {
        playbackWorker.submit("rebuild_temp_wav") {
            try {
                if (isReleased()) return@submit

                audioTrackLifecycle.detachAndRelease(
                    reason = "rebuild_temp_wav",
                    stop = true,
                    flush = true,
                )
                lap("tempWav-released-oldtrack")

                val frameSize = channels * if (bitsPerSample <= 16) 2 else 4
                val bytesPerMs = (sampleRate * frameSize).toDouble() / 1000.0
                val targetByteOffset = (positionMs.toDouble() * bytesPerMs)
                    .toLong()
                    .coerceIn(0, (dataSize - 1).coerceAtLeast(0))
                val alignedOffset = (targetByteOffset / frameSize) * frameSize
                startPlaybackFromOffset(
                    alignedOffset,
                    seekPath,
                    generation,
                    positionMs > 0,
                    originalSourcePath,
                )
                lap("tempWav-startPlayback")
            } catch (error: Exception) {
                if (!isReleased()) {
                    AppLogger.e(tag, "rebuildAudioTrack temporary WAV failed", error)
                }
            }
        }
    }
}
