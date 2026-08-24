package com.rawsmusic.module.player

/**
 * PCM crossfade mixer.
 *
 * Reference-compatible manual transitions use complementary linear-amplitude
 * envelopes. Automatic transitions pass their own explicitly planned gains.
 * The current buffer is mixed in place:
 * result[i] = current[i] * gainOut + next[i] * gainIn
 */
internal object PcmCrossfadeMixer {
    private const val S32_FLOAT_SCALE = 2147483648.0f

    fun gainOut(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

    fun gainIn(progress: Float): Float = progress.coerceIn(0f, 1f)

    fun mixInPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float,
        gainOutEnd: Float = gainOut,
        gainInEnd: Float = gainIn,
        frameSize: Int = 1,
        outputIsFloat: Boolean,
        bitsPerSample: Int,
        outputIsPacked24: Boolean = false,
        currentOffset: Int = 0,
        nextOffset: Int = 0,
    ) {
        if (outputIsFloat) {
            mixFloat32InPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn, gainOutEnd, gainInEnd, frameSize, currentOffset, nextOffset)
        } else if (outputIsPacked24) {
            mixS24PackedInPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn, gainOutEnd, gainInEnd, frameSize, currentOffset, nextOffset)
        } else if (bitsPerSample > 16) {
            mixS32InPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn, gainOutEnd, gainInEnd, frameSize, currentOffset, nextOffset)
        } else {
            mixS16InPlace(currentBuf, currentLen, nextBuf, nextLen, gainOut, gainIn, gainOutEnd, gainInEnd, frameSize, currentOffset, nextOffset)
        }
    }

    private fun gainAt(start: Float, end: Float, byteOffset: Int, byteLength: Int, frameSize: Int): Float {
        val frames = (byteLength / frameSize.coerceAtLeast(1)).coerceAtLeast(1)
        val frame = byteOffset / frameSize.coerceAtLeast(1)
        val progress = if (frames <= 1) 1f else frame.toFloat() / (frames - 1).toFloat()
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            (buf[offset + 3].toInt() shl 24)

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value ushr 8).toByte()
        buf[offset + 2] = (value ushr 16).toByte()
        buf[offset + 3] = (value ushr 24).toByte()
    }

    private fun mixFloat32InPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float,
        gainOutEnd: Float,
        gainInEnd: Float,
        frameSize: Int,
        currentOffset: Int,
        nextOffset: Int,
    ) {
        val byteLength = minOf(
            (currentLen - currentOffset).coerceAtLeast(0),
            (nextLen - nextOffset).coerceAtLeast(0),
        )
        val samples = byteLength / 4
        for (i in 0 until samples) {
            val relativeOffset = i * 4
            val currentSampleOffset = currentOffset + relativeOffset
            val nextSampleOffset = nextOffset + relativeOffset
            val out = gainAt(gainOut, gainOutEnd, relativeOffset, byteLength, frameSize)
            val inn = gainAt(gainIn, gainInEnd, relativeOffset, byteLength, frameSize)
            val cur = Float.fromBits(readIntLE(currentBuf, currentSampleOffset))
            val nxt = Float.fromBits(readIntLE(nextBuf, nextSampleOffset))
            writeIntLE(currentBuf, currentSampleOffset, (cur * out + nxt * inn).toRawBits())
        }
    }

    private fun mixS32InPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float,
        gainOutEnd: Float,
        gainInEnd: Float,
        frameSize: Int,
        currentOffset: Int,
        nextOffset: Int,
    ) {
        val byteLength = minOf(
            (currentLen - currentOffset).coerceAtLeast(0),
            (nextLen - nextOffset).coerceAtLeast(0),
        )
        val samples = byteLength / 4
        for (i in 0 until samples) {
            val relativeOffset = i * 4
            val currentSampleOffset = currentOffset + relativeOffset
            val nextSampleOffset = nextOffset + relativeOffset
            val out = gainAt(gainOut, gainOutEnd, relativeOffset, byteLength, frameSize)
            val inn = gainAt(gainIn, gainInEnd, relativeOffset, byteLength, frameSize)
            val cur = readIntLE(currentBuf, currentSampleOffset).toFloat() / S32_FLOAT_SCALE
            val nxt = readIntLE(nextBuf, nextSampleOffset).toFloat() / S32_FLOAT_SCALE
            val mixed = ((cur * out + nxt * inn) * S32_FLOAT_SCALE)
                .coerceIn(-2147483648f, 2147483647f)
            writeIntLE(currentBuf, currentSampleOffset, mixed.toInt())
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
        gainIn: Float,
        gainOutEnd: Float,
        gainInEnd: Float,
        frameSize: Int,
        currentOffset: Int,
        nextOffset: Int,
    ) {
        val byteLength = minOf(
            (currentLen - currentOffset).coerceAtLeast(0),
            (nextLen - nextOffset).coerceAtLeast(0),
        )
        val samples = byteLength / 3
        for (i in 0 until samples) {
            val relativeOffset = i * 3
            val currentSampleOffset = currentOffset + relativeOffset
            val nextSampleOffset = nextOffset + relativeOffset
            val out = gainAt(gainOut, gainOutEnd, relativeOffset, byteLength, frameSize)
            val inn = gainAt(gainIn, gainInEnd, relativeOffset, byteLength, frameSize)
            val cur = readS24LE(currentBuf, currentSampleOffset).toFloat() / 8388608.0f
            val nxt = readS24LE(nextBuf, nextSampleOffset).toFloat() / 8388608.0f
            val mixed = ((cur * out + nxt * inn) * 8388608.0f)
                .coerceIn(-8388608f, 8388607f)
                .toInt()
            writeS24LE(currentBuf, currentSampleOffset, mixed)
        }
    }

    private fun mixS16InPlace(
        currentBuf: ByteArray,
        currentLen: Int,
        nextBuf: ByteArray,
        nextLen: Int,
        gainOut: Float,
        gainIn: Float,
        gainOutEnd: Float,
        gainInEnd: Float,
        frameSize: Int,
        currentOffset: Int,
        nextOffset: Int,
    ) {
        val byteLength = minOf(
            (currentLen - currentOffset).coerceAtLeast(0),
            (nextLen - nextOffset).coerceAtLeast(0),
        )
        val samples = byteLength / 2
        for (i in 0 until samples) {
            val relativeOffset = i * 2
            val currentSampleOffset = currentOffset + relativeOffset
            val nextSampleOffset = nextOffset + relativeOffset
            val out = gainAt(gainOut, gainOutEnd, relativeOffset, byteLength, frameSize)
            val inn = gainAt(gainIn, gainInEnd, relativeOffset, byteLength, frameSize)
            val cur = ((currentBuf[currentSampleOffset].toInt() and 0xff) or (currentBuf[currentSampleOffset + 1].toInt() shl 8)).toShort().toInt()
            val nxt = ((nextBuf[nextSampleOffset].toInt() and 0xff) or (nextBuf[nextSampleOffset + 1].toInt() shl 8)).toShort().toInt()
            val mixed = (cur * out + nxt * inn).toInt().coerceIn(-32768, 32767)
            currentBuf[currentSampleOffset] = mixed.toByte()
            currentBuf[currentSampleOffset + 1] = (mixed shr 8).toByte()
        }
    }
}
