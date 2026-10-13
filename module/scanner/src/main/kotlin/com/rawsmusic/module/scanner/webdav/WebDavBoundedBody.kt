package com.rawsmusic.module.scanner.webdav

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object WebDavBoundedBody {
    fun read(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes in 1 until Int.MAX_VALUE)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
            if (count < 0) break
            if (count == 0) continue
            require(count <= maxBytes - output.size()) { "WebDAV backup exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
