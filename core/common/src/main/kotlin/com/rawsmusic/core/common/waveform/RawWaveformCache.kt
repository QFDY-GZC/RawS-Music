package com.rawsmusic.core.common.waveform

import android.content.Context
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Offline waveform source for progress bars.
 *
 * UI never scans on the main thread. It first draws a neutral placeholder, then this cache
 * returns a real offline/native PCM waveform when available. The scanned result is a
 * stable file-level cache, not the realtime PCM visualizer stream.
 */
object RawWaveformCache {
    data class LoadResult(val values: FloatArray, val isReal: Boolean)

    const val MAX_SAMPLE_COUNT = 21_600
    private const val VERSION = 6
    private const val MAGIC = 0x52535746 // RSWF
    private const val DIR = "waveform_v6"
    private const val DEFAULT_SAMPLE_COUNT = 100
    private val mutex = Mutex()
    private val memoryCache = ConcurrentHashMap<String, FloatArray>()

    fun clearMemory() {
        memoryCache.clear()
    }

    fun placeholder(sampleCount: Int = DEFAULT_SAMPLE_COUNT, style: Int = 0): FloatArray {
        val count = sampleCount.coerceIn(32, MAX_SAMPLE_COUNT)
        val level = if (style == 1) 0.035f else 0.055f
        return FloatArray(count) { level }
    }

    suspend fun loadOrScan(
        context: Context,
        audioFile: AudioFile?,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): FloatArray = loadOrScanResult(context, audioFile, sampleCount).values

    suspend fun loadOrScanResult(
        context: Context,
        audioFile: AudioFile?,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): LoadResult {
        val song = audioFile ?: return LoadResult(placeholder(sampleCount), false)
        val path = song.path
        if (path.isBlank()) return LoadResult(placeholder(sampleCount), false)
        val boundedSamples = sampleCount.coerceIn(32, MAX_SAMPLE_COUNT)
        val identity = identityFor(song, boundedSamples)
        memoryCache[identity]?.let { return LoadResult(it, true) }
        val file = cacheFile(context, identity)
        read(file, identity)?.let {
            memoryCache[identity] = it
            return LoadResult(it, true)
        }

        return mutex.withLock {
            memoryCache[identity]?.let { return@withLock LoadResult(it, true) }
            read(file, identity)?.let {
                memoryCache[identity] = it
                return@withLock LoadResult(it, true)
            }
            val scanned = scanNative(song, boundedSamples)
            val result = if (scanned.size == boundedSamples) normalize(scanned) else placeholder(boundedSamples)
            val isReal = scanned.size == boundedSamples
            if (isReal) {
                memoryCache[identity] = result
                write(file, identity, result)
            }
            LoadResult(result, isReal)
        }
    }

    fun tryReadCached(context: Context, audioFile: AudioFile?, sampleCount: Int = DEFAULT_SAMPLE_COUNT): FloatArray? {
        val song = audioFile ?: return null
        if (song.path.isBlank()) return null
        val boundedSamples = sampleCount.coerceIn(32, MAX_SAMPLE_COUNT)
        val identity = identityFor(song, boundedSamples)
        memoryCache[identity]?.let { return it }
        return read(cacheFile(context, identity), identity)?.also { memoryCache[identity] = it }
    }

    private fun scanNative(song: AudioFile, sampleCount: Int): FloatArray {
        val startMs = song.cueOffsetMs.coerceAtLeast(0L)
        val endMs = song.cueEndMs.takeIf { it > startMs } ?: 0L
        return runCatching {
            FFmpegBridge.scanWaveform(song.path, startMs, endMs, sampleCount)
        }.getOrElse { FloatArray(0) }
    }

    private fun normalize(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        var maxValue = 0f
        values.forEach { maxValue = max(maxValue, it) }
        if (maxValue <= 0.0001f) return values
        val gain = 1f / maxValue
        return FloatArray(values.size) { index ->
            (values[index].coerceAtLeast(0f) * gain).coerceIn(0f, 1f)
        }
    }

    private fun identityFor(song: AudioFile, sampleCount: Int): String {
        return buildString {
            append(song.path)
            append('|')
            append(song.fileSize)
            append('|')
            append(song.dateModified)
            append('|')
            append(song.cueOffsetMs)
            append('|')
            append(song.cueEndMs)
            append('|')
            append(song.cueTrackIndex)
            append('|')
            append(sampleCount)
        }
    }

    private fun cacheFile(context: Context, identity: String): File {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, sha256(identity) + ".rswf")
    }

    private fun read(file: File, expectedIdentity: String): FloatArray? {
        if (!file.isFile || file.length() < 24L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != MAGIC || version != VERSION) return null
                val identity = input.readUTF()
                if (identity != expectedIdentity) return null
                val count = input.readInt().coerceIn(0, MAX_SAMPLE_COUNT)
                if (count <= 0) return null
                FloatArray(count) { input.readFloat().coerceIn(0f, 1f) }
            }
        }.getOrNull()
    }

    private fun write(file: File, identity: String, values: FloatArray) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(identity)
                output.writeInt(values.size)
                values.forEach { output.writeFloat(it.coerceIn(0f, 1f)) }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { b -> append("%02x".format(b)) }
        }
    }
}
