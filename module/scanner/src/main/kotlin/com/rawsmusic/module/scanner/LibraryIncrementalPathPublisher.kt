package com.rawsmusic.module.scanner

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adds/refreshes a small set of filesystem directories without treating them as the complete
 * library source. This is intended for app-created audio (for example offline transcode output)
 * that Android MediaStore may not index, such as DSF.
 *
 * The operation is deliberately additive-only: it never calls deleteSongs and therefore cannot
 * remove unrelated library rows when the supplied directory is only a small output folder.
 */
object LibraryIncrementalPathPublisher {
    data class PublishResult(
        val scanned: Int,
        val upserted: Int,
        val failedDirectories: Int,
    )

    suspend fun scanAndUpsert(
        context: Context,
        directories: Collection<String>,
    ): Result<PublishResult> = withContext(Dispatchers.IO) {
        runCatching {
            val paths = directories
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(::File)
                .filter(File::isDirectory)
                .map(File::getAbsolutePath)
                .distinct()
                .toList()
            if (paths.isEmpty()) return@runCatching PublishResult(0, 0, 0)

            val report = MediaStoreScanner.scanCustomPathsByFileSystemReport(
                context = context.applicationContext,
                customPaths = paths,
                quickScan = false,
            )
            val scanOptions = MediaStoreScanner.ScanOptions.fromPreferences()
            val songs = report.songs.filter { MediaStoreScanner.shouldInclude(it, scanOptions) }
            val repository = LibraryScannerDependencies.repository(context.applicationContext)
            if (songs.isNotEmpty()) {
                repository.upsertSongsForScan(songs, refreshLibrary = false)
            }
            repository.refreshAfterScanSync()
            PublishResult(
                scanned = report.songs.size,
                upserted = songs.size,
                failedDirectories = report.failedDirectories.size,
            )
        }
    }
}
