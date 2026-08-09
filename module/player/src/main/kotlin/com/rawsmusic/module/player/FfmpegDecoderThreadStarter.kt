package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import kotlin.concurrent.thread

/** Creates a decoder thread only while the decoder ownership token is still current. */
internal class FfmpegDecoderThreadStarter(
    private val tag: String,
    private val ownsDecoder: (
        sourcePath: String,
        generation: Int,
        handle: Long,
        ringBuffer: RingBuffer,
        stopToken: DecoderStopToken,
    ) -> Boolean,
    private val runDecoder: (
        handle: Long,
        ringBuffer: RingBuffer,
        generation: Int,
        sourcePath: String,
        stopToken: DecoderStopToken,
        decodeChunkSize: Int,
    ) -> Unit,
    private val onThreadCreated: (Thread) -> Unit,
) {
    fun start(
        sourcePath: String,
        generation: Int,
        handle: Long,
        ringBuffer: RingBuffer,
        stopToken: DecoderStopToken,
        decodeChunkSize: Int,
    ): Boolean {
        if (!ownsDecoder(sourcePath, generation, handle, ringBuffer, stopToken)) {
            AppLogger.w(
                tag,
                "Decoder start rejected: stale ownership source=$sourcePath gen=$generation " +
                    "handle=$handle stop=${stopToken.isStopRequested}",
            )
            return false
        }

        val decoderThread = thread(start = false, name = "FfmpegDecoder", isDaemon = true) {
            runDecoder(handle, ringBuffer, generation, sourcePath, stopToken, decodeChunkSize)
        }
        onThreadCreated(decoderThread)
        decoderThread.start()
        return true
    }
}
