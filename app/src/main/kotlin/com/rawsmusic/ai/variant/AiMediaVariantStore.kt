package com.rawsmusic.ai.variant

import android.content.Context
import com.google.gson.Gson
import com.rawsmusic.ai.instrument.AiInstrumentLeadArtifact
import com.rawsmusic.separation.AiStemDependency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Persistent cache of encoded, playback-ready AI song variants. */
class AiMediaVariantStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_performance/variants").apply { mkdirs() }
    private val gson = Gson()

    fun fingerprint(
        dependency: AiStemDependency,
        lead: AiInstrumentLeadArtifact,
        mode: AiMediaVariantMode,
        config: AiMediaVariantMixConfig,
        audioFormat: String,
    ): String = AiMediaVariantFingerprint.build(dependency, lead, mode, config, audioFormat)

    suspend fun load(
        dependency: AiStemDependency,
        lead: AiInstrumentLeadArtifact,
        mode: AiMediaVariantMode,
        config: AiMediaVariantMixConfig,
        audioFormat: String,
    ): AiMediaVariantArtifact? = withContext(Dispatchers.IO) {
        val key = fingerprint(dependency, lead, mode, config, audioFormat)
        loadDirectory(File(root, key))?.takeIf { artifact ->
            artifact.fingerprint == key &&
                artifact.sourceFingerprint == dependency.sourceFingerprint &&
                artifact.stemDependencyFingerprint == dependency.dependencyFingerprint &&
                artifact.leadFingerprint == lead.fingerprint &&
                artifact.mode == mode &&
                artifact.packId == lead.packId && artifact.packVersion == lead.packVersion &&
                artifact.rendererId == lead.rendererId && artifact.rendererVersion == lead.rendererVersion &&
                artifact.mixConfig == config && artifact.audioFormat == audioFormat.lowercase()
        }
    }

    suspend fun commit(
        dependency: AiStemDependency,
        lead: AiInstrumentLeadArtifact,
        mode: AiMediaVariantMode,
        config: AiMediaVariantMixConfig,
        encodedAudio: File,
        audioFormat: String,
        sampleRate: Int,
        channels: Int,
        frameCount: Long,
        peakBeforeNormalization: Float,
        normalizationGainDb: Float,
    ): AiMediaVariantArtifact = withContext(Dispatchers.IO) {
        require(encodedAudio.isFile && encodedAudio.length() > 0L) { "AI media variant output is empty" }
        val extension = audioFormat.lowercase()
        require(extension in setOf("flac", "m4a")) { "Unsupported AI media variant format: $extension" }
        val key = fingerprint(dependency, lead, mode, config, extension)
        val target = File(root, key)
        val staging = File(root, ".$key.${System.nanoTime()}.tmp")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "无法创建 AI media variant staging 目录" }
        try {
            val stagedAudio = File(staging, "variant.$extension")
            encodedAudio.copyTo(stagedAudio, overwrite = false)
            val stored = StoredVariant(
                fingerprint = key,
                sourceFingerprint = dependency.sourceFingerprint,
                stemDependencyFingerprint = dependency.dependencyFingerprint,
                leadFingerprint = lead.fingerprint,
                mode = mode.name,
                packId = lead.packId,
                packVersion = lead.packVersion,
                rendererId = lead.rendererId,
                rendererVersion = lead.rendererVersion,
                vocalGainDb = config.vocalGainDb,
                instrumentalGainDb = config.instrumentalGainDb,
                leadGainDb = config.leadGainDb,
                outputCeilingDb = config.outputCeilingDb,
                mixerVersion = AiPcm16StereoMixer.MIXER_VERSION,
                audioFormat = extension,
                sampleRate = sampleRate,
                channels = channels,
                frameCount = frameCount,
                peakBeforeNormalization = peakBeforeNormalization,
                normalizationGainDb = normalizationGainDb,
                createdAtEpochMs = System.currentTimeMillis(),
                audioBytes = stagedAudio.length(),
                audioSha256 = sha256File(stagedAudio),
            )
            File(staging, METADATA_FILE).writeText(gson.toJson(stored))
            target.deleteRecursively()
            require(staging.renameTo(target)) { "无法原子提交 AI media variant" }
            prune(except = target)
            requireNotNull(loadDirectory(target)) { "无法读取已提交 AI media variant" }
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun listForSource(sourceFingerprint: String): List<AiMediaVariantArtifact> =
        withContext(Dispatchers.IO) {
            root.listFiles(File::isDirectory).orEmpty()
                .asSequence()
                .filterNot { it.name.startsWith('.') }
                .mapNotNull(::loadDirectory)
                .filter { it.sourceFingerprint == sourceFingerprint }
                .sortedByDescending { it.createdAtEpochMs }
                .toList()
        }

    suspend fun removeForSource(sourceFingerprint: String) = withContext(Dispatchers.IO) {
        root.listFiles(File::isDirectory).orEmpty().forEach { directory ->
            val artifact = loadDirectory(directory)
            if (artifact?.sourceFingerprint == sourceFingerprint) directory.deleteRecursively()
        }
    }

    private fun loadDirectory(directory: File): AiMediaVariantArtifact? = runCatching {
        val metadata = File(directory, METADATA_FILE)
        if (!metadata.isFile) return@runCatching null
        val stored = gson.fromJson(metadata.readText(), StoredVariant::class.java)
        require(stored.fingerprint == directory.name)
        val extension = stored.audioFormat.lowercase()
        require(extension in setOf("flac", "m4a"))
        val audio = File(directory, "variant.$extension")
        require(audio.isFile && audio.length() == stored.audioBytes)
        require(sha256File(audio) == stored.audioSha256)
        stored.toArtifact(audio)
    }.getOrNull()

    private fun prune(except: File) {
        root.listFiles(File::isDirectory).orEmpty()
            .filterNot { it == except || it.name.startsWith('.') }
            .sortedByDescending(File::lastModified)
            .drop(MAX_VARIANTS - 1)
            .forEach(File::deleteRecursively)
    }

    private data class StoredVariant(
        val fingerprint: String,
        val sourceFingerprint: String,
        val stemDependencyFingerprint: String,
        val leadFingerprint: String,
        val mode: String,
        val packId: String,
        val packVersion: String,
        val rendererId: String,
        val rendererVersion: String,
        val vocalGainDb: Float,
        val instrumentalGainDb: Float,
        val leadGainDb: Float,
        val outputCeilingDb: Float,
        val mixerVersion: String,
        val audioFormat: String,
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
        val peakBeforeNormalization: Float,
        val normalizationGainDb: Float,
        val createdAtEpochMs: Long,
        val audioBytes: Long,
        val audioSha256: String,
    ) {
        fun toArtifact(audioFile: File): AiMediaVariantArtifact = AiMediaVariantArtifact(
            fingerprint = fingerprint,
            sourceFingerprint = sourceFingerprint,
            stemDependencyFingerprint = stemDependencyFingerprint,
            leadFingerprint = leadFingerprint,
            mode = AiMediaVariantMode.valueOf(mode),
            packId = packId,
            packVersion = packVersion,
            rendererId = rendererId,
            rendererVersion = rendererVersion,
            mixConfig = AiMediaVariantMixConfig(
                vocalGainDb = vocalGainDb,
                instrumentalGainDb = instrumentalGainDb,
                leadGainDb = leadGainDb,
                outputCeilingDb = outputCeilingDb,
            ),
            mixerVersion = mixerVersion,
            audioFormat = audioFormat,
            sampleRate = sampleRate,
            channels = channels,
            frameCount = frameCount,
            peakBeforeNormalization = peakBeforeNormalization,
            normalizationGainDb = normalizationGainDb,
            createdAtEpochMs = createdAtEpochMs,
            audioFile = audioFile,
        )
    }

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

    companion object {
        private const val METADATA_FILE = "metadata.json"
        private const val MAX_VARIANTS = 64
        @Volatile private var instance: AiMediaVariantStore? = null

        fun get(context: Context): AiMediaVariantStore = instance ?: synchronized(this) {
            instance ?: AiMediaVariantStore(context.applicationContext).also { instance = it }
        }
    }
}
