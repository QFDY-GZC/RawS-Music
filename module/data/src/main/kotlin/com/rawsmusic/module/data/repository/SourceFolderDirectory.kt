package com.rawsmusic.module.data.repository

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A display/grouping directory; the AudioFile path remains the original playable URI or file. */
fun sourceFolderDirectory(pathOrUri: String): String {
    val path = externalStorageDocumentPath(pathOrUri) ?: pathOrUri
    return path.replace('\\', '/').substringBeforeLast('/', "").trimEnd('/')
}

private fun externalStorageDocumentPath(uri: String): String? {
    if (!uri.startsWith("content://com.android.externalstorage.documents/", ignoreCase = true)) {
        return null
    }
    val encodedId = uri.substringAfter("/document/", "")
        .substringBefore('?')
        .substringBefore('#')
    if (encodedId.isBlank()) return null

    val documentId = runCatching {
        // Document IDs may contain literal '+': URLDecoder would otherwise turn it into a space.
        URLDecoder.decode(encodedId.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrNull().orEmpty()
    if (documentId.startsWith("raw:", ignoreCase = true)) {
        return documentId.substring(4).takeIf { it.startsWith('/') }
    }

    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volume = documentId.substring(0, separator)
    val relative = documentId.substring(separator + 1).trimStart('/', '\\')
    val root = when {
        volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
        volume.equals("home", ignoreCase = true) -> "/storage/emulated/0/Documents"
        else -> "/storage/$volume"
    }
    return if (relative.isEmpty()) root else "$root/$relative"
}
