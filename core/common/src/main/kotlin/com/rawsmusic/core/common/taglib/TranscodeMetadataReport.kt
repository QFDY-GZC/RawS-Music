package com.rawsmusic.core.common.taglib

/**
 * Result of the offline metadata migration pass used by AudioTranscodeManager.
 *
 * The offline converter keeps a strict source-metadata contract across the lossless target matrix:
 * every textual property representable by TagLib's PropertyMap (including unknown and multi-value
 * properties) must survive, cover-art payloads are mapped to the target container's native picture
 * mechanism, and opaque/unrepresentable binary or container semantics are reported instead of
 * being silently dropped.
 */
data class TranscodeMetadataReport(
    val supported: Boolean,
    val saveOk: Boolean,
    val verifyOk: Boolean,
    val strictOk: Boolean,
    val sourceTextFields: Int,
    val sourceTextValues: Int,
    val targetTextFields: Int,
    val targetTextValues: Int,
    val rejectedFields: Int,
    val textMismatches: Int,
    val picturesCopied: Int,
    val pictureMismatches: Int,
    val unsupportedBinaryItems: Int,
    val mismatchKeys: List<String>,
) {
    companion object {
        val Unsupported = TranscodeMetadataReport(
            supported = false,
            saveOk = false,
            verifyOk = false,
            strictOk = false,
            sourceTextFields = 0,
            sourceTextValues = 0,
            targetTextFields = 0,
            targetTextValues = 0,
            rejectedFields = 0,
            textMismatches = 0,
            picturesCopied = 0,
            pictureMismatches = 0,
            unsupportedBinaryItems = 0,
            mismatchKeys = emptyList(),
        )

        internal fun parse(serialized: String?): TranscodeMetadataReport {
            if (serialized.isNullOrBlank()) return Unsupported
            val values = serialized.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()

            fun bool(name: String): Boolean = values[name] == "1"
            fun int(name: String): Int = values[name]?.toIntOrNull() ?: 0

            return TranscodeMetadataReport(
                supported = bool("supported"),
                saveOk = bool("saveOk"),
                verifyOk = bool("verifyOk"),
                strictOk = bool("strictOk"),
                sourceTextFields = int("sourceTextFields"),
                sourceTextValues = int("sourceTextValues"),
                targetTextFields = int("targetTextFields"),
                targetTextValues = int("targetTextValues"),
                rejectedFields = int("rejectedFields"),
                textMismatches = int("textMismatches"),
                picturesCopied = int("picturesCopied"),
                pictureMismatches = int("pictureMismatches"),
                unsupportedBinaryItems = int("unsupportedBinaryItems"),
                mismatchKeys = values["mismatchKeys"]
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty),
            )
        }
    }
}


/** Preflight snapshot of metadata that can be represented by TagLib's canonical property model. */
data class TranscodeMetadataSourceInfo(
    val supported: Boolean,
    val textFields: Int,
    val textValues: Int,
    val pictureCount: Int,
    val unsupportedOpaqueItems: Int,
    val unsupportedKeys: List<String>,
) {
    val strictReadable: Boolean
        get() = supported && unsupportedOpaqueItems == 0

    companion object {
        val Unsupported = TranscodeMetadataSourceInfo(false, 0, 0, 0, 0, emptyList())

        internal fun parse(serialized: String?): TranscodeMetadataSourceInfo {
            if (serialized.isNullOrBlank()) return Unsupported
            val values = serialized.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            fun int(name: String): Int = values[name]?.toIntOrNull() ?: 0
            return TranscodeMetadataSourceInfo(
                supported = values["supported"] == "1",
                textFields = int("textFields"),
                textValues = int("textValues"),
                pictureCount = int("pictureCount"),
                unsupportedOpaqueItems = int("unsupportedOpaqueItems"),
                unsupportedKeys = values["unsupportedKeys"]
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty),
            )
        }
    }
}
