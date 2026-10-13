package com.rawsmusic.core.ui.widget.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val EXPORT_DIR = "lyric_export"
private const val GALLERY_ALBUM = "RawSMusic"

internal fun lyricExportCacheFile(context: Context, baseName: String, extension: String): File {
    val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val safe = baseName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(48).ifBlank { "lyric" }
    return File(dir, "${safe}_$stamp.$extension")
}

internal suspend fun writePosterPng(bitmap: Bitmap, file: File): File = withContext(Dispatchers.IO) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    file
}

/** Copy [file] into the shared Movies/RawSMusic or Pictures/RawSMusic collection. */
internal suspend fun saveExportToGallery(context: Context, file: File, isVideo: Boolean): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val relativeDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val mime = if (isVideo) "video/mp4" else "image/png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/$GALLERY_ALBUM")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: return@runCatching null
                try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("no output stream")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
                "$relativeDir/$GALLERY_ALBUM"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(relativeDir), GALLERY_ALBUM)
                dir.mkdirs()
                val target = File(dir, file.name)
                file.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
                target.parent
            }
        }.getOrNull()
    }

internal fun shareExport(context: Context, file: File, isVideo: Boolean) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = if (isVideo) "video/mp4" else "image/png"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
