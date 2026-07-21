package com.rawsmusic.core.ui.widget.bitmaps

/**
 * JNI bridge for the native two-lane artwork transition state machine.
 *
 * Native owns only the deterministic two-lane state machine. Bitmap ownership remains in the
 * existing RawSMusic artwork provider, so this does not create a second decoder/cache pipeline.
 */
internal object NativePlayerArtworkBridge {
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("rawsmusic_artwork_transition")
            true
        }.getOrDefault(false)
    }

    fun create(): Long = if (isAvailable) nativeCreate() else 0L

    fun destroy(handle: Long) {
        if (handle != 0L && isAvailable) nativeDestroy(handle)
    }

    fun setArtwork(
        handle: Long,
        token: Int,
        primary: Boolean,
        requestGeneration: Int,
        durationMs: Int
    ) {
        if (handle != 0L && isAvailable) {
            nativeSetArtwork(handle, token, primary, requestGeneration, durationMs)
        }
    }

    fun setRatio(handle: Long, ratio: Float) {
        if (handle != 0L && isAvailable) nativeSetRatio(handle, ratio)
    }

    fun commit(handle: Long, generation: Int, flags: Int) {
        if (handle != 0L && isAvailable) nativeCommit(handle, generation, flags)
    }

    fun advance(handle: Long, deltaNs: Long, output: FloatArray): Boolean {
        return handle != 0L && isAvailable && nativeAdvance(handle, deltaNs, output)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetArtwork(
        handle: Long,
        token: Int,
        primary: Boolean,
        requestGeneration: Int,
        durationMs: Int
    )
    private external fun nativeSetRatio(handle: Long, ratio: Float)
    private external fun nativeCommit(handle: Long, generation: Int, flags: Int)
    private external fun nativeAdvance(handle: Long, deltaNs: Long, output: FloatArray): Boolean
}
