package com.rawsmusic.module.player.lyrics

/** Temporary diagnostic only; never throttles actual lyric publication. */
internal class BluetoothLyricDiagnosticGate {
    private data class Entry(val signature: String, val timeMs: Long)
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun shouldLog(phase: String, signature: String, nowMs: Long): Boolean {
        val previous = entries[phase]
        if (previous != null) {
            val elapsed = nowMs - previous.timeMs
            if (elapsed < 3000L || (signature == previous.signature && elapsed < 15000L)) return false
        }
        entries[phase] = Entry(signature, nowMs)
        return true
    }
}
