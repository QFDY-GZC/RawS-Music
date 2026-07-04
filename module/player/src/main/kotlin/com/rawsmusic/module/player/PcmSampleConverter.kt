package com.rawsmusic.module.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small PCM container-format conversion helpers used by the playback writer paths.
 *
 * These helpers intentionally only convert the sample container. They do not apply
 * dithering, gain, resampling, channel mixing, or USB stream-format policy.
 */
internal object PcmSampleConverter {
    private const val S32_FLOAT_SCALE = 2147483648.0f

    fun s32ToFloatPcm(src: ByteArray, length: Int, dst: ByteArray): Int {
        val samplesToWrite = minOf(length / 4, dst.size / 4)
        val bytesToWrite = samplesToWrite * 4
        val sb = ByteBuffer.wrap(src, 0, samplesToWrite * 4).order(ByteOrder.LITTLE_ENDIAN)
        val fb = ByteBuffer.wrap(dst, 0, bytesToWrite).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesToWrite) {
            val s32 = sb.getInt(i * 4)
            fb.putFloat(i * 4, s32.toFloat() / S32_FLOAT_SCALE)
        }
        return bytesToWrite
    }

    fun s32ToS16Pcm(src: ByteArray, length: Int, dst: ByteArray): Int {
        val samplesToWrite = minOf(length / 4, dst.size / 2)
        val bytesToWrite = samplesToWrite * 2
        val sb = ByteBuffer.wrap(src, 0, samplesToWrite * 4).order(ByteOrder.LITTLE_ENDIAN)
        val db = ByteBuffer.wrap(dst, 0, bytesToWrite).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesToWrite) {
            db.putShort(i * 2, (sb.getInt(i * 4) shr 16).toShort())
        }
        return bytesToWrite
    }

    fun s32ToS24PackedPcm(src: ByteArray, length: Int, dst: ByteArray): Int {
        val samplesToWrite = minOf(length / 4, dst.size / 3)
        var si = 0
        var di = 0
        repeat(samplesToWrite) {
            // S32LE -> S24LE packed: drop the least-significant byte, keep sign byte.
            dst[di] = src[si + 1]
            dst[di + 1] = src[si + 2]
            dst[di + 2] = src[si + 3]
            si += 4
            di += 3
        }
        return samplesToWrite * 3
    }
}
