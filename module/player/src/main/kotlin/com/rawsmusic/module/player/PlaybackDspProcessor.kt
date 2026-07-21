package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.dsp.StereoWidenModule
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns the playback DSP chain used by FfmpegAudioPlayer.
 *
 * This keeps NativeDSPEngine lifecycle, Kotlin stereo-widen fallback and temporary
 * PCM conversion buffers out of the playback loop. The caller still decides when
 * bit-perfect USB playback must bypass DSP entirely.
 */
class PlaybackDspProcessor(
    private val isBitPerfectBypassActive: () -> Boolean,
    private val reportBitPerfectBypass: (Long, String) -> Unit,
    private val isFloatOutputActive: () -> Boolean,
    private val isPacked24OutputActive: () -> Boolean,
    private val isRegularAndroidOutputActive: () -> Boolean
) {
    companion object {
        private const val TAG = "PlaybackDspProcessor"
        private const val USB_BIT_PERFECT_BYPASS_BIT_DSP = 1L shl 0
    }

    private val stereoWidenModule = StereoWidenModule()
    private var nativeDspEngine: NativeDSPEngine? = null
    private var useNativeDsp = false
    private var dspShortArray: ShortArray? = null
    private var dspByteBuffer: ByteBuffer? = null
    private var dspFloatArray: FloatArray? = null
    private var dspFloatByteBuffer: ByteBuffer? = null

    @Volatile
    var androidBinauralSpatialRequested: Boolean = false
        set(value) {
            field = value
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralSpatialIntensity: Float = 55f
        set(value) {
            field = value.coerceIn(0f, 100f)
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralSpatialRoom: Float = 18f
        set(value) {
            field = value.coerceIn(0f, 100f)
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralBrirEnabled: Boolean = true
        set(value) {
            field = value
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralSeparation: Float = 72f
        set(value) {
            field = value.coerceIn(0f, 100f)
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralHeadSizeCentimeters: Float = 57f
        set(value) {
            field = value.coerceIn(48f, 68f)
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralPinnaDetail: Float = 55f
        set(value) {
            field = value.coerceIn(0f, 100f)
            applyAndroidBinauralState(force = true)
        }

    @Volatile
    var androidBinauralHeadTrackingEnabled: Boolean = false
        set(value) {
            field = value
            applyAndroidHeadPose(force = true)
        }

    @Volatile private var headQuaternionX: Float = 0f
    @Volatile private var headQuaternionY: Float = 0f
    @Volatile private var headQuaternionZ: Float = 0f
    @Volatile private var headQuaternionW: Float = 1f

    private var appliedAndroidBinauralEnabled: Boolean? = null
    private var appliedAndroidBinauralIntensity: Float = Float.NaN
    private var appliedAndroidBinauralRoom: Float = Float.NaN
    private var appliedAndroidBinauralBrirEnabled: Boolean? = null
    private var appliedAndroidBinauralSeparation: Float = Float.NaN
    private var appliedAndroidBinauralHeadSize: Float = Float.NaN
    private var appliedAndroidBinauralPinna: Float = Float.NaN
    private var appliedHeadTrackingEnabled: Boolean? = null
    private var appliedHeadQuaternionX: Float = Float.NaN
    private var appliedHeadQuaternionY: Float = Float.NaN
    private var appliedHeadQuaternionZ: Float = Float.NaN
    private var appliedHeadQuaternionW: Float = Float.NaN

    @Volatile
    var stereoWidenFactor: Float = 0f
        set(value) {
            field = value
            stereoWidenModule.factor = value
            try {
                nativeDspEngine?.setStereoWiden(value)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "DSP: setStereoWiden failed, factor=$value", t)
            }
        }

    /** Exposed for PEQ / bass / treble / surround controllers. */
    val engine: NativeDSPEngine? get() = nativeDspEngine

    /** Called after NativeDSPEngine has been initialized again, so controllers can reconnect. */
    var onEngineReinit: (() -> Unit)? = null

    @Volatile
    private var dspLogTick = 0L

    fun init(sampleRate: Int, channels: Int) {
        val existing = nativeDspEngine
        if (existing == null) {
            try {
                val engine = NativeDSPEngine()
                engine.init(sampleRate, channels)
                engine.setStereoWiden(stereoWidenFactor)
                nativeDspEngine = engine
                resetAppliedAndroidBinauralState()
                applyAndroidBinauralState(force = true)
                useNativeDsp = true
                AppLogger.d(TAG, "DSP: NativeDSPEngine initialized, sr=$sampleRate, ch=$channels")
                notifyEngineReinit()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "DSP: NativeDSPEngine init failed, fallback to StereoWidenModule", t)
                nativeDspEngine = null
                useNativeDsp = false
            }
            return
        }

        try {
            existing.release()
            existing.init(sampleRate, channels)
            existing.setStereoWiden(stereoWidenFactor)
            resetAppliedAndroidBinauralState()
            applyAndroidBinauralState(force = true)
            useNativeDsp = true
            AppLogger.d(TAG, "DSP: NativeDSPEngine reinitialized, sr=$sampleRate, ch=$channels")
            notifyEngineReinit()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DSP: NativeDSPEngine reinit failed, fallback to StereoWidenModule", t)
            nativeDspEngine = null
            useNativeDsp = false
        }
    }

    private fun notifyEngineReinit() {
        try {
            onEngineReinit?.invoke()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DSP: onEngineReinit callback failed", t)
        }
    }

    fun release() {
        val engine = nativeDspEngine
        nativeDspEngine = null
        useNativeDsp = false
        resetAppliedAndroidBinauralState()
        try {
            engine?.release()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DSP: NativeDSPEngine release failed", t)
        }
    }

    fun process(buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        if (bitsPerSample <= 1) {
            return
        }
        if (isBitPerfectBypassActive()) {
            reportBitPerfectBypass(USB_BIT_PERFECT_BYPASS_BIT_DSP, "DSP")
            return
        }
        if (dspLogTick % 200L == 0L) {
            AppLogger.w(
                TAG,
                "DSP: active, factor=$stereoWidenFactor, native=$useNativeDsp, " +
                    "engine=${nativeDspEngine != null}, ch=$channels, bits=$bitsPerSample, " +
                    "rawSpatialRequested=$androidBinauralSpatialRequested, " +
                    "regularAndroid=${isRegularAndroidOutputActive()}, " +
                    "packed24=${isPacked24OutputActive()}, float=${isFloatOutputActive()}"
            )
        }
        dspLogTick++

        val engine = nativeDspEngine
        if (!useNativeDsp || engine == null) {
            stereoWidenModule.process(buffer, read, channels, sampleRate, bitsPerSample)
            return
        }
        applyAndroidBinauralState(force = false)
        if (!engine.hasActiveEffects()) {
            return
        }

        when {
            isPacked24OutputActive() -> {
                processNativePacked24(engine, buffer, read, channels)
            }
            bitsPerSample == 16 -> {
                processNativeShort(engine, buffer, read, channels)
            }
            bitsPerSample > 16 -> {
                processNativeFloatOrInt32(engine, buffer, read, channels)
            }
        }
    }

    private fun resetAppliedAndroidBinauralState() {
        appliedAndroidBinauralEnabled = null
        appliedAndroidBinauralIntensity = Float.NaN
        appliedAndroidBinauralRoom = Float.NaN
        appliedAndroidBinauralBrirEnabled = null
        appliedAndroidBinauralSeparation = Float.NaN
        appliedAndroidBinauralHeadSize = Float.NaN
        appliedAndroidBinauralPinna = Float.NaN
        appliedHeadTrackingEnabled = null
        appliedHeadQuaternionX = Float.NaN
        appliedHeadQuaternionY = Float.NaN
        appliedHeadQuaternionZ = Float.NaN
        appliedHeadQuaternionW = Float.NaN
    }

    private fun applyAndroidBinauralState(force: Boolean) {
        val engine = nativeDspEngine ?: return
        if (!engine.isInitialized()) return

        val enabled = androidBinauralSpatialRequested && isRegularAndroidOutputActive()
        val intensity = androidBinauralSpatialIntensity.coerceIn(0f, 100f)
        val room = androidBinauralSpatialRoom.coerceIn(0f, 100f)

        if (force || intensity != appliedAndroidBinauralIntensity || room != appliedAndroidBinauralRoom) {
            engine.setAndroidBinauralSpatialParameters(intensity, room)
            appliedAndroidBinauralIntensity = intensity
            appliedAndroidBinauralRoom = room
        }
        val brirEnabled = androidBinauralBrirEnabled
        val separation = androidBinauralSeparation.coerceIn(0f, 100f)
        val headSize = androidBinauralHeadSizeCentimeters.coerceIn(48f, 68f)
        val pinna = androidBinauralPinnaDetail.coerceIn(0f, 100f)
        if (
            force ||
            brirEnabled != appliedAndroidBinauralBrirEnabled ||
            separation != appliedAndroidBinauralSeparation ||
            headSize != appliedAndroidBinauralHeadSize ||
            pinna != appliedAndroidBinauralPinna
        ) {
            engine.setAndroidBinauralSpatialAdvancedParameters(
                brirEnabled,
                separation,
                headSize,
                pinna
            )
            appliedAndroidBinauralBrirEnabled = brirEnabled
            appliedAndroidBinauralSeparation = separation
            appliedAndroidBinauralHeadSize = headSize
            appliedAndroidBinauralPinna = pinna
        }
        applyAndroidHeadPose(force)
        if (force || enabled != appliedAndroidBinauralEnabled) {
            engine.setAndroidBinauralSpatialEnabled(enabled)
            appliedAndroidBinauralEnabled = enabled
            AppLogger.i(
                TAG,
                "Android binaural spatial state: requested=$androidBinauralSpatialRequested " +
                    "androidOutput=${isRegularAndroidOutputActive()} enabled=$enabled " +
                    "intensity=$intensity room=$room separation=$separation brir=$brirEnabled " +
                    "headSize=$headSize pinna=$pinna headTracking=$androidBinauralHeadTrackingEnabled " +
                    "packed24=${isPacked24OutputActive()} float=${isFloatOutputActive()}"
            )
        }
    }

    fun setAndroidBinauralHeadPose(
        quaternionX: Float,
        quaternionY: Float,
        quaternionZ: Float,
        quaternionW: Float
    ) {
        headQuaternionX = quaternionX
        headQuaternionY = quaternionY
        headQuaternionZ = quaternionZ
        headQuaternionW = quaternionW
        applyAndroidHeadPose(force = false)
    }

    private fun applyAndroidHeadPose(force: Boolean) {
        val engine = nativeDspEngine ?: return
        if (!engine.isInitialized()) return
        val enabled = androidBinauralHeadTrackingEnabled &&
            androidBinauralSpatialRequested &&
            isRegularAndroidOutputActive()
        val x = headQuaternionX
        val y = headQuaternionY
        val z = headQuaternionZ
        val w = headQuaternionW
        if (
            force ||
            enabled != appliedHeadTrackingEnabled ||
            x != appliedHeadQuaternionX ||
            y != appliedHeadQuaternionY ||
            z != appliedHeadQuaternionZ ||
            w != appliedHeadQuaternionW
        ) {
            engine.setAndroidBinauralHeadPose(enabled, x, y, z, w)
            appliedHeadTrackingEnabled = enabled
            appliedHeadQuaternionX = x
            appliedHeadQuaternionY = y
            appliedHeadQuaternionZ = z
            appliedHeadQuaternionW = w
        }
    }

    private fun processNativeShort(
        engine: NativeDSPEngine,
        buffer: ByteArray,
        read: Int,
        channels: Int
    ) {
        val shortCount = read / 2
        var shortArr = dspShortArray
        if (shortArr == null || shortArr.size < shortCount) {
            shortArr = ShortArray(shortCount)
            dspShortArray = shortArr
        }
        var bb = dspByteBuffer
        if (bb == null || bb.capacity() < read) {
            bb = ByteBuffer.allocate(read).order(ByteOrder.LITTLE_ENDIAN)
            dspByteBuffer = bb
        }
        (bb as Buffer).clear()
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.put(buffer, 0, read)
        bb.position(0)
        bb.asShortBuffer().get(shortArr, 0, shortCount)
        val result = engine.process(shortArr, shortCount, channels)
        if (result == 0) {
            bb.position(0)
            bb.asShortBuffer().put(shortArr, 0, shortCount)
            bb.position(0)
            bb.get(buffer, 0, read)
        } else {
            AppLogger.w(TAG, "DSP: Native short process failed (result=$result)")
        }
    }


    private fun processNativePacked24(
        engine: NativeDSPEngine,
        buffer: ByteArray,
        read: Int,
        channels: Int
    ) {
        val sampleCount = read / 3
        if (sampleCount <= 0) return

        var floatArr = dspFloatArray
        if (floatArr == null || floatArr.size < sampleCount) {
            floatArr = FloatArray(sampleCount)
            dspFloatArray = floatArr
        }

        var sourceOffset = 0
        for (i in 0 until sampleCount) {
            var value =
                (buffer[sourceOffset].toInt() and 0xff) or
                    ((buffer[sourceOffset + 1].toInt() and 0xff) shl 8) or
                    ((buffer[sourceOffset + 2].toInt() and 0xff) shl 16)
            if ((value and 0x00800000) != 0) {
                value = value or -0x01000000
            }
            floatArr[i] = value.toFloat() / 8388608.0f
            sourceOffset += 3
        }

        val result = engine.processFloat(floatArr, sampleCount, channels)
        if (result != 0) {
            AppLogger.w(TAG, "DSP: Native packed-24 process failed (result=$result)")
            return
        }

        var destinationOffset = 0
        for (i in 0 until sampleCount) {
            val sample = floatArr[i].coerceIn(-1.0f, 1.0f)
            val scaled = if (sample < 0.0f) {
                (sample * 8388608.0f).toInt()
            } else {
                (sample * 8388607.0f).toInt()
            }.coerceIn(-8388608, 8388607)

            buffer[destinationOffset] = (scaled and 0xff).toByte()
            buffer[destinationOffset + 1] = ((scaled ushr 8) and 0xff).toByte()
            buffer[destinationOffset + 2] = ((scaled ushr 16) and 0xff).toByte()
            destinationOffset += 3
        }
    }

    private fun processNativeFloatOrInt32(
        engine: NativeDSPEngine,
        buffer: ByteArray,
        read: Int,
        channels: Int
    ) {
        val sampleCount = read / 4
        var floatArr = dspFloatArray
        if (floatArr == null || floatArr.size < sampleCount) {
            floatArr = FloatArray(sampleCount)
            dspFloatArray = floatArr
        }
        var fbb = dspFloatByteBuffer
        if (fbb == null || fbb.capacity() < read) {
            fbb = ByteBuffer.allocate(read).order(ByteOrder.LITTLE_ENDIAN)
            dspFloatByteBuffer = fbb
        }
        (fbb as Buffer).clear()
        fbb.order(ByteOrder.LITTLE_ENDIAN)
        fbb.put(buffer, 0, read)
        fbb.position(0)

        val isFloatEncoding = isFloatOutputActive()
        if (isFloatEncoding) {
            fbb.asFloatBuffer().get(floatArr, 0, sampleCount)
        } else {
            for (i in 0 until sampleCount) {
                floatArr[i] = fbb.getInt(i * 4).toFloat() / 2147483648.0f
            }
        }

        val result = engine.processFloat(floatArr, sampleCount, channels)
        if (result == 0) {
            fbb.position(0)
            if (isFloatEncoding) {
                fbb.asFloatBuffer().put(floatArr, 0, sampleCount)
            } else {
                for (i in 0 until sampleCount) {
                    val v = (floatArr[i] * 2147483648.0f).coerceIn(-2147483648f, 2147483647f)
                    fbb.putInt(i * 4, v.toInt())
                }
            }
            fbb.position(0)
            fbb.get(buffer, 0, read)
        } else {
            AppLogger.w(TAG, "DSP: Native float process failed (result=$result)")
        }
    }
}
