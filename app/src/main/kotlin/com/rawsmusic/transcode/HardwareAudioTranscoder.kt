package com.rawsmusic.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Android hardware-encoder backend. Decode/resample remains owned by the native FFmpeg reader. */
internal object HardwareAudioTranscoder {
    private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L
    private const val MAX_INPUT_BYTES = 256 * 1024

    internal data class Selection(
        val profile: AudioTranscodeHardwareProfile,
    )

    internal data class SelectionResult(
        val selection: Selection? = null,
        val fallbackReason: String? = null,
    )

    internal sealed interface EncodeResult {
        data object Success : EncodeResult
        data object Cancelled : EncodeResult
        data class Failed(val detail: String) : EncodeResult
    }

    private data class ProbeKey(
        val sampleRateHz: Int,
        val channels: Int,
        val bitRateKbps: Int,
    )

    private val exactProbeCache = ConcurrentHashMap<ProbeKey, SelectionResult>()

    fun selectExact(
        format: AudioTranscodeFormat,
        sampleRateHz: Int,
        channels: Int,
        bitRateKbps: Int,
    ): SelectionResult {
        if (format != AudioTranscodeFormat.AAC) {
            return SelectionResult(
                fallbackReason = "当前硬件编码后端仅支持 AAC/M4A；${format.name} 使用 FFmpeg 软件编码",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return SelectionResult(
                fallbackReason = "Android ${Build.VERSION.SDK_INT} 无可靠硬件编码器分类 API，使用 FFmpeg 软件编码",
            )
        }
        if (sampleRateHz <= 0 || channels !in 1..2 || bitRateKbps <= 0) {
            return SelectionResult(fallbackReason = "硬件 AAC 参数无效，使用 FFmpeg 软件编码")
        }
        return exactProbeCache.getOrPut(ProbeKey(sampleRateHz, channels, bitRateKbps)) {
            probeExactAac(sampleRateHz, channels, bitRateKbps)
        }
    }

    fun knownHardwareProfiles(): List<AudioTranscodeHardwareProfile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val sampleRates = intArrayOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
        val bitRates = intArrayOf(96, 128, 160, 192, 256, 320)
        val results = ArrayList<AudioTranscodeHardwareProfile>()
        hardwareAacCodecInfos().forEach { info ->
            val caps = runCatching { info.getCapabilitiesForType(AAC_MIME) }.getOrNull() ?: return@forEach
            val audioCaps = caps.audioCapabilities ?: return@forEach
            for (channels in 1..minOf(2, audioCaps.maxInputChannelCount)) {
                sampleRates.forEach { rate ->
                    if (!audioCaps.isSampleRateSupported(rate)) return@forEach
                    bitRates.forEach { bitRate ->
                        if (audioCaps.bitrateRange.contains(bitRate * 1000)) {
                            results += AudioTranscodeHardwareProfile(
                                format = AudioTranscodeFormat.AAC,
                                sampleRateHz = rate,
                                channels = channels,
                                bitRateKbps = bitRate,
                                codecName = info.name,
                            )
                        }
                    }
                }
            }
        }
        return results.distinct()
    }

    /**
     * Expensive explicit capability enumeration: every returned profile has passed an actual
     * MediaCodec configure/start probe in this process. Exact-request previews use the same cache.
     */
    fun verifiedHardwareProfiles(): List<AudioTranscodeHardwareProfile> = knownHardwareProfiles()
        .asSequence()
        .distinctBy { Triple(it.sampleRateHz, it.channels, it.bitRateKbps) }
        .mapNotNull { candidate ->
            selectExact(
                format = candidate.format,
                sampleRateHz = candidate.sampleRateHz,
                channels = candidate.channels,
                bitRateKbps = candidate.bitRateKbps,
            ).selection?.profile
        }
        .distinct()
        .toList()

