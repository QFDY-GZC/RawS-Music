package com.rawsmusic.module.player

/** Decoder EOF must not reset a still-active automatic incoming gain to unity. */
internal object CrossfadeOutgoingEofPolicy {
    /** A retiring input's decoder fault must not become a terminal event for audible B. */
    fun keepPresentedIncomingAfterFailure(automatic: Boolean, outgoingHandle: Long,
        pendingHandle: Long, exactPendingTarget: Boolean, incomingPresented: Boolean): Boolean =
        automatic && outgoingHandle > 0 && pendingHandle > 0 && outgoingHandle != pendingHandle &&
            exactPendingTarget && incomingPresented

    fun shouldRenderSilence(read: Int, automatic: Boolean, producerDone: Boolean,
                            bufferedBytes: Int): Boolean = automatic && bufferedBytes == 0 &&
        (read == 0 || (read == -1 && producerDone))
}
