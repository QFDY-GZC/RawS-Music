package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioFormat
import com.rawsmusic.module.player.usb.UsbAudioFormatPolicy

/** Pure PCM/output decisions shared by Android and native playback paths. */
internal class FfmpegPcmOutputPolicy(
    private val context: Context,
    private val isUsbExclusive: () -> Boolean,
    private val probedEncoding: () -> Int,
    private val bitsPerSample: () -> Int,
    private val decoderSampleRate: () -> Int,
) {
    val useFloatOutput: Boolean
        get() = AudioOutputFormatPolicy.useFloatOutput(
            usbExclusiveMode = isUsbExclusive(),
            probedEncoding = probedEncoding(),
            floatEncoding = AudioFormat.ENCODING_PCM_FLOAT,
        )

    val usePacked24Output: Boolean
        get() = AudioOutputFormatPolicy.usePacked24Output(
            usbExclusiveMode = isUsbExclusive(),
            probedEncoding = probedEncoding(),
            packed24Encoding = AudioOutputManager.pcm24PackedEncodingOrNull(),
        )

    val useNativePcmOutput: Boolean
        get() = !isUsbExclusive() &&
            !AudioOutputManager.shouldUseScoMode(context) &&
            NativeAudioEngine.isSupported(AudioOutputManager.getCurrentOutputMode(context))

    val outputBytesPerSample: Int
        get() = UsbAudioFormatPolicy.decoderBytesPerSample(bitsPerSample())

    fun playbackBytesPerSample(): Int = UsbAudioFormatPolicy.playbackBytesPerSample(
        usbExclusiveMode = isUsbExclusive(),
        bitsPerSample = bitsPerSample(),
        useFloatOutput = useFloatOutput,
        usePacked24Output = usePacked24Output,
    )

    fun targetSampleRate(): Int {
        val requested = AudioOutputManager.getTargetSampleRate()
        return if (requested > 0) requested else decoderSampleRate().coerceAtLeast(44_100)
    }
}
