package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Experimental Coil lane for PowerList artwork.
 *
 * This is deliberately file-identity only.  It does not accept album/folder aliases because the
 * current RawSMusic scanner does not yet expose a stable artwork type/id source mapping.
 * Coil owns Compose request cancellation and painter state; BitmapProvider still owns RawSMusic's
 * audio/folder/embedded-art decode order, no-art sentinel, disk thumbnails and shared bitmap cache.
 */
data class PowerListCoilArtworkModel(
    val coverKey: String,
    val targetSide: Int,
    val modeLabel: String,
    val defaultArtworkEnabled: Boolean
) {
    val id: FileArtworkId = FileArtworkId.fromCoverKey(coverKey)
    val side: Int = targetSide.coerceAtLeast(1)
    val cacheKey: String = "powerlist-coil-file-v3:${id.value}:$side:default=$defaultArtworkEnabled"
}

object PowerListCoilArtwork {
    private const val DECODE_PARALLELISM = 2

    private val loaders = ConcurrentHashMap<Context, ImageLoader>()
    internal val decodeSemaphore = Semaphore(DECODE_PARALLELISM)

    fun imageLoader(context: Context): ImageLoader {
        val app = context.applicationContext
        return loaders.getOrPut(app) {
            ImageLoader.Builder(app)
                .components {
                    add(PowerListCoilArtworkFetcher.Factory())
                }
                .build()
        }
    }
}

private class PowerListCoilArtworkFetcher(
    private val data: PowerListCoilArtworkModel,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (data.id.isBlank) return null
        val knownNoArtwork = BitmapProvider.hasRecentThumbnailFailure(
            data.id.value,
            data.side,
            data.side
        )
        val bitmap = if (knownNoArtwork) {
            Log.d("RawArt", "COIL_DEFAULT_ARTWORK_SKIP_DECODE key=${data.id.value.takeLast(80)}")
            null
        } else {
            PowerListCoilArtwork.decodeSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    BitmapProvider.init(options.context)
                    BitmapProvider.peekPowerListThumbnail(
                        key = data.id.value,
                        targetWidth = data.side,
                        targetHeight = data.side
                    ) ?: BitmapProvider.executeThumbnail(
                        key = data.id.value,
                        targetWidth = data.side,
                        targetHeight = data.side
                    )
                }
            }
        }
        val resolvedBitmap = bitmap ?: if (
            data.defaultArtworkEnabled &&
            BitmapProvider.hasRecentThumbnailFailure(data.id.value, data.side, data.side)
        ) {
            Log.d("RawArt", "COIL_DEFAULT_ARTWORK_HIT key=${data.id.value.takeLast(80)}")
            BitmapFactory.decodeResource(
                options.context.resources,
                com.rawsmusic.core.ui.R.drawable.default_album_art
            )
        } else {
            null
        }
        if (resolvedBitmap == null || resolvedBitmap.isRecycled) return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, resolvedBitmap),
            isSampled = isSampled(resolvedBitmap, data.side),
            dataSource = DataSource.DISK
        )
    }

    private fun isSampled(bitmap: Bitmap, requestedSide: Int): Boolean {
        val actual = maxOf(bitmap.width, bitmap.height)
        return actual > requestedSide.coerceAtLeast(1)
    }

    class Factory : Fetcher.Factory<PowerListCoilArtworkModel> {
        override fun create(
            data: PowerListCoilArtworkModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            return PowerListCoilArtworkFetcher(data, options)
        }
    }
}
