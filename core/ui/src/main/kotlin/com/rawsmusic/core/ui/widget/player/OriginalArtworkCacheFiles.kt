package com.rawsmusic.core.ui.widget.player

import java.io.File

/** Give extracted picture bytes an image extension before passing them to the artwork provider. */
internal object OriginalArtworkCacheFiles {
    fun imageFileForViewer(extracted: File): File? {
        if (!extracted.isFile || !extracted.canRead()) return null
        val signature = ByteArray(12)
        val count = try {
            extracted.inputStream().use { input ->
                var read = 0
                while (read < signature.size) {
                    val next = input.read(signature, read, signature.size - read)
                    if (next < 0) break
                    read += next
                }
                read
            }
        } catch (_: Exception) {
            return null
        }
        val extension = when {
            count >= 3 && signature[0] == 0xFF.toByte() &&
                signature[1] == 0xD8.toByte() && signature[2] == 0xFF.toByte() -> "jpg"
            count >= 8 && signature.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ) -> "png"
            count >= 12 && String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(signature, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            count >= 6 && String(signature, 0, 3, Charsets.US_ASCII) == "GIF" -> "gif"
            count >= 2 && signature[0] == 'B'.code.toByte() && signature[1] == 'M'.code.toByte() -> "bmp"
            else -> return null
        }
        val image = File(extracted.parentFile, "${extracted.nameWithoutExtension}.$extension")
        if (image == extracted) return image
        return try {
            if (!image.isFile || image.length() != extracted.length()) {
                extracted.copyTo(image, overwrite = true)
            }
            image.takeIf { it.isFile && it.canRead() }
        } catch (_: Exception) {
            null
        }
    }
}
