package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Converts one FFmpeg decoder chunk to the AudioTrack/USB output format and
 * publishes it to the decoder ring buffer.
 *
 * Keeping this small hot-path object separate makes the decoder loop responsible
 * only for scheduling, seek barriers and EOF ownership.
 */
internal class FfmpegDecoderChunkWriter(
    private val tag: String,
    private val useFloatOutput: () -> Boolean,
    private val usePacked24Output: () -> Boolean,
    private val wavBitsPerSample: () -> Int,
    private val pcmOutputConversion: PcmOutputConversionController,
    private val floatBuffer: () -> ByteArray?,
    private val setFloatBuffer: (ByteArray) -> Unit,
    private val packed24Buffer: () -> ByteArray?,
    private val setPacked24Buffer: (ByteArray) -> Unit,
) {
    data class Result(
        val written: Int,
        val writeLength: Int,
    )

    fun write(
        decodeBuffer: ByteArray,
        decoded: Int,
        decodeCallCount: Int,
        ringBuffer: RingBuffer,
    ): Result {
        var writeData = decodeBuffer
        var writeLength = decoded
        val bitsPerSample = wavBitsPerSample()

        // AudioTrack FLOAT expects float32, while packed USB output may need
        // 24-bit samples. Keep the conversion buffers owned by the player so
        // repeated decoder chunks do not allocate.
        if (useFloatOutput() && bitsPerSample > 16) {
            val sampleCount = decoded / 4
            val needed = sampleCount * 4
            var target = floatBuffer()
            if (target == null || target.size < needed) {
                target = ByteArray(needed)
                setFloatBuffer(target)
            }
            writeLength = PcmSampleConverter.s32ToFloatPcm(decodeBuffer, decoded, target)
            writeData = target
        } else if (usePacked24Output() && bitsPerSample > 16) {
            val sampleCount = decoded / 4
            val needed = sampleCount * 3
            var target = packed24Buffer()
            if (target == null || target.size < needed) {
                target = ByteArray(needed)
                setPacked24Buffer(target)
            }
            writeLength = pcmOutputConversion.convertS32ToS24(
                decodeBuffer,
                decoded,
                target,
                bitsPerSample,
            )
            writeData = target
        }

        val written = ringBuffer.write(writeData, 0, writeLength)
        if (decodeCallCount == 1 || decodeCallCount % 5000 == 0) {
            AppLogger.d(
                tag,
                "Decoder thread: rb.write #$decodeCallCount decoded=$decoded " +
                    "writeLen=$writeLength written=$written rb.available=${ringBuffer.available()}"
            )
        }
        return Result(written = written, writeLength = writeLength)
    }
}
