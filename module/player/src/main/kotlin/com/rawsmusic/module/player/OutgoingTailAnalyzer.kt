package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.io.File

/** Independent look-ahead probe; never reads, drains, or locks the live playback queue. */
internal object OutgoingTailAnalyzer {
    private val cache = object : LinkedHashMap<String, OutgoingTailPlan?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OutgoingTailPlan?>?): Boolean = size > 48
    }

    fun analyze(path: String, durationMs: Long, vocalEndMs: Long?, current: () -> Boolean): OutgoingTailPlan? {
        val file = File(path)
        if (!file.isFile || durationMs < 30_000 || !current()) return null
        val start = (durationMs - 60_000).coerceAtLeast(0L)
        val earliest = maxOf(start, vocalEndMs ?: (durationMs - 30_000).coerceAtLeast(0L))
        val key = "${file.canonicalPath}|${file.length()}|${file.lastModified()}|$durationMs|$earliest|tail-v1"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val handle = PlaybackDecoderBridge.openDecoder(path, 16000, 16, 1)
        if (handle == 0L) return null
        return try {
            if (!PlaybackDecoderBridge.seekDecoder(handle, start)) return null
            val rate = PlaybackDecoderBridge.getDecoderSampleRate(handle)
            val channels = PlaybackDecoderBridge.getDecoderChannels(handle)
            val bits = PlaybackDecoderBridge.getDecoderBitsPerSample(handle)
            if (rate <= 0 || channels <= 0 || bits <= 1) return null
            val bytes = (rate / 10).coerceAtLeast(1) * channels * if (bits <= 16) 2 else 4
            if (bytes > 512 * 1024) return null
            val pcm = ByteArray(bytes)
            val levels = ArrayList<Float>(600)
            var filled = 0
            val deadline = System.nanoTime() + 1_500_000_000L
            while (levels.size < 600 && current() && System.nanoTime() < deadline) {
                val read = PlaybackDecoderBridge.decodeChunk(handle, pcm, filled, pcm.size - filled)
                if (read <= 0) break
                filled += read
                if (filled == bytes) {
                    levels += PcmLevelMeter.rmsDb(pcm, filled, false, false, bits)
                    filled = 0
                }
            }
            val plan = if (current()) OutgoingTailPolicy.select(levels, start, 100, earliest) else null
            if (current()) synchronized(cache) { cache[key] = plan }
            AppLogger.i("AutoTransitionTail", "ready key=${key.hashCode()} windows=${levels.size} plan=$plan")
            plan
        } finally { PlaybackDecoderBridge.closeDecoder(handle) }
    }
}
