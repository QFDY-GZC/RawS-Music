package com.rawsmusic.transcode

import com.rawsmusic.core.common.taglib.TranscodeMetadataReport
import java.io.File

/** Offline audio targets currently wired into the conversion engine. */
enum class AudioTranscodeFormat(
    val capabilityId: String,
    val extension: String,
    val acceptedExtensions: Set<String>,
    val isDsd: Boolean,
    val isLossy: Boolean,
) {
    FLAC("flac", "flac", setOf("flac"), false, false),
    OGG_FLAC("ogg_flac", "oga", setOf("oga", "ogg"), false, false),
    WAV("wav", "wav", setOf("wav"), false, false),
    AIFF("aiff", "aiff", setOf("aiff", "aif"), false, false),
    ALAC("alac", "m4a", setOf("m4a"), false, false),
    WAVPACK("wavpack", "wv", setOf("wv"), false, false),
    TTA("tta", "tta", setOf("tta"), false, false),
    DSF("dsf", "dsf", setOf("dsf"), true, false),
    AAC("aac", "m4a", setOf("m4a"), false, true),
    MP2("mp2", "mp2", setOf("mp2", "mpa", "m2a"), false, true),
    MP3("mp3", "mp3", setOf("mp3"), false, true),
    OPUS("opus", "opus", setOf("opus"), false, true),
    VORBIS("vorbis", "ogg", setOf("ogg"), false, true),
    WMA("wma", "wma", setOf("wma", "asf"), false, true),
}

enum class AudioTranscodeDsdRate(val multiplier: Int) {
    DSD64(64),
    DSD128(128),
    DSD256(256),
    DSD512(512),
    DSD1024(1024),
}

enum class AudioTranscodeMetadataPolicy {
    /** Any text, picture, or unsupported binary/semantic mismatch rejects the partial output. */
    STRICT,

    /** Explicit opt-in: text and picture payload must verify, but target-container semantic loss may remain a warning. */
    BEST_EFFORT,
}

enum class AudioTranscodeDitherPolicy {
    /** Apply dither only when reducing effective precision or when resampling into <32-bit PCM. */
    AUTO,
    OFF,
}

data class AudioTranscodeRequest(
    val inputPath: String,
    val outputPath: String,
    val format: AudioTranscodeFormat = AudioTranscodeFormat.FLAC,
    /** null means follow the source sample rate exactly. Ignored for DSF. */
    val targetSampleRateHz: Int? = null,
    /** null means follow the source effective bit depth exactly. Ignored for DSF. */
    val targetBitDepth: Int? = null,
    /** The only audio-quality selector exposed for DSF targets. */
    val dsdRate: AudioTranscodeDsdRate = AudioTranscodeDsdRate.DSD64,
    /** Used only by FLAC; ignored by PCM/ALAC/DSF targets. */
    val flacCompressionLevel: Int = 8,
    /** null uses the runtime-advertised default. Used only by lossy targets. */
    val targetBitRateKbps: Int? = null,
    val ditherPolicy: AudioTranscodeDitherPolicy = AudioTranscodeDitherPolicy.AUTO,
    val metadataPolicy: AudioTranscodeMetadataPolicy = AudioTranscodeMetadataPolicy.STRICT,
    val verificationMode: AudioTranscodeVerificationMode = AudioTranscodeVerificationMode.AUTO,
    /** Prefer a verified hardware encoder when this exact target/profile is supported. */
    val hardwareAccelerationEnabled: Boolean = false,
    /** Delete the original source only after the converted output passed every commit gate. */
    val deleteSourceOnSuccess: Boolean = false,
    /** Request one RawSMusic library scan after queue drain. MediaStore is always refreshed. */
    val autoScanOnSuccess: Boolean = false,
    val overwrite: Boolean = false,
)

enum class AudioTranscodeBackendKind {
    HARDWARE,
    SOFTWARE,
    BITSTREAM_COPY,
}

data class AudioTranscodeBackendInfo(
    val kind: AudioTranscodeBackendKind,
    /** MediaCodec component name for hardware, FFmpeg encoder identity for software. */
    val name: String,
    val hardwareRequested: Boolean,
    /** Present only when hardware was requested but software was selected. */
    val fallbackReason: String? = null,
)

