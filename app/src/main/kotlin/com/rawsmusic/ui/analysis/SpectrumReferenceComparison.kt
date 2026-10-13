package com.rawsmusic.ui.analysis

import android.content.Context
import android.net.Uri
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.transcode.AudioTranscodePcmDigest
import com.rawsmusic.transcode.AudioTranscodeProbe
import com.rawsmusic.transcode.NativeAudioTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SpectrumReferenceResult { NotChecked, Checking, Match, Different, Unsupported, Failed }

internal fun compareReferenceDigests(
    source: AudioTranscodeProbe?, reference: AudioTranscodeProbe?,
    sourceDigest: AudioTranscodePcmDigest?, referenceDigest: AudioTranscodePcmDigest?
): SpectrumReferenceResult {
    if (source == null || reference == null) return SpectrumReferenceResult.Failed
    if (source.isDsdSource || reference.isDsdSource || !source.bitDepthMeaningful ||
        !reference.bitDepthMeaningful || source.sampleRateHz != reference.sampleRateHz ||
        source.channels != reference.channels || source.bitDepth != reference.bitDepth ||
        source.sampleRateHz !in setOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000) ||
        source.bitDepth !in setOf(16, 24, 32) || source.channels !in 1..2 ||
        source.hasVideoStream || reference.hasVideoStream
    ) return SpectrumReferenceResult.Unsupported
    if (sourceDigest == null || referenceDigest == null || sourceDigest.frames <= 0L ||
        referenceDigest.frames <= 0L) return SpectrumReferenceResult.Failed
    return if (sourceDigest.frames == referenceDigest.frames &&
        sourceDigest.sha256.equals(referenceDigest.sha256, ignoreCase = true))
        SpectrumReferenceResult.Match else SpectrumReferenceResult.Different
}

/** A full offline comparison, run only after an explicit reference-file selection. */
internal suspend fun compareSpectrumReference(
    context: Context, song: AudioFile, referenceUri: Uri
): SpectrumReferenceResult = withContext(Dispatchers.IO) {
    if (song.path.isBlank() || song.cueTrackIndex > 0 || song.cueOffsetMs > 0L ||
        song.cueEndMs > 0L) return@withContext SpectrumReferenceResult.Unsupported
    try {
        val sourceUri = Uri.parse(song.path)
        val sourceFd = if (sourceUri.scheme == "content")
            context.contentResolver.openFileDescriptor(sourceUri, "r") else null
        sourceFd.use { openedSource ->
            if (sourceUri.scheme == "content" && openedSource == null)
                return@withContext SpectrumReferenceResult.Failed
            val sourcePath = openedSource?.let { "/proc/self/fd/${it.fd}" } ?: song.path
            val referenceFd = context.contentResolver.openFileDescriptor(referenceUri, "r")
                ?: return@withContext SpectrumReferenceResult.Failed
            referenceFd.use { openedReference ->
                val referencePath = "/proc/self/fd/${openedReference.fd}"
                val source = NativeAudioTranscoder.probe(sourcePath)
                val reference = NativeAudioTranscoder.probe(referencePath)
                if (source == null || reference == null) return@withContext SpectrumReferenceResult.Failed
                val eligibility = compareReferenceDigests(source, reference, null, null)
                if (eligibility == SpectrumReferenceResult.Unsupported) return@withContext eligibility
                val sourceDigest = NativeAudioTranscoder.createSession().use { session ->
                    session.canonicalPcmDigest(sourcePath, source.sampleRateHz, source.bitDepth)
                }
                val referenceDigest = NativeAudioTranscoder.createSession().use { session ->
                    session.canonicalPcmDigest(referencePath, source.sampleRateHz, source.bitDepth)
                }
                compareReferenceDigests(source, reference, sourceDigest, referenceDigest)
            }
        }
    } catch (_: Exception) {
        SpectrumReferenceResult.Failed
    }
}
