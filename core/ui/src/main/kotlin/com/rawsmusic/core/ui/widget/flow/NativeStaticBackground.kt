package com.rawsmusic.core.ui.widget.flow

import android.graphics.Bitmap

internal object NativeStaticBackground {
    private const val WIDTH = 96
    private const val HEIGHT = 160
    // Use a compact
    // source texture prevents recognisable cover details from becoming blobs.

    private val available = runCatching {
        System.loadLibrary("rawscoreservice")
        true
    }.getOrDefault(false)

    fun create(
        colors: IntArray,
        saturation: Float,
        brightness: Float
    ): Bitmap? {
        if (!available || colors.isEmpty()) return null
        return runCatching {
            val pixels = render(
                colors = colors,
                width = WIDTH,
                height = HEIGHT,
                saturation = saturation,
                brightness = brightness,
                textureStrength = 0f,
                blurRadius = 2
            )
            Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    fun createPlayer(
        artwork: Bitmap,
        saturation: Float,
        brightness: Float,
        gradient: Float,
        blur: Float,
        detail: Float
    ): Bitmap? {
        if (!available || artwork.isRecycled) return null
        return runCatching {
            val sampleSize = (1 shl detail.toInt().coerceIn(0, 10)).coerceIn(8, 256)
            val scaled = Bitmap.createScaledBitmap(
                artwork,
                sampleSize,
                sampleSize,
                true
            )
            val readable = if (scaled.config == Bitmap.Config.HARDWARE) {
                scaled.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                scaled
            }
            val artworkPixels = IntArray(readable.width * readable.height)
            readable.getPixels(
                artworkPixels,
                0,
                readable.width,
                0,
                0,
                readable.width,
                readable.height
            )
            val output = renderPlayer(
                artwork = artworkPixels,
                artworkWidth = readable.width,
                artworkHeight = readable.height,
                width = WIDTH,
                height = HEIGHT,
                saturation = saturation,
                brightness = brightness,
                gradient = gradient,
                blurLevel = blur,
                detailLevel = detail
            )
            if (readable !== artwork) readable.recycle()
            if (scaled !== readable && scaled !== artwork) scaled.recycle()
            Bitmap.createBitmap(output, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private external fun render(
        colors: IntArray,
        width: Int,
        height: Int,
        saturation: Float,
        brightness: Float,
        textureStrength: Float,
        blurRadius: Int
    ): IntArray

    private external fun renderPlayer(
        artwork: IntArray,
        artworkWidth: Int,
        artworkHeight: Int,
        width: Int,
        height: Int,
        saturation: Float,
        brightness: Float,
        gradient: Float,
        blurLevel: Float,
        detailLevel: Float
    ): IntArray
}
