package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Bridge for the fixed current/pending native track-slot state machine.
 *
 * Phase 1B couples slot READY to the actual native PCM queue watermark and
 * requires generation ownership for every promotion or retirement operation.
 */
internal object NativeTrackSlotState {
    private const val TAG = "NativeTrackSlotState"

    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("rawscoreservice")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native track-slot state unavailable", t)
            false
        }
    }

    @Volatile
    private var bridgeDisabled: Boolean = false

    data class BeginPendingResult(
        val configured: Boolean,
        val statusCode: Int,
        val detail: String,
        val usedAtomicBind: Boolean,
    )


    private fun isUsable(): Boolean = libraryLoaded && !bridgeDisabled

    private fun disableBridge(operation: String, error: Throwable) {
        bridgeDisabled = true
        AppLogger.w(TAG, "$operation failed; using Kotlin readiness fallback", error)
    }

    fun resetAll() {
        NativeRenderHandoffBarrier.reset()
        if (!isUsable()) return
        runCatching { nativeResetAll() }
            .onFailure { disableBridge("nativeResetAll", it) }
    }

    fun installCurrent(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        if (!isUsable()) return
        runCatching {
            nativeInstallCurrent(decoderSerial, generation, sampleRate, channels, bitsPerSample)
        }.onFailure { disableBridge("nativeInstallCurrent", it) }
    }

    fun beginPending(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        minimumReadyFrames: Long,
        targetReadyFrames: Long,
    ): BeginPendingResult {
        if (!isUsable()) {
            return BeginPendingResult(
                configured = false,
                statusCode = STATUS_BRIDGE_UNAVAILABLE,
                detail = "track_slot_bridge_unavailable",
                usedAtomicBind = false,
            )
        }

        if (NativeTransitionCapabilities.atomicPendingQueueBind) {
            val status = runCatching {
                nativeBeginPendingV3(
                    decoderSerial,
                    generation,
                    sampleRate,
                    channels,
                    bitsPerSample,
                    minimumReadyFrames,
                    targetReadyFrames,
                )
            }.getOrElse {
                disableBridge("nativeBeginPendingV3", it)
                return BeginPendingResult(
                    configured = false,
                    statusCode = STATUS_BRIDGE_UNAVAILABLE,
                    detail = "atomic_bind_exception:${it.javaClass.simpleName}",
                    usedAtomicBind = true,
                )
            }
            return BeginPendingResult(
                configured = status == STATUS_CONFIGURED,
                statusCode = status,
                detail = beginStatusName(status),
                usedAtomicBind = true,
            )
        }

        val invoked = runCatching {
            nativeBeginPendingV2(
                decoderSerial,
                generation,
                sampleRate,
                channels,
                bitsPerSample,
                minimumReadyFrames,
                targetReadyFrames,
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                disableBridge("nativeBeginPendingV2", it)
                false
            },
        )
        val configured = invoked && NativePcmSlotQueue.isConfigured(decoderSerial, generation)
        return BeginPendingResult(
            configured = configured,
            statusCode = if (configured) STATUS_CONFIGURED else STATUS_LEGACY_UNVERIFIED,
            detail = if (configured) "legacy_bind_verified" else "legacy_bind_unverified",
            usedAtomicBind = false,
        )
    }

    private fun beginStatusName(status: Int): String = when (status) {
        STATUS_CONFIGURED -> "configured"
        STATUS_INVALID_IDENTITY -> "invalid_identity"
        STATUS_INVALID_FORMAT -> "invalid_format"
        STATUS_INVALID_WATERMARK -> "invalid_watermark"
        STATUS_QUEUE_REJECTED -> "queue_rejected"
        STATUS_BRIDGE_UNAVAILABLE -> "bridge_unavailable"
        else -> "unknown_status_$status"
    }

    fun updatePendingReady(
        decoderSerial: Long,
        generation: Int,
        readyFrames: Long,
        eofDuringPrime: Boolean,
    ): Boolean {
        if (!isUsable()) return true
        return runCatching {
            nativeUpdatePendingReadyV2(decoderSerial, generation, readyFrames, eofDuringPrime)
        }.getOrElse {
            disableBridge("nativeUpdatePendingReady", it)
            true
        }
    }

    fun isPendingReady(decoderSerial: Long, generation: Int): Boolean {
        if (!isUsable()) return true
        return runCatching { nativeIsPendingReady(decoderSerial, generation) }
            .getOrElse {
                disableBridge("nativeIsPendingReady", it)
                true
            }
    }

    fun commitPending(decoderSerial: Long, generation: Int): Boolean {
        if (!isUsable()) return true
        return runCatching { nativeCommitPendingV2(decoderSerial, generation) }.getOrElse {
            disableBridge("nativeCommitPending", it)
            true
        }
    }

    fun retirePending(
        decoderSerial: Long,
        generation: Int,
        reasonCode: Int = 0,
    ) {
        NativeRenderHandoffBarrier.cancel(decoderSerial, generation, reasonCode)
        if (!isUsable()) return
        runCatching { nativeRetirePendingV2(decoderSerial, generation, reasonCode) }
            .onFailure { disableBridge("nativeRetirePending", it) }
    }

    fun retireCurrent(
        decoderSerial: Long,
        generation: Int,
        reasonCode: Int = 0,
    ) {
        if (!isUsable()) return
        runCatching { nativeRetireCurrentV2(decoderSerial, generation, reasonCode) }
            .onFailure { disableBridge("nativeRetireCurrent", it) }
    }

    fun snapshot(): String {
        if (!isUsable()) return "native_track_slots_unavailable"
        return runCatching { nativeSnapshot() }.getOrElse {
            disableBridge("nativeSnapshot", it)
            "native_track_slots_error:${it.javaClass.simpleName}"
        }
    }

    @JvmStatic private external fun nativeResetAll()

    @JvmStatic
    private external fun nativeInstallCurrent(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    )

    @JvmStatic
    private external fun nativeBeginPendingV3(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        minimumReadyFrames: Long,
        targetReadyFrames: Long,
    ): Int

    @JvmStatic
    private external fun nativeBeginPendingV2(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        minimumReadyFrames: Long,
        targetReadyFrames: Long,
    )

    @JvmStatic
    private external fun nativeUpdatePendingReadyV2(
        decoderSerial: Long,
        generation: Int,
        readyFrames: Long,
        eofDuringPrime: Boolean,
    ): Boolean

    @JvmStatic
    private external fun nativeIsPendingReady(
        decoderSerial: Long,
        generation: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeCommitPendingV2(
        decoderSerial: Long,
        generation: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeRetirePendingV2(
        decoderSerial: Long,
        generation: Int,
        reasonCode: Int,
    )

    @JvmStatic
    private external fun nativeRetireCurrentV2(
        decoderSerial: Long,
        generation: Int,
        reasonCode: Int,
    )

    @JvmStatic private external fun nativeSnapshot(): String

    private const val STATUS_CONFIGURED = 1
    private const val STATUS_INVALID_IDENTITY = -1
    private const val STATUS_INVALID_FORMAT = -2
    private const val STATUS_INVALID_WATERMARK = -3
    private const val STATUS_QUEUE_REJECTED = -4
    private const val STATUS_BRIDGE_UNAVAILABLE = -100
    private const val STATUS_LEGACY_UNVERIFIED = -101
}
