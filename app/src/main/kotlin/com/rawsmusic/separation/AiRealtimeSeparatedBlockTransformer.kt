package com.rawsmusic.separation

import java.io.Closeable

/**
 * Optional app-layer transform that consumes a separated mixture/vocal block before playback.
 *
 * Input/output are stereo interleaved float PCM at [sampleRate]. Heavy implementations run on the
 * AI transform worker. A transformer may opt into [realtimePlaybackSafe] when it only schedules
 * already-prepared events/samples and is therefore safe to run directly on cached-stem PCM.
 */
interface AiRealtimeSeparatedBlockTransformer : Closeable {
    val realtimePlaybackSafe: Boolean get() = false

    fun transform(
        mixtureStereo: FloatArray,
        vocalStereo: FloatArray,
        sampleRate: Int,
    ): FloatArray

    /** Optional absolute timeline anchor used by MID/MIDI event playback and seek recovery. */
    fun transformAt(
        mixtureStereo: FloatArray,
        vocalStereo: FloatArray,
        sampleRate: Int,
        playbackPositionMs: Long?,
    ): FloatArray = transform(mixtureStereo, vocalStereo, sampleRate)

    fun reset(reason: String) = Unit

    override fun close() = Unit
}
