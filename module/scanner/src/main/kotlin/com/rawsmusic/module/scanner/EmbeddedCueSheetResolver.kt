package com.rawsmusic.module.scanner

internal object EmbeddedCueSheetResolver {
    fun resolve(
        tagLibProperties: Map<String, List<String>>,
        ffmpegInfo: Map<String, String>,
    ): String {
        tagLibProperties.entries.firstNotNullOfOrNull { (key, values) ->
            values.firstOrNull { it.isNotBlank() }
                ?.takeIf { key.isCueSheetKey() }
        }?.let { return it }

        ffmpegInfo.entries.firstNotNullOfOrNull { (key, value) ->
            value.takeIf { it.isNotBlank() && key.isCueSheetKey() }
        }?.let { return it }

        return ""
    }

    private fun String.isCueSheetKey(): Boolean {
        val rawTagKey = substringAfterLast("_raw_tag_", this)
        return rawTagKey
            .replace("_", "")
            .replace("-", "")
            .equals("cuesheet", ignoreCase = true)
    }
}
