package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

internal enum class DecoderGaplessVerification {
    NO_METADATA,
    DECODER_TRIMMED,
    DECODER_UNTRIMMED,
    FRAME_COUNT_MISMATCH,
    INVALIDATED,
}

internal data class DecoderGaplessAuditResult(
    val verification: DecoderGaplessVerification,
    val codec: EncodedGaplessMetadata.Codec?,
    val decodedOutputFrames: Long,
    val expectedAudibleOutputFrames: Long,
    val expectedEncodedOutputFrames: Long,
    val toleranceFrames: Long,
    val detail: String,
)

/** Counts the real PCM frames emitted by the opaque FFmpeg decoder. */
internal class DecoderGaplessAudit(
    private val metadata: EncodedGaplessMetadata?,
    private val outputSampleRate: Int,
    private val outputFrameSize: Int,
) {
    private var decodedOutputFrames = 0L
    private var invalidReason: String? = null

    @Synchronized
    fun recordDecodedBytes(bytes: Int) {
        if (bytes <= 0 || outputFrameSize <= 0) return
        decodedOutputFrames += bytes.toLong() / outputFrameSize.toLong()
    }

    @Synchronized
    fun invalidate(reason: String) {
        if (invalidReason == null) invalidReason = reason
    }

    @Synchronized
    fun finish(): DecoderGaplessAuditResult {
        val gapless = metadata
        val invalid = invalidReason
        if (invalid != null) {
            return DecoderGaplessAuditResult(
                verification = DecoderGaplessVerification.INVALIDATED,
                codec = gapless?.codec,
                decodedOutputFrames = decodedOutputFrames,
                expectedAudibleOutputFrames = 0L,
                expectedEncodedOutputFrames = 0L,
                toleranceFrames = 0L,
                detail = invalid,
            )
        }
        if (gapless == null || outputSampleRate <= 0 || outputFrameSize <= 0) {
            return DecoderGaplessAuditResult(
                verification = DecoderGaplessVerification.NO_METADATA,
                codec = null,
                decodedOutputFrames = decodedOutputFrames,
                expectedAudibleOutputFrames = 0L,
                expectedEncodedOutputFrames = 0L,
                toleranceFrames = 0L,
                detail = "no_encoded_gapless_metadata",
            )
        }
        val expectedAudible = gapless.expectedOutputFrames(outputSampleRate)
        val expectedEncoded = gapless.encodedOutputFrames(outputSampleRate)
        val tolerance = max(64L, outputSampleRate.toLong() / 500L) // ~2 ms, enough for integer rescale rounding.
        val audibleDelta = abs(decodedOutputFrames - expectedAudible)
        val encodedDelta = abs(decodedOutputFrames - expectedEncoded)
        val verification = when {
            audibleDelta <= tolerance -> DecoderGaplessVerification.DECODER_TRIMMED
            encodedDelta <= tolerance -> DecoderGaplessVerification.DECODER_UNTRIMMED
            else -> DecoderGaplessVerification.FRAME_COUNT_MISMATCH
        }
        return DecoderGaplessAuditResult(
            verification = verification,
            codec = gapless.codec,
            decodedOutputFrames = decodedOutputFrames,
            expectedAudibleOutputFrames = expectedAudible,
            expectedEncodedOutputFrames = expectedEncoded,
            toleranceFrames = tolerance,
            detail = "source=${gapless.source} delay=${gapless.declaredDelayFrames} " +
                "padding=${gapless.declaredPaddingFrames}",
        )
    }
}

/**
 * Per-player runtime audit registry. A codec is blocked from native same-block stitching only
 * after the real decoder has proven that its PCM still contains padding or has an unexplained
 * frame-count mismatch. UNKNOWN remains allowed because FFmpeg's default is automatic trimming.
 */
internal class DecoderGaplessAuditRegistry(
    private val tag: String,
    private val resolvePath: (String) -> String,
) {
    private data class Entry(
        val path: String,
        val metadata: EncodedGaplessMetadata?,
        val audit: DecoderGaplessAudit,
    )

    private enum class RuntimeTrust { UNKNOWN, VERIFIED, UNSAFE }

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val trust = ConcurrentHashMap<EncodedGaplessMetadata.Codec, RuntimeTrust>()

    fun ensure(
        decoderSerial: Long,
        sourcePath: String,
        outputSampleRate: Int,
        outputFrameSize: Int,
        alreadyResolvedPath: String? = null,
    ): EncodedGaplessMetadata? {
        if (decoderSerial == 0L) return null
        return entries.computeIfAbsent(decoderSerial) {
            val resolved = alreadyResolvedPath ?: runCatching { resolvePath(sourcePath) }.getOrDefault(sourcePath)
            val metadata = EncodedGaplessMetadataProbe.probe(resolved)
            if (metadata != null) {
                AppLogger.i(
                    tag,
                    "Gapless metadata: codec=${metadata.codec} path=${sourcePath.substringAfterLast('/')} " +
                        "sourceRate=${metadata.sourceSampleRate} encoded=${metadata.encodedFrames} " +
                        "delay=${metadata.declaredDelayFrames} padding=${metadata.declaredPaddingFrames} " +
                        "audible=${metadata.expectedAudibleFrames} source=${metadata.source}",
                )
            }
            Entry(
                path = sourcePath,
                metadata = metadata,
                audit = DecoderGaplessAudit(metadata, outputSampleRate, outputFrameSize),
            )
        }.metadata
    }

    fun recordDecodedBytes(decoderSerial: Long, bytes: Int) {
        entries[decoderSerial]?.audit?.recordDecodedBytes(bytes)
    }

    fun invalidate(decoderSerial: Long, reason: String) {
        entries[decoderSerial]?.audit?.invalidate(reason)
    }

    fun finish(decoderSerial: Long): DecoderGaplessAuditResult? {
        val entry = entries.remove(decoderSerial) ?: return null
        val result = entry.audit.finish()
        val codec = result.codec
        if (codec != null) {
            when (result.verification) {
                DecoderGaplessVerification.DECODER_TRIMMED -> trust[codec] = RuntimeTrust.VERIFIED
                DecoderGaplessVerification.DECODER_UNTRIMMED,
                DecoderGaplessVerification.FRAME_COUNT_MISMATCH -> trust[codec] = RuntimeTrust.UNSAFE
                else -> Unit
            }
        }
        AppLogger.i(
            tag,
            "Gapless decoder audit: path=${entry.path.substringAfterLast('/')} " +
                "verification=${result.verification} codec=${result.codec} " +
                "decoded=${result.decodedOutputFrames} audible=${result.expectedAudibleOutputFrames} " +
                "encoded=${result.expectedEncodedOutputFrames} tolerance=${result.toleranceFrames} " +
                "detail=${result.detail}",
        )
        return result
    }

    fun discard(decoderSerial: Long, reason: String) {
        val removed = entries.remove(decoderSerial) ?: return
        AppLogger.d(
            tag,
            "Gapless decoder audit discarded: serial=$decoderSerial " +
                "path=${removed.path.substringAfterLast('/')} reason=$reason",
        )
    }

    fun isTrusted(codec: EncodedGaplessMetadata.Codec?): Boolean =
        codec == null || trust[codec] != RuntimeTrust.UNSAFE

    fun describe(codec: EncodedGaplessMetadata.Codec?): String =
        if (codec == null) "NO_METADATA" else (trust[codec] ?: RuntimeTrust.UNKNOWN).name
}
