package com.rawsmusic.helper

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deletes local songs through the scoped-storage consent flow when Android owns the media row.
 *
 * The old implementation called ContentResolver.delete() from an IO coroutine and swallowed the
 * RecoverableSecurityException, which looked like a successful UI action while no file moved. This
 * coordinator keeps the ActivityResult owner alive, removes cue rows without deleting their shared
 * audio file, and only drops database rows after the platform confirms the operation.
 */
class SongDeletionCoordinator(
    private val activity: ComponentActivity,
) {
    data class Result(
        val requested: Int,
        val deleted: Int,
        val failed: Int,
        val cancelled: Boolean,
        val deletedSongs: List<AudioFile> = emptyList(),
    )

    private data class Pending(
        val songs: List<AudioFile>,
        val directlyDeleted: List<AudioFile>,
        val callback: (Result) -> Unit,
    )

    private var pending: Pending? = null

    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val request = pending ?: return@registerForActivityResult
        pending = null
        if (result.resultCode != Activity.RESULT_OK) {
            request.callback(
                Result(
                    requested = request.songs.size + request.directlyDeleted.size,
                    deleted = request.directlyDeleted.size,
                    failed = request.songs.size,
                    cancelled = true,
                    deletedSongs = request.directlyDeleted,
                )
            )
            return@registerForActivityResult
        }
        activity.lifecycleScope.launch {
            finishConfirmedDelete(request)
        }
    }

    fun delete(songs: List<AudioFile>, callback: (Result) -> Unit) {
        val unique = songs.distinctBy { Triple(it.path, it.cueOffsetMs, it.cueTrackIndex) }
        if (unique.isEmpty()) {
            callback(Result(0, 0, 0, cancelled = false))
            return
        }
        if (pending != null) {
            callback(Result(unique.size, 0, unique.size, cancelled = true))
            return
        }

        activity.lifecycleScope.launch {
            val cueRows = unique.filter { it.cueOffsetMs > 0L || it.cueTrackIndex > 0 }
            val physicalSongs = unique - cueRows.toSet()
            if (cueRows.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    MusicRepository.deleteSongsSuspend(cueRows, refreshLibrary = false)
                }
            }

            val directlyDeleted = mutableListOf<AudioFile>()
            val consentSongs = mutableListOf<AudioFile>()
            var failed = 0

            withContext(Dispatchers.IO) {
                physicalSongs.forEach { song ->
                    val directFile = File(song.path)
                    val directSucceeded = runCatching {
                        directFile.isFile && directFile.delete()
                    }.getOrDefault(false)
                    if (directSucceeded) {
                        directlyDeleted += song
                    } else if (song.id > 0L) {
                        consentSongs += song
                    } else {
                        failed++
                    }
                }
                if (directlyDeleted.isNotEmpty()) {
                    MusicRepository.deleteSongsSuspend(directlyDeleted, refreshLibrary = false)
                }
                if (cueRows.isNotEmpty() || directlyDeleted.isNotEmpty()) {
                    MusicRepository.refreshAllSuspend()
                }
            }
            // Cue rows share the parent audio file. Scanning that still-existing path here would
            // immediately recreate the cue row that the user just removed from the library.
            scanDeletedPaths(directlyDeleted)

            if (consentSongs.isEmpty()) {
                callback(
                    Result(
                        requested = unique.size,
                        deleted = cueRows.size + directlyDeleted.size,
                        failed = failed,
                        cancelled = false,
                        deletedSongs = cueRows + directlyDeleted,
                    )
                )
                return@launch
            }

            val uris = consentSongs.map { mediaUri(it.id) }
            val intentSender = runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        MediaStore.createDeleteRequest(activity.contentResolver, uris).intentSender
                    }
                    else -> {
                        // On Android 10 a first delete attempt provides the recoverable consent.
                        try {
                            uris.forEach { activity.contentResolver.delete(it, null, null) }
                            null
                        } catch (recoverable: RecoverableSecurityException) {
                            recoverable.userAction.actionIntent.intentSender
                        }
                    }
                }
            }.onFailure { AppLogger.e(TAG, "Unable to create media delete request", it) }
                .getOrNull()

            if (intentSender == null) {
                // Android 10 may already have completed the delete without consent.
                val missingAfterAttempt = consentSongs.filter { File(it.path).exists() }
                val confirmed = consentSongs - missingAfterAttempt.toSet()
                if (confirmed.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        MusicRepository.deleteSongsSuspend(confirmed)
                    }
                    scanDeletedPaths(confirmed)
                }
                callback(
                    Result(
                        requested = unique.size,
                        deleted = cueRows.size + directlyDeleted.size + confirmed.size,
                        failed = failed + missingAfterAttempt.size,
                        cancelled = false,
                        deletedSongs = cueRows + directlyDeleted + confirmed,
                    )
                )
                return@launch
            }

            pending = Pending(
                songs = consentSongs,
                directlyDeleted = cueRows + directlyDeleted,
                callback = callback,
            )
            consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    private suspend fun finishConfirmedDelete(request: Pending) {
        withContext(Dispatchers.IO) {
            MusicRepository.deleteSongsSuspend(request.songs)
        }
        scanDeletedPaths(request.songs)
        request.callback(
            Result(
                requested = request.songs.size + request.directlyDeleted.size,
                deleted = request.songs.size + request.directlyDeleted.size,
                failed = 0,
                cancelled = false,
                deletedSongs = request.directlyDeleted + request.songs,
            )
        )
    }

    private fun mediaUri(id: Long): Uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        id,
    )

    private fun scanDeletedPaths(songs: List<AudioFile>) {
        val paths = songs.map(AudioFile::path).filter { it.isNotBlank() }.distinct()
        if (paths.isEmpty()) return
        runCatching { MediaScannerConnection.scanFile(activity, paths.toTypedArray(), null, null) }
    }

    private companion object {
        const val TAG = "SongDeletion"
    }
}
