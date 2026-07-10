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
    private val isFloatOutputActive: () -> Boolean
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
                    "engine=${nativeDspEngine != null}, ch=$channels, bits=$bitsPerSample"
            )
        }
        dspLogTick++

        val engine = nativeDspEngine
        if (!useNativeDsp || engine == null) {
            stereoWidenModule.process(buffer, read, channels, sampleRate, bitsPerSample)
            return
        }
        if (!engine.hasActiveEffects()) {
            return
        }

        if (bitsPerSample == 16) {
            processNativeShort(engine, buffer, read, channels)
        } else if (bitsPerSample > 16) {
            processNativeFloatOrInt32(engine, buffer, read, channels)
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