data class AudioTranscodeHardwareProfile(
    val format: AudioTranscodeFormat,
    val sampleRateHz: Int,
    val channels: Int,
    val bitRateKbps: Int,
    val codecName: String,
)

data class AudioTranscodeProbe(
    /** Decoder PCM-domain rate. For DSD sources FFmpeg exposes the byte clock (DSD bit rate / 8). */
    val sampleRateHz: Int,
    /** Meaningful source precision when known. DSD reports 1; lossy/unknown precision reports 0. */
    val bitDepth: Int,
    val channels: Int,
    val durationMs: Long,
    val bitRate: Long,
    val codecId: Int,
    val isDsdSource: Boolean = false,
    /** Logical one-bit DSD rate from the source container, e.g. 2_822_400 for DSD64. */
    val dsdSampleRateHz: Int = 0,
    /** False when PCM bit depth is not an intrinsic source property (for example AAC/MP3). */
    val bitDepthMeaningful: Boolean = true,
    /** Current audio-only converter deliberately rejects containers that also carry video streams. */
    val hasVideoStream: Boolean = false,
)

data class AudioDsfProbe(
    val sampleRateHz: Int,
    val channels: Int,
    /** DSF packing field. RawSMusic writes 8 for MSB-first bytes from the existing P2D core. */
    val bitsPerSample: Int,
    val sampleCount: Long,
    val blockSizePerChannel: Int,
    val durationMs: Long,
    val fileSize: Long,
    val metadataOffset: Long,
    val dataChunkSize: Long,
) {
    fun asAudioProbe(): AudioTranscodeProbe = AudioTranscodeProbe(
        sampleRateHz = sampleRateHz,
        bitDepth = 1,
        channels = channels,
        durationMs = durationMs,
        bitRate = sampleRateHz.toLong() * channels.toLong(),
        codecId = 0,
    )
}

data class AudioTranscodeProfile(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val codecId: Int,
    /** 0 for lossless/PCM profiles. Positive for an actually-opened lossy bitrate profile. */
    val bitRateKbps: Int = 0,
)

data class AudioTranscodeCapability(
    val id: String,
    val container: String,
    val lossless: Boolean,
    val profiles: Set<AudioTranscodeProfile>,
    val compressionRange: IntRange?,
    val dsdRates: Set<AudioTranscodeDsdRate> = emptySet(),
    val defaultBitRateKbps: Int? = null,
) {
    val sampleRatesHz: Set<Int>
        get() = profiles.mapTo(linkedSetOf()) { it.sampleRateHz }

    val bitDepths: Set<Int>
        get() = profiles.mapNotNullTo(linkedSetOf()) { profile ->
            profile.bitDepth.takeIf { it > 0 }
        }

    val bitRatesKbps: Set<Int>
        get() = profiles.mapNotNullTo(linkedSetOf()) { profile ->
            profile.bitRateKbps.takeIf { it > 0 }
        }

    fun supports(sampleRateHz: Int, bitDepth: Int): Boolean =
        profiles.any { it.sampleRateHz == sampleRateHz && it.bitDepth == bitDepth }

    fun codecIdFor(sampleRateHz: Int, bitDepth: Int): Int? =
        profiles.firstOrNull { it.sampleRateHz == sampleRateHz && it.bitDepth == bitDepth }?.codecId

    fun supportsLossy(sampleRateHz: Int, bitRateKbps: Int): Boolean =
        !lossless && profiles.any {
            it.sampleRateHz == sampleRateHz && it.bitRateKbps == bitRateKbps && it.codecId != 0
        }

    fun codecIdForLossy(sampleRateHz: Int, bitRateKbps: Int): Int? =
        profiles.firstOrNull {
            it.sampleRateHz == sampleRateHz && it.bitRateKbps == bitRateKbps && it.codecId != 0
        }?.codecId

    fun bitRatesFor(sampleRateHz: Int): Set<Int> = profiles
        .asSequence()
        .filter { it.sampleRateHz == sampleRateHz && it.bitRateKbps > 0 }
        .mapTo(linkedSetOf()) { it.bitRateKbps }

    fun recommendedBitRateKbps(sampleRateHz: Int): Int? {
        val available = bitRatesFor(sampleRateHz)
        if (available.isEmpty()) return null
        val preferred = defaultBitRateKbps
        if (preferred != null && preferred in available) return preferred
        return preferred?.let { target -> available.minByOrNull { kotlin.math.abs(it - target) } }
            ?: available.maxOrNull()
    }

    fun recommendedSampleRateHz(sourceSampleRateHz: Int): Int? {
        val available = sampleRatesHz
        if (available.isEmpty()) return null
        if (sourceSampleRateHz in available) return sourceSampleRateHz
        return available.minByOrNull { kotlin.math.abs(it - sourceSampleRateHz) }
    }

    fun supportsDsd(rate: AudioTranscodeDsdRate): Boolean = rate in dsdRates
}

