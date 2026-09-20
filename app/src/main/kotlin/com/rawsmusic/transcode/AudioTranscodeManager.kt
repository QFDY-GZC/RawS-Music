package com.rawsmusic.transcode

import android.system.Os
import android.system.OsConstants
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.taglib.TranscodeMetadataReport
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serialized offline audio conversion owner.
 *
 * Phase 5 accepts any source that both FFmpeg can decode and TagLib can inspect, preserves a
 * strict preflight for opaque metadata, supports PCM/lossless -> DSF through the existing P2D core,
 * and converts DSF/DFF back to the lossless PCM target matrix through FFmpeg dsd2pcm + swresample.
 */
object AudioTranscodeManager {
    val selectableSampleRatesHz: List<Int> = listOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000,
    )
    val selectableBitDepths: List<Int> = listOf(16, 24, 32)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-AudioTranscode").apply {
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }

    private val capabilitySnapshot: List<AudioTranscodeCapability> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeAudioTranscoder.capabilities()
    }

    fun capabilities(): List<AudioTranscodeCapability> = capabilitySnapshot

    fun hardwareCapabilities(): List<AudioTranscodeHardwareProfile> =
        HardwareAudioTranscoder.verifiedHardwareProfiles()

    fun probe(path: String): AudioTranscodeProbe? = NativeAudioTranscoder.probe(path)

    fun previewAll(requests: List<AudioTranscodeRequest>): AudioTranscodeBatchPreview =
        AudioTranscodeBatchPreview(
            requests.map { request ->
                AudioTranscodeBatchPreviewItem(request = request, preview = preview(request))
            },
        )

    /**
     * Resolves source-following parameters and all known preflight conflicts without creating any
     * output file. This is the single contract intended for the future conversion-options UI.
     */
    fun preview(request: AudioTranscodeRequest): AudioTranscodePreview {
        val input = File(request.inputPath)
        val output = File(request.outputPath)
        val issues = mutableListOf<AudioTranscodePreviewIssue>()

        if (!input.isFile || input.length() <= 0L) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                "源文件不存在或为空：${input.absolutePath}",
            )
        }

        val source = if (input.isFile && input.length() > 0L) {
            NativeAudioTranscoder.probe(input.absolutePath)
        } else {
            null
        }
        if (input.isFile && source == null) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                "FFmpeg 无法打开源音频或当前构建缺少对应 decoder",
            )
        }

        val metadataSource = if (
            input.isFile && TagLibBridge.isLoaded() && TagLibBridge.isSupported(input.absolutePath)
        ) {
            TagLibBridge.inspectMetadataForTranscode(input.absolutePath)
        } else {
            null
        }
        if (input.isFile && metadataSource?.supported != true) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                "当前源格式没有可用的 TagLib 元数据适配器，禁止静默丢失标签",
            )
        } else if (metadataSource != null && metadataSource.unsupportedOpaqueItems > 0) {
            val detail = buildString {
                append("源标签包含 ${metadataSource.unsupportedOpaqueItems} 个无法映射到 PropertyMap/PICTURE 的 opaque 对象")
                if (metadataSource.unsupportedKeys.isNotEmpty()) {
                    append("：${metadataSource.unsupportedKeys.joinToString()}")
                }
            }
            issues += if (request.metadataPolicy == AudioTranscodeMetadataPolicy.STRICT) {
                blocking(AudioTranscodePreviewIssueCode.SOURCE_METADATA_OPAQUE_ITEMS, detail)
            } else {
                warning(AudioTranscodePreviewIssueCode.SOURCE_METADATA_OPAQUE_ITEMS, "$detail；BEST_EFFORT 将允许降级")
            }
        }

        val capability = capabilities().firstOrNull { it.id == request.format.capabilityId }
        if (capability == null) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                "当前 native 构建没有可用的 ${request.format.name} 转换能力",
            )
        }

        if (output.extension.lowercase() !in request.format.acceptedExtensions) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                "${request.format.name} 输出扩展名必须为 ${request.format.acceptedExtensions.joinToString { ".$it" }}",
            )
        }
        val samePath = runCatching { input.canonicalFile == output.canonicalFile }.getOrDefault(false)
        if (samePath) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.SAME_SOURCE_AND_TARGET,
                "源文件与输出文件不能是同一路径",
            )
        }
        if (output.exists() && !request.overwrite) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.OUTPUT_EXISTS,
                "目标文件已存在：${output.absolutePath}",
            )
        }

        if (source?.hasVideoStream == true) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.VIDEO_SOURCE_DEFERRED,
                "当前版本仅处理纯音频源；检测到视频流，视频→音频将在后续独立开放",
            )
        }

        if (source != null) {
            if (source.isDsdSource) {
                if (defaultPcmSampleRateForDsd(source.dsdSampleRateHz) == null) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                        "无法识别 DSD 采样率族：${source.dsdSampleRateHz} Hz",
                    )
                }
            } else {
                if (source.sampleRateHz !in 8_000..768_000) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                        "源采样率超出当前解码/重采样安全范围：${source.sampleRateHz} Hz",
                    )
                }
                if (source.bitDepthMeaningful && source.bitDepth !in 1..64) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                        "源有效位深无效：${source.bitDepth}-bit",
                    )
                }
            }
            if (source.channels <= 0) {
                issues += blocking(
                    AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
                    "源文件没有有效声道布局",
                )
            }
        }

        val sourcePreferredPcmRate = source?.let {
            if (it.isDsdSource) defaultPcmSampleRateForDsd(it.dsdSampleRateHz) else it.sampleRateHz
        }
        val resolvedRate = if (request.format.isDsd) null else sourcePreferredPcmRate?.let { preferred ->
            request.targetSampleRateHz
                ?: capability?.recommendedSampleRateHz(preferred)
                ?: preferred
        }
        val resolvedBits = if (request.format.isDsd || request.format.isLossy) null else source?.let {
            request.targetBitDepth ?: recommendedBitDepthForSource(
                capability = capability,
                sampleRateHz = resolvedRate,
                sourceBits = when {
                    it.isDsdSource -> 24
                    !it.bitDepthMeaningful -> 24
                    it.bitDepth <= 16 -> 16
                    it.bitDepth <= 24 -> 24
                    else -> 32
                },
            )
        }
        val resolvedBitRateKbps = if (request.format.isLossy) {
            request.targetBitRateKbps ?: resolvedRate?.let { capability?.recommendedBitRateKbps(it) }
        } else {
            null
        }
        val resolvedDsdRate = request.dsdRate.takeIf { request.format.isDsd }
        val sourceDsdRate = source
            ?.takeIf { it.isDsdSource }
            ?.let { dsdRateForLogicalRate(it.dsdSampleRateHz) }
        val resolvedDsdSampleRate = when {
            source == null || resolvedDsdRate == null -> null
            source.isDsdSource && sourceDsdRate == resolvedDsdRate -> source.dsdSampleRateHz
            !source.isDsdSource -> resolveDsdSampleRateHz(source.sampleRateHz, resolvedDsdRate)
            else -> null
        }
        val sourceExtension = input.extension.lowercase()
        val directDsdTransfer = request.format.isDsd && source?.isDsdSource == true &&
            sourceExtension in setOf("dsf", "dff", "dsdiff") &&
            sourceDsdRate != null && sourceDsdRate == resolvedDsdRate
        val backend = if (directDsdTransfer) {
            AudioTranscodeBackendInfo(
                kind = AudioTranscodeBackendKind.BITSTREAM_COPY,
                name = if (sourceExtension == "dsf") {
                    "RawSMusic DSF bitstream copy"
                } else {
                    "RawSMusic DSD direct remux"
                },
                hardwareRequested = request.hardwareAccelerationEnabled,
            )
        } else {
            resolveBackend(
                request = request,
                source = source,
                resolvedRate = resolvedRate,
                resolvedBitRateKbps = resolvedBitRateKbps,
            )
        }

        if (source != null && !source.isDsdSource && request.targetSampleRateHz == null &&
            resolvedRate != null && resolvedRate != source.sampleRateHz
        ) {
            issues += AudioTranscodePreviewIssue(
                AudioTranscodePreviewIssueCode.SOURCE_SAMPLE_RATE_NORMALIZED,
                AudioTranscodePreviewIssueSeverity.INFO,
                "源采样率 ${source.sampleRateHz} Hz 不在目标 encoder 的可用矩阵中；自动转换到最接近的 $resolvedRate Hz",
            )
        }
        if (source != null && !source.isDsdSource && source.bitDepthMeaningful &&
            !request.format.isLossy && !request.format.isDsd && request.targetBitDepth == null &&
            resolvedBits != null && resolvedBits != source.bitDepth
        ) {
            issues += AudioTranscodePreviewIssue(
                AudioTranscodePreviewIssueCode.SOURCE_BIT_DEPTH_NORMALIZED,
                AudioTranscodePreviewIssueSeverity.INFO,
                "源有效位深 ${source.bitDepth}-bit 将归一到目标格式实际支持的 $resolvedBits-bit",
            )
        }

        if (request.format.isDsd) {
            if (source?.isDsdSource == true) {
                when {
                    sourceDsdRate == null -> {
                        issues += blocking(
                            AudioTranscodePreviewIssueCode.DSD_TO_DSD_UNSUPPORTED,
                            "无法把源 DSD 时钟 ${source.dsdSampleRateHz} Hz 映射到 DSD64/128/256/512/1024",
                        )
                    }
                    sourceDsdRate != resolvedDsdRate -> {
                        issues += blocking(
                            AudioTranscodePreviewIssueCode.DSD_TO_DSD_UNSUPPORTED,
                            "DSD 源为 ${sourceDsdRate.name}，目标选择 ${resolvedDsdRate?.name}；改变 DSD rate 需要解调后重新调制，当前不会伪装成同格式转换",
                        )
                    }
                    sourceExtension == "dsf" -> {
                        issues += AudioTranscodePreviewIssue(
                            AudioTranscodePreviewIssueCode.SAME_FORMAT_REENCODE_NOT_ALLOWED,
                            AudioTranscodePreviewIssueSeverity.INFO,
                            "源文件已经是 DSF 且 DSD rate 不变；将直接复制原始 DSF bitstream 到新文件，再执行元数据迁移，不经过 PCM/DSD 重调制。",
                        )
                    }
                    sourceExtension !in setOf("dff", "dsdiff") -> {
                        issues += blocking(
                            AudioTranscodePreviewIssueCode.DSD_TO_DSD_UNSUPPORTED,
                            "当前 raw DSD direct remux 仅开放 DFF/DSDIFF → DSF",
                        )
                    }
                    directDsdTransfer -> {
                        issues += AudioTranscodePreviewIssue(
                            AudioTranscodePreviewIssueCode.DSD_DIRECT_REMUX,
                            AudioTranscodePreviewIssueSeverity.INFO,
                            "DFF/DSDIFF 将直接复制原始 DSD bitstream 到 DSF：只规范化 packet 的 planar/interleaved 布局和 LSBF/MSBF 位序，不经过 dsd2pcm，也不重新 P2D。",
                        )
                    }
                }
            }
            if (request.targetSampleRateHz != null || request.targetBitDepth != null) {
                issues += AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.DSD_PCM_OPTIONS_IGNORED,
                    AudioTranscodePreviewIssueSeverity.INFO,
                    "DSF 目标仅使用 ${request.dsdRate.name}；PCM 采样率/位深选项不参与转换",
                )
            }
            if (source != null && source.channels !in 1..2) {
                issues += blocking(
                    AudioTranscodePreviewIssueCode.DSD_CHANNEL_LAYOUT_UNSUPPORTED,
                    "当前 DSF writer 只发布经过验证的单声道/双声道布局；当前为 ${source.channels} 声道，不会自动下混",
                )
            }
            if (source != null && resolvedDsdSampleRate == null) {
                issues += blocking(
                    AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                    if (source.isDsdSource) {
                        "目标 DSD rate 与源 DSD 时钟不一致，无法 direct remux"
                    } else {
                        "无法从源采样率确定 44.1/48 kHz DSD 基频族"
                    },
                )
            }
            if (capability != null && resolvedDsdRate != null && !capability.supportsDsd(resolvedDsdRate)) {
                issues += blocking(
                    AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                    "当前 native 构建不支持 ${resolvedDsdRate.name}",
                )
            }
        } else {
            request.targetSampleRateHz?.let { rate ->
                if (rate !in selectableSampleRatesHz) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                        "采样率只能从 ${selectableSampleRatesHz.joinToString()} 中选择，或跟随源文件",
                    )
                }
            }
            if (request.format.isLossy) {
                if (request.targetSampleRateHz == null && sourcePreferredPcmRate != null &&
                    resolvedRate != null && resolvedRate != sourcePreferredPcmRate
                ) {
                    issues += AudioTranscodePreviewIssue(
                        AudioTranscodePreviewIssueCode.LOSSY_SAMPLE_RATE_ADJUSTED,
                        AudioTranscodePreviewIssueSeverity.INFO,
                        "${request.format.name} 当前 runtime encoder 不支持跟随源的 $sourcePreferredPcmRate Hz；自动使用最接近的 $resolvedRate Hz",
                    )
                }
                if (request.targetBitDepth != null) {
                    issues += AudioTranscodePreviewIssue(
                        AudioTranscodePreviewIssueCode.LOSSY_BIT_DEPTH_IGNORED,
                        AudioTranscodePreviewIssueSeverity.INFO,
                        "${request.format.name} 是有损编码；PCM 位深不是编码器质量参数，已忽略位深选项",
                    )
                }
            } else {
                request.targetBitDepth?.let { bits ->
                    if (bits !in selectableBitDepths) {
                        issues += blocking(
                            AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                            "位深只能选择 16/24/32-bit，或跟随源文件",
                        )
                    }
                }
            }
            if (source?.isDsdSource == true && resolvedRate != null && !request.format.isLossy) {
                issues += AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.DSD_TO_PCM_FILTER_CHAIN,
                    AudioTranscodePreviewIssueSeverity.INFO,
                    "DSD ${source.dsdSampleRateHz} Hz 将经 FFmpeg 96-tap dsd2pcm 低通，再由 swresample 抗混叠降采样到 $resolvedRate Hz / ${resolvedBits ?: 24}-bit",
                )
            }
            if (source != null && !source.isDsdSource && !source.bitDepthMeaningful &&
                request.targetBitDepth == null && !request.format.isLossy
            ) {
                issues += AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.SOURCE_BIT_DEPTH_DEFAULTED,
                    AudioTranscodePreviewIssueSeverity.INFO,
                    "源 codec 没有固有 PCM 位深；默认输出 24-bit，可在高级选项改为 16/32-bit",
                )
            }
            if (request.format.isLossy) {
                val bitRate = resolvedBitRateKbps
                if (bitRate == null) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                        "当前 ${request.format.name} 编码器没有可用的运行时码率 profile",
                    )
                } else if (capability != null && resolvedRate != null &&
                    !capability.supportsLossy(resolvedRate, bitRate)
                ) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                        "当前 ${request.format.name} 编码器无法打开 $resolvedRate Hz / ${bitRate} kbps 组合",
                    )
                }
                if (source != null && source.channels !in 1..2) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.LOSSY_CHANNEL_LAYOUT_UNSUPPORTED,
                        "当前有损输出能力只发布经过双声道 runtime probe 的 profile；${source.channels} 声道源暂不自动下混",
                    )
                }
                issues += warning(
                    AudioTranscodePreviewIssueCode.LOSSY_OUTPUT,
                    "${request.format.name} 为有损编码；转换会产生不可逆信息损失，再次转码还会增加 generation loss",
                )
                if (request.targetBitRateKbps == null && bitRate != null) {
                    issues += AudioTranscodePreviewIssue(
                        AudioTranscodePreviewIssueCode.LOSSY_BITRATE_DEFAULTED,
                        AudioTranscodePreviewIssueSeverity.INFO,
                        "未指定码率，使用当前编码器实际可打开的默认值 ${bitRate} kbps",
                    )
                }
            } else if (capability != null && resolvedRate != null && resolvedBits != null &&
                !capability.supports(resolvedRate, resolvedBits)
            ) {
                issues += blocking(
                    AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                    "当前 ${request.format.name} 编码器不支持 $resolvedRate Hz / $resolvedBits-bit 组合",
                )
            }

            // The compact runtime capability table is intentionally probed with stereo so the UI
            // can enumerate it cheaply. A real task must additionally open the encoder with the
            // source channel count. This keeps the no-silent-downmix contract for 5.1/7.1 files
            // and prevents a stereo-only codec from failing late during conversion.
            if (source != null && capability != null && resolvedRate != null &&
                backend.kind != AudioTranscodeBackendKind.HARDWARE
            ) {
                val exactSoftwareProfile = if (request.format.isLossy) {
                    resolvedBitRateKbps?.let { bitRate ->
                        NativeAudioTranscoder.canOpenProfile(
                            formatId = request.format.capabilityId,
                            sampleRateHz = resolvedRate,
                            bitDepth = 0,
                            bitRateKbps = bitRate,
                            channels = source.channels,
                        )
                    } ?: false
                } else {
                    resolvedBits?.let { bits ->
                        NativeAudioTranscoder.canOpenProfile(
                            formatId = request.format.capabilityId,
                            sampleRateHz = resolvedRate,
                            bitDepth = bits,
                            bitRateKbps = 0,
                            channels = source.channels,
                        )
                    } ?: false
                }
                if (!exactSoftwareProfile) {
                    issues += blocking(
                        AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                        buildString {
                            append("当前 ${request.format.name} 软件 encoder 无法实际打开 ")
                            append("$resolvedRate Hz / ${source.channels}ch")
                            if (request.format.isLossy) append(" / ${resolvedBitRateKbps} kbps")
                            else append(" / ${resolvedBits}-bit")
                            append("；不会自动下混声道")
                        },
                    )
                }
            }
            val targetCodecId = when {
                capability == null || resolvedRate == null -> null
                request.format.isLossy && resolvedBitRateKbps != null ->
                    capability.codecIdForLossy(resolvedRate, resolvedBitRateKbps)
                resolvedBits != null -> capability.codecIdFor(resolvedRate, resolvedBits)
                else -> null
            }
            val sameCodecContainer = source != null &&
                input.extension.lowercase() in request.format.acceptedExtensions &&
                targetCodecId != null && targetCodecId == source.codecId
            if (sameCodecContainer) {
                val sameRate = resolvedRate == source.sampleRateHz
                val sameBits = request.format.isLossy ||
                    !source.bitDepthMeaningful || resolvedBits == source.bitDepth
                issues += AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.SAME_FORMAT_REENCODE_NOT_ALLOWED,
                    AudioTranscodePreviewIssueSeverity.INFO,
                    if (sameRate && sameBits) {
                        "源文件已经是 ${request.format.name} 且主要音频参数不变；仍会重新编码生成独立新文件。"
                    } else {
                        buildString {
                            append("源文件已经是 ${request.format.name}；将按目标参数重新编码")
                            if (!sameRate) append("，采样率 ${source.sampleRateHz} → $resolvedRate Hz")
                            if (!sameBits && resolvedBits != null) append("，位深 ${source.bitDepth} → $resolvedBits-bit")
                            append("。")
                        }
                    },
                )
            }
        }

        val compressionRange = capability?.compressionRange
        if (request.format == AudioTranscodeFormat.FLAC && compressionRange != null &&
            request.flacCompressionLevel !in compressionRange
        ) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                "FLAC 压缩级别必须位于 $compressionRange",
            )
        }

        val sourceQuantBits = when {
            source == null -> null
            source.isDsdSource -> 32
            source.bitDepthMeaningful -> source.bitDepth
            else -> 32
        }
        val autoDither = !request.format.isDsd && !request.format.isLossy &&
            source != null && resolvedRate != null && resolvedBits != null && sourceQuantBits != null &&
            request.ditherPolicy == AudioTranscodeDitherPolicy.AUTO &&
            resolvedBits < 32 &&
            (resolvedBits < sourceQuantBits || resolvedRate != source.sampleRateHz)
        if (!request.format.isDsd && source != null && !source.isDsdSource && resolvedRate != null &&
            resolvedRate > source.sampleRateHz
        ) {
            issues += warning(
                AudioTranscodePreviewIssueCode.UPSAMPLING,
                "将从 ${source.sampleRateHz} Hz 上采样到 $resolvedRate Hz；不会恢复源文件不存在的频率信息",
            )
        }
        if (!request.format.isDsd && !request.format.isLossy && source != null && !source.isDsdSource &&
            source.bitDepthMeaningful && resolvedBits != null && resolvedBits > source.bitDepth
        ) {
            issues += warning(
                AudioTranscodePreviewIssueCode.BIT_DEPTH_INCREASE,
                "将从 ${source.bitDepth}-bit 扩展到 $resolvedBits-bit；不会增加源文件真实有效位深",
            )
        }
        if (autoDither) {
            issues += AudioTranscodePreviewIssue(
                AudioTranscodePreviewIssueCode.DITHER_WILL_APPLY,
                AudioTranscodePreviewIssueSeverity.INFO,
                "目标参数会降低/重新量化精度，将自动应用 dither",
            )
        }
        val pcmHashComparable = source != null &&
            capability?.lossless == true &&
            !request.format.isDsd &&
            !request.format.isLossy &&
            !source.isDsdSource &&
            source.bitDepthMeaningful &&
            resolvedRate == source.sampleRateHz &&
            resolvedBits == source.bitDepth &&
            !autoDither
        if (request.verificationMode == AudioTranscodeVerificationMode.FULL_PCM_HASH &&
            !pcmHashComparable
        ) {
            issues += blocking(
                AudioTranscodePreviewIssueCode.PCM_HASH_NOT_COMPARABLE,
                "完整 PCM SHA-256 只适用于无重采样、无位深变化、无 dither 的无损 PCM→PCM 转换",
            )
        }
        if (request.hardwareAccelerationEnabled && backend.kind != AudioTranscodeBackendKind.BITSTREAM_COPY) {
            if (backend.kind == AudioTranscodeBackendKind.HARDWARE) {
                issues += AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.HARDWARE_ACCELERATION_SELECTED,
                    AudioTranscodePreviewIssueSeverity.INFO,
                    "硬件编码已命中：${backend.name}。该具体 profile 已通过 MediaCodec configure/start 探针。",
                )
            } else {
                issues += AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.HARDWARE_ACCELERATION_SOFTWARE_FALLBACK,
                    AudioTranscodePreviewIssueSeverity.INFO,
                    backend.fallbackReason ?: "当前请求没有可用硬件 encoder，使用 ${backend.name}",
                )
            }
        }
        if (request.format == AudioTranscodeFormat.ALAC || request.format == AudioTranscodeFormat.AAC) {
            issues += warning(
                AudioTranscodePreviewIssueCode.METADATA_SEMANTICS_MAY_DEGRADE,
                "M4A/MP4 covr 不保存部分来源格式的封面角色和描述；STRICT 会在发现语义损失时拒绝提交，BEST_EFFORT 才允许降级",
            )
        }

        val estimate = when {
            source == null -> null
            request.format.isDsd && resolvedDsdSampleRate != null ->
                estimateDsfOutputRange(source, resolvedDsdSampleRate)
            request.format.isLossy && resolvedRate != null && resolvedBitRateKbps != null ->
                estimateLossyOutputRange(source, resolvedBitRateKbps)
            resolvedRate != null && resolvedBits != null ->
                estimatePcmOutputRange(source, request.format, resolvedRate, resolvedBits)
            else -> null
        }
        if (estimate != null && estimate.second >= 4L * 1024L * 1024L * 1024L) {
            issues += warning(
                AudioTranscodePreviewIssueCode.LARGE_OUTPUT,
                "预计输出上限约 ${formatBytes(estimate.second)}",
            )
        }

        return AudioTranscodePreview(
            source = source,
            capability = capability,
            resolvedSampleRateHz = resolvedRate,
            resolvedBitDepth = resolvedBits,
            resolvedDsdRate = resolvedDsdRate,
            resolvedDsdSampleRateHz = resolvedDsdSampleRate,
            resolvedBitRateKbps = resolvedBitRateKbps,
            backend = backend,
            autoDitherWillApply = autoDither,
            estimatedOutputBytesMin = estimate?.first,
            estimatedOutputBytesMax = estimate?.second,
            issues = issues.distinctBy { it.code to it.detail },
        )
    }

    private fun recommendedBitDepthForSource(
        capability: AudioTranscodeCapability?,
        sampleRateHz: Int?,
        sourceBits: Int,
    ): Int {
        val available = capability?.profiles
            ?.asSequence()
            ?.filter { profile -> sampleRateHz == null || profile.sampleRateHz == sampleRateHz }
            ?.map { it.bitDepth }
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            ?.toList()
            .orEmpty()
        if (available.isEmpty()) return when {
            sourceBits <= 16 -> 16
            sourceBits <= 24 -> 24
            else -> 32
        }
        if (sourceBits in available) return sourceBits
        return available.firstOrNull { it >= sourceBits } ?: available.last()
    }

    private fun resolveBackend(
        request: AudioTranscodeRequest,
        source: AudioTranscodeProbe?,
        resolvedRate: Int?,
        resolvedBitRateKbps: Int?,
    ): AudioTranscodeBackendInfo {
        val softwareName = when (request.format) {
            AudioTranscodeFormat.DSF -> "RawSMusic Offline P2D"
            else -> "FFmpeg ${request.format.name}"
        }
        if (!request.hardwareAccelerationEnabled) {
            return AudioTranscodeBackendInfo(
                kind = AudioTranscodeBackendKind.SOFTWARE,
                name = softwareName,
                hardwareRequested = false,
            )
        }
        if (request.format != AudioTranscodeFormat.AAC) {
            val reason = HardwareAudioTranscoder.selectExact(
                format = request.format,
                sampleRateHz = resolvedRate ?: 0,
                channels = source?.channels ?: 0,
                bitRateKbps = resolvedBitRateKbps ?: 0,
            ).fallbackReason
            return AudioTranscodeBackendInfo(
                kind = AudioTranscodeBackendKind.SOFTWARE,
                name = softwareName,
                hardwareRequested = true,
                fallbackReason = reason ?: "${request.format.name} 当前没有安全的 Android hardware encoder backend，使用 $softwareName",
            )
        }
        if (source == null || resolvedRate == null || resolvedBitRateKbps == null) {
            return AudioTranscodeBackendInfo(
                kind = AudioTranscodeBackendKind.SOFTWARE,
                name = softwareName,
                hardwareRequested = true,
                fallbackReason = "硬件编码参数尚未解析完成，使用 $softwareName",
            )
        }
        val selection = HardwareAudioTranscoder.selectExact(
            format = request.format,
            sampleRateHz = resolvedRate,
            channels = source.channels,
            bitRateKbps = resolvedBitRateKbps,
        )
        val hardware = selection.selection
        return if (hardware != null) {
            AudioTranscodeBackendInfo(
                kind = AudioTranscodeBackendKind.HARDWARE,
                name = hardware.profile.codecName,
                hardwareRequested = true,
            )
        } else {
            AudioTranscodeBackendInfo(
                kind = AudioTranscodeBackendKind.SOFTWARE,
                name = softwareName,
                hardwareRequested = true,
                fallbackReason = selection.fallbackReason ?: "设备不支持该硬件编码 profile，使用 $softwareName",
            )
        }
    }

    private fun resolveDsdSampleRateHz(
        sourceSampleRateHz: Int,
        rate: AudioTranscodeDsdRate,
    ): Int? {
        val base = when {
            sourceSampleRateHz in selectableSampleRatesHz && sourceSampleRateHz % 48_000 == 0 -> 48_000
            sourceSampleRateHz in selectableSampleRatesHz && sourceSampleRateHz % 44_100 == 0 -> 44_100
            else -> return null
        }
        return base * rate.multiplier
    }

    private fun dsdRateForLogicalRate(logicalDsdSampleRateHz: Int): AudioTranscodeDsdRate? {
        if (logicalDsdSampleRateHz <= 0) return null
        return AudioTranscodeDsdRate.entries.firstOrNull { rate ->
            logicalDsdSampleRateHz == 44_100 * rate.multiplier ||
                logicalDsdSampleRateHz == 48_000 * rate.multiplier
        }
    }

    private fun defaultPcmSampleRateForDsd(logicalDsdSampleRateHz: Int): Int? = when {
        logicalDsdSampleRateHz > 0 && logicalDsdSampleRateHz % (48_000 * 64) == 0 -> 192_000
        logicalDsdSampleRateHz > 0 && logicalDsdSampleRateHz % (44_100 * 64) == 0 -> 176_400
        else -> null
    }

    fun start(request: AudioTranscodeRequest): AudioTranscodeTask {
        val task = AudioTranscodeTask(request)
        executor.execute {
            if (!task.markStarted()) return@execute
            val result = runTask(task, request)
            task.finish(result)
        }
        return task
    }

    private fun runTask(
        task: AudioTranscodeTask,
        request: AudioTranscodeRequest,
    ): AudioTranscodeResult {
        var partial: File? = null
        try {
            if (task.isCancellationRequested()) return AudioTranscodeResult.Cancelled
            task.updateStage(AudioTranscodeStage.PROBING, 0)

            val preflight = preview(request)
            val blocking = preflight.issues.firstOrNull {
                it.severity == AudioTranscodePreviewIssueSeverity.BLOCKING
            }
            if (blocking != null || !preflight.canStart) {
                return failureForPreviewIssue(blocking)
            }
            val source = requireNotNull(preflight.source)
            val capability = requireNotNull(preflight.capability)
            val targetRate = preflight.resolvedSampleRateHz
            val targetBits = preflight.resolvedBitDepth
            val targetBitRateKbps = preflight.resolvedBitRateKbps
            val dsdRate = preflight.resolvedDsdRate
            val targetDsdSampleRate = preflight.resolvedDsdSampleRateHz
            val expectedCodecId = when {
                request.format.isDsd -> null
                request.format.isLossy -> {
                    val rate = requireNotNull(targetRate)
                    val bitRate = requireNotNull(targetBitRateKbps)
                    capability.codecIdForLossy(rate, bitRate)
                        ?: return AudioTranscodeResult.Failure(
                            AudioTranscodeFailureReason.UNSUPPORTED_TARGET,
                            "目标有损 profile 在运行时能力矩阵中不存在",
                        )
                }
                else -> {
                    val rate = requireNotNull(targetRate)
                    val bits = requireNotNull(targetBits)
                    capability.codecIdFor(rate, bits)
                        ?: return AudioTranscodeResult.Failure(
                            AudioTranscodeFailureReason.UNSUPPORTED_TARGET,
                            "目标 profile 在运行时能力矩阵中不存在",
                        )
                }
            }
            if (request.format.isDsd && (dsdRate == null || targetDsdSampleRate == null)) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.UNSUPPORTED_TARGET,
                    "DSD 目标参数无法解析",
                )
            }

            val input = File(request.inputPath)
            val output = File(request.outputPath)
            val parent = output.parentFile
                ?: return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.INVALID_REQUEST,
                    "输出路径没有父目录",
                )
            if (!parent.exists() && !parent.mkdirs()) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.COMMIT_FAILED,
                    "无法创建输出目录：${parent.absolutePath}",
                )
            }
            if (!parent.isDirectory) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.COMMIT_FAILED,
                    "输出父路径不是目录：${parent.absolutePath}",
                )
            }

            val requiredBytes = (preflight.estimatedOutputBytesMax ?: (256L * 1024L * 1024L)) +
                64L * 1024L * 1024L
            val usable = parent.usableSpace
            if (usable > 0L && requiredBytes > usable) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.INSUFFICIENT_SPACE,
                    "空间不足：至少需要约 ${formatBytes(requiredBytes)}，当前可用 ${formatBytes(usable)}",
                )
            }

            val partialFile = File(
                parent,
                ".${output.nameWithoutExtension}.raws-partial-${UUID.randomUUID()}.${request.format.extension}",
            )
            partial = partialFile
            if (partialFile.exists()) partialFile.delete()
            task.updateStage(AudioTranscodeStage.PROBING, 1000)
            if (task.isCancellationRequested()) return AudioTranscodeResult.Cancelled

            task.updateStage(AudioTranscodeStage.ENCODING, 0)
            var actualBackend = preflight.backend ?: resolveBackend(
                request = request,
                source = source,
                resolvedRate = targetRate,
                resolvedBitRateKbps = targetBitRateKbps,
            )
            var nativeFailureDetail: String? = null
            val nativeCode = NativeAudioTranscoder.createSession().use { session ->
                task.attachSession(session)
                val polling = AtomicBoolean(true)
                val poller = thread(
                    start = true,
                    isDaemon = true,
                    name = "RawS-AudioTranscodeProgress",
                ) {
                    while (polling.get()) {
                        task.updateStage(AudioTranscodeStage.ENCODING, session.progressPermille())
                        try {
                            Thread.sleep(160L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                try {
                    if (task.isCancellationRequested()) session.cancel()
                    if (request.format.isDsd) {
                        if (actualBackend.kind == AudioTranscodeBackendKind.BITSTREAM_COPY) {
                            if (input.extension.equals("dsf", ignoreCase = true)) {
                                input.copyTo(partialFile, overwrite = true)
                                0
                            } else {
                                session.remuxDsdToDsf(
                                    inputPath = input.absolutePath,
                                    outputPath = partialFile.absolutePath,
                                    dsdRate = requireNotNull(dsdRate),
                                )
                            }
                        } else {
                            session.transcodeDsf(
                                inputPath = input.absolutePath,
                                outputPath = partialFile.absolutePath,
                                dsdRate = requireNotNull(dsdRate),
                            )
                        }
                    } else if (request.format.isLossy) {
                        val rate = requireNotNull(targetRate)
                        val bitRate = requireNotNull(targetBitRateKbps)
                        if (actualBackend.kind == AudioTranscodeBackendKind.HARDWARE &&
                            request.format == AudioTranscodeFormat.AAC
                        ) {
                            val selectionResult = HardwareAudioTranscoder.selectExact(
                                format = request.format,
                                sampleRateHz = rate,
                                channels = source.channels,
                                bitRateKbps = bitRate,
                            )
                            val selection = selectionResult.selection
                            if (selection != null) {
                                when (val hardwareResult = HardwareAudioTranscoder.encodeAac(
                                    session = session,
                                    inputPath = input.absolutePath,
                                    outputPath = partialFile.absolutePath,
                                    selection = selection,
                                    isCancelled = task::isCancellationRequested,
                                )) {
                                    HardwareAudioTranscoder.EncodeResult.Success -> 0
                                    HardwareAudioTranscoder.EncodeResult.Cancelled ->
                                        NativeAudioTranscoder.ERR_CANCELLED
                                    is HardwareAudioTranscoder.EncodeResult.Failed -> {
                                        if (partialFile.exists() && !partialFile.delete()) {
                                            return@use -1
                                        }
                                        actualBackend = AudioTranscodeBackendInfo(
                                            kind = AudioTranscodeBackendKind.SOFTWARE,
                                            name = "FFmpeg ${request.format.name}",
                                            hardwareRequested = true,
                                            fallbackReason = "硬件 ${selection.profile.codecName} 运行失败：${hardwareResult.detail}；已回退 FFmpeg 软件编码",
                                        )
                                        session.transcodeLossy(
                                            inputPath = input.absolutePath,
                                            outputPath = partialFile.absolutePath,
                                            formatId = request.format.capabilityId,
                                            sampleRateHz = rate,
                                            bitRateKbps = bitRate,
                                        )
                                    }
                                }
                            } else {
                                actualBackend = AudioTranscodeBackendInfo(
                                    kind = AudioTranscodeBackendKind.SOFTWARE,
                                    name = "FFmpeg ${request.format.name}",
                                    hardwareRequested = true,
                                    fallbackReason = selectionResult.fallbackReason
                                        ?: "硬件 AAC profile 在执行前失效；已回退 FFmpeg 软件编码",
                                )
                                session.transcodeLossy(
                                    inputPath = input.absolutePath,
                                    outputPath = partialFile.absolutePath,
                                    formatId = request.format.capabilityId,
                                    sampleRateHz = rate,
                                    bitRateKbps = bitRate,
                                )
                            }
                        } else {
                            session.transcodeLossy(
                                inputPath = input.absolutePath,
                                outputPath = partialFile.absolutePath,
                                formatId = request.format.capabilityId,
                                sampleRateHz = rate,
                                bitRateKbps = bitRate,
                            )
                        }
                    } else {
                        session.transcodeLossless(
                            inputPath = input.absolutePath,
                            outputPath = partialFile.absolutePath,
                            formatId = request.format.capabilityId,
                            sampleRateHz = requireNotNull(targetRate),
                            bitDepth = requireNotNull(targetBits),
                            flacCompressionLevel = request.flacCompressionLevel,
                            autoDither = request.ditherPolicy == AudioTranscodeDitherPolicy.AUTO,
                        )
                    }
                } finally {
                    nativeFailureDetail = session.lastErrorDetail()
                    polling.set(false)
                    poller.interrupt()
                    runCatching { poller.join(500L) }
                    task.detachSession(session)
                }
            }
            if (nativeCode == NativeAudioTranscoder.ERR_CANCELLED || task.isCancellationRequested()) {
                return AudioTranscodeResult.Cancelled
            }
            if (nativeCode != 0 || !partialFile.isFile || partialFile.length() <= 0L) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.ENCODE_FAILED,
                    buildString {
                        append("${request.format.name} 编码失败（native=$nativeCode")
                        nativeFailureDetail?.takeIf(String::isNotBlank)?.let { detail ->
                            append("，$detail")
                        }
                        append('）')
                    },
                    nativeCode = nativeCode,
                )
            }
            task.updateStage(AudioTranscodeStage.ENCODING, 1000)

            task.updateStage(AudioTranscodeStage.MIGRATING_METADATA, 0)
            val metadata = TagLibBridge.migrateMetadataForTranscode(
                sourcePath = input.absolutePath,
                targetPath = partialFile.absolutePath,
                encoderLabel = metadataEncoderLabel(request, actualBackend),
            )
            val metadataAccepted = when (request.metadataPolicy) {
                AudioTranscodeMetadataPolicy.STRICT -> metadata.strictOk
                AudioTranscodeMetadataPolicy.BEST_EFFORT ->
                    metadata.supported && metadata.saveOk && metadata.verifyOk
            }
            if (!metadataAccepted) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.METADATA_MIGRATION_FAILED,
                    metadataFailureDetail(metadata),
                    metadata = metadata,
                )
            }
            task.updateStage(AudioTranscodeStage.MIGRATING_METADATA, 1000)
            if (task.isCancellationRequested()) return AudioTranscodeResult.Cancelled

            task.updateStage(AudioTranscodeStage.VERIFYING, 0)
            val dsfProbe = if (request.format.isDsd) {
                NativeAudioTranscoder.probeDsf(partialFile.absolutePath)
                    ?: return AudioTranscodeResult.Failure(
                        AudioTranscodeFailureReason.VERIFICATION_FAILED,
                        "DSF 容器无法重新打开验证",
                        metadata = metadata,
                    )
            } else {
                null
            }
            val outputProbe = dsfProbe?.asAudioProbe() ?:
                (NativeAudioTranscoder.probe(partialFile.absolutePath)
                    ?: return AudioTranscodeResult.Failure(
                        AudioTranscodeFailureReason.VERIFICATION_FAILED,
                        "转换文件无法重新打开验证",
                        metadata = metadata,
                    ))
            var verification = if (dsfProbe != null) {
                verifyDsf(
                    source = source,
                    output = dsfProbe,
                    targetDsdSampleRate = requireNotNull(targetDsdSampleRate),
                )
            } else {
                verifyFast(
                    source = source,
                    output = outputProbe,
                    expectedCodecId = requireNotNull(expectedCodecId),
                    targetRate = requireNotNull(targetRate),
                    targetBits = targetBits,
                    lossy = request.format.isLossy,
                )
            }
            if (!verification.passed) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.VERIFICATION_FAILED,
                    "转换后参数/容器校验失败：$verification",
                    metadata = metadata,
                )
            }

            val pcmHashComparable = capability.lossless &&
                !request.format.isDsd &&
                !request.format.isLossy &&
                !source.isDsdSource &&
                source.bitDepthMeaningful &&
                targetRate == source.sampleRateHz &&
                targetBits == source.bitDepth &&
                !preflight.autoDitherWillApply
            val shouldHash = pcmHashComparable &&
                request.verificationMode != AudioTranscodeVerificationMode.FAST
            if (shouldHash) {
                val rate = requireNotNull(targetRate)
                val bits = requireNotNull(targetBits)
                task.updateStage(AudioTranscodeStage.VERIFYING, 100)
                val sourceDigest = computePcmDigest(
                    task = task,
                    path = input.absolutePath,
                    sampleRateHz = rate,
                    bitDepth = bits,
                ) ?: return if (task.isCancellationRequested()) {
                    AudioTranscodeResult.Cancelled
                } else {
                    AudioTranscodeResult.Failure(
                        AudioTranscodeFailureReason.VERIFICATION_FAILED,
                        "源文件 decoded PCM SHA-256 计算失败",
                        metadata = metadata,
                    )
                }
                task.updateStage(AudioTranscodeStage.VERIFYING, 520)
                val targetDigest = computePcmDigest(
                    task = task,
                    path = partialFile.absolutePath,
                    sampleRateHz = rate,
                    bitDepth = bits,
                ) ?: return if (task.isCancellationRequested()) {
                    AudioTranscodeResult.Cancelled
                } else {
                    AudioTranscodeResult.Failure(
                        AudioTranscodeFailureReason.VERIFICATION_FAILED,
                        "目标文件 decoded PCM SHA-256 计算失败",
                        metadata = metadata,
                    )
                }
                val hashesMatch = sourceDigest.sha256 == targetDigest.sha256 &&
                    sourceDigest.frames == targetDigest.frames
                verification = verification.copy(
                    pcmHashMatches = hashesMatch,
                    sourcePcmSha256 = sourceDigest.sha256,
                    outputPcmSha256 = targetDigest.sha256,
                )
                if (!hashesMatch) {
                    return AudioTranscodeResult.Failure(
                        AudioTranscodeFailureReason.VERIFICATION_FAILED,
                        "无损转换 decoded PCM SHA-256 不一致：source=${sourceDigest.sha256} target=${targetDigest.sha256}",
                        metadata = metadata,
                    )
                }
            }
            task.updateStage(AudioTranscodeStage.VERIFYING, 1000)
            if (task.isCancellationRequested()) return AudioTranscodeResult.Cancelled

            task.updateStage(AudioTranscodeStage.COMMITTING, 0)
            syncFile(partialFile)
            if (output.exists() && !request.overwrite) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.OUTPUT_EXISTS,
                    "提交前发现目标文件已存在：${output.absolutePath}",
                    metadata = metadata,
                )
            }
            try {
                Os.rename(partialFile.absolutePath, output.absolutePath)
                syncDirectory(parent)
            } catch (error: Throwable) {
                return AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.COMMIT_FAILED,
                    "原子提交失败：${error.message ?: error::class.java.simpleName}",
                    metadata = metadata,
                )
            }
            partial = null
            task.updateStage(AudioTranscodeStage.COMMITTING, 1000)
            task.updateStage(AudioTranscodeStage.COMPLETED, 1000)

            return AudioTranscodeResult.Success(
                output = output,
                source = source,
                outputProbe = outputProbe,
                metadata = metadata,
                verification = verification,
                autoDitherApplied = preflight.autoDitherWillApply,
                dsfProbe = dsfProbe,
                backend = actualBackend,
            )
        } catch (error: Throwable) {
            return if (task.isCancellationRequested()) {
                AudioTranscodeResult.Cancelled
            } else {
                AudioTranscodeResult.Failure(
                    AudioTranscodeFailureReason.INTERNAL_ERROR,
                    error.message ?: error::class.java.simpleName,
                )
            }
        } finally {
            partial?.let { runCatching { if (it.exists()) it.delete() } }
        }
    }

    private fun failureForPreviewIssue(issue: AudioTranscodePreviewIssue?): AudioTranscodeResult.Failure {
        if (issue == null) {
            return AudioTranscodeResult.Failure(
                AudioTranscodeFailureReason.INVALID_REQUEST,
                "转换预检失败",
            )
        }
        val reason = when (issue.code) {
            AudioTranscodePreviewIssueCode.OUTPUT_EXISTS -> AudioTranscodeFailureReason.OUTPUT_EXISTS
            AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE -> AudioTranscodeFailureReason.UNSUPPORTED_TARGET
            AudioTranscodePreviewIssueCode.SOURCE_PARAMETER_OUTSIDE_POLICY,
            AudioTranscodePreviewIssueCode.SOURCE_METADATA_OPAQUE_ITEMS,
            AudioTranscodePreviewIssueCode.DSD_CHANNEL_LAYOUT_UNSUPPORTED,
            AudioTranscodePreviewIssueCode.LOSSY_CHANNEL_LAYOUT_UNSUPPORTED,
            AudioTranscodePreviewIssueCode.DSD_TO_DSD_UNSUPPORTED,
            AudioTranscodePreviewIssueCode.VIDEO_SOURCE_DEFERRED ->
                AudioTranscodeFailureReason.UNSUPPORTED_SOURCE
            AudioTranscodePreviewIssueCode.SAME_SOURCE_AND_TARGET,
            AudioTranscodePreviewIssueCode.SAME_FORMAT_REENCODE_NOT_ALLOWED,
            AudioTranscodePreviewIssueCode.PCM_HASH_NOT_COMPARABLE ->
                AudioTranscodeFailureReason.INVALID_REQUEST
            else -> AudioTranscodeFailureReason.INVALID_REQUEST
        }
        return AudioTranscodeResult.Failure(reason, issue.detail)
    }

    private fun blocking(
        code: AudioTranscodePreviewIssueCode,
        detail: String,
    ) = AudioTranscodePreviewIssue(code, AudioTranscodePreviewIssueSeverity.BLOCKING, detail)

    private fun warning(
        code: AudioTranscodePreviewIssueCode,
        detail: String,
    ) = AudioTranscodePreviewIssue(code, AudioTranscodePreviewIssueSeverity.WARNING, detail)

    private fun estimatePcmOutputRange(
        source: AudioTranscodeProbe,
        format: AudioTranscodeFormat,
        targetRate: Int,
        targetBits: Int,
    ): Pair<Long, Long>? {
        if (source.durationMs <= 0L || source.channels <= 0) return null
        val seconds = source.durationMs.toDouble() / 1000.0
        val storedBytesPerSample = when (targetBits) {
            16 -> 2
            24 -> 3
            32 -> 4
            else -> return null
        }
        val pcmBytes = seconds * targetRate.toDouble() * source.channels.toDouble() * storedBytesPerSample
        val minFactor: Double
        val maxFactor: Double
        when (format) {
            AudioTranscodeFormat.WAV,
            AudioTranscodeFormat.AIFF -> {
                minFactor = 1.0
                maxFactor = 1.001
            }
            AudioTranscodeFormat.FLAC,
            AudioTranscodeFormat.OGG_FLAC -> {
                minFactor = 0.40
                maxFactor = 0.92
            }
            AudioTranscodeFormat.ALAC -> {
                minFactor = 0.45
                maxFactor = 0.95
            }
            AudioTranscodeFormat.WAVPACK -> {
                minFactor = 0.38
                maxFactor = 0.92
            }
            AudioTranscodeFormat.TTA -> {
                minFactor = 0.42
                maxFactor = 0.98
            }
            AudioTranscodeFormat.DSF,
            AudioTranscodeFormat.AAC,
            AudioTranscodeFormat.MP2,
            AudioTranscodeFormat.MP3,
            AudioTranscodeFormat.OPUS,
            AudioTranscodeFormat.VORBIS,
            AudioTranscodeFormat.WMA -> return null
        }
        val overhead = 2L * 1024L * 1024L
        val min = (pcmBytes * minFactor).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        val max = (pcmBytes * maxFactor).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong() + overhead
        return min.coerceAtLeast(0L) to max.coerceAtLeast(min)
    }

    private fun estimateLossyOutputRange(
        source: AudioTranscodeProbe,
        bitRateKbps: Int,
    ): Pair<Long, Long>? {
        if (source.durationMs <= 0L || bitRateKbps <= 0) return null
        val payload = source.durationMs.toDouble() / 1000.0 * bitRateKbps.toDouble() * 1000.0 / 8.0
        val base = payload.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong().coerceAtLeast(0L)
        val min = (base * 0.94).toLong().coerceAtLeast(0L)
        val max = (base * 1.08).toLong() + 2L * 1024L * 1024L
        return min to max.coerceAtLeast(min)
    }

    private fun estimateDsfOutputRange(
        source: AudioTranscodeProbe,
        targetDsdSampleRateHz: Int,
    ): Pair<Long, Long>? {
        if (source.durationMs <= 0L || source.channels !in 1..2 || targetDsdSampleRateHz <= 0) return null
        val logicalBitsPerChannel = source.durationMs.toDouble() * targetDsdSampleRateHz.toDouble() / 1000.0
        val logicalBytesPerChannel = kotlin.math.ceil(logicalBitsPerChannel / 8.0).toLong()
        val blockSize = 4096L
        val paddedPerChannel = ((logicalBytesPerChannel + blockSize - 1L) / blockSize) * blockSize
        val audioOnly = 92L + paddedPerChannel * source.channels.toLong()
        // Metadata size is source-dependent; reserve a conservative 2 MiB envelope for preview/disk preflight.
        return audioOnly to (audioOnly + 2L * 1024L * 1024L)
    }

    private fun verifyFast(
        source: AudioTranscodeProbe,
        output: AudioTranscodeProbe,
        expectedCodecId: Int,
        targetRate: Int,
        targetBits: Int?,
        lossy: Boolean,
    ): AudioTranscodeVerification {
        val durationToleranceMs = if (lossy) 300L else 100L
        val durationMatches = if (source.durationMs > 0L && output.durationMs > 0L) {
            abs(source.durationMs - output.durationMs) <= durationToleranceMs
        } else {
            source.durationMs == output.durationMs || output.durationMs > 0L
        }
        return AudioTranscodeVerification(
            sampleRateMatches = output.sampleRateHz == targetRate,
            bitDepthMatches = lossy || (targetBits != null && output.bitDepth == targetBits),
            channelsMatch = output.channels == source.channels,
            durationMatches = durationMatches,
            codecMatches = expectedCodecId == 0 || output.codecId == expectedCodecId,
        )
    }

    private fun computePcmDigest(
        task: AudioTranscodeTask,
        path: String,
        sampleRateHz: Int,
        bitDepth: Int,
    ): AudioTranscodePcmDigest? = NativeAudioTranscoder.createSession().use { session ->
        task.attachSession(session)
        try {
            if (task.isCancellationRequested()) session.cancel()
            session.canonicalPcmDigest(
                inputPath = path,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
            )
        } finally {
            task.detachSession(session)
        }
    }

    private fun verifyDsf(
        source: AudioTranscodeProbe,
        output: AudioDsfProbe,
        targetDsdSampleRate: Int,
    ): AudioTranscodeVerification {
        val durationMatches = if (source.durationMs > 0L && output.durationMs > 0L) {
            abs(source.durationMs - output.durationMs) <= 100L
        } else {
            output.durationMs > 0L
        }
        val structureMatches = output.bitsPerSample == 8 &&
            output.blockSizePerChannel == 4096 &&
            output.sampleCount > 0L &&
            output.dataChunkSize >= 12L &&
            output.fileSize >= 92L &&
            (output.metadataOffset == 0L || output.metadataOffset >= 92L)
        return AudioTranscodeVerification(
            sampleRateMatches = output.sampleRateHz == targetDsdSampleRate,
            bitDepthMatches = output.bitsPerSample == 8,
            channelsMatch = output.channels == source.channels,
            durationMatches = durationMatches,
            codecMatches = true,
            containerStructureMatches = structureMatches,
        )
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { stream -> stream.fd.sync() }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = runCatching {
            Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        }.getOrNull() ?: return
        try {
            runCatching { Os.fsync(descriptor) }
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    private fun metadataEncoderLabel(
        request: AudioTranscodeRequest,
        backend: AudioTranscodeBackendInfo,
    ): String = when (backend.kind) {
        AudioTranscodeBackendKind.BITSTREAM_COPY -> "RawSMusic DSD Direct Remux"
        AudioTranscodeBackendKind.HARDWARE -> "RawSMusic / MediaCodec ${backend.name}"
        AudioTranscodeBackendKind.SOFTWARE -> when (request.format) {
            AudioTranscodeFormat.DSF -> "RawSMusic PCMToDSD"
            else -> "RawSMusic / ${backend.name}"
        }
    }

    private fun metadataFailureDetail(report: TranscodeMetadataReport): String = buildString {
        append("元数据严格迁移失败")
        if (!report.supported) append("：当前源/目标组合没有迁移器")
        if (!report.saveOk) append("；目标标签写入失败")
        if (report.saveOk && !report.verifyOk) append("；目标标签写入后重新打开验证失败")
        if (report.rejectedFields > 0) append("；${report.rejectedFields} 个文本字段被目标格式拒绝")
        if (report.textMismatches > 0) append("；${report.textMismatches} 个文本字段验证不一致")
        if (report.pictureMismatches > 0) append("；${report.pictureMismatches} 张图片 payload 验证不一致")
        if (report.unsupportedBinaryItems > 0) append("；${report.unsupportedBinaryItems} 个二进制/容器语义无法完整表达")
        if (report.mismatchKeys.isNotEmpty()) append("；字段=${report.mismatchKeys.joinToString()}")
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) "%.2f GiB".format(gib)
        else "%.1f MiB".format(bytes.toDouble() / (1024.0 * 1024.0))
    }
}

