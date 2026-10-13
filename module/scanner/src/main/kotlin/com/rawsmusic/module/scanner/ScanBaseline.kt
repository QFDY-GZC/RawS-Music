package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile

data class ScanBaseline(
    val songs: List<AudioFile> = emptyList(),
    val pendingKeys: Set<String> = emptySet(),
    val directories: Map<String, Long> = emptyMap(),
)

data class MetadataScanOutcome(val song: AudioFile, val completed: Boolean)

internal fun metadataReadCompleted(sampleRate: Int, channels: Int): Boolean = sampleRate > 0 && channels > 0
internal fun MetadataScanOutcome.resolveAgainst(existing: List<AudioFile>): List<AudioFile> =
    if (!completed && existing.isNotEmpty()) existing else listOf(song)
