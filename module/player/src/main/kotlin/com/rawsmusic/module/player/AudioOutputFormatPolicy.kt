package com.rawsmusic.module.player

import android.media.AudioFormat
import com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer

/** Pure Android output encoding decisions used by the PCM writer. */
internal object AudioOutputFormatPolicy {
    fun useFloatOutput(
        usbExclusiveMode: Boolean,
        probedEncoding: Int,
        floatEncoding: Int
    ): Boolean = !usbExclusiveMode && probedEncoding == floatEncoding

    fun usePacked24Output(
        usbExclusiveMode: Boolean,
        probedEncoding: Int,
        packed24Encoding: Int?
    ): Boolean = !usbExclusiveMode && packed24Encoding != null && probedEncoding == packed24Encoding

    /**
     * Decoder container width. Raw DSD passthrough is byte-domain data: FFmpeg reports
     * bitsPerSample=1 and sampleRate=DSD_rate/8, with exactly one byte per channel per
     * decoder frame. PCM keeps the normal S16/S32 container policy.
     */
    fun decoderBytesPerSample(bitsPerSample: Int): Int = when {
        bitsPerSample == 1 -> 1
        bitsPerSample <= 16 -> 2
        else -> 4
    }

    /** Returns the byte width emitted to the selected playback sink. */
    fun playbackBytesPerSample(
        usbExclusiveMode: Boolean,
        bitsPerSample: Int,
        useFloatOutput: Boolean,
        usePacked24Output: Boolean,
    ): Int = when {
        usbExclusiveMode -> decoderBytesPerSample(bitsPerSample)
        useFloatOutput -> 4
        usePacked24Output -> 3
        else -> decoderBytesPerSample(bitsPerSample)
    }

    /** Maps decoder PCM to the compact encoding IDs consumed by the spectrum pipeline. */
    fun visualizerSampleEncoding(
        bitsPerSample: Int,
        useFloatOutput: Boolean,
        usePacked24Output: Boolean,
    ): Int = when {
        useFloatOutput -> NativeStereoSpectrumAnalyzer.PCM_FLOAT32_LE
        usePacked24Output -> NativeStereoSpectrumAnalyzer.PCM_S24_PACKED_LE
        bitsPerSample <= 16 -> NativeStereoSpectrumAnalyzer.PCM_S16_LE
        else -> NativeStereoSpectrumAnalyzer.PCM_S32_LE
    }
}
