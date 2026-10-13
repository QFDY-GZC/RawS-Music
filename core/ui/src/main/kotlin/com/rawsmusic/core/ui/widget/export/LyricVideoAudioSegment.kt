package com.rawsmusic.core.ui.widget.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import java.nio.ByteBuffer
import kotlin.math.min

/** One encoded AAC access unit kept in memory until the MP4 muxer starts. */
internal class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
)

internal class EncodedAudioTrack(
    val format: MediaFormat,
    val samples: List<EncodedSample>,
)

/**
 * Decode [startMs, endMs) of [path] through the app's FFmpeg streaming decoder (so every format the
 * player supports works: FLAC/APE/DSD/...), apply short fade-in/out ramps and encode to AAC-LC with
 * the platform MediaCodec. Samples are returned in memory; a lyric-video segment is short.
 */
internal fun encodeAudioSegmentAac(
    path: String,
    startMs: Long,
    endMs: Long,
    isCancelled: () -> Boolean,
    onProgress: (Float) -> Unit,
): EncodedAudioTrack? {
    val durationMs = (endMs - startMs).coerceAtLeast(0L)
    if (durationMs <= 0L || path.isBlank()) return null
    val handle = FFmpegBridge.openDecoder(path, AUDIO_SAMPLE_RATE, 16, AUDIO_CHANNELS)
    if (handle == 0L) return null
    var codec: MediaCodec? = null
    try {
        if (startMs > 0L) FFmpegBridge.seekDecoder(handle, startMs)
        val frameBytes = AUDIO_CHANNELS * 2
        val totalFrames = durationMs * AUDIO_SAMPLE_RATE / 1000L
        val fadeFrames = min(AUDIO_SAMPLE_RATE * AUDIO_FADE_MS / 1000L, totalFrames / 4)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_BYTES)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val scratch = ByteArray(AUDIO_CHUNK_BYTES)
        val samples = ArrayList<EncodedSample>()
        val info = MediaCodec.BufferInfo()
        var outputFormat: MediaFormat? = null
        var submittedFrames = 0L
        var inputEos = false
        var outputEos = false
        var decoderEnded = false

        while (!outputEos) {
            if (isCancelled()) return null
            if (!inputEos) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: return null
                    input.clear()
                    val remainingFrames = totalFrames - submittedFrames
                    val wantFrames = min(
                        remainingFrames,
                        (min(input.capacity(), scratch.size) / frameBytes).toLong(),
                    ).toInt()
                    var readBytes = 0
                    if (wantFrames > 0 && !decoderEnded) {
                        val wantBytes = wantFrames * frameBytes
                        while (readBytes < wantBytes) {
                            val read = FFmpegBridge.decodeChunk(handle, scratch, readBytes, wantBytes - readBytes)
                            if (read <= 0) {
                                decoderEnded = true
                                break
                            }
                            readBytes += read
                        }
                        readBytes -= readBytes % frameBytes
                    }
                    if (readBytes <= 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, framesToUs(submittedFrames),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEos = true
                    } else {
                        applyFades(scratch, readBytes, submittedFrames, totalFrames, fadeFrames)
                        input.put(scratch, 0, readBytes)
                        codec.queueInputBuffer(inputIndex, 0, readBytes, framesToUs(submittedFrames), 0)
                        submittedFrames += readBytes / frameBytes
                        onProgress((submittedFrames.toFloat() / totalFrames.coerceAtLeast(1L)).coerceIn(0f, 1f))
                    }
                }
            }
            var draining = true
            while (draining) {
                val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    outputIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outputIndex)
                        if (encoded != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            samples += EncodedSample(
                                data = encoded.copyRange(info.offset, info.size),
                                presentationTimeUs = info.presentationTimeUs,
                                flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(),
                            )
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (outputEos) draining = false
                    }
                }
            }
        }
        val resolvedFormat = outputFormat ?: return null
        return EncodedAudioTrack(resolvedFormat, samples)
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        FFmpegBridge.closeDecoder(handle)
    }
}

private fun framesToUs(frames: Long): Long = frames * 1_000_000L / AUDIO_SAMPLE_RATE

private fun ByteBuffer.copyRange(offset: Int, size: Int): ByteArray {
    val out = ByteArray(size)
    val duplicate = duplicate()
    duplicate.position(offset)
    duplicate.limit(offset + size)
    duplicate.get(out)
    return out
}

/** Linear 16-bit PCM ramps at both ends so a cut mid-phrase never clicks. */
private fun applyFades(
    pcm: ByteArray,
    byteCount: Int,
    firstFrame: Long,
    totalFrames: Long,
    fadeFrames: Long,
) {
    if (fadeFrames <= 0L) return
    val frameBytes = AUDIO_CHANNELS * 2
    val frames = byteCount / frameBytes
    for (i in 0 until frames) {
        val frame = firstFrame + i
        val gain = when {
            frame < fadeFrames -> frame.toFloat() / fadeFrames
            frame >= totalFrames - fadeFrames -> (totalFrames - frame).toFloat() / fadeFrames
            else -> continue
        }.coerceIn(0f, 1f)
        for (c in 0 until AUDIO_CHANNELS) {
            val index = i * frameBytes + c * 2
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[index] = (scaled and 0xFF).toByte()
            pcm[index + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
    }
}

private const val AUDIO_SAMPLE_RATE = 44_100
private const val AUDIO_CHANNELS = 2
private const val AUDIO_BIT_RATE = 192_000
private const val AUDIO_FADE_MS = 300L
private const val AUDIO_CHUNK_BYTES = 16 * 1024
private const val CODEC_TIMEOUT_US = 10_000L
