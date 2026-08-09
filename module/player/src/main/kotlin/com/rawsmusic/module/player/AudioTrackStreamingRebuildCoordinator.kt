package com.rawsmusic.module.player

import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger

/** Coordinates a streaming AudioTrack rebuild without changing decoder ownership rules. */
internal class AudioTrackStreamingRebuildCoordinator(
    private val tag: String,
    private val playbackWorker: PlaybackWorkerController,
    private val audioTrackLifecycle: AudioTrackLifecycleController,
    private val isReleased: () -> Boolean,
    private val stopPlaybackFlags: () -> Unit,
    private val closeRingBuffer: () -> Unit,
    private val detachAudioTrack: () -> AudioTrack?,
    private val stopDetachedAudioTrack: (AudioTrack?) -> Unit,
    private val cancelPlaybackWorker: () -> Unit,
    private val beginInternalRestart: (String) -> Int,
    private val setPreparing: () -> Unit,
    private val captureDecoder: () -> DecoderLifecycleRetirer.Target,
    private val retireDecoder: (DecoderLifecycleRetirer.Target, (String) -> Unit) -> Unit,
    private val setSeekPosition: (Long) -> Unit,
    private val isStillCurrentPlayback: (String, Int) -> Boolean,
    private val prepareAndStartPlayback: (String, Int) -> Unit,
    private val setError: () -> Unit,
) {
    fun rebuild(positionMs: Long, path: String, lap: (String) -> Unit) {
        stopPlaybackFlags()
        closeRingBuffer()
        val oldTrack = detachAudioTrack()
        stopDetachedAudioTrack(oldTrack)
        lap("after-stop-oldtrack")

        cancelPlaybackWorker()
        val generation = beginInternalRestart(path)
        setPreparing()
        lap("after-preparing")

        playbackWorker.submit("audio_track_rebuild") {
            val executorStart = System.nanoTime()
            fun executorLap(name: String) {
                AppLogger.w(
                    tag,
                    "rebuildAudioTrack executor lap[$name] = " +
                        "${"%.1f".format((System.nanoTime() - executorStart) / 1_000_000.0)}ms",
                )
            }
            try {
                if (isReleased()) return@submit

                val oldDecoder = captureDecoder()
                if (oldDecoder.thread != null && oldDecoder.thread.isAlive) {
                    oldDecoder.stopToken.request(
                        reason = "audio_track_rebuild_prepare",
                        closeRetiredHandleInOwnerThread = true,
                    )
                }
                executorLap("after-pre-arm")

                audioTrackLifecycle.releaseDetached(
                    track = oldTrack,
                    reason = "audio_track_rebuild_streaming",
                    stop = false,
                    flush = true,
                )
                executorLap("after-release-oldtrack")

                retireDecoder(oldDecoder, ::executorLap)
                executorLap("after-retire-decoder")

                if (positionMs > 0) {
                    setSeekPosition(positionMs)
                }
                if (!isStillCurrentPlayback(path, generation)) return@submit
                executorLap("before-prepareAndStart")

                prepareAndStartPlayback(path, generation)
                executorLap("after-prepareAndStart")
                AppLogger.w(
                    tag,
                    "rebuildAudioTrack TOTAL = " +
                        "${"%.1f".format((System.nanoTime() - executorStart) / 1_000_000.0)}ms",
                )
            } catch (error: Exception) {
                if (!isReleased()) {
                    AppLogger.e(tag, "rebuildAudioTrack (streaming) failed", error)
                    setError()
                }
            }
        }
    }
}
