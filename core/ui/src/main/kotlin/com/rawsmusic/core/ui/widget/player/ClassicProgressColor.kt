package com.rawsmusic.core.ui.widget.player

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.google.android.material.color.utilities.Hct
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkBitmapRuntime
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import com.rawsmusic.core.common.utils.AppLogger

private val classicLightColorCache by lazy { LruCache<String, Int>(48) }

@SuppressLint("RestrictedApi")
internal fun classicLightTone80(seed: Int): Int {
    // UxPn(CONTENT,80) selects the CONTENT primary palette, whose hue/chroma equal the seed.
    val source = Hct.fromInt(seed)
    return Hct.from(source.hue, source.chroma, 80.0).toInt()
}

@Composable
internal fun rememberClassicLightColor(artworkKey: String?, fallback: Color): Color {
    val context = LocalContext.current.applicationContext
    val revision = BitmapProvider.artworkRevision
    val fallbackTone = remember(fallback) { Color(classicLightTone80(fallback.toArgb())) }
    val latestFallback by rememberUpdatedState(fallbackTone)
    val owner = remember { ClassicProgressColorOwner(latestFallback.toArgb()) }
    owner.bind(artworkKey)
    var color by remember { mutableStateOf(latestFallback) }
    LaunchedEffect(artworkKey, revision) {
        if (artworkKey.isNullOrBlank()) {
            owner.accept(artworkKey, latestFallback.toArgb())
            color = latestFallback
            return@LaunchedEffect
        }
        val cacheKey = "$artworkKey|$revision"
        classicLightColorCache.get(cacheKey)?.let {
            owner.accept(artworkKey, it)
            color = Color(owner.color)
            return@LaunchedEffect
        }
        // A visible artwork request may not have admitted its source flight yet. A single
        // analysis miss is therefore not evidence of absent artwork. Keep the last valid tint.
        val retryDelays = longArrayOf(0, 80, 160, 320, 640, 1000, 1500, 2000)
        for ((attempt, retryDelay) in retryDelays.withIndex()) {
            if (retryDelay > 0L) delay(retryDelay)
            currentCoroutineContext().ensureActive()
            val argb = try {
                val bitmap = ArtworkBitmapRuntime.executePixelAnalysisBitmap(context, artworkKey, 112)
                withContext(Dispatchers.Default) {
                    if (bitmap == null || bitmap.isRecycled) return@withContext null
                    val blackBacked = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(blackBacked).apply {
                            drawColor(android.graphics.Color.BLACK)
                            drawBitmap(bitmap, 0f, 0f, null)
                        }
                        val seed = if (Build.VERSION.SDK_INT >= 27) {
                            WallpaperColors.fromBitmap(blackBacked).primaryColor.toArgb()
                        } else {
                            Palette.from(blackBacked).generate().dominantSwatch?.rgb
                        }
                        seed?.let(::classicLightTone80)
                    } finally { blackBacked.recycle() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (argb != null) {
                classicLightColorCache.put(cacheKey, argb)
                owner.accept(artworkKey, argb)
                color = Color(owner.color)
                AppLogger.i("ClassicProgressColor", "ready key=${artworkKey.hashCode()} attempt=${attempt + 1}")
                return@LaunchedEffect
            }
            if (BitmapProvider.isKnownNoArtwork(artworkKey)) {
                owner.accept(artworkKey, latestFallback.toArgb())
                color = Color(owner.color)
                return@LaunchedEffect
            }
        }
        AppLogger.i("ClassicProgressColor", "pending key=${artworkKey.hashCode()} retained_last_color=true")
    }
    return color
}
