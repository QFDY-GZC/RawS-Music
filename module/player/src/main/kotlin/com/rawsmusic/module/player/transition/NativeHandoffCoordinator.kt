package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.GaplessNextDecoder

/**
 * Keeps Phase-1B native slot activation out of FfmpegAudioPlayer.
 *
 * The native block barrier is attempted first. Until the native renderer owns
 * all PCM blocks, a direct native-slot commit remains as a compatibility path.
 */
internal class NativeHandoffCoordinator(
    private val tag: String,
) {
    @Volatile private var kotlinCompatibilityKey: String? = null

    private fun compatibilityKey(
        prepared: GaplessNextDecoder.Prepared,
        mode: NativeRenderHandoffBarrier.Mode,
    ): String = "${prepared.ownerGeneration}:${prepared.decoderSerial}:${mode.name}"

    data class CommitResult(
        val committed: Boolean,
        val atBlockBoundary: Boolean,
        val usedCompatibilityCommit: Boolean,
    )

    fun arm(
        prepared: GaplessNextDecoder.Prepared,
        mode: NativeRenderHandoffBarrier.Mode,
    ): Boolean {
        if (!prepared.nativePrimedQueue) {
            if (NativeTransitionCapabilities.complete) {
                AppLogger.e(
                    tag,
                    "Native handoff rejected because a complete library did not configure the pending PCM queue " +
                        "mode=$mode serial=${prepared.decoderSerial} generation=${prepared.ownerGeneration}",
                )
                return false
            }
            kotlinCompatibilityKey = compatibilityKey(prepared, mode)
            AppLogger.w(
                tag,
                "Legacy Kotlin pending-queue handoff armed mode=$mode serial=${prepared.decoderSerial} " +
                    "generation=${prepared.ownerGeneration}",
            )
            return true
        }

        val armed = NativeRenderHandoffBarrier.arm(
            decoderSerial = prepared.decoderSerial,
            generation = prepared.ownerGeneration,
            mode = mode,
            frameSize = prepared.frameSize,
        )
        if (!armed) {
            AppLogger.w(
                tag,
                "Native handoff arm rejected mode=$mode serial=${prepared.decoderSerial} " +
                    "generation=${prepared.ownerGeneration} slots=${NativeTrackSlotState.snapshot()} " +
                    "barrier=${NativeRenderHandoffBarrier.snapshot()}",
            )
        }
        return armed
    }

    fun commitAtBlockBoundary(
        prepared: GaplessNextDecoder.Prepared,
        mode: NativeRenderHandoffBarrier.Mode,
        completedBlockFrames: Long,
    ): CommitResult {
        val compatibilityKey = compatibilityKey(prepared, mode)
        if (!prepared.nativePrimedQueue && kotlinCompatibilityKey == compatibilityKey) {
            kotlinCompatibilityKey = null
            return CommitResult(
                committed = true,
                atBlockBoundary = true,
                usedCompatibilityCommit = true,
            )
        }

        // Gapless EOF can arrive without a prior transition-start callback.
        if (mode == NativeRenderHandoffBarrier.Mode.GAPLESS) arm(prepared, mode)

        val boundaryCommitted = NativeRenderHandoffBarrier.commitAtBlockBoundary(
            decoderSerial = prepared.decoderSerial,
            generation = prepared.ownerGeneration,
            completedBlockFrames = completedBlockFrames.coerceAtLeast(0L),
        )
        if (boundaryCommitted) {
            return CommitResult(
                committed = true,
                atBlockBoundary = true,
                usedCompatibilityCommit = false,
            )
        }

        if (NativeTransitionCapabilities.complete) {
            AppLogger.e(
                tag,
                "Native handoff boundary rejected; strict Phase-8 ownership blocks direct commit " +
                    "mode=$mode serial=${prepared.decoderSerial} generation=${prepared.ownerGeneration} " +
                    "frames=$completedBlockFrames slots=${NativeTrackSlotState.snapshot()} " +
                    "barrier=${NativeRenderHandoffBarrier.snapshot()}",
            )
            return CommitResult(
                committed = false,
                atBlockBoundary = false,
                usedCompatibilityCommit = false,
            )
        }

        // Old libraries predating the block-boundary barrier keep the previous slot-only
        // compatibility path. A complete Phase-8 library must never enter this branch.
        val compatibilityCommitted = NativeTrackSlotState.commitPending(
            prepared.decoderSerial,
            prepared.ownerGeneration,
        )
        AppLogger.w(
            tag,
            "Legacy native handoff compatibility commit=$compatibilityCommitted " +
                "mode=$mode serial=${prepared.decoderSerial} generation=${prepared.ownerGeneration} " +
                "frames=$completedBlockFrames slots=${NativeTrackSlotState.snapshot()} " +
                "barrier=${NativeRenderHandoffBarrier.snapshot()}",
        )
        return CommitResult(
            committed = compatibilityCommitted,
            atBlockBoundary = false,
            usedCompatibilityCommit = compatibilityCommitted,
        )
    }

    fun cancel(prepared: GaplessNextDecoder.Prepared, reason: String) {
        kotlinCompatibilityKey = null
        NativeRenderHandoffBarrier.cancel(
            prepared.decoderSerial,
            prepared.ownerGeneration,
            reason.hashCode(),
        )
    }

    /**
     * Clears a handoff token when its prepared decoder is no longer observable.
     *
     * During seek cancellation the pending decoder can already have left the public prepared
     * snapshot, so there is no [GaplessNextDecoder.Prepared] instance to pass to [cancel]. Leaving
     * the compatibility key/barrier armed would allow a late block to commit the abandoned
     * crossfade after the seek has started.
     */
    fun reset(reason: String) {
        kotlinCompatibilityKey = null
        NativeRenderHandoffBarrier.reset()
        AppLogger.d(tag, "Native handoff reset reason=$reason")
    }
}
