package com.rawsmusic.core.ui.widget.player

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import java.io.File

internal fun sharePlayerAudio(context: Context, song: AudioFile?) {
    if (song == null || !launchAudioShare(context, song)) {
        Toast.makeText(context, R.string.player_share_audio_unavailable, Toast.LENGTH_SHORT).show()
    }
}

private fun launchAudioShare(context: Context, song: AudioFile): Boolean {
    val uri = resolveShareUri(context, song) ?: return false
    val mimeType = resolveAudioMimeType(context, uri, song)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, song.displayName)
        clipData = ClipData.newUri(context.contentResolver, song.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        sendIntent,
        context.getString(R.string.player_share_audio_chooser)
    ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching {
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
}

private fun resolveShareUri(context: Context, song: AudioFile): Uri? {
    if (song.path.startsWith("content://", ignoreCase = true)) {
        return runCatching { Uri.parse(song.path) }.getOrNull()
    }

    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    if (song.id > 0L) {
        val candidate = ContentUris.withAppendedId(collection, song.id)
        val exists = runCatching {
            context.contentResolver.query(
                candidate,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        if (exists) return candidate
    }

    if (song.path.isNotBlank()) {
        val mediaStoreUri = runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(song.path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0))
                else null
            }
        }.getOrNull()
        if (mediaStoreUri != null) return mediaStoreUri
    }

    val file = File(song.path)
    if (!file.isFile) return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

private fun resolveAudioMimeType(context: Context, uri: Uri, song: AudioFile): String {
    context.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") }?.let { return it }
    return when (song.path.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "alac" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aiff", "aif" -> "audio/aiff"
        "ape" -> "audio/ape"
        "dsf" -> "audio/x-dsf"
        "dff" -> "audio/x-dff"
        else -> MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(song.path.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("audio/") }
            ?: "audio/*"
    }
}
