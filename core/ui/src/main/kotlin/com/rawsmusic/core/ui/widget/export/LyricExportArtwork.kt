package com.rawsmusic.core.ui.widget.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkBitmapRuntime
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode the selected source itself for export, not the 256px color-analysis thumbnail. */
internal suspend fun loadLyricExportArtwork(
    context: Context,
    key: String?,
    targetSize: LyricVideoSize,
): Bitmap? {
    if (key.isNullOrBlank()) return null
    // First ask for the encoded source itself. The ordinary artwork cache may only hold a
    // thumbnail, so a cache hit is not proof that the original source has been indexed.
    val source = withContext(Dispatchers.IO) {
        BitmapProvider.originalArtworkSourcePathForExport(key)
    } ?: run {
        ArtworkBitmapRuntime.executePixelAnalysisBitmap(context, key)
        BitmapProvider.originalArtworkSourcePath(key)
    }
    if (source != null) {
        val original = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                BitmapFactory.decodeFile(source, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }.onFailure { Log.w(VIDEO_EXPORT_TAG, "original artwork decode failed", it) }.getOrNull()
        }
        if (original != null) {
            Log.i(VIDEO_EXPORT_TAG, "artwork original=${original.width}x${original.height}")
            return original
        }
    }
    val fallback = ArtworkBitmapRuntime.executeBitmap(context, key, targetSize.width, targetSize.height)
    Log.w(VIDEO_EXPORT_TAG, "artwork original unavailable; fallback=${fallback?.width}x${fallback?.height}")
    return fallback
}
