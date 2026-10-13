package com.rawsmusic.transcode

/** Formats actual failure information without depending on Android resources. */
internal fun formatTranscodeFailureDetail(
    language: String,
    reason: AudioTranscodeFailureReason?,
    rawDetail: String?,
    nativeCode: Int?,
    localizedReason: (AudioTranscodeFailureReason) -> String,
    withNativeCode: (String, Int) -> String,
): String? {
    val raw = rawDetail?.trim().orEmpty()
    if (reason == null && raw.isBlank() && nativeCode == null) return null
    val rawContainsHan = raw.any { ch ->
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }
    if (raw.isNotBlank() && (language == "zh" || !rawContainsHan)) return raw
    val base = reason?.let(localizedReason)
        ?: raw.takeIf(String::isNotBlank)
        ?: localizedReason(AudioTranscodeFailureReason.INTERNAL_ERROR)
    return nativeCode?.let { withNativeCode(base, it) } ?: base
}
