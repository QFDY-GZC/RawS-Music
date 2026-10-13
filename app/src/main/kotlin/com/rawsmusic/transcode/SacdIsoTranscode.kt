package com.rawsmusic.transcode

import java.io.File
import java.util.UUID

/** Offline-only staging. Never modifies the input and always retires temporary audio files. */
internal object SacdIsoTranscode {
    fun encode(
        session: NativeAudioTranscoder.Session, source: AudioTranscodeProbe,
        input: File, output: File, tags: Map<String, String>,
        isCancelled: () -> Boolean, onPackaging: () -> Unit, onProgress: (Int) -> Unit,
    ): Pair<Int, SacdIsoWriter.Result?> {
        val stem = ".raws-sacd-${UUID.randomUUID()}"
        val pcm = File(output.parentFile, "$stem.wav")
        val stagedDsf = File(output.parentFile, "$stem.dsf")
        try {
            val dsf = if (source.isDsdSource && input.extension.equals("dsf", true)) input else {
                val result = if (source.isDsdSource) {
                    session.remuxDsdToDsf(input.absolutePath, stagedDsf.absolutePath, AudioTranscodeDsdRate.DSD64)
                } else {
                    // A SACD clock is always 64 * 44100, including sources in the 48 kHz family.
                    val prepare = session.transcodeLossless(input.absolutePath, pcm.absolutePath,
                        "wav", 44_100, 32, 0, false)
                    if (prepare != 0) return prepare to null
                    if (isCancelled()) return NativeAudioTranscoder.ERR_CANCELLED to null
                    session.transcodeDsf(pcm.absolutePath, stagedDsf.absolutePath, AudioTranscodeDsdRate.DSD64)
                }
                if (result != 0) return result to null
                stagedDsf
            }
            if (isCancelled()) return NativeAudioTranscoder.ERR_CANCELLED to null
            onPackaging()
            fun tag(key: String): String = tags.entries.firstOrNull { it.key.equals(key, true) }?.value.orEmpty()
            val packed = SacdIsoWriter.write(dsf, output,
                tag("title").ifBlank { input.nameWithoutExtension }, tag("artist"),
                tag("album").ifBlank { input.nameWithoutExtension }, isCancelled, onProgress)
            return 0 to packed
        } finally {
            pcm.delete()
            stagedDsf.delete()
        }
    }
}