enum class AudioTranscodePreviewIssueCode {
    OUTPUT_EXISTS,
    SAME_SOURCE_AND_TARGET,
    SOURCE_PARAMETER_OUTSIDE_POLICY,
    TARGET_PROFILE_UNAVAILABLE,
    UPSAMPLING,
    BIT_DEPTH_INCREASE,
    DITHER_WILL_APPLY,
    METADATA_SEMANTICS_MAY_DEGRADE,
    SOURCE_METADATA_OPAQUE_ITEMS,
    SAME_FORMAT_REENCODE_NOT_ALLOWED,
    DSD_TO_DSD_UNSUPPORTED,
    DSD_DIRECT_REMUX,
    DSD_TO_PCM_FILTER_CHAIN,
    SOURCE_BIT_DEPTH_DEFAULTED,
    VIDEO_SOURCE_DEFERRED,
    DSD_CHANNEL_LAYOUT_UNSUPPORTED,
    DSD_PCM_OPTIONS_IGNORED,
    LOSSY_BIT_DEPTH_IGNORED,
    LOSSY_OUTPUT,
    LOSSY_BITRATE_DEFAULTED,
    LOSSY_SAMPLE_RATE_ADJUSTED,
    LOSSY_CHANNEL_LAYOUT_UNSUPPORTED,
    HARDWARE_ACCELERATION_SELECTED,
    HARDWARE_ACCELERATION_SOFTWARE_FALLBACK,
    SOURCE_SAMPLE_RATE_NORMALIZED,
    SOURCE_BIT_DEPTH_NORMALIZED,
    PCM_HASH_NOT_COMPARABLE,
    LARGE_OUTPUT,
}

enum class AudioTranscodePreviewIssueSeverity {
    INFO,
    WARNING,
    BLOCKING,
}

data class AudioTranscodePreviewIssue(
    val code: AudioTranscodePreviewIssueCode,
    val severity: AudioTranscodePreviewIssueSeverity,
    val detail: String,
)

data class AudioTranscodePreview(
    val source: AudioTranscodeProbe?,
    val capability: AudioTranscodeCapability?,
    val resolvedSampleRateHz: Int?,
    val resolvedBitDepth: Int?,
    val resolvedDsdRate: AudioTranscodeDsdRate? = null,
    val resolvedDsdSampleRateHz: Int? = null,
    val resolvedBitRateKbps: Int? = null,
    val backend: AudioTranscodeBackendInfo? = null,
    val autoDitherWillApply: Boolean,
    val estimatedOutputBytesMin: Long?,
    val estimatedOutputBytesMax: Long?,
    val issues: List<AudioTranscodePreviewIssue>,
) {
    val canStart: Boolean
        get() = source != null && capability != null && issues.none {
            it.severity == AudioTranscodePreviewIssueSeverity.BLOCKING
        }
}

data class AudioTranscodeBatchPreviewItem(
    val request: AudioTranscodeRequest,
    val preview: AudioTranscodePreview,
)