    fun encodeAac(
        session: NativeAudioTranscoder.Session,
        inputPath: String,
        outputPath: String,
        selection: Selection,
        isCancelled: () -> Boolean,
    ): EncodeResult {
        val profile = selection.profile
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var reader: NativeAudioTranscoder.Pcm16Reader? = null
        try {
            codec = MediaCodec.createByCodecName(profile.codecName)
            codec.configure(
                createAacFormat(
                    profile.sampleRateHz,
                    profile.channels,
                    profile.bitRateKbps,
                ),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            codec.start()
            reader = session.openPcm16Reader(
                inputPath = inputPath,
                sampleRateHz = profile.sampleRateHz,
                channels = profile.channels,
            ) ?: return EncodeResult.Failed("FFmpeg PCM reader 无法打开源文件")

            File(outputPath).parentFile?.mkdirs()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var inputEos = false
            var outputEos = false
            var submittedFrames = 0L

            while (!outputEos) {
                if (isCancelled()) {
                    session.cancel()
                    return EncodeResult.Cancelled
                }

                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: return EncodeResult.Failed("硬件 AAC encoder 返回空 input buffer")
                        inputBuffer.clear()
                        val frameBytes = profile.channels * 2
                        val maxFrames = inputBuffer.capacity() / frameBytes
                        if (maxFrames <= 0) {
                            return EncodeResult.Failed("硬件 AAC input buffer 小于一个 PCM frame")
                        }
                        val frames = reader.readInto(inputBuffer, maxFrames)
                        when {
                            frames == NativeAudioTranscoder.ERR_CANCELLED -> return EncodeResult.Cancelled
                            frames < 0 -> return EncodeResult.Failed("FFmpeg PCM reader 失败: $frames")
                            frames == 0 -> {
                                val ptsUs = framesToUs(submittedFrames, profile.sampleRateHz)
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            }
                            else -> {
                                val size = frames * frameBytes
                                val ptsUs = framesToUs(submittedFrames, profile.sampleRateHz)
                                codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
                                submittedFrames += frames
                            }
                        }
                    }
                }

                var drain = true
                while (drain) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> drain = false
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                return EncodeResult.Failed("硬件 AAC output format 重复变化")
                            }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outputIndex)
                            if (encoded != null && bufferInfo.size > 0 &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (!muxerStarted || trackIndex < 0) {
                                    return EncodeResult.Failed("硬件 AAC 在 muxer track 建立前产生音频 packet")
                                }
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            outputEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (!outputEos) drain = inputEos
                        }
                    }
                }
            }
            return EncodeResult.Success
        } catch (t: Throwable) {
            return EncodeResult.Failed(t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { reader?.close() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun probeExactAac(
        sampleRateHz: Int,
        channels: Int,
        bitRateKbps: Int,
    ): SelectionResult {
        val candidateInfos = hardwareAacCodecInfos().filter { info ->
            val caps = runCatching { info.getCapabilitiesForType(AAC_MIME) }.getOrNull()
                ?: return@filter false
            val audioCaps = caps.audioCapabilities ?: return@filter false
            audioCaps.maxInputChannelCount >= channels &&
                audioCaps.isSampleRateSupported(sampleRateHz) &&
                audioCaps.bitrateRange.contains(bitRateKbps * 1000)
        }
        if (candidateInfos.isEmpty()) {
            return SelectionResult(
                fallbackReason = "设备没有支持 ${sampleRateHz}Hz/${channels}ch/${bitRateKbps}kbps 的硬件 AAC encoder",
            )
        }

        val failures = ArrayList<String>()
        candidateInfos.forEach { info ->
            val accepted = runCatching {
                val codec = MediaCodec.createByCodecName(info.name)
                try {
                    codec.configure(
                        createAacFormat(sampleRateHz, channels, bitRateKbps),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                    codec.start()
                    true
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            }.getOrElse {
                failures += "${info.name}: ${it.message ?: it::class.java.simpleName}"
                false
            }
            if (accepted) {
                return SelectionResult(
                    selection = Selection(
                        AudioTranscodeHardwareProfile(
                            format = AudioTranscodeFormat.AAC,
                            sampleRateHz = sampleRateHz,
                            channels = channels,
                            bitRateKbps = bitRateKbps,
                            codecName = info.name,
                        )
                    )
                )
            }
        }
        return SelectionResult(
            fallbackReason = buildString {
                append("硬件 AAC capability 声称支持但实际 configure/start 失败")
                failures.firstOrNull()?.let { append("：").append(it) }
            },
        )
    }

    private fun hardwareAacCodecInfos(): List<MediaCodecInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            info.isEncoder &&
                info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(AAC_MIME, ignoreCase = true) }
        }
    }

    private fun createAacFormat(
        sampleRateHz: Int,
        channels: Int,
        bitRateKbps: Int,
    ): MediaFormat = MediaFormat.createAudioFormat(AAC_MIME, sampleRateHz, channels).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitRateKbps * 1000)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
    }

    private fun framesToUs(frames: Long, sampleRateHz: Int): Long =
        frames * 1_000_000L / sampleRateHz.toLong()
}
