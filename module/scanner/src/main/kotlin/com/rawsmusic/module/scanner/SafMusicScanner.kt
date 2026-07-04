package com.rawsmusic.module.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences

private const val TAG = "SafMusicScanner"

object SafMusicScanner {

    fun scanSelectedFolders(context: Context): List<AudioFile> {
        val result = mutableListOf<AudioFile>()

        AppPreferences.Scanner.musicFolderUris.forEach { rawUri ->
            val treeUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return@forEach
            val root = DocumentFile.fromTreeUri(context, treeUri)

            if (root == null || !root.exists() || !root.canRead()) {
                Log.w(TAG, "SAF folder unavailable: $rawUri")
                return@forEach
            }

            scanFolderRecursive(context, root, result)
        }

        return result.distinctBy { it.path }
    }

    private fun scanFolderRecursive(
        context: Context,
        folder: DocumentFile,
        out: MutableList<AudioFile>
    ) {
        val children = runCatching { folder.listFiles() }.getOrElse { error ->
            Log.w(TAG, "listFiles failed: ${folder.uri}", error)
            return
        }

        children.forEach { file ->
            when {
                file.isDirectory -> scanFolderRecursive(context, file, out)
                file.isFile && isSupportedAudio(file) -> {
                    readAudioFile(context, file)?.let(out::add)
                }
            }
        }
    }

    private fun isSupportedAudio(file: DocumentFile): Boolean {
        val name = file.name.orEmpty()
        val mime = file.type.orEmpty().lowercase()

        if (AppPreferences.Scanner.ignoreVideoFormats) {
            if (mime.startsWith("video/")) return false
            if (name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS) return false
        }

        if (mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun readAudioFile(context: Context, file: DocumentFile): AudioFile? {
        val uri = file.uri
        val name = file.name.orEmpty()
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val minSec = AppPreferences.Scanner.minTrackDurationSeconds
            if (minSec > 0 && durationMs in 1 until minSec * 1000L) return null

            val title = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_TITLE
            ).orEmpty().ifBlank { name.substringBeforeLast('.') }

            val artist = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ARTIST
            ).orEmpty().ifBlank { "未知艺术家" }

            val album = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUM
            ).orEmpty().ifBlank { "未知专辑" }

            val albumArtist = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
            ).orEmpty()

            val composer = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_COMPOSER
            ).orEmpty()

            val genre = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_GENRE
            ).orEmpty()

            val year = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_YEAR
            )?.toIntOrNull() ?: 0

            val track = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
            )?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

            AudioFile(
                id = stableSafId(uri),
                path = uri.toString(),
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                composer = composer,
                genre = genre,
                year = year,
                trackNumber = track,
                duration = durationMs,
                fileSize = file.length(),
                dateAdded = System.currentTimeMillis() / 1000,
                albumArtPath = uri.toString()
            )
        } catch (e: Throwable) {
            Log.w(TAG, "read SAF audio failed: $uri", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun stableSafId(uri: Uri): Long {
        val hash = uri.toString().hashCode().toLong()
        return if (hash < 0) -hash else hash
    }

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
        "ape", "wv", "tta", "tak", "alac", "aiff", "aif",
        "dsf", "dff", "mka", "mpc", "cue"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "avi", "webm",
        "flv", "wmv", "3gp", "3g2", "ts", "mts", "m2ts", "mpg", "mpeg"
    )
}
