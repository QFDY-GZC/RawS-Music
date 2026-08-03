package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Publishes same-format USB track switches to the active feeder.
 *
 * The controller only validates/prepares the next decoder and waits for an
 * acknowledgement. The feeder remains the sole owner of the native USB cut.
 */
internal class UsbSameProfileTrackSwitchCoordinator(
    private val tag: String,
    private val acknowledgementTimeoutMs: Long,
    private val isUsbExclusive: () -> Boolean,
    private val isPlayingState: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val isReleased: () -> Boolean,
    private val isNativeRunning: () -> Boolean,
    private val currentGeneration: () -> Int,
    private val snapshotPrepared: (Int) -> GaplessNextDecoder.Prepared?,
    private val closeNextDecoder: () -> Unit,
    private val setNextSongPath: (String) -> Unit,
    private val setCrossfadeDurationMs: (Int) -> Unit,
    private val prepareNextDecoder: (String, Int) -> Boolean,
    private val currentFormat: () -> PcmFormat,
    private val wakeFeeder: () -> Unit,
) {
    data class PcmFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    private val serial = AtomicLong(0L)
    private val pendingRequest = AtomicReference<UsbManualSwitchRequest?>(null)

    suspend fun request(
        nextPath: String,
        reason: String,
    ): FfmpegAudioPlayer.UsbManualSwitchResult {
        if (nextPath.isBlank() ||
            !isUsbExclusive() ||
            !isPlayingState() ||
            !isPlaying() ||
            isReleased() ||
            !isNativeRunning()
        ) {
            return FfmpegAudioPlayer.UsbManualSwitchResult.REJECTED
        }

        val generation = currentGeneration()
        var prepared = snapshotPrepared(generation)
        if (prepared?.path != nextPath) {
            closeNextDecoder()
            setNextSongPath(nextPath)
            // Strict bit-perfect USB switching is a cut/gapless handoff, not a
            // PCM crossfade that would require a second mixed output path.
            setCrossfadeDurationMs(0)
            if (!prepareNextDecoder(nextPath, generation)) {
                return FfmpegAudioPlayer.UsbManualSwitchResult.REJECTED
            }
            prepared = snapshotPrepared(generation)
        } else {
            setNextSongPath(nextPath)
            setCrossfadeDurationMs(0)
            AppLogger.d(tag, "USB same-profile switch reusing prepared decoder path=$nextPath gen=$generation")
        }

        val current = currentFormat()
        val compatible = prepared != null &&
            prepared.path == nextPath &&
            prepared.sampleRate == current.sampleRate &&
            prepared.channels == current.channels &&
            prepared.bitsPerSample == current.bitsPerSample
        if (!compatible) {
            AppLogger.w(
                tag,
                "USB same-profile switch rejected: current=${current.sampleRate}/${current.bitsPerSample}/${current.channels} " +
                    "next=${prepared?.sampleRate}/${prepared?.bitsPerSample}/${prepared?.channels} path=$nextPath",
            )
            closeNextDecoder()
            return FfmpegAudioPlayer.UsbManualSwitchResult.REJECTED
        }

        val request = UsbManualSwitchRequest(
            serial = serial.incrementAndGet(),
            generation = generation,
            targetPath = nextPath,
            reason = reason,
        )
        pendingRequest.getAndSet(request)?.let { replaced ->
            replaced.completion.complete(false)
            AppLogger.w(
                tag,
                "USB same-profile switch superseded before feeder commit: " +
                    "oldSerial=${replaced.serial} newSerial=${request.serial}",
            )
        }
        wakeFeeder()
        AppLogger.i(
            tag,
            "USB same-profile switch queued on feeder: serial=${request.serial} " +
                "path=$nextPath gen=$generation reason=$reason",
        )

        val committed = withTimeoutOrNull(acknowledgementTimeoutMs) {
            request.completion.await()
        }
        if (committed == null) {
            val removedBeforeConsume = pendingRequest.compareAndSet(request, null)
            if (removedBeforeConsume) {
                closeNextDecoder()
            }
            AppLogger.e(
                tag,
                "USB same-profile switch acknowledgement timeout: serial=${request.serial} " +
                    "removedBeforeConsume=$removedBeforeConsume path=$nextPath reason=$reason",
            )
            return FfmpegAudioPlayer.UsbManualSwitchResult.TIMED_OUT
        }
        return if (committed) {
            FfmpegAudioPlayer.UsbManualSwitchResult.COMMITTED
        } else {
            FfmpegAudioPlayer.UsbManualSwitchResult.FAILED
        }
    }

    /** Called only from the USB feeder loop at its write boundary. */
    fun takePendingRequest(): UsbManualSwitchRequest? = pendingRequest.getAndSet(null)

    fun cancelPending(reason: String) {
        pendingRequest.getAndSet(null)?.let { request ->
            request.completion.complete(false)
            AppLogger.d(tag, "USB manual switch cancelled: serial=${request.serial} reason=$reason")
        }
    }
}
