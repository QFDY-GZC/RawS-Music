package com.rawsmusic.module.data.prefs

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal object BackupBoundedReader {
    const val MAX_BYTES = 33554432

    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes >= 0) { "Invalid backup size limit" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            // Read just one byte beyond the remaining allowance, never the rest of a large stream.
            val requested = minOf(buffer.size.toLong(), maxBytes.toLong() - total + 1).toInt()
            val count = input.read(buffer, 0, requested)
            if (count == -1) return output.toByteArray()
            if (count == 0) {
                val byte = input.read()
                if (byte == -1) return output.toByteArray()
                require(total < maxBytes) { "Backup exceeds size limit" }
                output.write(byte)
                total++
            } else {
                require(count.toLong() + total <= maxBytes) { "Backup exceeds size limit" }
                output.write(buffer, 0, count)
                total += count
            }
        }
    }
}
