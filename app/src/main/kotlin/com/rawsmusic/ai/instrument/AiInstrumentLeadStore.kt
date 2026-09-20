package com.rawsmusic.ai.instrument

import android.content.Context
import com.google.gson.Gson
import com.rawsmusic.ai.melody.AiPerformanceTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class AiInstrumentLeadArtifact(
    val fingerprint: String,
    val performanceDependencyFingerprint: String,
    val performanceContentFingerprint: String,
    val melodyModelId: String,
    val melodyModelVersion: String,
    val extractorVersion: String,
    val packId: String,
    val packVersion: String,
    val packContentFingerprint: String,
    val rendererId: String,
    val rendererVersion: String,
    val audioFormat: String,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val renderedNotes: Int,
    val audioFile: File,
)

/** Cache for independent rendered lead tracks. Mixing/media-variant ownership is intentionally later. */
class AiInstrumentLeadStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_performance/leads").apply { mkdirs() }
    private val gson = Gson()

    fun fingerprint(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        renderer: AiInstrumentRenderer,
        config: AiInstrumentRenderConfig,
    ): String = sha256(
        listOf(
            "rawsmusic-instrument-lead-v1",
            performance.dependencyFingerprint,
            performanceContentFingerprint(performance),
            performance.modelId,
            performance.modelVersion,
            performance.extractorVersion,
            pack.manifest.id,
            pack.manifest.version,
            pack.manifest.contentFingerprint(),
            renderer.rendererId,
            renderer.rendererVersion,
            "${config.masterGainDb}|${config.attackMs}|${config.releaseMs}|${config.maximumTailMs}",
        ).joinToString("|")
    )

    suspend fun load(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        renderer: AiInstrumentRenderer,
        config: AiInstrumentRenderConfig,
    ): AiInstrumentLeadArtifact? = withContext(Dispatchers.IO) {
        val key = fingerprint(performance, pack, renderer, config)
        val dir = File(root, key)
        val metadata = File(dir, METADATA_FILE)
        val audio = File(dir, AUDIO_FILE)
        if (!metadata.isFile || !audio.isFile) return@withContext null
        runCatching {
            val stored = gson.fromJson(metadata.readText(), StoredArtifact::class.java)
            require(stored.fingerprint == key)
            require(stored.performanceDependencyFingerprint == performance.dependencyFingerprint)
            require(stored.performanceContentFingerprint == performanceContentFingerprint(performance))
            require(stored.packId == pack.manifest.id && stored.packVersion == pack.manifest.version)
            require(stored.packContentFingerprint == pack.manifest.contentFingerprint())
            require(stored.rendererId == renderer.rendererId && stored.rendererVersion == renderer.rendererVersion)
            require(audio.length() == stored.audioBytes && sha256File(audio) == stored.audioSha256)
            stored.toArtifact(audio)
        }.getOrNull()
    }

    suspend fun renderOrLoad(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        renderer: AiInstrumentRenderer,
        config: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiInstrumentLeadArtifact = withContext(Dispatchers.IO) {
        load(performance, pack, renderer, config)?.let { return@withContext it }
        val key = fingerprint(performance, pack, renderer, config)
        val staging = File(root, ".$key.${System.nanoTime()}.tmp")
        val target = File(root, key)
        staging.deleteRecursively()
        require(staging.mkdirs()) { "无法创建 AI 乐器 lead staging 目录" }
        try {
            val stagedAudio = File(staging, AUDIO_FILE)
            val result = renderer.render(performance, pack, stagedAudio, config, onProgress, isCancelled)
            val stored = StoredArtifact(
                fingerprint = key,
                performanceDependencyFingerprint = performance.dependencyFingerprint,
                performanceContentFingerprint = performanceContentFingerprint(performance),
                melodyModelId = performance.modelId,
                melodyModelVersion = performance.modelVersion,
                extractorVersion = performance.extractorVersion,
                packId = pack.manifest.id,
                packVersion = pack.manifest.version,
                packContentFingerprint = pack.manifest.contentFingerprint(),
                rendererId = renderer.rendererId,
                rendererVersion = renderer.rendererVersion,
                audioFormat = result.audioFormat,
                sampleRate = result.sampleRate,
                channels = result.channels,
                frameCount = result.frameCount,
                renderedNotes = result.renderedNotes,
                audioBytes = stagedAudio.length(),
                audioSha256 = sha256File(stagedAudio),
            )
            File(staging, METADATA_FILE).writeText(gson.toJson(stored))
            target.deleteRecursively()
            require(staging.renameTo(target)) { "无法原子提交 AI 乐器 lead" }
            prune(except = target)
            stored.toArtifact(File(target, AUDIO_FILE))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun prune(except: File) {
        root.listFiles(File::isDirectory).orEmpty()
            .filterNot { it == except }
            .sortedByDescending(File::lastModified)
            .drop(MAX_LEADS - 1)
            .forEach(File::deleteRecursively)
    }

    private fun performanceContentFingerprint(track: AiPerformanceTrack): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateInt(value: Int) {
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }
        updateInt(track.sampleRate)
        updateInt(track.hopLength)
        updateInt(track.frameCount)
        repeat(track.frameCount) { frame ->
            updateInt(track.f0Hz[frame].toRawBits())
            updateInt(track.voicingConfidence[frame].toRawBits())
            updateInt(track.loudnessRms[frame].toRawBits())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class StoredArtifact(
        val fingerprint: String,
        val performanceDependencyFingerprint: String,
        val performanceContentFingerprint: String,
        val melodyModelId: String,
        val melodyModelVersion: String,
        val extractorVersion: String,
        val packId: String,
        val packVersion: String,
        val packContentFingerprint: String,
        val rendererId: String,
        val rendererVersion: String,
        val audioFormat: String,
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
        val renderedNotes: Int,
        val audioBytes: Long,
        val audioSha256: String,
    ) {
        fun toArtifact(audio: File) = AiInstrumentLeadArtifact(
            fingerprint = fingerprint,
            performanceDependencyFingerprint = performanceDependencyFingerprint,
            performanceContentFingerprint = performanceContentFingerprint,
            melodyModelId = melodyModelId,
            melodyModelVersion = melodyModelVersion,
            extractorVersion = extractorVersion,
            packId = packId,
            packVersion = packVersion,
            packContentFingerprint = packContentFingerprint,
            rendererId = rendererId,
            rendererVersion = rendererVersion,
            audioFormat = audioFormat,
            sampleRate = sampleRate,
            channels = channels,
            frameCount = frameCount,
            renderedNotes = renderedNotes,
            audioFile = audio,
        )
    }

    companion object {
        private const val METADATA_FILE = "metadata.json"
        private const val AUDIO_FILE = "lead.wav"
        private const val MAX_LEADS = 48
        @Volatile private var instance: AiInstrumentLeadStore? = null

        fun get(context: Context): AiInstrumentLeadStore = instance ?: synchronized(this) {
            instance ?: AiInstrumentLeadStore(context).also { instance = it }
        }
    }
}
