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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Offline waveform source for progress bars.
 *
 * UI never scans on the main thread. It first draws a neutral placeholder, then this cache
 * returns a real offline/native seek-scanned waveform when available. The scanned result is a
 * stable file-level cache, not the realtime PCM visualizer stream.
 */
object RawWaveformCache {
    private const val VERSION = 3
    private const val MAGIC = 0x52535746 // RSWF
    private const val DIR = "waveform_v3"
    private const val DEFAULT_SAMPLE_COUNT = 100
    private val mutex = Mutex()

    fun placeholder(sampleCount: Int = DEFAULT_SAMPLE_COUNT, style: Int = 0): FloatArray {
        val count = sampleCount.coerceIn(32, 100)
        val period = if (style == 1) 1.5f else 3.0f
        val amp = if (style == 1) 0.25f else 0.33f
        return FloatArray(count) { index ->
            val t = index.toFloat() / count.toFloat()
            (abs(sin((t * PI * period).toFloat())) * amp).coerceIn(0f, 0.36f)
        }
    }

    suspend fun loadOrScan(
        context: Context,
        audioFile: AudioFile?,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): FloatArray {
        val song = audioFile ?: return placeholder(sampleCount)
        val path = song.path
        if (path.isBlank()) return placeholder(sampleCount)
        val boundedSamples = sampleCount.coerceIn(32, 100)
        val identity = identityFor(song, boundedSamples)
        val file = cacheFile(context, identity)
        read(file, identity)?.let { return it }

        return mutex.withLock {
            read(file, identity)?.let { return@withLock it }
            val scanned = scanNative(song, boundedSamples)
            val result = if (scanned.size == boundedSamples) normalize(scanned) else placeholder(boundedSamples)
            if (scanned.size == boundedSamples) write(file, identity, result)
            result
        }
    }

    fun tryReadCached(context: Context, audioFile: AudioFile?, sampleCount: Int = DEFAULT_SAMPLE_COUNT): FloatArray? {
        val song = audioFile ?: return null
        if (song.path.isBlank()) return null
        val boundedSamples = sampleCount.coerceIn(32, 100)
        val identity = identityFor(song, boundedSamples)
        return read(cacheFile(context, identity), identity)
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
            append(song.duration)
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
                val count = input.readInt().coerceIn(0, 100)
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
