package com.rawsmusic.module.player

import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the already-converted PCM container used by FfmpegAudioPlayer to AudioTrack.
 *
 * AudioTrack's PCM_FLOAT path is special: using the FloatArray overload avoids devices that
 * accept a float AudioTrack but then block or fail when fed through the byte[] write overload.
 * Other integer PCM encodings stay on the byte[] path.
 */
internal class AudioTrackPcmWriter(
    private val encodingProvider: () -> Int
) {
    private var floatScratch: FloatArray? = null

    fun write(
        track: AudioTrack,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        writeMode: Int = AudioTrack.WRITE_BLOCKING
    ): Int {
        if (length <= 0) return 0
        val encoding = encodingProvider()
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            writeFloat(track, buffer, offset, length, writeMode)
        } else {
            writeBytes(track, buffer, offset, length, writeMode)
        }
    }

    private fun writeFloat(
        track: AudioTrack,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        writeMode: Int
    ): Int {
        val sampleCount = length / 4
        if (sampleCount <= 0) return 0
        var floats = floatScratch
        if (floats == null || floats.size < sampleCount) {
            floats = FloatArray(sampleCount)
            floatScratch = floats
        }
        ByteBuffer.wrap(buffer, offset, sampleCount * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floats, 0, sampleCount)
        val samplesWritten = track.write(floats, 0, sampleCount, writeMode)
        return if (samplesWritten > 0) samplesWritten * 4 else samplesWritten
    }

    private fun writeBytes(
        track: AudioTrack,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        writeMode: Int
    ): Int {
        return if (android.os.Build.VERSION.SDK_INT >= 23) {
            track.write(buffer, offset, length, writeMode)
        } else {
            track.write(buffer, offset, length)
        }
    }
}
