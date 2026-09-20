package com.rawsmusic.ai.instrument

/** Minimal SFZ subset used to import pinned sample-based piano repositories. */
data class AiSfzSampleRegion(
    val samplePath: String,
    val lowKey: Int,
    val highKey: Int,
    val rootMidiNote: Int,
    val velocityMin: Int,
    val velocityMax: Int,
    val loopStartFrame: Long? = null,
    val loopEndFrameExclusive: Long? = null,
)

internal object AiSfzPianoMapParser {
    fun parse(text: String): List<AiSfzSampleRegion> {
        val global = linkedMapOf<String, String>()
        var group = linkedMapOf<String, String>()
        var region: LinkedHashMap<String, String>? = null
        val output = mutableListOf<AiSfzSampleRegion>()

        fun flushRegion() {
            val attrs = region ?: return
            val merged = LinkedHashMap<String, String>()
            merged.putAll(global)
            merged.putAll(group)
            merged.putAll(attrs)
            output += regionFrom(merged)
            region = null
        }

        var scope = Scope.NONE
        text.lineSequence().forEach { rawLine ->
            var line = rawLine.substringBefore("//").trim()
            if (line.isEmpty()) return@forEach
            while (line.isNotEmpty()) {
                val tagMatch = TAG.find(line)
                if (tagMatch != null && tagMatch.range.first == 0) {
                    when (tagMatch.groupValues[1].lowercase()) {
                        "global" -> {
                            flushRegion()
                            scope = Scope.GLOBAL
                            group = linkedMapOf()
                        }
                        "group" -> {
                            flushRegion()
                            scope = Scope.GROUP
                            group = linkedMapOf()
                        }
                        "region" -> {
                            flushRegion()
                            scope = Scope.REGION
                            region = linkedMapOf()
                        }
                    }
                    line = line.substring(tagMatch.range.last + 1).trim()
                    continue
                }
                val nextTag = TAG.find(line)?.takeIf { it.range.first > 0 }
                val attributes = if (nextTag == null) line else line.substring(0, nextTag.range.first).trim()
                ATTRIBUTE.findAll(attributes).forEach { match ->
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    when (scope) {
                        Scope.GLOBAL -> global[key] = value
                        Scope.GROUP -> group[key] = value
                        Scope.REGION -> region?.set(key, value)
                        Scope.NONE -> Unit
                    }
                }
                line = if (nextTag == null) "" else line.substring(nextTag.range.first).trim()
            }
        }
        flushRegion()
        require(output.isNotEmpty()) { "SFZ 未包含可用 region" }
        require(output.size <= AiInstrumentPackManifest.MAX_SAMPLES) { "SFZ region 数量过多" }
        return output
    }

    private fun regionFrom(attrs: Map<String, String>): AiSfzSampleRegion {
        val sample = attrs["sample"]?.replace('\\', '/')?.trim().orEmpty()
        require(AiInstrumentPackManifest.isSafeRelativePath(sample)) { "SFZ sample 路径无效" }
        val key = attrs["key"]?.toIntOrNull()
        val low = attrs["lokey"]?.toIntOrNull() ?: key ?: 0
        val high = attrs["hikey"]?.toIntOrNull() ?: key ?: 127
        val root = attrs["pitch_keycenter"]?.toIntOrNull() ?: key ?: ((low + high) / 2)
        val vmin = attrs["lovel"]?.toIntOrNull() ?: 1
        val vmax = attrs["hivel"]?.toIntOrNull() ?: 127
        require(low in 0..127 && high in low..127 && root in 0..127) { "SFZ key range 无效" }
        require(vmin in 1..127 && vmax in vmin..127) { "SFZ velocity range 无效" }

        val loopMode = attrs["loop_mode"].orEmpty().lowercase()
        val rawLoopStart = attrs["loop_start"]?.toLongOrNull()
        val rawLoopEnd = attrs["loop_end"]?.toLongOrNull()
        val hasLoop = loopMode != "no_loop" && rawLoopStart != null && rawLoopEnd != null
        if (hasLoop) require(rawLoopEnd!! > rawLoopStart!! + 1L) { "SFZ loop range 无效" }

        return AiSfzSampleRegion(
            samplePath = sample,
            lowKey = low,
            highKey = high,
            rootMidiNote = root,
            velocityMin = vmin,
            velocityMax = vmax,
            loopStartFrame = if (hasLoop) rawLoopStart else null,
            // SFZ loop_end is the last source frame in common samplers; store our internal bound exclusive.
            loopEndFrameExclusive = if (hasLoop) rawLoopEnd!! + 1L else null,
        )
    }

    private enum class Scope { NONE, GLOBAL, GROUP, REGION }

    private val TAG = Regex("^<(global|group|region)>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([A-Za-z0-9_]+)=([^\\s]+)")
}
