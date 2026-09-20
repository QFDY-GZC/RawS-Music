package com.rawsmusic.ai.variant

import com.rawsmusic.ai.instrument.AiInstrumentLeadArtifact
import com.rawsmusic.separation.AiStemDependency
import java.security.MessageDigest

internal object AiMediaVariantFingerprint {
    fun build(
        dependency: AiStemDependency,
        lead: AiInstrumentLeadArtifact,
        mode: AiMediaVariantMode,
        config: AiMediaVariantMixConfig,
        audioFormat: String,
    ): String {
        val identity = listOf(
            "rawsmusic-ai-media-variant-v1",
            dependency.sourceFingerprint,
            dependency.dependencyFingerprint,
            lead.fingerprint,
            lead.audioFile.length().toString(),
            lead.audioFile.lastModified().toString(),
            lead.packId,
            lead.packVersion,
            lead.rendererId,
            lead.rendererVersion,
            mode.name,
            config.vocalGainDb.toString(),
            config.instrumentalGainDb.toString(),
            config.leadGainDb.toString(),
            config.outputCeilingDb.toString(),
            AiPcm16StereoMixer.MIXER_VERSION,
            audioFormat.lowercase(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
