package com.rawsmusic.ai.variant

import android.content.Context
import com.rawsmusic.ai.instrument.AiInstrumentLeadArtifact
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.separation.AiStemAudioEncoder
import com.rawsmusic.separation.AiStemDependency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Generates encoded media variants entirely off the playback hot path. */
class AiMediaVariantGenerator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = AiMediaVariantStore.get(appContext)
    private val mixer = AiOfflineMediaMixer(appContext)
    private val encodeRoot = File(appContext.cacheDir, "ai_performance/variant_encode").apply { mkdirs() }

    suspend fun generateOrLoad(
        dependency: AiStemDependency,
        lead: AiInstrumentLeadArtifact,
        mode: AiMediaVariantMode,
        config: AiMediaVariantMixConfig = AiMediaVariantMixConfig.defaultFor(mode),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiMediaVariantArtifact = withContext(Dispatchers.IO) {
        require(lead.performanceDependencyFingerprint == dependency.dependencyFingerprint) {
            "AI instrument lead is stale for this song"
        }
        val lossless = dependency.outputFormat.equals("flac", ignoreCase = true)
        val audioFormat = if (lossless) "flac" else "m4a"
        store.load(dependency, lead, mode, config, audioFormat)?.let {
            onProgress(1f)
            return@withContext it
        }

        val token = "${mode.name.lowercase()}_${UUID.randomUUID()}"
        val encodeDir = File(encodeRoot, token).apply {
            deleteRecursively()
            require(mkdirs()) { "无法创建 AI media variant 编码目录" }
        }
        try {
            val mixed = mixer.mix(
                dependency = dependency,
                lead = lead,
                mode = mode,
                config = config,
                workToken = token,
                onProgress = { fraction -> onProgress(fraction * 0.90f) },
                isCancelled = isCancelled,
            )
            check(!isCancelled()) { "AI media variant generation cancelled" }
            val encoded = File(encodeDir, "variant.$audioFormat")
            AiStemAudioEncoder.encode(mixed.wavFile, encoded, lossless).getOrThrow()
            val expectedDurationMs = mixed.frameCount * 1000L / mixed.sampleRate.coerceAtLeast(1)
            val encodedDurationMs = FFmpegBridge.probeDuration(encoded.absolutePath)
            if (encodedDurationMs > 0L) {
                require(abs(encodedDurationMs - expectedDurationMs) <= ENCODED_DURATION_TOLERANCE_MS) {
                    "AI media variant duration mismatch: $encodedDurationMs/$expectedDurationMs ms"
                }
            }
            onProgress(0.96f)
            check(!isCancelled()) { "AI media variant generation cancelled" }
            val committed = store.commit(
                dependency = dependency,
                lead = lead,
                mode = mode,
                config = config,
                encodedAudio = encoded,
                audioFormat = audioFormat,
                sampleRate = mixed.sampleRate,
                channels = mixed.channels,
                frameCount = mixed.frameCount,
                peakBeforeNormalization = mixed.peakBeforeNormalization,
                normalizationGainDb = mixed.normalizationGainDb,
            )
            onProgress(1f)
            committed
        } finally {
            mixer.cleanup(token)
            encodeDir.deleteRecursively()
        }
    }

    companion object {
        private const val ENCODED_DURATION_TOLERANCE_MS = 160L
        @Volatile private var instance: AiMediaVariantGenerator? = null
        fun get(context: Context): AiMediaVariantGenerator = instance ?: synchronized(this) {
            instance ?: AiMediaVariantGenerator(context.applicationContext).also { instance = it }
        }
    }
}
