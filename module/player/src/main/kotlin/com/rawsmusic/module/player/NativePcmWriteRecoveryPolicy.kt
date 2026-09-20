package com.rawsmusic.module.player

/**
 * Recovery order for a native PCM backend write failure after WRITE_PAUSED has been handled.
 *
 * Retargeting keeps the same engine/queue, so an unaccepted tail can be retried exactly. Rebuilding
 * replaces/closes the sink and is therefore safe only before this logical PCM block has accepted any
 * bytes; otherwise we cannot know how much of the prefix was already audible/queued.
 */
internal enum class NativePcmWriteRecoveryStep {
    Retarget,
    Rebuild,
    Fail,
}

internal fun nextNativePcmWriteRecoveryStep(
    written: Int,
    acceptedBytes: Int,
    retargetAttempted: Boolean,
    rebuildAttempted: Boolean,
): NativePcmWriteRecoveryStep {
    if (written >= 0) return NativePcmWriteRecoveryStep.Fail
    if (!retargetAttempted) return NativePcmWriteRecoveryStep.Retarget
    if (!rebuildAttempted && acceptedBytes == 0) return NativePcmWriteRecoveryStep.Rebuild
    return NativePcmWriteRecoveryStep.Fail
}
