package com.rawsmusic.module.player

/**
 * Stateful native PCM ditherer used only at an integer bit-depth reduction.
 * Float, 32-bit, bit-perfect and native DSD paths never call this class.
 */
internal class PcmDitherEngine {
    companion object {
        init {
            runCatching { System.loadLibrary("rawscoreservice") }
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, channels: Int): Long
        @JvmStatic private external fun nativeReset(handle: Long, sampleRate: Int, channels: Int)
        @JvmStatic private external fun nativeSetMode(handle: Long, mode: Int)
        @JvmStatic private external fun nativeProcessS32ToS16(
            handle: Long,
            source: ByteArray,
            sourceLength: Int,
            destination: ByteArray,
            destinationLength: Int
        ): Int
        @JvmStatic private external fun nativeProcessS32ToS24(
            handle: Long,
            source: ByteArray,
            sourceLength: Int,
            destination: ByteArray,
            destinationLength: Int
        ): Int
        @JvmStatic private external fun nativeRelease(handle: Long)
    }

    private var handle: Long = 0L
    private var mode: Int = PcmDitherMode.MODIFIED_E_WEIGHTED.id

    fun configure(sampleRate: Int, channels: Int) {
        if (handle == 0L) {
            handle = runCatching { nativeCreate(sampleRate, channels) }.getOrDefault(0L)
        } else {
            runCatching { nativeReset(handle, sampleRate, channels) }
        }
        if (handle != 0L) runCatching { nativeSetMode(handle, mode) }
    }

    fun setMode(value: Int) {
        mode = PcmDitherMode.fromId(value).id
        if (handle != 0L) runCatching { nativeSetMode(handle, mode) }
    }

    fun processS32ToS16(source: ByteArray, sourceLength: Int, destination: ByteArray): Int {
        val h = handle
        if (h == 0L || mode == PcmDitherMode.OFF.id) return 0
        return runCatching {
            nativeProcessS32ToS16(h, source, sourceLength, destination, destination.size)
        }.getOrDefault(0)
    }

    fun processS32ToS24(source: ByteArray, sourceLength: Int, destination: ByteArray): Int {
        val h = handle
        if (h == 0L || mode == PcmDitherMode.OFF.id) return 0
        return runCatching {
            nativeProcessS32ToS24(h, source, sourceLength, destination, destination.size)
        }.getOrDefault(0)
    }

    fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeRelease(h) }
    }
}
