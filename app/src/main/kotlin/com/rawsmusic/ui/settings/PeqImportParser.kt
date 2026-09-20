package com.rawsmusic.ui.settings

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.AutoEqPreset
import com.rawsmusic.module.player.dsp.PEQFilter
import com.rawsmusic.module.player.dsp.sanitizePeqPreamp

internal data class ParsedPeqImport(
    val name: String,
    val preamp: Float,
    val filters: List<PEQFilter>,
    val sourceBandCount: Int,
    val sourceLabel: String,
    val preferOriginalOrder: Boolean = false,
)

private val peqImportGson = Gson()

/** Shared parser for both PEQ entry points: AutoEq text/JSON and RawSMusic JSON formats. */
internal fun parsePeqImport(text: String, context: Context): ParsedPeqImport? {
    val trimmed = text.trim().removePrefix("\uFEFF").trimStart()
    if (trimmed.isEmpty()) return null

    val looksLikeAutoEqText = trimmed.lineSequence().any {
        it.trimStart().startsWith("Filter", ignoreCase = true) && it.contains("Fc", ignoreCase = true)
    }
    if (looksLikeAutoEqText) {
        val autoEq = AutoEqPreset.parse(
            name = context.getString(R.string.settings_peq_imported_autoeq_name),
            source = "file",
            text = trimmed,
        ) ?: return null
        val filters = autoEq.toPEQFilters()
        return ParsedPeqImport(
            name = autoEq.name,
            preamp = autoEq.safePreamp,
            filters = filters,
            sourceBandCount = filters.size,
            sourceLabel = "AutoEq",
            preferOriginalOrder = true,
        )
    }

    if (trimmed.contains("\"fc\"", ignoreCase = true) &&
        trimmed.contains("\"q\"", ignoreCase = true)
    ) {
        AutoEqPreset.fromJson(trimmed)?.let { preset ->
            val filters = preset.toPEQFilters()
            if (filters.isNotEmpty()) {
                return ParsedPeqImport(
                    name = preset.name,
                    preamp = preset.safePreamp,
                    filters = filters,
                    sourceBandCount = filters.size,
                    sourceLabel = "AutoEq JSON",
                    preferOriginalOrder = true,
                )
            }
        }
    }

    PEQPreset.fromJson(trimmed)?.let { preset ->
        if (preset.filters.isNotEmpty()) {
            return ParsedPeqImport(
                name = preset.name,
                preamp = sanitizePeqPreamp(preset.preamp),
                filters = preset.filters.map { it.sanitized() },
                sourceBandCount = preset.bandCount,
                sourceLabel = "RawSMusic JSON",
            )
        }
    }

    return try {
        val type = object : TypeToken<List<PEQFilter>>() {}.type
        val filters = peqImportGson.fromJson<List<PEQFilter>>(trimmed, type)
            .orEmpty()
            .map { it.sanitized() }
            .filter { it.frequency in PEQFilter.FREQUENCY_RANGE }
        if (filters.isEmpty()) null else ParsedPeqImport(
            name = context.getString(R.string.settings_peq_imported_list_name),
            preamp = 0f,
            filters = filters,
            sourceBandCount = filters.size,
            sourceLabel = "Filter JSON",
        )
    } catch (_: Throwable) {
        null
    }
}
