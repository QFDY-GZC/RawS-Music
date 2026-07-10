package com.rawsmusic.core.common.model

import com.rawsmusic.core.common.utils.BitrateNormalizer

/**
 * AudioFile 技术元数据判断扩展。
 *
 * 统一各扫描器对"数据是否完整"、"是否有损"、"是否需要补全"的判断标准。
 */

fun AudioFile.codecNameForCheck(): String {
    return encodingFormat.ifBlank { format }.uppercase()
}

fun AudioFile.isLossyCodec(): Boolean {
    val f = codecNameForCheck()
    return f in LOSSY_CODECS
}

fun AudioFile.isDsdSourceFile(): Boolean {
    val codec = codecNameForCheck()
    val ext = extension.uppercase()
    return bitsPerSample == 1 ||
        codec.contains("DSD") ||
        format.equals("DSD", true) ||
        format.equals("DSF", true) ||
        format.equals("DFF", true) ||
        ext == "DSF" ||
        ext == "DFF" ||
        ext == "DSDIFF"
}

/**
 * 有损格式本身没有可靠的"源位深"，不应因为位深为 0 就反复 enrich。
 */
fun AudioFile.requiresSourceBitDepth(): Boolean {
    return !isLossyCodec()
}

/**
 * 基础技术元数据：时长、码率、采样率、声道。
 */
fun AudioFile.hasBasicTechnicalMetadata(): Boolean {
    if (duration <= 0L || bitRate <= 0 || sampleRate <= 0 || channelCount <= 0) return false
    val normalizedBitRate = BitrateNormalizer.toBps(
        rawBitrate = bitRate,
        durationMs = duration,
        fileSizeBytes = fileSize,
        codecName = encodingFormat,
        formatName = format,
        filePath = path
    )
    if (normalizedBitRate <= 0) return false
    // UI/DB 中出现过 MP3/AAC 被缓存成几十 Mbps 的异常值；这些应触发重新 enrich。
    return true
}

/**
 * 完整技术元数据：基础 + 位深（无损格式要求位深 > 0）。
 */
fun AudioFile.hasCompleteTechnicalMetadata(): Boolean {
    if (!hasBasicTechnicalMetadata()) return false
    return if (requiresSourceBitDepth()) bitsPerSample > 0 else true
}

/**
 * 是否需要 FFmpeg/native 补全技术元数据。
 */
fun AudioFile.needsTechnicalMetadataEnrich(): Boolean {
    return !hasCompleteTechnicalMetadata()
}

private val LOSSY_CODECS = setOf(
    "MP3", "AAC", "M4A", "MP4", "OPUS", "OGG", "VORBIS", "WMA"
)
