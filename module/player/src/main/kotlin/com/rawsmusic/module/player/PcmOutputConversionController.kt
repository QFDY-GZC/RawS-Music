package com.rawsmusic.module.player

import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Owns integer PCM container conversion and optional dither state.
 *
 * The decoder and USB feeder only need a conversion boundary. Keeping the
 * stateful dither engine here prevents FfmpegAudioPlayer from also owning the
 * quantization policy and makes the conversion path reusable by every writer.
 */
internal class PcmOutputConversionController(
    private val isStrictUsbBitPerfectPath: () -> Boolean,
    private val isUsbRawDsdDirectActive: () -> Boolean,
) {
    private val ditherEngine = PcmDitherEngine()

    @Volatile
    private var activeMode = AppPreferences.Player.pcmDitherMode

    var mode: Int
        get() = activeMode
        set(value) {
            activeMode = PcmDitherMode.fromId(value).id
            ditherEngine.setMode(activeMode)
        }

    fun configure(sampleRate: Int, channels: Int) {
        ditherEngine.configure(sampleRate, channels)
        ditherEngine.setMode(activeMode)
    }

    fun close() {
        ditherEngine.close()
    }

    fun convertS32ToS16(
        source: ByteArray,
        length: Int,
        destination: ByteArray,
        sourceBits: Int,
    ): Int {
        if (shouldDither(sourceBits, 16)) {
            ditherEngine.processS32ToS16(source, length, destination)
                .takeIf { it > 0 }
                ?.let { return it }
        }
        return PcmSampleConverter.s32ToS16Pcm(source, length, destination)
    }

    fun convertS32ToS24(
        source: ByteArray,
        length: Int,
        destination: ByteArray,
        sourceBits: Int,
    ): Int {
        if (shouldDither(sourceBits, 24)) {
            ditherEngine.processS32ToS24(source, length, destination)
                .takeIf { it > 0 }
                ?.let { return it }
        }
        return PcmSampleConverter.s32ToS24PackedPcm(source, length, destination)
    }

    private fun shouldDither(sourceBits: Int, targetBits: Int): Boolean =
        activeMode != PcmDitherMode.OFF.id &&
            sourceBits > targetBits &&
            !isStrictUsbBitPerfectPath() &&
            !isUsbRawDsdDirectActive()
}
