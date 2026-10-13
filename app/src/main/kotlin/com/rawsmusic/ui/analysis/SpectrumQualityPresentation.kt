package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.model.AudioFile
import java.util.Locale

/** A display hint about observed data, never a verified score for sound quality or provenance. */
internal enum class SpectrumQualityDisplay { Unavailable, Lossy, NoFlags, ReferenceMatch, Review, PrecisionReview, HighSpecReview, ClippingRisk }

internal fun isKnownLosslessSpectrumSource(song: AudioFile): Boolean {
    val codec = song.encodingFormat.ifBlank { song.format }.trim().uppercase(Locale.ROOT)
    return codec in setOf("FLAC", "ALAC", "APE", "WAVPACK", "WV", "PCM", "WAV", "WAVE",
        "AIFF", "AIF", "OGGFLAC", "DSF", "DFF")
}

internal fun isKnownLossySpectrumSource(song: AudioFile): Boolean {
    val codec = song.encodingFormat.ifBlank { song.format }.trim().uppercase(Locale.ROOT)
    return codec in setOf("MP3", "MP2", "MPEG AUDIO", "AAC", "OPUS", "VORBIS", "OGG VORBIS", "WMA", "AMR")
}

internal fun spectrumQualityDisplay(song: AudioFile, report: SpectrumQualityReport,
    reference: SpectrumReferenceResult = SpectrumReferenceResult.NotChecked): SpectrumQualityDisplay {
    val codec = song.encodingFormat.ifBlank { song.format }.trim().uppercase(Locale.ROOT)
    val knownLossless = isKnownLosslessSpectrumSource(song)
    when (report.assessment) {
        SpectrumAssessment.Insufficient, SpectrumAssessment.LimitedScope -> return SpectrumQualityDisplay.Unavailable
        SpectrumAssessment.SustainedRail -> return SpectrumQualityDisplay.ClippingRisk
        SpectrumAssessment.PrecisionLimited -> return if (knownLossless)
            SpectrumQualityDisplay.PrecisionReview else SpectrumQualityDisplay.Review
        SpectrumAssessment.Review, SpectrumAssessment.BandLimited -> {
            val edge = report.bandEdge
            return if (knownLossless && song.sampleRate >= 88_200 && song.channelCount in 1..2 &&
                !report.resampled && !report.dsd && !report.coarseFrequencyGrid &&
                edge?.evidence == SpectrumEvidence.Moderate && edge.possibleUpsampling)
                SpectrumQualityDisplay.HighSpecReview else SpectrumQualityDisplay.Review
        }
        SpectrumAssessment.NoFlags -> Unit
    }
    return when {
        codec in setOf("MP3", "MPEG AUDIO", "AAC", "OPUS", "VORBIS", "OGG VORBIS", "WMA", "AMR") ->
            SpectrumQualityDisplay.Lossy
        knownLossless ->
            if (reference == SpectrumReferenceResult.Match) SpectrumQualityDisplay.ReferenceMatch
            else SpectrumQualityDisplay.NoFlags
        else -> SpectrumQualityDisplay.Unavailable
    }
}
