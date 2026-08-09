package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger

/** Read-only view of the native USB runtime transport metrics. */
internal class UsbRuntimeMetricsReader(
    private val isInitialized: () -> Boolean,
    private val currentHandle: () -> Long,
    private val snapshot: () -> Snapshot,
    private val refreshSnapshot: () -> Unit,
    private val nativeGetPacketSize: () -> Int,
    private val nativeGetTransferCapacityBytes: () -> Int,
    private val nativeGetMaxPacketBytes: () -> Int,
    private val nativeGetServiceIntervalsPerSecond: () -> Int,
    private val nativeGetNominalBytesPerInterval: () -> Int,
    private val nativeGetNominalBytesPerTransfer: () -> Int,
    private val nativeGetCompletedUsbBytesPerSecond: () -> Long,
    private val nativeGetScheduledUsbBytesPerSecond: () -> Long,
    private val nativeGetFeedbackState: () -> Int,
    private val nativeGetFeedbackSampleRateMilli: () -> Int,
    private val nativeGetPacingMode: () -> Int,
    private val nativeGetStatsString: (Long) -> String,
    private val nativeGetStreamSessionId: (Long) -> Long,
    private val nativeGetRecommendedDelayUs: () -> Int,
    private val nativeGetBufferUsedBytes: () -> Int,
    private val nativeGetOutputBytesPerSecond: () -> Int,
    private val nativeGetOutputSampleRate: () -> Int,
    private val nativeGetCurrentFrameBytes: () -> Int,
    private val currentFeedbackEndpoint: () -> Int,
) {
    data class Snapshot(
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        val subslotBytes: Int,
        val outEndpoint: Int,
        val feedbackEndpoint: Int,
        val interfaceNumber: Int,
        val altSetting: Int,
    )

    fun transferCapacityBytes(): Int {
        if (!isInitialized()) return 0
        return runCatching { nativeGetTransferCapacityBytes() }
            .getOrElse { nativeGetPacketSize() }
    }

    fun maxPacketBytes(): Int = if (isInitialized()) {
        runCatching { nativeGetMaxPacketBytes() }.getOrDefault(0)
    } else 0

    fun serviceIntervalsPerSecond(): Int = if (isInitialized()) {
        runCatching { nativeGetServiceIntervalsPerSecond() }.getOrDefault(0)
    } else 0

    fun nominalBytesPerInterval(): Int = if (isInitialized()) {
        runCatching { nativeGetNominalBytesPerInterval() }.getOrDefault(0)
    } else 0

    fun nominalBytesPerTransfer(): Int = if (isInitialized()) {
        runCatching { nativeGetNominalBytesPerTransfer() }.getOrDefault(0)
    } else 0

    fun completedUsbBytesPerSecond(): Long = if (isInitialized()) {
        runCatching { nativeGetCompletedUsbBytesPerSecond() }.getOrDefault(0L)
    } else 0L

    fun scheduledUsbBytesPerSecond(): Long = if (isInitialized()) {
        runCatching { nativeGetScheduledUsbBytesPerSecond() }.getOrDefault(0L)
    } else 0L

    fun feedbackState(): UsbAudioEngine.FeedbackState = if (isInitialized()) {
        UsbAudioEngine.FeedbackState.fromId(runCatching { nativeGetFeedbackState() }.getOrDefault(0))
    } else {
        UsbAudioEngine.FeedbackState.NONE
    }

    fun feedbackSampleRate(): Double = if (isInitialized()) {
        runCatching { nativeGetFeedbackSampleRateMilli() / 1000.0 }.getOrDefault(0.0)
    } else 0.0

    fun pacingMode(): UsbAudioEngine.PacingMode {
        if (!isInitialized()) return UsbAudioEngine.PacingMode.Unknown
        val handle = currentHandle()
        if (handle != 0L) {
            val raw = runCatching { nativeGetStatsString(handle) }.getOrDefault("")
            UsbRuntimeStatsParser.parsePacingModeId(raw)
                ?.let { return UsbAudioEngine.PacingMode.fromId(it) }
            UsbRuntimeStatsParser.parsePacingModeName(raw)
                ?.let { name ->
                    UsbAudioEngine.PacingMode.entries.firstOrNull { it.name == name }?.let { return it }
                }
        }
        return UsbAudioEngine.PacingMode.fromId(runCatching { nativeGetPacingMode() }.getOrDefault(-1))
    }

    fun feedbackLooksUnsafeForPacer(): Boolean {
        if (!isInitialized()) return false
        refreshSnapshot()
        if (currentFeedbackEndpoint() <= 0) return false
        return feedbackState() in setOf(
            UsbAudioEngine.FeedbackState.SUSPECT,
            UsbAudioEngine.FeedbackState.DEGRADED,
            UsbAudioEngine.FeedbackState.FAILED,
        )
    }

    fun streamSessionId(): Long {
        val handle = currentHandle()
        if (handle == 0L) return 0L
        return runCatching { nativeGetStreamSessionId(handle) }.getOrDefault(0L)
    }

    fun recommendedWriteChunkBytes(deviceBytesPerSecond: Long, frameBytes: Int, targetMs: Long): Int =
        UsbOutputSizingPolicy.recommendedWriteChunkBytes(deviceBytesPerSecond, frameBytes, targetMs)

    fun recommendedDelayUs(): Int = if (isInitialized()) nativeGetRecommendedDelayUs() else 5000

    fun bufferUsedBytes(): Int = if (isInitialized()) {
        runCatching { nativeGetBufferUsedBytes() }.getOrDefault(0)
    } else 0

    fun outputBytesPerSecond(): Int {
        if (!isInitialized()) return 0
        val nativeBps = runCatching { nativeGetOutputBytesPerSecond() }.getOrDefault(0)
        refreshSnapshot()
        val state = snapshot()
        val computedBps = UsbOutputSizingPolicy.runtimeBytesPerSecond(
            sampleRate = state.sampleRate,
            channels = state.channels,
            subslotSize = state.subslotBytes,
        )
        if (computedBps > 0) {
            if (nativeBps > 0 && nativeBps != computedBps) {
                AppLogger.w(
                    TAG,
                    "USB runtime BPS corrected: native=$nativeBps computed=$computedBps " +
                        "sr=${state.sampleRate} ch=${state.channels} subslot=${state.subslotBytes}"
                )
            }
            return computedBps
        }
        return nativeBps
    }

    fun runtimeFormat(): UsbAudioEngine.UsbRuntimeFormat {
        refreshSnapshot()
        val state = snapshot()
        val frameBytesFromNative = runCatching { nativeGetCurrentFrameBytes() }.getOrDefault(0)
        val frameBytes = frameBytesFromNative.takeIf { it > 0 }
            ?: (state.channels * state.subslotBytes).takeIf { it > 0 }
            ?: 0
        val bytesPerSecond = UsbOutputSizingPolicy.runtimeBytesPerSecond(
            sampleRate = state.sampleRate,
            channels = state.channels,
            subslotSize = state.subslotBytes,
        ).takeIf { it > 0 }
            ?: runCatching { nativeGetOutputBytesPerSecond() }.getOrDefault(0)
        return UsbAudioEngine.UsbRuntimeFormat(
            sampleRate = state.sampleRate,
            channels = state.channels,
            validBits = state.bits,
            subslotBytes = state.subslotBytes,
            frameBytes = frameBytes,
            bytesPerSecond = bytesPerSecond,
            iface = state.interfaceNumber,
            alt = state.altSetting,
            outEndpoint = state.outEndpoint,
            feedbackEndpoint = state.feedbackEndpoint,
        )
    }

    fun outputSampleRate(): Int = if (isInitialized()) {
        runCatching { nativeGetOutputSampleRate() }.getOrDefault(0)
    } else 0

    private companion object {
        const val TAG = "UsbRuntimeMetricsReader"
    }
}
