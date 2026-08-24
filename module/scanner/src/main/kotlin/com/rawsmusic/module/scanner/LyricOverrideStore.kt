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

    fun write(
        song: AudioFile,
        content: String,
        suffix: String = ".raws.ttml",
    ): File {
        val target = fileFor(song, suffix) ?: error("The private lyric override store is unavailable")
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
            val formats = listOf(".raws.ttml", ".raws.enhanced.lrc", ".raws.lrc")
            if (song.cueTrackIndex > 0 || song.cueOffsetMs > 0L) {
                formats.forEach { suffix ->
                    add(File(root, "$base.track${song.cueTrackIndex}$suffix"))
                }
            }
            formats.forEach { suffix -> add(File(root, "$base$suffix")) }
        }
    }

    fun filesFor(songPath: String): List<File> {
        val root = rootDirectory ?: return emptyList()
        val base = pathHash(songPath)
        return listOf(
            File(root, "$base.raws.ttml"),
            File(root, "$base.raws.enhanced.lrc"),
            File(root, "$base.raws.lrc"),
        )
    }

    private fun fileFor(song: AudioFile, suffix: String): File? {
        val root = rootDirectory ?: return null
        val base = pathHash(song.path)
        val trackSuffix = if (song.cueTrackIndex > 0 || song.cueOffsetMs > 0L) {
            ".track${song.cueTrackIndex}$suffix"
        } else {
            suffix
        }
        return File(root, base + trackSuffix)
    }

    private fun pathHash(path: String): String {
        val normalized = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
