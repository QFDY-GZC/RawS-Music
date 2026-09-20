package com.rawsmusic.ui.settings

import com.rawsmusic.module.player.dsp.GraphicEQPreset
import com.rawsmusic.module.player.dsp.PEQFilter
import kotlin.math.ln

internal data class AutoEqGraphicEqPoint(
    val frequencyHz: Float,
    val gainDb: Float,
)

internal data class ParsedAutoEqGraphicEq(
    val points: List<AutoEqGraphicEqPoint>,
) {
    fun toGraphicEqPreset(
        bandCount: Int,
        name: String,
    ): GraphicEQPreset {
        val targetCount = bandCount.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
        val targetFrequencies = PEQFilter.defaultFreqsForCount(targetCount)
        val gains = FloatArray(targetCount) { index ->
            interpolatedGainAt(targetFrequencies[index])
        }
        return GraphicEQPreset(
            name = name,
            bandCount = targetCount,
            gains = gains,
            isBuiltIn = false,
        )
    }

    private fun interpolatedGainAt(frequencyHz: Float): Float {
        if (points.isEmpty()) return 0f
        if (frequencyHz <= points.first().frequencyHz) return points.first().gainDb
        if (frequencyHz >= points.last().frequencyHz) return points.last().gainDb

        var low = 0
        var high = points.lastIndex
        while (low + 1 < high) {
            val mid = (low + high) ushr 1
            if (points[mid].frequencyHz <= frequencyHz) low = mid else high = mid
        }

        val left = points[low]
        val right = points[high]
        if (right.frequencyHz <= left.frequencyHz) return left.gainDb

        // Graphic EQ frequency axes are logarithmic. Interpolate in log-frequency space so the
        // dense AutoEq curve is sampled without biasing the wide high-frequency intervals.
        val x = ln(frequencyHz.coerceAtLeast(1f))
        val x0 = ln(left.frequencyHz.coerceAtLeast(1f))
        val x1 = ln(right.frequencyHz.coerceAtLeast(1f))
        val t = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        return left.gainDb + (right.gainDb - left.gainDb) * t
    }
}

/** Parses the standard AutoEq `GraphicEQ: frequency gain; ...` text format. */
internal fun parseAutoEqGraphicEq(text: String): ParsedAutoEqGraphicEq? {
    val normalized = text
        .trim()
        .removePrefix("\uFEFF")
        .trimStart()
        .replace('\u2212', '-')
    if (normalized.isEmpty()) return null

    val marker = Regex("(?im)^\\s*GraphicEQ\\s*:").find(normalized) ?: return null
    val body = normalized
        .substring(marker.range.last + 1)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
    if (body.isEmpty()) return null

    val parsed = body.split(';')
        .mapNotNull { segment ->
            val fields = segment.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (fields.size < 2) return@mapNotNull null
            val frequency = fields[0].toFloatOrNull() ?: return@mapNotNull null
            val gain = fields[1].toFloatOrNull() ?: return@mapNotNull null
            if (!frequency.isFinite() || !gain.isFinite() || frequency <= 0f) return@mapNotNull null
            AutoEqGraphicEqPoint(frequencyHz = frequency, gainDb = gain)
        }
        .sortedBy { it.frequencyHz }

    if (parsed.size < 2) return null

    // AutoEq normally emits unique ascending frequencies, but keep the final value if a malformed
    // or hand-edited file repeats a frequency so interpolation remains deterministic.
    val deduped = ArrayList<AutoEqGraphicEqPoint>(parsed.size)
    parsed.forEach { point ->
        if (deduped.isNotEmpty() && deduped.last().frequencyHz == point.frequencyHz) {
            deduped[deduped.lastIndex] = point
        } else {
            deduped += point
        }
    }
    return if (deduped.size >= 2) ParsedAutoEqGraphicEq(deduped) else null
}
