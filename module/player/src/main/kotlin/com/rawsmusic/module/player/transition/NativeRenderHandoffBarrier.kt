package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Control bridge for activation at a renderer-declared PCM block boundary.
 *
 * Phase 1B keeps the existing Kotlin mixer as a fallback. A native commit is
 * accepted only when the matching pending slot is READY, its queue is frame
 * aligned, and the transition was armed for the correct mode.
 */
internal object NativeRenderHandoffBarrier {
    private const val TAG = "NativeRenderHandoff"
    private const val MODE_GAPLESS = 1
    private const val MODE_CROSSFADE = 2

    enum class Mode { GAPLESS, CROSSFADE }

    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("rawscoreservice")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native handoff barrier unavailable", t)
            false
        }
    }

    @Volatile
    private var bridgeDisabled: Boolean = false

    private fun isUsable(): Boolean = libraryLoaded && !bridgeDisabled

    private fun disableBridge(operation: String, error: Throwable) {
        bridgeDisabled = true
        AppLogger.w(TAG, "$operation failed; preserving legacy handoff", error)
    }

    fun reset() {
        if (!isUsable()) return
        runCatching { nativeReset() }
            .onFailure { disableBridge("nativeReset", it) }
    }

    fun arm(
        decoderSerial: Long,
        generation: Int,
        mode: Mode,
        frameSize: Int,
    ): Boolean {
        if (!isUsable()) return false
        val nativeMode = if (mode == Mode.CROSSFADE) MODE_CROSSFADE else MODE_GAPLESS
        return runCatching {
            nativeArm(decoderSerial, generation, nativeMode, frameSize)
        }.getOrElse {
            disableBridge("nativeArm", it)
            false
        }
    }

    fun commitAtBlockBoundary(
        decoderSerial: Long,
        generation: Int,
        completedBlockFrames: Long,
    ): Boolean {
        if (!isUsable()) return false
        return runCatching {
            nativeCommitAtBlockBoundary(decoderSerial, generation, completedBlockFrames)
        }.getOrElse {
            disableBridge("nativeCommitAtBlockBoundary", it)
            false
        }
    }

    fun cancel(decoderSerial: Long, generation: Int, reasonCode: Int = 0) {
        if (!isUsable()) return
        runCatching { nativeCancel(decoderSerial, generation, reasonCode) }
            .onFailure { disableBridge("nativeCancel", it) }
    }

    fun snapshot(): String {
        if (!isUsable()) return "native_handoff_barrier_unavailable"
        return runCatching { nativeSnapshot() }.getOrElse {
            disableBridge("nativeSnapshot", it)
            "native_handoff_barrier_error:${it.javaClass.simpleName}"
        }
    }

    @JvmStatic private external fun nativeReset()

    @JvmStatic
    private external fun nativeArm(
        decoderSerial: Long,
        generation: Int,
        mode: Int,
        frameSize: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeCommitAtBlockBoundary(
        decoderSerial: Long,
        generation: Int,
        completedBlockFrames: Long,
    ): Boolean

    @JvmStatic
    private external fun nativeCancel(
        decoderSerial: Long,
        generation: Int,
        reasonCode: Int,
    )

    @JvmStatic private external fun nativeSnapshot(): String
}
