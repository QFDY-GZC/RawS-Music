package com.rawsmusic.ai.melody

import android.content.Context
import com.rawsmusic.separation.AiStemDependency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Persistent cache of continuous performance tracks. It contains analysis data, never audio. */
class AiPerformanceTrackStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_performance/tracks").apply { mkdirs() }

    fun cacheFingerprint(
        dependency: AiStemDependency,
        model: AiMelodyModelDescriptor,
    ): String = sha256(
        "rawsmusic-performance-v1|${dependency.dependencyFingerprint}|${model.id}|${model.version}|" +
            AiMelodyExtractor.EXTRACTOR_VERSION
    )

    suspend fun load(
        dependency: AiStemDependency,
        model: AiMelodyModelDescriptor,
    ): AiPerformanceTrack? = withContext(Dispatchers.IO) {
        val fingerprint = cacheFingerprint(dependency, model)
        val file = File(root, "$fingerprint.rsp.gz")
        if (!file.isFile) return@withContext null
        runCatching { readTrack(file) }
            .getOrNull()
            ?.takeIf { track ->
                track.dependencyFingerprint == dependency.dependencyFingerprint &&
                    track.modelId == model.id && track.modelVersion == model.version &&
                    track.extractorVersion == AiMelodyExtractor.EXTRACTOR_VERSION &&
                    track.sampleRate == model.sampleRate && track.hopLength == model.hopLength
            }
    }

    suspend fun commit(
        dependency: AiStemDependency,
        model: AiMelodyModelDescriptor,
        track: AiPerformanceTrack,
    ): File = withContext(Dispatchers.IO) {
        require(track.dependencyFingerprint == dependency.dependencyFingerprint)
        require(track.modelId == model.id && track.modelVersion == model.version)
        require(root.isDirectory || root.mkdirs()) { "无法创建 AI 演奏分析缓存目录" }
        val fingerprint = cacheFingerprint(dependency, model)
        val target = File(root, "$fingerprint.rsp.gz")
        val staging = File(root, ".$fingerprint.${System.nanoTime()}.tmp")
        try {
            writeTrack(staging, track)
            if (target.exists()) target.delete()
            require(staging.renameTo(target)) { "无法原子提交 PerformanceTrack" }
            prune(except = target)
            target
        } finally {
            staging.delete()
        }
    }

    private fun writeTrack(file: File, track: AiPerformanceTrack) {
        DataOutputStream(
            BufferedOutputStream(GZIPOutputStream(FileOutputStream(file)), BUFFER_BYTES)
        ).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA_VERSION)
            output.writeUTF(track.dependencyFingerprint)
            output.writeUTF(track.modelId)
            output.writeUTF(track.modelVersion)
            output.writeUTF(track.extractorVersion)
            output.writeInt(track.sampleRate)
            output.writeInt(track.hopLength)
            output.writeInt(track.frameCount)
            repeat(track.frameCount) { frame ->
                output.writeFloat(track.f0Hz[frame])
                output.writeFloat(track.voicingConfidence[frame])
                output.writeFloat(track.loudnessRms[frame])
            }
        }
    }

    private fun readTrack(file: File): AiPerformanceTrack {
        DataInputStream(
            BufferedInputStream(GZIPInputStream(FileInputStream(file)), BUFFER_BYTES)
        ).use { input ->
            require(input.readInt() == MAGIC) { "PerformanceTrack magic 无效" }
            require(input.readInt() == SCHEMA_VERSION) { "PerformanceTrack schema 不支持" }
            val dependency = input.readUTF()
            val modelId = input.readUTF()
            val modelVersion = input.readUTF()
            val extractorVersion = input.readUTF()
            val sampleRate = input.readInt()
            val hopLength = input.readInt()
            val frameCount = input.readInt()
            require(frameCount in 1..MAX_FRAMES) { "PerformanceTrack 帧数无效" }
            val f0 = FloatArray(frameCount)
            val confidence = FloatArray(frameCount)
            val loudness = FloatArray(frameCount)
            repeat(frameCount) { frame ->
                f0[frame] = input.readFloat()
                confidence[frame] = input.readFloat()
                loudness[frame] = input.readFloat()
            }
            return AiPerformanceTrack(
                dependencyFingerprint = dependency,
                modelId = modelId,
                modelVersion = modelVersion,
                extractorVersion = extractorVersion,
                sampleRate = sampleRate,
                hopLength = hopLength,
                f0Hz = f0,
                voicingConfidence = confidence,
                loudnessRms = loudness,
            )
        }
    }

    private fun prune(except: File) {
        root.listFiles { file -> file.isFile && file.name.endsWith(".rsp.gz") }
            .orEmpty()
            .filterNot { it == except }
            .sortedByDescending(File::lastModified)
            .drop(MAX_TRACK_FILES - 1)
            .forEach(File::delete)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAGIC = 0x52535046 // RSPF
        private const val SCHEMA_VERSION = 1
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_TRACK_FILES = 96
        private const val MAX_FRAMES = 6 * 60 * 60 * 100 // six hours at 10 ms
        @Volatile private var instance: AiPerformanceTrackStore? = null

        fun get(context: Context): AiPerformanceTrackStore = instance ?: synchronized(this) {
            instance ?: AiPerformanceTrackStore(context).also { instance = it }
        }
    }
}
