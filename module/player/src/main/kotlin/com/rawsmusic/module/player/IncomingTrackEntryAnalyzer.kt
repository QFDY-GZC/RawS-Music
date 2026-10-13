package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.scanner.LyricParser
import java.io.File

/** Runs only on the pending decoder's preparation worker, before its producer starts. */
internal object IncomingTrackEntryAnalyzer {
    private val cache = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 48
    }

    fun analyze(path: String, current: () -> Boolean, introLeadMs: Long = 4000L): Long {
        if (!File(path).isFile || !current()) return 0L
        val handle = PlaybackDecoderBridge.openDecoder(path, 16000, 16, 1)
        if (handle == 0L) return 0L
        return try {
            prepare(handle, path, PlaybackDecoderBridge.getDecoderSampleRate(handle),
                PlaybackDecoderBridge.getDecoderChannels(handle),
                PlaybackDecoderBridge.getDecoderBitsPerSample(handle), current, introLeadMs)
        } finally {
            PlaybackDecoderBridge.closeDecoder(handle)
        }
    }

    fun prepare(handle: Long, path: String, rate: Int, channels: Int, bits: Int,
                current: () -> Boolean, introLeadMs: Long = 4000L): Long {
        val file = File(path)
        if (!file.isFile || rate <= 0 || channels <= 0 || bits <= 1 ||
            PlaybackDecoderBridge.getDecoderDuration(handle) < 30_000L) return 0L
        val lrcFile = runCatching { LyricParser.findLrcFile(path) }.getOrNull()
        val key = "${file.canonicalPath}|${file.length()}|${file.lastModified()}|" +
            "${lrcFile?.absolutePath}|${lrcFile?.lastModified()}|entry-v4|$introLeadMs"
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) {
            check(PlaybackDecoderBridge.seekDecoder(handle, cached)) { "incoming entry seek failed" }
            return cached
        }
        val firstLyric = runCatching {
            val lrc = lrcFile?.takeIf { it.length() <= 512 * 1024 } ?: return@runCatching null
            val lyrics = LyricParser.parseFromFile(lrc)
            val lines = lyrics.lines.filter { TransitionLyricContent.isPerformed(it.text) }
            if (lines.size < 4) null else lines.minOf { it.timeStamp + lyrics.offset }
                .takeIf { it in 0 until PlaybackDecoderBridge.getDecoderDuration(handle) }
        }.getOrNull()
        // Probe around the actual first vocal, rather than spending the budget decoding a long intro.
        val sampleStartMs = firstLyric?.let { (it - 1000L).coerceAtLeast(0L) } ?: 0L
        if (sampleStartMs > 0L) check(PlaybackDecoderBridge.seekDecoder(handle, sampleStartMs)) {
            "incoming vocal probe seek failed"
        }
        val frameSize = channels * if (bits <= 16) 2 else 4
        val windowFrames = (rate / 10).coerceAtLeast(1)
        val windowBytes = windowFrames * frameSize
        if (windowBytes > 512 * 1024) return 0L
        val pcm = ByteArray(windowBytes)
        val targetWindows = if (firstLyric != null) 30 else 300
        val levels = ArrayList<Float>(targetWindows)
        val deadline = System.nanoTime() + 1_500_000_000L
        var filled = 0
        while (levels.size < targetWindows && current() && System.nanoTime() < deadline) {
            val read = PlaybackDecoderBridge.decodeChunk(handle, pcm, filled, pcm.size - filled)
            if (read <= 0) break
            filled += read
            if (filled == pcm.size) {
                levels += PcmLevelMeter.rmsDb(pcm, filled, false, false, bits)
                filled = 0
            }
        }
        val selected = if (current()) IncomingEntryPolicy.select(levels, 100L, firstLyric,
            introLeadMs = introLeadMs, sampleStartMs = sampleStartMs) else 0L
        // A failed rewind must discard this decoder; otherwise analysis would consume the intro.
        check(PlaybackDecoderBridge.seekDecoder(handle, selected)) { "incoming analysis rewind failed" }
        if (current()) synchronized(cache) { cache[key] = selected }
        AppLogger.i("AutoTransitionEntry", "ready key=${key.hashCode()} windows=${levels.size} " +
            "sourceStartMs=$selected firstLyricMs=$firstLyric introLeadMs=$introLeadMs probeStartMs=$sampleStartMs")
        return selected
    }
}
