package com.rawsmusic.module.player.dsp

import android.util.Log

/**
 * Thin JNI bridge for the native FFT convolver.
 *
 * Keep this separated from NativeDSPEngine so convolver parameters/JNI entrypoints
 * do not keep growing inside the general DSP engine wrapper. The bridge receives
 * a NativeDSPEngine only to reuse its native DSPChain handle.
 *
 * Every JNI call is guarded. This is important while APK/native-library versions
 * can temporarily be out of sync: a missing convolver symbol must disable only
 * the convolver, never crash the settings screen or interrupt playback.
 */
internal object NativeFftConvolverBridge {
    private const val TAG = "NativeFftConvolver"
    private const val MAX_NATIVE_IR_FRAMES = 32768
    private const val MAX_NATIVE_IR_CHANNELS = 64

    @Volatile
    private var nativeApiAvailable = true

    @Volatile
    private var linkageFailureLogged = false

    fun isAvailable(): Boolean = nativeApiAvailable

    fun setEnabled(engine: NativeDSPEngine, enabled: Boolean) {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return
        safeUnit("nativeSetEnabled") {
            nativeSetEnabled(handle, enabled)
        }
    }

    fun setWetDry(engine: NativeDSPEngine, wet: Float, dry: Float) {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return
        safeUnit("nativeSetWetDry") {
            nativeSetWetDry(handle, sanitizeMix(wet, 1f), sanitizeMix(dry, 0f))
        }
    }

    fun setGainDb(engine: NativeDSPEngine, gainDb: Float) {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return
        safeUnit("nativeSetGainDb") {
            nativeSetGainDb(handle, sanitizeGain(gainDb))
        }
    }

    fun setPreDelayMs(engine: NativeDSPEngine, preDelayMs: Float) {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return
        safeUnit("nativeSetPreDelayMs") {
            nativeSetPreDelayMs(handle, sanitizePreDelay(preDelayMs))
        }
    }

    fun setMix(engine: NativeDSPEngine, wet: Float, dry: Float, gainDb: Float, preDelayMs: Float) {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return
        safeUnit("nativeSetMix") {
            nativeSetMix(
                handle,
                sanitizeMix(wet, 1f),
                sanitizeMix(dry, 0f),
                sanitizeGain(gainDb),
                sanitizePreDelay(preDelayMs)
            )
        }
    }

    fun loadIr(engine: NativeDSPEngine, ir: FloatArray, frames: Int, irChannels: Int): Boolean {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L || irChannels !in 1..MAX_NATIVE_IR_CHANNELS) return false
        val maxFrames = ir.size / irChannels
        if (maxFrames <= 0) return false
        val safeFrames = frames.coerceIn(1, minOf(maxFrames, MAX_NATIVE_IR_FRAMES))
        if (ir.size < safeFrames * irChannels) return false
        return safeBoolean("nativeLoadIr") {
            nativeLoadIr(handle, ir, safeFrames, irChannels)
        }
    }

    fun clearIr(engine: NativeDSPEngine) {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return
        safeUnit("nativeClearIr") {
            nativeClearIr(handle)
        }
    }

    fun isReady(engine: NativeDSPEngine): Boolean {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return false
        return safeBoolean("nativeIsReady") {
            nativeIsReady(handle)
        }
    }

    fun latencyFrames(engine: NativeDSPEngine): Int {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return 0
        return safeInt("nativeGetLatencyFrames") {
            nativeGetLatencyFrames(handle)
        }
    }


    fun streamChannels(engine: NativeDSPEngine): Int {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return 0
        return safeInt("nativeGetStreamChannels") {
            nativeGetStreamChannels(handle)
        }
    }

    fun preDelayFrames(engine: NativeDSPEngine): Int {
        val handle = engine.nativeHandleForBridge()
        if (handle == 0L) return 0
        return safeInt("nativeGetPreDelayFrames") {
            nativeGetPreDelayFrames(handle)
        }
    }

    private inline fun safeUnit(operation: String, block: () -> Unit) {
        if (!nativeApiAvailable) return
        try {
            block()
        } catch (error: LinkageError) {
            disableNativeApi(operation, error)
        }
    }

    private inline fun safeBoolean(operation: String, block: () -> Boolean): Boolean {
        if (!nativeApiAvailable) return false
        return try {
            block()
        } catch (error: LinkageError) {
            disableNativeApi(operation, error)
            false
        }
    }

    private inline fun safeInt(operation: String, block: () -> Int): Int {
        if (!nativeApiAvailable) return 0
        return try {
            block()
        } catch (error: LinkageError) {
            disableNativeApi(operation, error)
            0
        }
    }

    private fun disableNativeApi(operation: String, error: LinkageError) {
        nativeApiAvailable = false
        if (!linkageFailureLogged) {
            synchronized(this) {
                if (!linkageFailureLogged) {
                    linkageFailureLogged = true
                    Log.e(
                        TAG,
                        "FFT convolver JNI is unavailable at $operation; " +
                            "disabling only the convolver for this process",
                        error
                    )
                }
            }
        }
    }

    private fun sanitizeMix(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 2f) else fallback

    private fun sanitizeGain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-24f, 24f) else 0f

    private fun sanitizePreDelay(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 500f) else 0f

    private external fun nativeSetEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetWetDry(handle: Long, wet: Float, dry: Float)
    private external fun nativeSetGainDb(handle: Long, gainDb: Float)
    private external fun nativeSetPreDelayMs(handle: Long, preDelayMs: Float)
    private external fun nativeSetMix(handle: Long, wet: Float, dry: Float, gainDb: Float, preDelayMs: Float)
    private external fun nativeLoadIr(handle: Long, ir: FloatArray, frames: Int, irChannels: Int): Boolean
    private external fun nativeClearIr(handle: Long)
    private external fun nativeIsReady(handle: Long): Boolean
    private external fun nativeGetLatencyFrames(handle: Long): Int
    private external fun nativeGetPreDelayFrames(handle: Long): Int
    private external fun nativeGetStreamChannels(handle: Long): Int
}
