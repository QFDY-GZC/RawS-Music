package com.rawsmusic.lyrico

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import java.io.File
import java.io.RandomAccessFile

/**
 * Resolves real write paths for an audio item without trusting File.canWrite().
 *
 * File.canWrite() is only a Unix mode-bit/access check. On scoped-storage devices the app may be
 * allowed to update a MediaStore item through its content URI even when the raw path reports false.
 * The opposite also occurs on some vendor builds. This class probes the exact operation that will
 * be used and exposes the MediaStore URI needed for a system write-consent request.
 */
object LyricoAudioWriteAccess {
    private const val TAG = "LyricoAudioWrite"

    data class Snapshot(
        val mediaUri: Uri?,
        val canCreateSibling: Boolean,
        val canOpenDirectFile: Boolean,
        val canOpenMediaUri: Boolean
    ) {
        val canWrite: Boolean
            get() = canCreateSibling || canOpenDirectFile || canOpenMediaUri
    }

    fun inspect(context: Context, song: AudioFile): Snapshot {
        val mediaUri = resolveMediaStoreUri(context, song)
        return Snapshot(
            mediaUri = mediaUri,
            canCreateSibling = canCreateSibling(song.path),
            canOpenDirectFile = canOpenDirectFile(song.path),
            canOpenMediaUri = mediaUri?.let { canOpenUri(context, it, "rw") } == true
        )
    }

    fun resolveMediaStoreUri(context: Context, song: AudioFile): Uri? {
        if (song.path.startsWith("content://", ignoreCase = true)) {
            return runCatching { Uri.parse(song.path) }.getOrNull()
        }

        val collections = mediaCollections(context)
        if (song.id > 0L) {
            collections.forEach { collection ->
                val candidate = ContentUris.withAppendedId(collection, song.id)
                val matches = runCatching {
                    context.contentResolver.query(
                        candidate,
                        arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use false
                        val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (dataColumn < 0 || song.path.isBlank()) return@use true
                        samePath(cursor.getString(dataColumn).orEmpty(), song.path)
                    } == true
                }.onFailure {
                    Log.d(TAG, "MediaStore id verification failed for $candidate: ${it.message}")
                }.getOrDefault(false)
                if (matches) return candidate
            }
        }

        if (song.path.isBlank()) return null
        collections.forEach { collection ->
            val resolved = runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(song.path),
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }.onFailure {
                Log.d(TAG, "MediaStore path lookup failed for ${song.path}: ${it.message}")
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private fun mediaCollections(context: Context): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        val volumeNames = runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        return buildList {
            volumeNames.forEach { volume ->
                add(MediaStore.Audio.Media.getContentUri(volume))
            }
            add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }.distinct()
    }

    fun canOpenUri(context: Context, uri: Uri, mode: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, mode)?.use { true } == true
    }.getOrDefault(false)

    private fun canOpenDirectFile(path: String): Boolean {
        if (path.isBlank() || path.startsWith("content://", ignoreCase = true)) return false
        val file = File(path)
        if (!file.isFile) return false
        return runCatching { RandomAccessFile(file, "rw").use { } }.isSuccess
    }

    private fun canCreateSibling(path: String): Boolean {
        if (path.isBlank() || path.startsWith("content://", ignoreCase = true)) return false
        val source = File(path)
        val parent = source.parentFile ?: return false
        if (!parent.isDirectory) return false
        val probe = File(parent, ".raws-write-probe-${android.os.Process.myPid()}-${System.nanoTime()}")
        return runCatching {
            probe.createNewFile() && probe.delete()
        }.getOrDefault(false).also {
            if (probe.exists()) probe.delete()
        }
    }

    private fun samePath(first: String, second: String): Boolean = runCatching {
        File(first).canonicalPath == File(second).canonicalPath
    }.getOrElse { first == second }
}
