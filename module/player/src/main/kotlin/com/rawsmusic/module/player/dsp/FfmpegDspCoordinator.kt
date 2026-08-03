package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.player.PlaybackDspProcessor

/**
 * Owns the DSP configuration surface used by FfmpegAudioPlayer.
 *
 * Keeping this state outside the decoder makes the PCM path explicit: the
 * player only initializes, processes, and releases the processor.
 */
internal class FfmpegDspCoordinator(
    isBitPerfectBypassActive: () -> Boolean,
    reportBitPerfectBypass: (Long, String) -> Unit,
    isFloatOutputActive: () -> Boolean,
    isPacked24OutputActive: () -> Boolean,
    isRegularAndroidOutputActive: () -> Boolean,
) {
    private val processor = PlaybackDspProcessor(
        isBitPerfectBypassActive = isBitPerfectBypassActive,
        reportBitPerfectBypass = reportBitPerfectBypass,
        isFloatOutputActive = isFloatOutputActive,
        isPacked24OutputActive = isPacked24OutputActive,
        isRegularAndroidOutputActive = isRegularAndroidOutputActive,
    )

    val engine: NativeDSPEngine?
        get() = processor.engine

    var onEngineReinit: (() -> Unit)? = null
        set(value) {
            field = value
            processor.onEngineReinit = value
        }

    @Volatile
    var stereoWidenFactor: Float = 0f
        set(value) {
            field = value
            processor.stereoWidenFactor = value
        }

    @Volatile
    var internalDoublePrecisionProcessing: Boolean = false
        set(value) {
            field = value
            processor.internalDoublePrecisionProcessing = value
        }

    @Volatile
    var realtimeStemEnabled: Boolean = false
        set(value) {
            field = value
            processor.realtimeStemEnabled = value
        }

    @Volatile
    var realtimeStemMode: Int = 0
        set(value) {
            field = value.coerceIn(0, 1)
            processor.realtimeStemMode = field
        }

    @Volatile
    var realtimeStemStrength: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            processor.realtimeStemStrength = field
        }

    @Volatile
    var androidBinauralSpatialEnabled: Boolean = false
        set(value) {
            field = value
            processor.androidBinauralSpatialRequested = value
        }

    @Volatile
    var androidBinauralSpatialIntensity: Float = 55f
        set(value) {
            field = value.coerceIn(0f, 100f)
            processor.androidBinauralSpatialIntensity = field
        }

    @Volatile
    var androidBinauralSpatialRoom: Float = 18f
        set(value) {
            field = value.coerceIn(0f, 100f)
            processor.androidBinauralSpatialRoom = field
        }

    @Volatile
    var androidBinauralBrirEnabled: Boolean = true
        set(value) {
            field = value
            processor.androidBinauralBrirEnabled = value
        }

    @Volatile
    var androidBinauralSeparation: Float = 72f
        set(value) {
            field = value.coerceIn(0f, 100f)
            processor.androidBinauralSeparation = field
        }

    @Volatile
    var androidBinauralHeadSizeCentimeters: Float = 57f
        set(value) {
            field = value.coerceIn(48f, 68f)
            processor.androidBinauralHeadSizeCentimeters = field
        }

    @Volatile
    var androidBinauralPinnaDetail: Float = 55f
        set(value) {
            field = value.coerceIn(0f, 100f)
            processor.androidBinauralPinnaDetail = field
        }

    @Volatile
    var androidBinauralHeadTrackingEnabled: Boolean = false
        set(value) {
            field = value
            processor.androidBinauralHeadTrackingEnabled = value
        }

    fun setAndroidDvc(enabled: Boolean, gain: Float, noDvcHeadroomDb: Float) {
        processor.androidDvcGain = gain.coerceIn(0f, 1f)
        processor.androidNoDvcHeadroomDb = noDvcHeadroomDb.coerceIn(-24f, 0f)
        processor.androidDvcEnabled = enabled
    }

    fun setAndroidBinauralHeadPose(x: Float, y: Float, z: Float, w: Float) {
        processor.setAndroidBinauralHeadPose(x, y, z, w)
    }

    fun init(sampleRate: Int, channels: Int) {
        processor.init(sampleRate, channels)
    }

    fun release() {
        processor.release()
    }

    fun process(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): Int = processor.process(buffer, read, channels, sampleRate, bitsPerSample)

    fun processAfterRealtime(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): Int = processor.processAfterRealtime(
        buffer,
        read,
        channels,
        sampleRate,
        bitsPerSample,
    )
}
