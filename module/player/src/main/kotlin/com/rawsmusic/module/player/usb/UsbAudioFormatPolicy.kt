package com.rawsmusic.module.player.usb

/** Pure PCM container decisions shared by the decoder and visualizer paths. */
internal object UsbAudioFormatPolicy {
    fun decoderBytesPerSample(bits: Int): Int = when {
        bits <= 1 -> 1
        bits <= 16 -> 2
        else -> 4
    }

    fun visualizerSampleEncoding(
        bitsPerSample: Int,
        useFloatOutput: Boolean,
        usePacked24Output: Boolean
    ): Int = when {
        useFloatOutput -> com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer.PCM_FLOAT32_LE
        usePacked24Output -> com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer.PCM_S24_PACKED_LE
        bitsPerSample <= 16 -> com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer.PCM_S16_LE
        else -> com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer.PCM_S32_LE
    }

    fun playbackBytesPerSample(
        usbExclusiveMode: Boolean,
        bitsPerSample: Int,
        useFloatOutput: Boolean,
        usePacked24Output: Boolean
    ): Int = when {
        usbExclusiveMode -> decoderBytesPerSample(bitsPerSample)
        useFloatOutput -> 4
        usePacked24Output -> 3
        else -> 4
    }
}
