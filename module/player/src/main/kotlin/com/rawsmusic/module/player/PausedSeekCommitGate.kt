package com.rawsmusic.module.player

/**
 * Coordinates a seek requested while playback is paused with the decoder thread that applies it.
 *
 * The UI/controller can move to the target immediately, but resume must not release the paused
 * output while the decoder still points at the old timestamp. The gate tracks the latest decoder
 * seek serial and gives resume a short bounded wait for that exact request.
 */
internal class PausedSeekCommitGate {
    data class AwaitResult(
        val targetMs: Long,
        val committed: Boolean,
        val serial: Long,
    )

    private val lock = Object()

    @Volatile
    private var pendingSerial = -1L

    @Volatile
    private var pendingTargetMs = -1L

    @Volatile
    private var committedSerial = -1L

    fun arm(serial: Long, targetMs: Long) {
        synchronized(lock) {
            pendingSerial = serial
            pendingTargetMs = targetMs.coerceAtLeast(0L)
            lock.notifyAll()
        }
    }

    fun markCommitted(serial: Long) {
        synchronized(lock) {
            if (serial > committedSerial) committedSerial = serial
            lock.notifyAll()
        }
    }

    fun awaitLatest(timeoutMs: Long): AwaitResult? {
        val serial: Long
        val targetMs: Long
        synchronized(lock) {
            serial = pendingSerial
            targetMs = pendingTargetMs
            if (serial < 0L || targetMs < 0L) return null

            val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
            while (committedSerial < serial) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                val waitMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
                lock.wait(waitMs.coerceAtMost(25L))
            }

            val committed = committedSerial >= serial
            if (committed && pendingSerial == serial) {
                pendingSerial = -1L
                pendingTargetMs = -1L
            }
            return AwaitResult(targetMs = targetMs, committed = committed, serial = serial)
        }
    }

    fun clear() {
        synchronized(lock) {
            pendingSerial = -1L
            pendingTargetMs = -1L
            lock.notifyAll()
        }
    }
}
