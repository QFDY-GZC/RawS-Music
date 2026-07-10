package com.rawsmusic.core.common.utils

import android.net.Uri
import android.provider.MediaStore
import com.rawsmusic.core.common.model.AudioFile
import java.io.File

object AudioUtils {

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "ape", "opus", "alac", "dsf", "dff", "aiff"
    )

    fun isAudioFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    fun isAudioFile(file: File): Boolean {
        return file.isFile && isAudioFile(file.name)
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
            sizeBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024 * 1024))
        }
    }

    fun getAudioContentUri(context: android.content.Context, fileId: Long): Uri {
        return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileId.toString())
    }
}