class AudioTranscodeTask internal constructor(
    val request: AudioTranscodeRequest,
) {
    private val started = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val completion = CompletableDeferred<AudioTranscodeResult>()
    private val _progress = MutableStateFlow(
        AudioTranscodeProgress(AudioTranscodeStage.QUEUED, 0, 0),
    )

    @Volatile
    private var nativeSession: NativeAudioTranscoder.Session? = null

    val progress: StateFlow<AudioTranscodeProgress> = _progress.asStateFlow()

    fun cancel() {
        cancelRequested.set(true)
        nativeSession?.cancel()
        if (!started.get()) {
            _progress.value = AudioTranscodeProgress(AudioTranscodeStage.CANCELLED, 1000, 1000)
            completion.complete(AudioTranscodeResult.Cancelled)
        }
    }

    suspend fun await(): AudioTranscodeResult = completion.await()

    internal fun isCancellationRequested(): Boolean = cancelRequested.get()

    internal fun markStarted(): Boolean {
        if (completion.isCompleted) return false
        started.set(true)
        return true
    }

    internal fun attachSession(session: NativeAudioTranscoder.Session) {
        nativeSession = session
        if (cancelRequested.get()) session.cancel()
    }

    internal fun detachSession(session: NativeAudioTranscoder.Session) {
        if (nativeSession === session) nativeSession = null
    }

    internal fun updateStage(stage: AudioTranscodeStage, stagePermille: Int) {
        if (completion.isCompleted) return
        val normalized = stagePermille.coerceIn(0, 1000)
        _progress.value = AudioTranscodeProgress(
            stage = stage,
            stagePermille = normalized,
            overallPermille = overallProgress(stage, normalized),
        )
    }

    internal fun finish(result: AudioTranscodeResult) {
        nativeSession = null
        val finalStage = when (result) {
            is AudioTranscodeResult.Success -> AudioTranscodeStage.COMPLETED
            is AudioTranscodeResult.Failure -> AudioTranscodeStage.FAILED
            AudioTranscodeResult.Cancelled -> AudioTranscodeStage.CANCELLED
        }
        _progress.value = AudioTranscodeProgress(finalStage, 1000, 1000)
        completion.complete(result)
    }

    private fun overallProgress(stage: AudioTranscodeStage, stagePermille: Int): Int {
        val (base, weight) = when (stage) {
            AudioTranscodeStage.QUEUED -> 0 to 0
            AudioTranscodeStage.PROBING -> 0 to 40
            AudioTranscodeStage.ENCODING -> 40 to 800
            AudioTranscodeStage.MIGRATING_METADATA -> 840 to 80
            AudioTranscodeStage.VERIFYING -> 920 to 60
            AudioTranscodeStage.COMMITTING -> 980 to 20
            AudioTranscodeStage.COMPLETED,
            AudioTranscodeStage.FAILED,
            AudioTranscodeStage.CANCELLED -> 1000 to 0
        }
        return (base + weight * stagePermille / 1000).coerceIn(0, 1000)
    }
}
