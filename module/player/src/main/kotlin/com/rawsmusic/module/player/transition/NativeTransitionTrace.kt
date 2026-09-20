package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/** Thin optional bridge to the fixed-size native transition trace ring. */
internal object NativeTransitionTrace {
    private const val TAG = "NativeTransitionTrace"

    private val available: Boolean by lazy {
        try {
            System.loadLibrary("rawscoreservice")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native transition trace unavailable", t)
            false
        }
    }

    fun setContext(token: PlaybackTransitionToken?, outputGeneration: Long) {
        if (!available) return
        runCatching {
            nativeSetContext(
                sessionId = token?.sessionId ?: 0L,
                transitionId = token?.transitionId ?: 0L,
                generation = token?.generation ?: 0,
                outputGeneration = outputGeneration,
                reason = token?.reason?.ordinal ?: -1,
            )
        }.onFailure { AppLogger.w(TAG, "nativeSetContext failed", it) }
    }

    fun snapshot(): String {
        if (!available) return "native_trace_unavailable"
        return runCatching { nativeSnapshot() }.getOrElse { "native_trace_error:${it.javaClass.simpleName}" }
    }

    @JvmStatic
    private external fun nativeSetContext(
        sessionId: Long,
        transitionId: Long,
        generation: Int,
        outputGeneration: Long,
        reason: Int,
    )

    @JvmStatic
    private external fun nativeSnapshot(): String
}
