package com.rawsmusic.module.player

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe circular byte buffer for streaming audio pipeline.
 *
 * - [write] blocks if buffer is full until space is available.
 * - [read] blocks if buffer is empty until data is written or [close] is called.
 * - [clear] resets the buffer (used for seek).
 * - [close] signals EOF; [read] returns 0 after all remaining data is consumed.
 */
class RingBuffer(private val capacity: Int) {

    private val buffer = ByteArray(capacity)
    private var readPos = 0
    private var writePos = 0
    private var count = 0  // bytes currently in buffer

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    @Volatile
    private var closed = false

    /**
     * Write data into the ring buffer. Blocks if the buffer is full.
     * @return number of bytes actually written, or -1 if buffer is closed.
     */
    fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        lock.withLock {
            if (closed) return -1

            var remaining = length
            var srcOffset = offset

            while (remaining > 0) {
                // Wait until there's space
                while (count >= capacity && !closed) {
                    notFull.await()
                }
                if (closed) return -1

                val space = capacity - count
                val toWrite = minOf(remaining, space)

                // Copy in two segments if wrapping
                val firstSegment = minOf(toWrite, capacity - writePos)
                System.arraycopy(data, srcOffset, buffer, writePos, firstSegment)
                if (toWrite > firstSegment) {
                    System.arraycopy(data, srcOffset + firstSegment, buffer, 0, toWrite - firstSegment)
                }

                writePos = (writePos + toWrite) % capacity
                count += toWrite
                remaining -= toWrite
                srcOffset += toWrite

                notEmpty.signal()
            }
            return length
        }
    }

    /**
     * Read data from the ring buffer. Blocks if empty.
     * @return number of bytes read, or 0 if closed and buffer empty (EOF).
     */
    fun read(dest: ByteArray, offset: Int, maxBytes: Int): Int {
        if (maxBytes <= 0) return 0
        lock.withLock {
            // Wait for data
            while (count == 0 && !closed) {
                notEmpty.await()
            }

            if (count == 0 && closed) return 0  // EOF

            val toRead = minOf(maxBytes, count)
            val firstSegment = minOf(toRead, capacity - readPos)
            System.arraycopy(buffer, readPos, dest, offset, firstSegment)
            if (toRead > firstSegment) {
                System.arraycopy(buffer, 0, dest, offset + firstSegment, toRead - firstSegment)
            }

            readPos = (readPos + toRead) % capacity
            count -= toRead

            notFull.signal()
            return toRead
        }
    }

    /**
     * Non-blocking read. Returns immediately with available data.
     * @return number of bytes read, or 0 if no data available. -1 if closed and empty.
     */
    fun readNonBlocking(dest: ByteArray, offset: Int, maxBytes: Int): Int {
        lock.withLock {
            if (count == 0) return if (closed) -1 else 0

            val toRead = minOf(maxBytes, count)
            val firstSegment = minOf(toRead, capacity - readPos)
            System.arraycopy(buffer, readPos, dest, offset, firstSegment)
            if (toRead > firstSegment) {
                System.arraycopy(buffer, 0, dest, offset + firstSegment, toRead - firstSegment)
            }

            readPos = (readPos + toRead) % capacity
            count -= toRead

            notFull.signal()
            return toRead
        }
    }

    /**
     * Clear all buffered data. Used during seek.
     */
    fun clear() {
        lock.withLock {
            readPos = 0
            writePos = 0
            count = 0
            closed = false
            notFull.signalAll()
            notEmpty.signalAll()
        }
    }

    /**
     * Signal EOF. After this, [write] will return -1 and [read] will return 0 once empty.
     */
    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }

    /**
     * Reopen after a [clear] if needed.
     */
    fun open() {
        lock.withLock {
            closed = false
        }
    }

    /**
     * Number of bytes currently available to read.
     */
    fun available(): Int {
        lock.withLock { return count }
    }

    /**
     * Whether the buffer is closed and empty (EOF reached).
     */
    fun isEof(): Boolean {
        lock.withLock { return closed && count == 0 }
    }

    fun isClosed(): Boolean = closed
}