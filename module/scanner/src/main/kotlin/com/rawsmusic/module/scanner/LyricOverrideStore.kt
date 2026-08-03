package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import java.io.File
import java.security.MessageDigest

/** App-private lyric override fallback for storage providers that do not allow sibling files. */
object LyricOverrideStore {
    @Volatile
    private var rootDirectory: File? = null

    fun install(root: File) {
        root.mkdirs()
        rootDirectory = root
    }

    fun write(song: AudioFile, content: String): File {
        val target = fileFor(song) ?: error("The private lyric override store is unavailable")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp-${System.nanoTime()}")
        temporary.writeText(content, Charsets.UTF_8)
        require(temporary.length() > 0L) { "Generated lyric file is empty" }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Unable to replace the previous private lyric override")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target
    }

    fun filesFor(song: AudioFile): List<File> {
        val root = rootDirectory ?: return emptyList()
        val base = pathHash(song.path)
        return buildList {
            if (song.cueTrackIndex > 0 || song.cueOffsetMs > 0L) {
                add(File(root, "$base.track${song.cueTrackIndex}.raws.ttml"))
            }
            add(File(root, "$base.raws.ttml"))
        }
    }

    fun filesFor(songPath: String): List<File> {
        val root = rootDirectory ?: return emptyList()
        return listOf(File(root, "${pathHash(songPath)}.raws.ttml"))
    }

    private fun fileFor(song: AudioFile): File? {
        val root = rootDirectory ?: return null
        val base = pathHash(song.path)
        val suffix = if (song.cueTrackIndex > 0 || song.cueOffsetMs > 0L) {
            ".track${song.cueTrackIndex}.raws.ttml"
        } else {
            ".raws.ttml"
        }
        return File(root, base + suffix)
    }

    private fun pathHash(path: String): String {
        val normalized = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
