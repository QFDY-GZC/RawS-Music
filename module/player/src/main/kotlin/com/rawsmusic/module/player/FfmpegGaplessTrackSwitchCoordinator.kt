package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.transition.NativeRenderHandoffBarrier

/** Coordinates the atomic handoff from the current decoder to a prepared next track. */
internal class FfmpegGaplessTrackSwitchCoordinator(
    private val tag: String,
    private val nextPath: () -> String?,
    private val currentGeneration: () -> Int,
    private val peekPrepared: (String, Int, Long?) -> GaplessNextDecoder.Prepared?,
    private val claimPreparedAtBoundary: (
        String,
        Int,
        Long?,
        NativeRenderHandoffBarrier.Mode,
        Long,
    ) -> GaplessNextDecoder.Prepared?,
    private val applyPrepared: (GaplessNextDecoder.Prepared) -> Boolean,
    private val retireCurrentDecoder: () -> Unit,
    private val onFormatChanged: () -> Unit,
    private val closeNextDecoder: () -> Unit,
    private val ringBufferCapacity: (Int, Int) -> Int,
    private val installRingBuffer: (RingBuffer) -> Unit,
    private val startDecoder: (RingBuffer, Int, String) -> Unit,
    private val commitTrack: (String, Long) -> Unit,
    private val resetRealtimeSeparation: (String) -> Unit,
) {
    fun switch(
        startPositionMs: Long = 0L,
        handoffMode: NativeRenderHandoffBarrier.Mode =
            NativeRenderHandoffBarrier.Mode.GAPLESS,
        completedBlockFrames: Long = 0L,
        expectedDecoderSerial: Long? = null,
    ): Boolean {
        val path = nextPath() ?: return false
        val switchStart = System.nanoTime()
        fun lap(name: String) {
            val ms = (System.nanoTime() - switchStart) / 1_000_000.0
            AppLogger.d(tag, "Gapless switch lap[$name] = ${"%.1f".format(ms)}ms")
        }

        val generation = currentGeneration()
        AppLogger.d(tag, "Gapless: switchToNextSong START path=$path gen=$generation")
        val candidate = peekPrepared(path, generation, expectedDecoderSerial)
        if (candidate == null) {
            AppLogger.w(
                tag,
                "Gapless switch deferred: pending decoder is not READY path=$path gen=$generation " +
                    "expectedSerial=$expectedDecoderSerial",
            )
            return false
        }
        lap("after-ready-check")

        val prepared = claimPreparedAtBoundary(
            path,
            generation,
            candidate.decoderSerial,
            handoffMode,
            completedBlockFrames,
        )
        if (prepared == null) {
            AppLogger.e(
                tag,
                "Gapless: native ownership commit rejected; pending remains managed " +
                    "mode=$handoffMode serial=${candidate.decoderSerial} " +
                    "generation=${candidate.ownerGeneration} frames=$completedBlockFrames",
            )
            return false
        }
        lap("after-native-boundary")

        retireCurrentDecoder()
        lap("after-close-old-decoder")

        val formatChanged = applyPrepared(prepared)
        if (formatChanged) {
            onFormatChanged()
            lap("after-rebuildAudioTrack")
        }

        closeNextDecoder()
        lap("after-closeNextDecoder")

        val newRingBuffer = RingBuffer(ringBufferCapacity(prepared.sampleRate, prepared.channels))
        installRingBuffer(newRingBuffer)
        val seededBytes = prepared.drainPrimedTo(newRingBuffer)
        AppLogger.d(
            tag,
            "Gapless: installed READY slot prefix bytes=$seededBytes remainingPrimed=${prepared.primedBytes}",
        )
        lap("after-new-ringbuffer")

        startDecoder(newRingBuffer, generation, path)
        lap("after-start-decoder-thread")

        commitTrack(path, startPositionMs.coerceAtLeast(0L))
        resetRealtimeSeparation("gapless_commit")
        lap("after-track-commit")

        val totalMs = (System.nanoTime() - switchStart) / 1_000_000.0
        AppLogger.d(tag, "Gapless: switch complete, formatChanged=$formatChanged, TOTAL=${"%.1f".format(totalMs)}ms")
        return true
    }
}