data class AudioTranscodeBatchPreview(
    val items: List<AudioTranscodeBatchPreviewItem>,
) {
    val totalCount: Int get() = items.size
    val readyCount: Int get() = items.count { it.preview.canStart }
    val blockedCount: Int get() = totalCount - readyCount
    val warningCount: Int get() = items.count { item ->
        item.preview.issues.any { it.severity == AudioTranscodePreviewIssueSeverity.WARNING }
    }
    val hardwareCount: Int get() = items.count {
        it.preview.backend?.kind == AudioTranscodeBackendKind.HARDWARE
    }
    val softwareCount: Int get() = items.count {
        it.preview.backend?.kind == AudioTranscodeBackendKind.SOFTWARE
    }
    val bitstreamCopyCount: Int get() = items.count {
        it.preview.backend?.kind == AudioTranscodeBackendKind.BITSTREAM_COPY
    }
    val estimatedOutputBytesMin: Long? get() = sumEstimates { it.estimatedOutputBytesMin }
    val estimatedOutputBytesMax: Long? get() = sumEstimates { it.estimatedOutputBytesMax }

    private fun sumEstimates(selector: (AudioTranscodePreview) -> Long?): Long? {
        if (items.isEmpty()) return 0L
        var sum = 0L
        for (item in items) {
            val value = selector(item.preview) ?: return null
            if (Long.MAX_VALUE - sum < value) return Long.MAX_VALUE
            sum += value
        }
        return sum
    }
}

enum class AudioTranscodeVerificationMode {
    /** Fast verification plus decoded-PCM SHA-256 whenever exact comparison is valid. */
    AUTO,

    /** Parameter/container verification only; skips the second full decode. */
    FAST,

    /** Require decoded-PCM SHA-256; preview blocks if this request cannot be compared exactly. */
    FULL_PCM_HASH,
}

enum class AudioTranscodeStage {
    QUEUED,
    PROBING,
    ENCODING,
    MIGRATING_METADATA,
    VERIFYING,
    COMMITTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AudioTranscodeProgress(
    val stage: AudioTranscodeStage,
    val stagePermille: Int,
    val overallPermille: Int,
)

data class AudioTranscodePcmDigest(
    val sha256: String,
    val frames: Long,
)

data class AudioTranscodeVerification(
    val sampleRateMatches: Boolean,
    val bitDepthMatches: Boolean,
    val channelsMatch: Boolean,
    val durationMatches: Boolean,
    val codecMatches: Boolean,
    val containerStructureMatches: Boolean = true,
    /** null means this request is not eligible for exact decoded-PCM comparison. */
    val pcmHashMatches: Boolean? = null,
    val sourcePcmSha256: String? = null,
    val outputPcmSha256: String? = null,
) {
    val passed: Boolean
        get() = sampleRateMatches && bitDepthMatches && channelsMatch && durationMatches &&
            codecMatches && containerStructureMatches && pcmHashMatches != false
}

enum class AudioTranscodeFailureReason {
    INVALID_REQUEST,
    UNSUPPORTED_SOURCE,
    UNSUPPORTED_TARGET,
    OUTPUT_EXISTS,
    INSUFFICIENT_SPACE,
    ENCODE_FAILED,
    METADATA_MIGRATION_FAILED,
    VERIFICATION_FAILED,
    COMMIT_FAILED,
    PROCESS_INTERRUPTED,
    INTERNAL_ERROR,
}

sealed interface AudioTranscodeResult {
    data class Success(
        val output: File,
        val source: AudioTranscodeProbe,
        val outputProbe: AudioTranscodeProbe,
        val metadata: TranscodeMetadataReport,
        val verification: AudioTranscodeVerification,
        val autoDitherApplied: Boolean,
        val dsfProbe: AudioDsfProbe? = null,
        val backend: AudioTranscodeBackendInfo = AudioTranscodeBackendInfo(
            kind = AudioTranscodeBackendKind.SOFTWARE,
            name = "FFmpeg",
            hardwareRequested = false,
        ),
    ) : AudioTranscodeResult

    data class Failure(
        val reason: AudioTranscodeFailureReason,
        val detail: String,
        val nativeCode: Int? = null,
        val metadata: TranscodeMetadataReport? = null,
    ) : AudioTranscodeResult

    data object Cancelled : AudioTranscodeResult
}
