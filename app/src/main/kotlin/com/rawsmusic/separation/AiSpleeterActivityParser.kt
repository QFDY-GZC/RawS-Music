package com.rawsmusic.separation

import com.rawsmusic.core.common.model.VoiceActivityMap
import com.rawsmusic.core.common.model.VoiceActivitySpan
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Converts the compact native Spleeter envelope into the shared, cached activity map. */
object AiSpleeterActivityParser {
    private const val PREFIX = "OK|"
    private const val MIN_RMS = 1.0e-9f
    private const val WINDOW_MS = 93L
    private const val START_HANG_FRAMES = 3
    private const val STOP_HANG_FRAMES = 10
    private const val MERGE_GAP_MS = 140L
    private const val PRE_ROLL_MS = 80L
    private const val POST_ROLL_MS = 180L

    fun parse(
        raw: String,
        sourceIdentity: String,
        analyzerVersion: String,
    ): VoiceActivityMap {
        require(raw.startsWith(PREFIX)) { "Spleeter 活动结果格式无效" }
        // Native emits: OK|sampleRate|hopSamples|durationMs|value0,value1,...
        // After removing the prefix there are four fields; keep the final field
        // intact because it contains the complete envelope.
        val fields = raw.removePrefix(PREFIX).split('|', limit = 4)
        require(fields.size == 4) { "Spleeter 活动结果字段不完整" }
        val sampleRate = fields[0].toInt().coerceAtLeast(1)
        val hopSamples = fields[1].toInt().coerceAtLeast(1)
        val durationMs = fields[2].toLong().coerceAtLeast(0L)
        val values = fields[3].split(',')
            .asSequence()
            .filter(String::isNotBlank)
            .map(String::toFloat)
            .toList()
        require(values.isNotEmpty()) { "Spleeter 没有返回活动帧" }
        require(values.all(Float::isFinite)) { "Spleeter 活动结果包含无效数值" }
        val maxValue = values.maxOrNull()?.coerceAtLeast(MIN_RMS) ?: MIN_RMS
        val db = FloatArray(values.size) { index ->
            (20.0 * log10((values[index] / maxValue).coerceAtLeast(MIN_RMS).toDouble()))
                .toFloat()
        }
        val sorted = db.copyOf().sorted()
        val noiseFloor = sorted[(sorted.lastIndex * 0.20f).toInt().coerceIn(0, sorted.lastIndex)]
        val startThreshold = max(noiseFloor + 9.0f, -54.0f)
        val stopThreshold = max(noiseFloor + 5.0f, -60.0f)
        val hopMs = max(1L, (hopSamples * 1_000L + sampleRate / 2L) / sampleRate)
        val spans = ArrayList<VoiceActivitySpan>()
        var candidateStart = -1
        var activeStart = -1
        var quietFrames = 0

        fun append(start: Int, endExclusive: Int) {
            if (endExclusive <= start) return
            val average = db.copyOfRange(start, endExclusive).average().toFloat()
            val confidence = ((average - noiseFloor) / 24.0f).coerceIn(0f, 1f)
            val startMs = max(0L, start * hopMs - PRE_ROLL_MS)
            val endMs = min(
                durationMs,
                (endExclusive - 1L) * hopMs + WINDOW_MS + POST_ROLL_MS,
            )
            if (endMs <= startMs) return
            val previous = spans.lastOrNull()
            if (previous != null && startMs <= previous.endMs + MERGE_GAP_MS) {
                spans[spans.lastIndex] = VoiceActivitySpan(
                    startMs = previous.startMs,
                    endMs = max(previous.endMs, endMs),
                    confidence = ((previous.confidence + confidence) * 0.5f).coerceIn(0f, 1f),
                )
            } else {
                spans += VoiceActivitySpan(startMs, endMs, confidence)
            }
        }

        db.forEachIndexed { index, value ->
            if (activeStart < 0) {
                if (value >= startThreshold) {
                    if (candidateStart < 0) candidateStart = index
                    if (index - candidateStart + 1 >= START_HANG_FRAMES) {
                        activeStart = candidateStart
                        candidateStart = -1
                    }
                } else {
                    candidateStart = -1
                }
            } else if (value < stopThreshold) {
                quietFrames++
                if (quietFrames >= STOP_HANG_FRAMES) {
                    append(activeStart, max(activeStart + 1, index - STOP_HANG_FRAMES + 1))
                    activeStart = -1
                    quietFrames = 0
                }
            } else {
                quietFrames = 0
            }
        }
        if (activeStart >= 0) append(activeStart, db.size)
        return VoiceActivityMap(
            sourceIdentity = sourceIdentity,
            analyzerVersion = analyzerVersion,
            sampleRate = sampleRate,
            hopMs = hopMs,
            durationMs = durationMs,
            spans = spans,
            generatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
