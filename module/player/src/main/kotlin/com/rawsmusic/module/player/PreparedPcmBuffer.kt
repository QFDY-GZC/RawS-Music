package com.rawsmusic.module.player

import com.rawsmusic.module.player.transition.NativePcmSlotQueue

/**
 * Fixed-capacity continuous PCM queue owned by a prepared decoder slot.
 *
 * When the native queue is configured, a dedicated pending producer keeps this
 * queue filled while the renderer only performs non-blocking reads. The Kotlin
 * byte array remains as a compatibility fallback for stale native libraries and
 * focused JVM tests.
 */
internal class PreparedPcmBuffer(
    capacityBytes: Int,
    val frameSize: Int,
    private val decoderSerial: Long = 0L,
    private val ownerGeneration: Int = 0,
    nativeQueueConfigured: Boolean? = null,
) {
    private val requestedCapacity = capacityBytes.coerceAtLeast(frameSize.coerceAtLeast(1))
    private val nativeBacked = nativeQueueConfigured ?: (
        decoderSerial != 0L && NativePcmSlotQueue.isConfigured(decoderSerial, ownerGeneration)
    )
    private val fallbackData = if (nativeBacked) null else ByteArray(requestedCapacity)
    private var fallbackReadOffset = 0
    private var fallbackWriteOffset = 0
    private val drainScratch = ByteArray(16 * 1024)

    val capacity: Int get() = requestedCapacity
    val isNative: Boolean get() = nativeBacked

    @get:Synchronized
    val availableBytes: Int
        get() = if (nativeBacked) {
            NativePcmSlotQueue.availableBytes(decoderSerial, ownerGeneration)
        } else {
            fallbackWriteOffset - fallbackReadOffset
        }

    @get:Synchronized
    val writableBytes: Int
        get() = (requestedCapacity - availableBytes).coerceAtLeast(0)

    @get:Synchronized
    val availableFrames: Long
        get() = if (nativeBacked) {
            NativePcmSlotQueue.availableFrames(decoderSerial, ownerGeneration)
        } else if (frameSize > 0) {
            ((fallbackWriteOffset - fallbackReadOffset) / frameSize).toLong()
        } else {
            0L
        }

    @get:Synchronized
    val totalWrittenFrames: Long
        get() = if (nativeBacked) {
            NativePcmSlotQueue.totalWrittenFrames(decoderSerial, ownerGeneration)
        } else if (frameSize > 0) {
            (fallbackWriteOffset / frameSize).toLong()
        } else {
            0L
        }

    @Synchronized
    fun append(source: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || frameSize <= 0) return 0
        val aligned = PcmFrameAligner.alignDown(length, frameSize)
        if (aligned <= 0) return 0
        if (nativeBacked) {
            val appended = NativePcmSlotQueue.appendPending(
                decoderSerial,
                ownerGeneration,
                source,
                offset,
                aligned,
            )
            if (appended < 0) {
                // Losing the native pending-slot owner is an expected cancellation boundary:
                // closeNextDecoder()/prepare-next replacement can revoke the slot while the
                // speculative producer is between decodeChunk() and append(). The producer's
                // contract already treats a negative append as ownership loss and exits cleanly.
                // Throwing here turned that normal race into an uncaught worker-thread crash.
                return -1
            }
            return appended
        }

        val data = fallbackData ?: return 0
        val writable = PcmFrameAligner.alignDown(data.size - fallbackWriteOffset, frameSize)
        val count = minOf(aligned, writable)
        if (count <= 0) return 0
        System.arraycopy(source, offset, data, fallbackWriteOffset, count)
        fallbackWriteOffset += count
        return count
    }

    @Synchronized
    fun read(destination: ByteArray, offset: Int, maxBytes: Int): Int {
        if (maxBytes <= 0 || frameSize <= 0) return 0
        val alignedMaximum = PcmFrameAligner.alignDown(maxBytes, frameSize)
        if (alignedMaximum <= 0) return 0
        if (nativeBacked) {
            val read = NativePcmSlotQueue.read(
                decoderSerial,
                ownerGeneration,
                destination,
                offset,
                alignedMaximum,
            )
            if (read < 0) {
                throw IllegalStateException(
                    "Native prepared PCM read lost ownership serial=$decoderSerial generation=$ownerGeneration",
                )
            }
            return read
        }

        val data = fallbackData ?: return 0
        val count = minOf(
            alignedMaximum,
            PcmFrameAligner.alignDown(fallbackWriteOffset - fallbackReadOffset, frameSize),
        )
        if (count <= 0) return 0
        System.arraycopy(data, fallbackReadOffset, destination, offset, count)
        fallbackReadOffset += count
        return count
    }

    @Synchronized
    fun drainInto(target: RingBuffer, writeChunk: ((ByteArray, Int) -> Int)? = null): Int {
        var total = 0
        while (true) {
            val count = read(drainScratch, 0, drainScratch.size)
            if (count <= 0) break
            val written = writeChunk?.invoke(drainScratch, count)
                ?: target.write(drainScratch, 0, count)
            if (written <= 0) break
            total += written
        }
        return total
    }
}
