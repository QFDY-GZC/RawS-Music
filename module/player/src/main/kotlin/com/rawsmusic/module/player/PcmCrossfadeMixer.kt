package com.rawsmusic.module.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Constant-power PCM crossfade mixer.
 *
 * The current buffer is mixed in place:
 * result[i] = current[i] * gainOut(progress) + next[i] * gainIn(progress)
 */
internal object PcmCrossfadeMixer {
    private const val S32_FLOAT_SCALE = 2147483648.0f

    fun gainOut(progress: Float): Float {
        return cos(progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
    }

    fun gainIn(progress: Float): Float {
        return sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
    }

    fun mixInPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float,
        outputIsFloat: Boolean,
        bitsPerSample: Int,
        outputIsPacked24: Boolean = false
    ) {
        if (outputIsFloat) {
            mixFloat32InPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn)
        } else if (outputIsPacked24) {
            mixS24PackedInPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn)
        } else if (bitsPerSample > 16) {
            mixS32InPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn)
        } else {
            mixS16InPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn)
        }
    }

    private fun mixFloat32InPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float
    ) {
        val samples = minOf(currentLen, nextLen) / 4
        val curBB = ByteBuffer.wrap(currentBuf).order(ByteOrder.LITTLE_ENDIAN)
        val nxtBB = ByteBuffer.wrap(nextBuf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val offset = i * 4
            val cur = curBB.getFloat(offset)
            val nxt = nxtBB.getFloat(offset)
            curBB.putFloat(offset, cur * gainOut + nxt * gainIn)
        }
    }

    private fun mixS32InPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float
    ) {
        val samples = minOf(currentLen, nextLen) / 4
        val curBB = ByteBuffer.wrap(currentBuf).order(ByteOrder.LITTLE_ENDIAN)
        val nxtBB = ByteBuffer.wrap(nextBuf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val offset = i * 4
            val cur = curBB.getInt(offset).toFloat() / S32_FLOAT_SCALE
            val nxt = nxtBB.getInt(offset).toFloat() / S32_FLOAT_SCALE
            val mixed = ((cur * gainOut + nxt * gainIn) * S32_FLOAT_SCALE)
                .coerceIn(-2147483648f, 2147483647f)
            curBB.putInt(offset, mixed.toInt())
        }
    }

    private fun readS24LE(buf: ByteArray, offset: Int): Int {
        var v = (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16)
        if ((v and 0x00800000) != 0) v = v or -0x01000000
        return v
    }

    private fun writeS24LE(buf: ByteArray, offset: Int, value: Int) {
        val v = value.coerceIn(-8388608, 8388607)
        buf[offset] = (v and 0xff).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xff).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xff).toByte()
    }

    private fun mixS24PackedInPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float
    ) {
        val samples = minOf(currentLen, nextLen) / 3
        for (i in 0 until samples) {
            val offset = i * 3
            val cur = readS24LE(currentBuf, offset).toFloat() / 8388608.0f
            val nxt = readS24LE(nextBuf, offset).toFloat() / 8388608.0f
            val mixed = ((cur * gainOut + nxt * gainIn) * 8388608.0f)
                .coerceIn(-8388608f, 8388607f)
                .toInt()
            writeS24LE(currentBuf, offset, mixed)
        }
    }

    private fun mixS16InPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float
    ) {
        val samples = minOf(currentLen, nextLen) / 2
        val curBB = ByteBuffer.wrap(currentBuf).order(ByteOrder.LITTLE_ENDIAN)
        val nxtBB = ByteBuffer.wrap(nextBuf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val offset = i * 2
            val cur = curBB.getShort(offset).toInt()
            val nxt = nxtBB.getShort(offset).toInt()
            val mixed = (cur * gainOut + nxt * gainIn).toInt().coerceIn(-32768, 32767)
            curBB.putShort(offset, mixed.toShort())
        }
    }
}
