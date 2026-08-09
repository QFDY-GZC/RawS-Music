package com.rawsmusic.module.player

/**
 * Prevents a streaming writer from publishing a buffer that was read across a decoder seek.
 *
 * A seek is non-blocking: the UI thread arms a serial, the decoder thread performs the native seek
 * and clears its ring buffer, then marks the serial committed. Output loops sample a read token
 * before touching the ring buffer. A token is accepted only when it began after the latest seek was
 * committed; reads that began before/during the seek are discarded. This closes the small race that
 * is most visible immediately after crossfade handoff, when the replacement decoder/ring buffer is
 * still warming up.
 */
internal class SeekOutputBarrier {
    data class ReadToken(
        val activeSerialAtStart: Long,
        val committedSerialAtStart: Long
    )

    enum class ReadDecision {
        Accept,
        Discard,
        AcceptAndRelease
    }

    @Volatile
    private var activeSerial: Long = 0L

    @Volatile
    private var committedSerial: Long = 0L

    @Volatile
    private var targetMs: Long = -1L

    @Synchronized
    fun arm(serial: Long, positionMs: Long) {
        if (serial <= 0L) return
        activeSerial = serial
        committedSerial = 0L
        targetMs = positionMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun markCommitted(serial: Long): Boolean {
        if (serial <= 0L || activeSerial != serial) return false
        committedSerial = serial
        return true
    }

    @Synchronized
    fun cancel(serial: Long) {
        if (serial <= 0L || activeSerial != serial) return
        activeSerial = 0L
        committedSerial = 0L
        targetMs = -1L
    }

    @Synchronized
    fun clear() {
        activeSerial = 0L
        committedSerial = 0L
        targetMs = -1L
    }

    fun beginRead(): ReadToken = ReadToken(
        activeSerialAtStart = activeSerial,
        committedSerialAtStart = committedSerial
    )

    @Synchronized
    fun finishRead(token: ReadToken): ReadDecision {
        val current = activeSerial
        if (current == 0L) return ReadDecision.Accept

        // A new seek appeared after this read began, or this read began while the seek was still
        // uncommitted. In either case the bytes may belong to the old decoder position.
        if (token.activeSerialAtStart != current || token.committedSerialAtStart != current) {
            return ReadDecision.Discard
        }

        // This is the first read that began after the decoder committed the newest seek. The caller
        // may flush the output immediately before writing this buffer, then release the barrier.
        activeSerial = 0L
        committedSerial = 0L
        targetMs = -1L
        return ReadDecision.AcceptAndRelease
    }


    fun describe(): String =
        "active=$activeSerial committed=$committedSerial targetMs=$targetMs"
}
