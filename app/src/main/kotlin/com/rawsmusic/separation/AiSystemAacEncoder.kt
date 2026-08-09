package com.rawsmusic.separation

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Uses the platform AAC encoder only when the cropped FFmpeg build has no AAC encoder. */
object AiSystemAacEncoder {
    private const val TAG = "AiSystemAacEncoder"
    private const val MIME_TYPE = "audio/mp4a-latm"
    private const val SAMPLE_RATE = 44_100
    private const val BIT_RATE = 256_000
    // Feed larger PCM blocks to avoid thousands of short MediaCodec wakeups for long tracks.
    private const val MAX_INPUT_FRAMES = 16_384
    private const val INPUT_TIMEOUT_US = 2_000L
    private const val OUTPUT_TIMEOUT_US = 0L
    private const val EOS_OUTPUT_TIMEOUT_US = 10_000L

    fun encode(inputWav: File, output: File): File {
        val reader = WavPcm16Reader(inputWav)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            require(reader.sampleRate == SAMPLE_RATE) {
                "系统 AAC 回退仅支持 44.1 kHz，实际为 ${reader.sampleRate} Hz"
            }
            require(reader.channels in 1..2) { "系统 AAC 回退仅支持单声道或双声道" }
            output.parentFile?.mkdirs()
            output.delete()

            val format = MediaFormat.createAudioFormat(MIME_TYPE, reader.sampleRate, reader.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_FRAMES * reader.channels * 2)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var inputFinished = false
            var outputFinished = false
            var presentationTimeUs = 0L
            var trackIndex = -1
            var encodedFrames = 0L
            val encodeStartNs = System.nanoTime()

            while (!outputFinished) {
                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("AAC 输入缓冲区不可用")
                        inputBuffer.clear()
                        val maxFrames = minOf(
                            MAX_INPUT_FRAMES,
                            inputBuffer.remaining() / (reader.channels * 2)
                        )
                        val pcm = reader.readFrames(maxFrames)
                        if (pcm.isEmpty()) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputFinished = true
                        } else {
                            inputBuffer.put(pcm)
                            val frames = pcm.size / (reader.channels * 2)
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                pcm.size,
                                presentationTimeUs,
                                0,
                            )
                            presentationTimeUs += frames * 1_000_000L / reader.sampleRate
                            encodedFrames += frames
                        }
                    }
                }

                var drainMore = true
                while (drainMore) {
                    val timeoutUs = if (inputFinished) EOS_OUTPUT_TIMEOUT_US else OUTPUT_TIMEOUT_US
                    when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drainMore = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC 输出格式重复变化" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && info.size > 0 && muxerStarted &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, outputBuffer, info)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputFinished = true
                                drainMore = false
                            }
                        }
                    }
                }
            }
            check(muxerStarted && output.isFile && output.length() > 0L) {
                "系统 AAC 编码没有产生有效的 M4A 输出"
            }
            val elapsedMs = (System.nanoTime() - encodeStartNs) / 1_000_000L
            val sourceSeconds = encodedFrames.toDouble() / reader.sampleRate.toDouble()
            val realtimeFactor = if (elapsedMs > 0L) sourceSeconds * 1_000.0 / elapsedMs else 0.0
            Log.i(
                TAG,
                "AAC_ENCODE_PERF elapsedMs=$elapsedMs frames=$encodedFrames " +
                    "sourceSeconds=${"%.2f".format(java.util.Locale.US, sourceSeconds)} " +
                    "realtimeFactor=${"%.2f".format(java.util.Locale.US, realtimeFactor)}"
            )
            Log.i(TAG, "encoded format=m4a codec=MediaCodec-AAC-LC sampleRate=44100 bitRate=$BIT_RATE output=${output.absolutePath}")
            return output
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            reader.close()
            if (!output.isFile || output.length() == 0L) output.delete()
        }
    }

    private class WavPcm16Reader(file: File) : Closeable {
        private val input = RandomAccessFile(file, "r")
        var sampleRate: Int = 0
            private set
        var channels: Int = 0
            private set
        private var format = 0
        private var bitsPerSample = 0
        private var blockAlign = 0
        private var dataOffset = 0L
        private var dataSize = 0L
        private var framesRead = 0L

        init {
            parseHeader()
            input.seek(dataOffset)
        }

        fun readFrames(maxFrames: Int): ByteArray {
            if (maxFrames <= 0 || framesRead * blockAlign.toLong() >= dataSize) return ByteArray(0)
            val remaining = ((dataSize - framesRead * blockAlign) / blockAlign).toInt()
            val frameCount = minOf(maxFrames, remaining)
            val raw = ByteArray(frameCount * blockAlign)
            input.readFully(raw)
            val result = ByteArray(frameCount * channels * 2)
            val bytesPerSample = bitsPerSample / 8
            var out = 0
            for (frame in 0 until frameCount) {
                val frameStart = frame * blockAlign
                for (channel in 0 until channels) {
                    val sampleStart = frameStart + channel * bytesPerSample
                    val sample = decode(raw, sampleStart)
                    result[out++] = (sample and 0xff).toByte()
                    result[out++] = ((sample ushr 8) and 0xff).toByte()
                }
            }
            framesRead += frameCount
            return result
        }

        private fun parseHeader() {
            val header = ByteArray(12)
            input.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "不是有效的 RIFF/WAVE 文件"
            }
            var foundFormat = false
            var foundData = false
            while (!foundFormat || !foundData) {
                val chunk = ByteArray(8)
                input.readFully(chunk)
                val name = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = uint32(chunk, 4)
                val payload = input.filePointer
                when (name) {
                    "fmt " -> {
                        require(size in 16..4096) { "WAV fmt 区块无效" }
                        val bytes = ByteArray(size.toInt())
                        input.readFully(bytes)
                        format = uint16(bytes, 0)
                        channels = uint16(bytes, 2)
                        sampleRate = uint32(bytes, 4).toInt()
                        blockAlign = uint16(bytes, 12)
                        bitsPerSample = uint16(bytes, 14)
                        foundFormat = true
                    }
                    "data" -> {
                        dataOffset = payload
                        dataSize = size
                        foundData = true
                    }
                }
                input.seek(payload + size + (size and 1L))
            }
            require(foundFormat && foundData && format in intArrayOf(1, 3)) {
                "WAV 编码格式不受支持"
            }
            require(channels in 1..2 && sampleRate > 0 && bitsPerSample in intArrayOf(8, 16, 24, 32)) {
                "WAV 音频参数不受支持"
            }
            require(format != 3 || bitsPerSample == 32) { "仅支持 32-bit float WAV" }
            require(blockAlign >= channels * (bitsPerSample / 8) && dataSize >= blockAlign) {
                "WAV 音频帧参数无效"
            }
        }

        private fun decode(bytes: ByteArray, offset: Int): Int {
            if (format == 3) {
                val bits = readInt(bytes, offset)
                var value = Float.fromBits(bits)
                if (!value.isFinite()) value = 0f
                value = value.coerceIn(-1f, 1f)
                return (value * 32767f).roundToInt().coerceIn(-32768, 32767)
            }
            return when (bitsPerSample) {
                8 -> ((bytes[offset].toInt() and 0xff) - 128) shl 8
                16 -> readShort(bytes, offset)
                24 -> {
                    var value = (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16)
                    if ((value and 0x800000) != 0) value = value or -0x1000000
                    (value shr 8).coerceIn(-32768, 32767)
                }
                else -> readInt(bytes, offset) shr 16
            }
        }

        override fun close() = input.close()

        private fun uint16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun uint32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)

        private fun readShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                (bytes[offset + 3].toInt() shl 24)
    }
}
